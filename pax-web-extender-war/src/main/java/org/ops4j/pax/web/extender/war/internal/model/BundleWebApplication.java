/*
 * Copyright 2007 Alin Dreghiciu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.extender.war.internal.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;

import org.apache.felix.utils.extender.Extension;
import org.apache.tomcat.util.descriptor.web.ServletDef;
import org.apache.tomcat.util.descriptor.web.WebXml;
import org.apache.tomcat.util.descriptor.web.WebXmlParser;
import org.ops4j.pax.web.extender.war.internal.WarExtenderContext;
import org.ops4j.pax.web.service.PaxWebConstants;
import org.ops4j.pax.web.service.WebContainer;
import org.ops4j.pax.web.service.spi.model.OsgiContextModel;
import org.ops4j.pax.web.service.spi.model.events.WebApplicationEvent;
import org.ops4j.pax.web.service.spi.model.views.WebAppWebContainerView;
import org.ops4j.pax.web.service.spi.servlet.OsgiServletContextClassLoader;
import org.ops4j.pax.web.service.spi.util.Utils;
import org.ops4j.pax.web.service.spi.util.WebContainerManager;
import org.ops4j.pax.web.utils.ClassPathUtil;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Similarly to {@code BundleWhiteboardApplication} from pax-web-extender-whiteboard, this class collects
 * the web elements being part of a WAB (Web Application Bundle, according to OSGi CMPN chapter 128). In Whiteboard
 * case, the web elements come from OSGi services registered through related {@link org.osgi.framework.BundleContext}
 * and here they come from the {@code web.xml} of such bundle.</p>
 *
 * <p>The web elements themselves, which come from {@code web.xml} (or fragments) are held in separate wrapper object
 * and this class is modelled a bit after {@code BlueprintContainerImpl} from Aries, where the "container" itself
 * is being scheduled over and over again to progress through different lifecycle stages - including stages where
 * the container may wait for the dependencies. Here, there are not many dependencies, but the most important one
 * is the availability of {@link org.ops4j.pax.web.service.WebContainer} service refrence.</p>
 *
 * <p>Before Pax Web 8, the synchronization of states to the availability of
 * {@link org.ops4j.pax.web.service.WebContainer} service was quite confusing, because it involved registration
 * and listening to different <em>intermediary</em> OSGi services like {@code WebAppDependencyHolder}.</p>
 *
 * @author Alin Dreghiciu
 * @author Grzegorz Grzybek
 * @since 0.3.0, December 27, 2007
 */
public class BundleWebApplication {

	public static final Logger LOG = LoggerFactory.getLogger(BundleWebApplication.class);

	/**
	 * Hardcoded equivalent of Tomcat's {@code tomcat.util.scan.StandardJarScanFilter.jarsToSkip} - bundles by
	 * symbolic name.
	 */
	private static final Set<String> IGNORED_BUNDLES;

	static {
		IGNORED_BUNDLES = new HashSet<>(Arrays.asList(
				"javax.el-api", // yes - even in mvn:jakarta.el/jakarta.el-api
				"jakarta.servlet-api",
				"jakarta.annotation-api",
				"org.ops4j.pax.logging.pax-logging-api",
				"org.ops4j.pax.web.pax-web-api",
				"org.ops4j.pax.web.pax-web-spi",
				"org.ops4j.pax.web.pax-web-tomcat-common",
				"org.eclipse.jdt.core.compiler.batch"
		));
	}

	/**
	 * The original WAB (according to 128.3 Web Application Bundle) which is the base of this {@link BundleWebApplication}.
	 * Additional bundles (like fragments) may <em>contribute</em> to this web application, but original bundle needs
	 * to be specified.
	 */
	private final Bundle bundle;

	/**
	 * A reference to {@link WarExtenderContext} to access supporting services (like event dispatcher or web.xml
	 * parser).
	 */
	private final WarExtenderContext extenderContext;

	private final WebContainerManager webContainerManager;

	/**
	 * Current {@link ServiceReference} to use when obtaining a {@link WebContainer} from
	 * {@link WebContainerManager}. {@link WebContainerManager} ensures that this reference is consistent - never
	 * set when there's already a reference set without unsetting it first.
	 */
	private volatile ServiceReference<WebContainer> webContainerServiceRef;

	private final Lock refLock = new ReentrantLock();

	/**
	 * The {@link ExecutorService} where this bundle web application is scheduled to progresss through its lifecycle
	 */
	private final ExecutorService pool;

	/** The current state of the web application */
	private final AtomicReference<State> deploymentState = new AtomicReference<>(State.UNCONFIGURED);

	/** Latch to be setup during deployment, so when stop() is called before WAB is DEPLOYED, we can wait */
	private CountDownLatch deployingLatch = null;

	/** Latch to be setup during context allocation, so when stop() is called before WAB is DEPLOYING, we can wait */
	private CountDownLatch allocatingLatch = null;

	/**
	 * The {@link ServletContext#getContextPath() context path} of this web application - can't be taken from
	 * {@code web.xml}, it should be configured <em>externally</em>.
	 */
	private String contextPath;

	private final ClassLoader classLoader;

	// similar to org.apache.catalina.startup.ContextConfig.ok
	private boolean fragmentParsingOK = true;

	public BundleWebApplication(Bundle bundle, WebContainerManager webContainerManager,
			WarExtenderContext extenderContext, ExecutorService pool) {
		this.bundle = bundle;
		this.webContainerManager = webContainerManager;
		this.extenderContext = extenderContext;
		this.pool = pool;

		OsgiServletContextClassLoader loader = new OsgiServletContextClassLoader();
		loader.addBundle(bundle);
		// pax-web-tomcat-common used to parse the descriptors
		loader.addBundle(FrameworkUtil.getBundle(WebXmlParser.class));
		this.classLoader = loader;
	}

	@Override
	public String toString() {
		return "Web Application \"" + contextPath
				+ "\" for bundle " + bundle.getSymbolicName() + "/" + bundle.getVersion();
	}

	/**
	 * <p>A {@link BundleWebApplication} can be started only once. Even if "Figure 128.2 State diagram Web Application"
	 * shows that WAB 	 * can go from UNDEPLOYED to DEPLOYING state, we're using
	 * {@link org.apache.felix.utils.extender.AbstractExtender#destroyExtension}, so what will be started after
	 * undeployment is a new instance of {@link BundleWebApplication}. Again it's important to distinguish
	 * {@link BundleWebApplication.State} and {@link WebApplicationEvent.State}.</p>
	 *
	 * <p>This method should be called only from {@link Extension#start()} and within the scope of a thread
	 * from pax-web-extender-war thread pool.</p>
	 */
	public void start() {
		State state = deploymentState.get();
		if (state != State.UNCONFIGURED) {
			throw new IllegalStateException("Can't start " + this + ": it's already in " + state + " state");
		}

		scheduleIfPossible(null, State.CONFIGURING, true);
	}

	/**
	 * <p>A {@link BundleWebApplication} can also be stopped only once. After the WAB (a web-enabled {@link Bundle})
	 * is stopped, it's associated {@link BundleWebApplication} is removed from the extender and when the WAB is
	 * started again, new instance is created</p>
	 *
	 * <p>Before Pax Web 8, if WAB was stopped immediately after it has been started, it had to wait for full
	 * deployment. Now we're able to stop it for example after web.xml has been parsed, but before the web elements
	 * were actually registered. The moment of stopping affects the amount of resources to cleanup.</p>
	 */
	public void stop() {
		// while start() should be called only in UNCONFIGURED state, we should be ready to stop() the WAB
		// in any state - even during web.xml parsing process.
		// also this method is called from org.apache.felix.utils.extender.Extension.destroy() method, which doesn't
		// use pax-web-extender-war pool. And most probably we're in bundle-stopping thread. This is in accordance
		// with "128.3.8 Stopping the Web Application Bundle": "This undeploying must occur synchronously with the
		// WAB's stopping event" and we should not schedule UNDEPLOYING of this WAB, but instead perform everything
		// in current thread.
		// Otherwise, the undeployment might've been happening after Felix/Equinox already cleaned up everything
		// related to WAB's bundle

		State state = deploymentState.getAndSet(State.UNDEPLOYING);
		extenderContext.webEvent(new WebApplicationEvent(WebApplicationEvent.State.UNDEPLOYING, bundle));

		// get a WebContainer for the last time - it doesn't have to be available (no need to lock here)
		ServiceReference<WebContainer> ref = webContainerServiceRef;
		WebAppWebContainerView view = webContainerManager.containerView(bundle.getBundleContext(),
				ref, WebAppWebContainerView.class);

		// depending on current state, we may have to clean up more resources or just finish quickly
		switch (state) {
			case UNCONFIGURED:
			case CONFIGURING:
			case UNDEPLOYED:
			case UNDEPLOYING:
			case WAITING_FOR_WEB_CONTAINER:
				// web container is not available, but there's nothing to clean up
			case WAITING_FOR_CONTEXT:
				// it'll never be considered again, as the extension is already removed from
				// org.ops4j.pax.web.extender.war.internal.WarExtenderContext.webApplications
			case FAILED:
				LOG.debug("Stopping {} in {} state. No need to cleanup anything.", this, state);
				break;
			case ALLOCATING_CONTEXT:
				// the WAB is in the process of allocating the context and we have to wait till it finishes
				try {
					if (!allocatingLatch.await(10, TimeUnit.SECONDS)) {
						LOG.warn("Timeout waiting for end of context allocation for {}."
								+ " Can't free the context, leaving it in inconsistent state.", this);
					} else {
						if (view != null) {
							LOG.info("Undeploying {} after its context has been allocated", this);
							releaseContext(view, false);
						} else {
							LOG.warn("Successful wait for context allocation for {}, but WebContainer is no longer "
									+ "available and we can't release the context.", this);
						}
					}
				} catch (InterruptedException e) {
					LOG.warn("Thread interrupted while waiting for end of context allocation for {}."
							+ " Can't free the context, leaving it in inconsistent state.", this);
					Thread.currentThread().interrupt();
				}
				break;
			case DEPLOYING:
				// it's not deployed yet, but it'd not be wise to interrup() - let's just wait for DEPLOYED. That's
				// similar to pre Pax Web 8, where entire SimpleExtension#start() and SimpleExtension#destroy()
				// were synchronized on an extension object itself.
				try {
					if (!deployingLatch.await(10, TimeUnit.SECONDS)) {
						LOG.warn("Timeout waiting for end of deployment of {}."
								+ " Can't undeploy the application which may be left in inconsistent state.", this);
					} else {
						if (view != null) {
							LOG.info("Undeploying {} after waiting for its full deployment", this);
							undeploy(view);
						} else {
							LOG.warn("Successful wait for full deployment of {}, but WebContainer is no longer "
									+ "available and we can't undeploy it", this);
						}
					}
				} catch (InterruptedException e) {
					LOG.warn("Thread interrupted while waiting for end of deployment of {}."
							+ " Can't undeploy the application which may be left in inconsistent state.", this);
					Thread.currentThread().interrupt();
				}
				break;
			case DEPLOYED:
				// this is the most typical case - application was fully deployed, not it has to be fully undeployed.
				// the is the same case as implemented before Pax Web 8, where everything was fully synchronized -
				// simply even after web.xml has just started to be parsed, stop() had to wait for full deployment.
				if (view != null) {
					LOG.info("Undeploying fully deployed {}", this);
					undeploy(view);
				} else {
					LOG.info("Can't undeploy {} - WebContainer reference is no longer available", this);
				}
				break;
			default:
				break;
		}

		// whether the undeployment failed, succeeded or wasn't needed, we broadcast an event and set final stage.
		deploymentState.set(State.UNDEPLOYED);
		extenderContext.webEvent(new WebApplicationEvent(WebApplicationEvent.State.UNDEPLOYED, bundle));

		webContainerManager.releaseContainer(bundle.getBundleContext(), ref);
	}

	/**
	 * Private {@code schedule()} method modelled after Aries {@code BlueprintContainerImpl#schedule()}. It's
	 * important to be aware that the {@link ExecutorService} used may NOT be a single-thread pool. So we can't
	 * guarantee (same as with Aries Blueprint) that rescheduled invocation will be happening in the same thread (and
	 * as a consequence, sequentially after current run cycle).
	 *
	 * @param expectedState can be specified to prevent scheduling, if current state is different
	 * @param newState
	 * @param synchronous whether to schedule synchronously (run {@link #deploy()} directly) or not (pass to
	 *        a thread pool)
	 */
	private boolean scheduleIfPossible(State expectedState, State newState, boolean synchronous) {
		if (expectedState != null) {
			if (deploymentState.compareAndSet(expectedState, newState)) {
				if (!synchronous) {
					pool.submit(this::deploy);
				} else {
					deploy();
				}
				return true;
			}
			return false;
		} else {
			deploymentState.set(newState);
			if (!synchronous) {
				pool.submit(this::deploy);
			} else {
				deploy();
			}
			return true;
		}
	}

	public void webContainerAdded(ServiceReference<WebContainer> ref) {
		// There's whiteboard-equivalent method BundleWhiteboardApplication.webContainerAdded(), but it's easier
		// to implement, as BundleWhiteboardApplication doesn't have state-based lifecycle. Here we can't simply
		// register the web elements from parsed web.xml (and fragmnets + annotated web elements of the WAB), because
		// parsing may have not yet been finished
		//
		// the important thing to remember is that the WebContainer ref may be passed in two possible threads:
		// 1) when the BundleWebApplication is created, before WabExtension is returned to felix-extender, in
		//    a thread that calls org.osgi.util.tracker.BundleTrackerCustomizer.addingBundle(). This may be
		//    FelixStartLevel thread or e.g., Karaf Shell Console thread.
		//    In this case, the BundleWebApplication is definitely NOT scheduled yet
		// 2) a thread from single-thread pool managed in org.ops4j.pax.web.service.spi.util.WebContainerManager. And
		//    this is the case were we have to coordinate how WebContainer ref is set and how this BundleWebApplication
		//    already progressed through its lifecycle

		refLock.lock();
		try {
			webContainerServiceRef = ref;

			// No need to schedule if WAB is not waiting for the container.
			// Also, even if the WAB was already in state AFTER "allocating context" and prepared to register WAB's web
			// elements, after new WebContainer reference is set, we have to get back to ALLOCATING_CONTEXT, as the new
			// reference may be for a WebContainer with completely different setup
			scheduleIfPossible(State.WAITING_FOR_WEB_CONTAINER, State.ALLOCATING_CONTEXT, false);
		} finally {
			refLock.unlock();
		}
	}

	public void webContainerRemoved(ServiceReference<WebContainer> ref) {
		if (ref != webContainerServiceRef) {
			throw new IllegalStateException("Removing unknown WebContainer reference " + ref
					+ ", expecting " + webContainerServiceRef);
		}

		// previously set WebContainer ref was removed. But we may be in the pending process of WAB registration,
		// web.xml parsing or waiting for the reference.
		// while stop() is definitely an end of this BundleWebApplication (because stopped WAB may start with
		// new bundle fragments attached, with potentially new web fragments), disappearance of WebContainer reference
		// for DEPLOYED web application should bring it back to ALLOCATION_CONTEXT state, so we preserve the parsed
		// state of web elements

		refLock.lock();
		try {
			WebAppWebContainerView view = webContainerManager.containerView(bundle.getBundleContext(),
					webContainerServiceRef, WebAppWebContainerView.class);

			if (view == null) {
				LOG.warn("WebContainer reference {} was removed, but {} can't access the WebContainer service"
						+ " and it can't be undeployed.", ref, this);
				// but still let's start with new allocation attempt (in new WebContainer)
				deploymentState.set(State.ALLOCATING_CONTEXT);
			} else {
				// as in stop(), we should UNDEPLOY the application, but (unlike as in stop()) to the stage, where
				// it could potentially be DEPLOYED again. re-registration of WebContainer service won't change the
				// information content of current WAB (when WAB is restarted/refreshed, it may get new bundle
				// fragments attached, thus new web fragments may become available), so it's safe to reuse already
				// parsed web.xml + fragments + annotations

				State state = deploymentState.getAndSet(State.UNDEPLOYING);
				extenderContext.webEvent(new WebApplicationEvent(WebApplicationEvent.State.UNDEPLOYING, bundle));

				// similar state check as with stop(), but with slightly different handling
				switch (state) {
					case UNCONFIGURED:
					case CONFIGURING:
					case UNDEPLOYED:
					case UNDEPLOYING:
					case WAITING_FOR_WEB_CONTAINER:
					case WAITING_FOR_CONTEXT:
						break;
					case FAILED:
						// FAILED state of WAB is different that FAILED event (which may mean the context is not free),
						// but with new WebContainer reference that may soon be set, we'll give this WAS another chance
						break;
					case ALLOCATING_CONTEXT:
						try {
							if (!allocatingLatch.await(10, TimeUnit.SECONDS)) {
								LOG.warn("Timeout waiting for end of context allocation for {}."
										+ " Can't free the context, leaving it in inconsistent state.", this);
							} else {
								LOG.info("Undeploying {} after its context has been allocated", this);
								releaseContext(view, false);
							}
						} catch (InterruptedException e) {
							LOG.warn("Thread interrupted while waiting for end of context allocation for {}."
									+ " Can't free the context, leaving it in inconsistent state.", this);
							Thread.currentThread().interrupt();
						}
						break;
					case DEPLOYING:
						try {
							if (!deployingLatch.await(10, TimeUnit.SECONDS)) {
								LOG.warn("Timeout waiting for end of deployment of {}."
										+ " Can't undeploy the application which may be left in inconsistent state"
										+ " (in previous WebContainer).", this);
							} else {
								LOG.info("Undeploying {} from previous WebContainer after waiting for its full"
										+ " deployment", this);
								undeploy(view);
							}
						} catch (InterruptedException e) {
							LOG.warn("Thread interrupted while waiting for end of deployment of {}."
									+ " Can't undeploy the application which may be left in inconsistent state.", this);
							Thread.currentThread().interrupt();
						}
						break;
					case DEPLOYED:
						LOG.info("Undeploying fully deployed {}", this);
						undeploy(view);
						break;
					default:
						break;
				}

				// with the removal of WebContainer reference, we broadcast UNDEPLOYED event, but the state is as
				// if the WAB was waiting for the context to be allocated. WAITING_FOR_WEB_CONTAINER state
				// means that if the WebContainer is available again, the WAB will be scheduled to ALLOCATING_CONTEXT
				// state
				deploymentState.set(State.WAITING_FOR_WEB_CONTAINER);
				extenderContext.webEvent(new WebApplicationEvent(WebApplicationEvent.State.UNDEPLOYED, bundle));
			}

			webContainerManager.releaseContainer(bundle.getBundleContext(), ref);
			webContainerServiceRef = null;
		} finally {
			refLock.unlock();
		}
	}

	/**
	 * Gets a {@link WebContainer} and if it's not available, this {@link BundleWebApplication} automatically
	 * enters {@link State#WAITING_FOR_WEB_CONTAINER} state.
	 * @return
	 * @param currentState a guarding state, so if it is changed externally (to for example {@link State#UNDEPLOYING}),
	 *        container won't enter {@link State#WAITING_FOR_WEB_CONTAINER} state.
	 */
	private WebAppWebContainerView currentWebContainer(State currentState) {
		refLock.lock();
		try {
			WebAppWebContainerView view = webContainerManager.containerView(bundle.getBundleContext(),
					webContainerServiceRef, WebAppWebContainerView.class);
			if (view == null) {
				if (deploymentState.compareAndSet(currentState, State.WAITING_FOR_WEB_CONTAINER)) {
					LOG.debug("WebContainer service reference is not available. {} enters Grace Period state.", this);
					// note: there may be duplicate WAITING events if web container is not available before
					// context path reservation and before actual registration of webapp's web elements.
					extenderContext.webEvent(new WebApplicationEvent(WebApplicationEvent.State.WAITING, bundle));
				}
			}
			return view;
		} finally {
			refLock.unlock();
		}
	}

	/**
	 * <p>Method invoked within the thread of pax-web-extender-war thread pool. It's final goal is to progress to
	 * {@code DEPLOYED}</em> state, but there may be some internal states in between.</p>
	 *
	 * <p>This method is quite similar (in terms of its structure), but very simplified comparing to
	 * {@code org.apache.aries.blueprint.container.BlueprintContainerImpl#doRun()}, especially in relation to "waiting
	 * for service". In Blueprint this may involve waiting for multiple services and with additional constraints
	 * (mandatory, optional), while here, it's only single, mandatory {@link org.ops4j.pax.web.service.WebContainer}
	 * service.</p>
	 */
	private void deploy() {
		try {
			// progress through states in a loop - when everything is available, we can simply transition to
			// final state in single thread/task run. If we need to wait for anything, we'll break the loop
			if (bundle.getState() != Bundle.ACTIVE && bundle.getState() != Bundle.STARTING) {
				return;
			}

			// This state machine should match OSGi CMPN 128 specification:
			//  - 128.3.2 Starting the Web Application Bundle
			//     1. "Wait for the WAB to become ready" - ensured by
			//        org.apache.felix.utils.extender.AbstractExtender.modifiedBundle. The specification fragment:
			//        "The following steps can take place asynchronously with the starting of the WAB." means that
			//        we can (and in fact we always do) call deploy() in a thread from pax-web-extender-war pool,
			//        instead of a thread that started the bundle (most probably FelixStartLevel or Karaf's
			//        feature installation thread)
			//     2. Post an org/osgi/service/web/DEPLOYING event
			//     3. Validate that the Web-ContextPath manifest header does not match the Context Path of any
			//        other currently deployed web application.
			//     4. The Web Runtime processes deployment information by processing the web.xml descriptor,
			//        if present. In Pax Web we're also checking bundle fragments, web fragments and annotated
			//        classes within (or reachable from) the WAB.
			//     5. Publish the Servlet Context as a service with identifying service properties - ensured by
			//        org.ops4j.pax.web.service.spi.task.BatchVisitor.visit(OsgiContextModelChange) and
			//        org.ops4j.pax.web.service.spi.servlet.OsgiServletContext.register()
			//     6. Post an org/osgi/service/web/DEPLOYED event
			//
			//  - 128.3.8 Stopping the Web Application Bundle
			//     1. A web application is stopped by stopping the corresponding WAB - handled by
			//        org.apache.felix.utils.extender.AbstractExtender.destroyExtension(). The specification fragment:
			//        "This undeploying must occur synchronously with the WAB's stopping event" means that we can't
			//        perform the undeployment in a thread from pax-web-extender-war thread pool and instead use the
			//        thred that stops the bundle. I'm not sure why this is the case, but we have to stick to
			//        specification.
			//     2. An org/osgi/service/web/UNDEPLOYING event is posted
			//     3. Unregister the corresponding Servlet Context service - implemented in
			//        org.ops4j.pax.web.service.spi.servlet.OsgiServletContext.unregister
			//     4. The Web Runtime must stop serving content from the Web Application - sure it must!
			//     5. The Web Runtime must clean up any Web Application specific resources - yes
			//     6. Emit an org/osgi/service/web/UNDEPLOYED event
			//     7. It is possible that there are one or more colliding WABs because they had the same Context
			//        Path as this stopped WAB. If such colliding WABs exists then the Web Extender must attempt to
			//        deploy the colliding WAB with the lowest bundle id.
			//
			// Although the validation of Web-ContextPath is specified to be done before parsing of web.xml, we
			// actually want to parse web.xml earlier. First - we'll log parsing errors as soon as possible and
			// move the WAB into "failed permanently" state (so it's never attempted to be deployed again) and
			// second - even if there's a conflict with existing context path, we'll be ready (parsed/configured)
			// at the time when we can try the deployment again (after the conflicting context is gone)
			//
			// Each if{} branch should only change the deploymentState using compareAndSet(), because in parallel
			// there may be an external change to BundleWebApplication state. And while we can imagine a stop() call
			// during the process of parsing web.xml, we're not implementing (for now: 2021-01-08) thread interruption
			// here, so each if{} is kind of "atomic". But as a consequence, we have to get() the state again
			// after each if{}.

			State state = deploymentState.get();
			if (state == State.CONFIGURING) {
				LOG.info("Processing configuration of {}", this);
				// Post an org/osgi/service/web/DEPLOYING event
				extenderContext.webEvent(new WebApplicationEvent(WebApplicationEvent.State.DEPLOYING, bundle));

				// The Web Runtime processes deployment information by processing the web.xml descriptor ...
				parseMetadata();

				// after web.xml/web-fragment.xml/annotations are read, we have to check if the context path
				// is available
				if (deploymentState.compareAndSet(State.CONFIGURING, State.ALLOCATING_CONTEXT)) {
					if (allocatingLatch != null && allocatingLatch.getCount() > 0) {
						// for now let's keep this brute check
						throw new IllegalStateException("[dev error] Previous context allocation attempt didn't finish"
								+ " properly. Existing latch found.");
					}
					allocatingLatch = new CountDownLatch(1);
				}
			}

			// even if after the above if{} was executed and the state is expected to be ALLOCATING_CONTEXT,
			// we may already be in different state - e.g., STOPPING
			// but most common scenario is that we continue the WAB deployment

			state = deploymentState.get();
			if (state == State.ALLOCATING_CONTEXT) {
				LOG.debug("Checking if {} context path is available", contextPath);

				// but need a WebContainer to allocate the context
				WebAppWebContainerView view = currentWebContainer(state);
				if (view == null) {
					return;
				}
				if (!view.allocateContext(bundle, contextPath)) {
					LOG.debug("Context path {} is already used. {} will wait for this context to be available.",
							contextPath, this);
					// 128.3.2 Starting the Web Application Bundle, point 3): If the Context Path value is already in
					// use by another Web Application, then the Web Application must not be deployed, and the
					// deployment fails. [...] If the prior Web Application with the same Context Path is undeployed
					// later, this Web Application should be considered as a candidate.
					if (deploymentState.compareAndSet(state, State.WAITING_FOR_CONTEXT)) {
						extenderContext.webEvent(new WebApplicationEvent(WebApplicationEvent.State.FAILED, bundle));
					}
					return;
				}
				// from now on, this.contextPath is "ours" and we can do anything with it
				if (deploymentState.compareAndSet(state, State.DEPLOYING)) {
					// count down this latch, so it's free even before the deployment attempt
					allocatingLatch.countDown();

					if (deployingLatch != null && deployingLatch.getCount() > 0) {
						// for now let's keep this brute check
						throw new IllegalStateException("[dev error] Previous deployment attempt didn't finish properly."
								+ " Existing latch found.");
					}
					deployingLatch = new CountDownLatch(1);
				}
			}

			state = deploymentState.get();
			if (state == State.DEPLOYING) {
				LOG.debug("Registering {} in WebContainer", contextPath);

				// we have to get the view again, as we may have been rescheduled after waiting for WebContainer
				WebAppWebContainerView view = currentWebContainer(state);
				if (view == null) {
					return;
				}

				// this is were the full WAR/WAB information is passed as a model to WebContainer (through special view)
				view.justDoIt(contextPath);

				if (deploymentState.compareAndSet(state, State.DEPLOYED)) {
					extenderContext.webEvent(new WebApplicationEvent(WebApplicationEvent.State.DEPLOYED, bundle));
				}
			}
		} catch (Throwable t) {
			deploymentState.set(State.FAILED);

			// we are not configuring a listener that sends the events to LogService (we have one for EventAdmin),
			// because we use SLF4J logger directly (backed by pax-logging)
			LOG.error("Problem processing {}: {}", this, t.getMessage(), t);

			extenderContext.webEvent(new WebApplicationEvent(WebApplicationEvent.State.FAILED, bundle, t));
		} finally {
			// context allocation or deployment may end up with missing WebContainer reference, an exception or
			// success. In all the cases we have to trigger corresponding latches, so potential parallel stop()
			// knows about it
			if (allocatingLatch != null) {
				allocatingLatch.countDown();
			}
			if (deployingLatch != null) {
				deployingLatch.countDown();
			}
		}
	}

	/**
	 * <p>Not schedulable equivalent of {@link #deploy()}. It fully undeploys the {@link BundleWebApplication} using
	 * passed {@link WebAppWebContainerView}.</p>
	 *
	 * <p>While {@link #deploy()}, the "starting" side of WAB's lifecycle may be rescheduled few times when
	 * {@link WebContainer} is not available, here we just have to do everything in one shot.</p>
	 * @param view
	 */
	private void undeploy(WebAppWebContainerView view) {
		if (view == null) {
			throw new IllegalArgumentException("Can't undeploy " + this + " without valid WebContainer");
		}
		try {
			// 1. undeploy all the web elements from current WAB
			view.justDoIt("end of work");

			// 2. free the context
			releaseContext(view, true);
		} catch (Exception e) {
			// 128.3.8 Stopping the Web Application Bundle: Any failure during undeploying should be logged but must
			// not stop the cleaning up of resources and notification of (other) listeners as well as handling any
			// collisions.
			LOG.warn("Problem undeploying {}: {}", this, e.getMessage(), e);
		}
	}

	/**
	 * This wasn't present in Pax Web before version 8. Now we can stop/undeploy the application after it has
	 * allocated the context, but before it managed to register any web elements there.
	 * @param view
	 * @param propagateException
	 */
	private void releaseContext(WebAppWebContainerView view, boolean propagateException) {
		if (view == null) {
			throw new IllegalArgumentException("Can't undeploy " + this + " without valid WebContainer");
		}
		try {
			view.releaseContext(bundle, contextPath);
		} catch (Exception e) {
			if (propagateException) {
				throw new RuntimeException(e);
			} else {
				LOG.warn("Problem releasing context for {}: {}", this, e.getMessage(), e);
			}
		}
	}

	// --- Lifecycle processing methods

	/**
	 * <p>Parse all the possible descriptors to create final web application model. The rules specified in Servlet
	 * specification should be applied. We're following the order from
	 * {@code org.apache.catalina.startup.ContextConfig#webConfig()}. And we're even using Tomcat's {@code }web.xml}
	 * parser.</p>
	 *
	 * <p>After the descriptors/annotations are parsed, the model stays in current {@link BundleWebApplication}
	 * whether the {@link WebContainer} is available or not. However if the bundle itself is stopped and started
	 * again (which may happen during refresh), we have to parse everything again, because new model parts may
	 * become available in OSGi bundle fragments.</p>
	 */
	private void parseMetadata() {
		LOG.debug("Processing web.xml, web fragments and annotations");

		// Servlet spec, 8.1 Annotations and pluggability:
		//  - "metadata-complete" attribute on web descriptor defines whether this deployment descriptor and any web
		//    fragments, if any, are complete, or whether the class files available to this module and packaged with
		//    this application should be examined for annotations that specify deployment information.
		//  - classes using annotations will have their annotations processed only if they are located in the
		//    WEB-INF/classes directory, or if they are packaged in a jar file located in WEB-INF/lib within the
		//    application - in OSGi, we're processing jars (and locations) from Bundle-ClassPath
		//  - Annotations that do not have equivalents in the deployment XSD include
		//    javax.servlet.annotation.HandlesTypes and all of the CDI-related annotations. These annotations must be
		//    processed during annotation scanning, regardless of the value of "metadata-complete".
		//  - there are annotations to be processed from different packages:
		//     - javax.servlet.annotation
		//     - javax.annotation
		//     - javax.annotation.security
		//     - javax.annotation.sql
		//     - javax.ejb
		//     - javax.jms
		//     - javax.mail
		//     - javax.persistence
		//     - javax.resource
		//     - javax.jws.*
		//     - javax.xml.ws.*
		//
		// Servlet spec, 8.2.1 Modularity of web.xml:
		//  - A web fragment is a part or all of the web.xml that can be specified and included in a library or
		//    framework jar's META-INF directory. A plain old jar file in the WEB-INF/lib directory with no
		//    web-fragment.xml is also considered a fragment. Any annotations specified in it will be processed [...]
		//  - fragment's top level element for the descriptor MUST be web-fragment and the corresponding descriptor
		//    file MUST be called web-fragment.xml
		//  - web-fragment.xml descriptor must be in the META-INF/ directory of the jar file.
		//  - In order for any other types of resources (e.g., class files) of the framework to be made available to a
		//    web application, it is sufficient for the framework to be present anywhere in the classloader delegation
		//    chain of the web application. In other words, only JAR files bundled in a web application's WEB-INF/lib
		//    directory, but not those higher up in the class loading delegation chain, need to be scanned for
		//    web-fragment.xml
		//
		// (for example, myfaces-impl-2.3.3.jar contains /META-INF/web-fragment.xml with
		//    <listener>
		//        <listener-class>org.apache.myfaces.webapp.StartupServletContextListener</listener-class>
		//    </listener>
		//
		// Servlet spec, 8.2.4 Shared libraries / runtimes pluggability:
		//  - The ServletContainerInitializer class is looked up via the jar services API. In JavaEE env, it's
		//    traversing up the ClassLoader hierarchy up to web container's top CL. But in OSGi there's no "top"
		//
		// There's something wrong with metadata-complete...:
		//  - Servlet spec 8.1: This attribute defines whether this deployment descriptor and any web fragments, if
		//    any, are complete, or whether the class files available to this module and packaged with this application
		//    should be examined for annotations that specify deployment information.
		//  - Servlet spec 15.5.1: If metadata-complete is set to " true ", the deployment tool only examines the
		//    web.xml file and must ignore annotations such as @WebServlet , @WebFilter , and @WebListener present in
		//    the class files of the application, and must also ignore any web- fragment.xml descriptor packaged in
		//    a jar file in WEB-INF/lib.
		// So I'm not sure whether to process web-fragment.xml or not...

		// OSGi CMPN 128.3.1 WAB Definition:
		//  - web.xml must be found with the Bundle findEntries method at the path WEB-INF/web.xml. The findEntries
		//    method includes [bundle] fragments
		//
		// OSGi CMPN 128.6.4 Resource Injection and Annotations:
		//  - The Web Application web.xml descriptor can specify the metadata-complete attribute on the web-app
		//    element. This attribute defines whether the web.xml descriptor is complete, or whether the classes in the
		//    bundle should be examined for deployment annotations. If the metadata-complete attribute is set to true,
		//    the Web Runtime must ignore any servlet annotations present in the class files of the Web Application.
		//    Otherwise, if the metadata-complete attribute is not specified, or is set to false, the container should
		//    process the class files of the Web Application for annotations, if supported.
		//    So nothing about META-INF/web-fragment.xml descriptors...
		//
		// OSGi CMPN 128 spec doesn't say anything at all about ServletContainerInitializers...

		// org.apache.catalina.startup.ContextConfig#webConfig() works like this:
		//  1. "default web.xml" is treated as fragment (so lower priority than app's "main" web.xml) and comes from:
		//   - "global"
		//     - org.apache.catalina.core.StandardContext.getDefaultWebXml()
		//     - org.apache.catalina.startup.ContextConfig.getDefaultWebXml()
		//     - in Tomcat (standalone) 9.0.41 it's file:/data/servers/apache-tomcat-9.0.41/conf/web.xml
		//       - overridable=true, distributable=true, alwaysAddWelcomeFiles=false, replaceWelcomeFiles=true
		//       - two servlets:
		//         - "default" -> org.apache.catalina.servlets.DefaultServlet
		//         - "jsp" -> org.apache.jasper.servlet.JspServlet
		//       - three mappings:
		//         - "*.jspx" -> "jsp"
		//         - "*.jsp" -> "jsp"
		//         - "/" -> "default"
		//       - three welcome files:
		//         - "index.html"
		//         - "index.htm"
		//         - "index.jsp"
		//   - "host"
		//     - org.apache.catalina.Host.getConfigBaseFile()
		//     - in Tomcat (standalone) 9.0.41 it's null
		//   - "default" and "host" web.xml are merged together
		//  2. "tomcat web.xml" - also as fragment
		//   - /WEB-INF/tomcat-web.xml from context (org.apache.catalina.WebResourceRoot.getResource())
		//     - overridable=true, distributable=true, alwaysAddWelcomeFiles=false, replaceWelcomeFiles=true
		//  3. "context web.xml"
		//   - /WEB-INF/web.xml from context (javax.servlet.ServletContext.getResourceAsStream())
		//  4. ContextConfig.processJarsForWebFragments() - fragments from org.apache.tomcat.JarScanner.scan()
		//   - META-INF/web-fragment.xml from each JAR in /WEB-INF/lib
		//   - in Tomcat (standalone) 9.0.41, /examples context has /WEB-INF/lib/taglibs-standard-impl-1.2.5.jar
		//     and /WEB-INF/lib/taglibs-standard-spec-1.2.5.jar, but the latter is skipped by default
		//     (org.apache.tomcat.util.scan.StandardJarScanFilter.defaultSkip)
		//   - As per http://java.net/jira/browse/SERVLET_SPEC-36, if the main web.xml is marked as metadata-complete,
		//     JARs are still processed for SCIs.
		//   - Tomcat checks all classloaders starting from javax.servlet.ServletContext.getClassLoader() up to
		//     the parent of java.lang.ClassLoader.getSystemClassLoader()
		//   - fragments are ordered using org.apache.tomcat.util.descriptor.web.WebXml.orderWebFragments()
		//  5. org.apache.catalina.startup.ContextConfig.processServletContainerInitializers()
		//   - /META-INF/services/javax.servlet.ServletContainerInitializer files are loaded from CL hierarchy
		//   - order may be consulted from "javax.servlet.context.orderedLibs" attribute (see Servlet spec,
		//     8.3 JSP container pluggability) - this order affects SCIs
		//   - these are found in Tomcat 9.0.41 hierarchy:
		//     - "jar:file:/data/servers/apache-tomcat-9.0.41/lib/tomcat-websocket.jar!/META-INF/services/javax.servlet.ServletContainerInitializer"
		//     - "jar:file:/data/servers/apache-tomcat-9.0.41/lib/jasper.jar!/META-INF/services/javax.servlet.ServletContainerInitializer"
		//   - these provide the following SCIs:
		//     - org.apache.tomcat.websocket.server.WsSci
		//       - @javax.servlet.annotation.HandlesTypes is:
		//         - interface javax.websocket.server.ServerEndpoint
		//         - interface javax.websocket.server.ServerApplicationConfig
		//         - class javax.websocket.Endpoint
		//     - org.apache.jasper.servlet.JasperInitializer
		//   - the HandlesTypes are not yet scanned for the classes to pass to SCIs
		//   - META-INF/services/javax.servlet.ServletContainerInitializer is not loaded from the WAR itself, only from
		//     its JARs - because java.lang.ClassLoader.getResources() is used both for parent classloaders and
		//     the WAR itself. When orderedLibs are present, direct JAR access is used for the non-excluded /WEB-INF/lib/*.jar
		//  6. if metadata-complete == false, classes from /WEB-INF/classes are checked (Tomcat uses BCEL)
		//   - but if it's true, classes still should be scanned for @HandlesTypes for SCIs from jars not excluded
		//     in absolute ordering (Tomcat uses "if (!webXml.isMetadataComplete() || typeInitializerMap.size() > 0)")
		//   - All *.class files are checked using org.apache.tomcat.util.bcel.classfile.ClassParser
		//  7. tomcat-web.xml is merged in
		//  8. default web.xml is merged in
		//  9. org.apache.catalina.startup.ContextConfig.convertJsps() - servlets with JSP files are converted
		// 10. org.apache.catalina.startup.ContextConfig.configureContext() - finally parsed web elements are applied
		//     to org.apache.catalina.Context
		//
		// In Jetty, web.xml parsing is performed by org.eclipse.jetty.webapp.StandardDescriptorProcessor and
		// org.eclipse.jetty.plus.webapp.PlusDescriptorProcessor
		// WebApp configuration happens during context start:
		// org.eclipse.jetty.server.handler.ContextHandler.doStart()
		//   org.eclipse.jetty.webapp.WebAppContext.startContext()
		//     - use org.eclipse.jetty.webapp.Configuration instances to configure the contexts (like adding metadata
		//       from different sources)
		//     org.eclipse.jetty.webapp.MetaData.resolve() - based on prepared metadata (sources)
		//     org.eclipse.jetty.servlet.ServletContextHandler.startContext()
		// Default Jetty's configuration classes are:
		//  - "org.eclipse.jetty.webapp.WebInfConfiguration"
		//     - it prepares resources/paths to be used later - from parent classloader (container paths) and
		//       /WEB-INF/lib (webinf paths)
		//  - "org.eclipse.jetty.webapp.WebXmlConfiguration"
		//     - default descriptor is taken from org/eclipse/jetty/webapp/webdefault.xml from jetty-webapp
		//       webdefault.xml is a bit more complex than Tomcat's conf/web.xml. It adds:
		//        - org.eclipse.jetty.servlet.listener.ELContextCleaner
		//        - org.eclipse.jetty.servlet.listener.IntrospectorCleaner
		//        - org.eclipse.jetty.servlet.DefaultServlet mapped to "/"
		//        - org.eclipse.jetty.jsp.JettyJspServlet (extends org.apache.jasper.servlet.JspServlet) mapped
		//          to *.jsp, *.jspf, *.jspx, *.xsp, *.JSP, *.JSPF, *.JSPX, *.XSP
		//        - 30 minutes session timeout
		//        - welcome files: index.html, index.htm, index.jsp
		//        - <locale-encoding-mapping-list>
		//        - <security-constraint> that disables TRACE verb
		//     - normal /WEB-INF/web.xml
		//  - "org.eclipse.jetty.webapp.MetaInfConfiguration" - scanning JARs using
		//    org.eclipse.jetty.webapp.MetaInfConfiguration.scanJars():
		//     - selected container jars
		//     - /WEB-INF/lib/*.jar
		//    this is were the ordering takes place. These resources are being searched for:
		//     - org.eclipse.jetty.webapp.MetaInfConfiguration.scanForResources() - META-INF/resources
		//     - org.eclipse.jetty.webapp.MetaInfConfiguration.scanForFragment() - META-INF/web-fragment.xml
		//     - org.eclipse.jetty.webapp.MetaInfConfiguration.scanForTlds() - META-INF/**/*.tld
		//  - "org.eclipse.jetty.webapp.FragmentConfiguration"
		//     - fragments scanned by MetaInfConfiguration are processed
		//     - MetaInfConfiguration doesn't call org.eclipse.jetty.webapp.MetaData.addFragment(), but only prepares
		//       "org.eclipse.jetty.webFragments" context attribute to be processed here
		//  - "org.eclipse.jetty.webapp.JettyWebXmlConfiguration
		//     - WEB-INF/jetty8-web.xml, WEB-INF/jetty-web.xml (seems to be the preferred one),
		//       WEB-INF/web-jetty.xml (in that order) are checked - first found is used
		//     - parsed using org.eclipse.jetty.xml.XmlConfiguration

		// additionally:
		//  - Tomcat handles context.xml files set by org.apache.catalina.core.StandardContext.setDefaultContextXml()
		//  - Jetty handles jetty[0-9]?-web.xml, jetty-web.xml or web-jetty.xml
		// These are container specific files that can alter (respectively):
		//  - org.apache.catalina.core.StandardContext
		//  - org.eclipse.jetty.webapp.WebAppContext

		Bundle paxWebJspBundle = Utils.getPaxWebJspBundle(bundle);

		ClassLoader tccl = Thread.currentThread().getContextClassLoader();
		try {
			// TCCL set only at the time of parsing - not at the time of deployment of the model to a WebContainer
			Thread.currentThread().setContextClassLoader(this.classLoader);
			WebXmlParser parser = new WebXmlParser(false, false, true);

			try {
				// Global web.xml (in Tomcat it's CATALINA_HOME/conf/web.xml and we package it into pax-web-spi)
				// TODO: it should be parsed only once
				WebXml defaultWebXml = new WebXml();
				defaultWebXml.setDistributable(true);
				defaultWebXml.setOverridable(true);
				defaultWebXml.setAlwaysAddWelcomeFiles(false);
				defaultWebXml.setReplaceWelcomeFiles(true);
				URL defaultWebXmlURI = OsgiContextModel.class.getResource("/org/ops4j/pax/web/service/spi/model/default-web.xml");
				parser.parseWebXml(defaultWebXmlURI, defaultWebXml, false);
				defaultWebXml.setURL(defaultWebXmlURI);

				// review the servlets
				for (Iterator<Map.Entry<String, ServletDef>> iterator = defaultWebXml.getServlets().entrySet().iterator(); iterator.hasNext(); ) {
					Map.Entry<String, ServletDef> e = iterator.next();
					String name = e.getKey();
					ServletDef servlet = e.getValue();
					if ("default".equals(name)) {
						// which means it'll be replaced by container-specific "default" servlet
						// TODO: maybe there's a better hack
						servlet.setServletClass(Servlet.class.getName());
					} else if ("jsp".equals(name)) {
						if (paxWebJspBundle != null) {
							servlet.setServletClass(PaxWebConstants.DEFAULT_JSP_SERVLET_CLASS);
						} else {
							// no support for JSP == no JSP servlet at all
							iterator.remove();
							// no JSP servlet mapping
							defaultWebXml.getServletMappings().keySet().remove("jsp");
							// and no JSP welcome file
							defaultWebXml.getWelcomeFiles().remove("index.jsp");
						}
					}
				}

				// default-web.xml from Tomcat includes 5 important parts:
				// - "default" servlet + "/" mapping
				// - "jsp" servlet + ".jsp" and ".jspx" mappings
				// - huge list of mime mappings
				// - session timeout set to 30 (minutes)
				// - 3 welcome files: index.html, index.htm and index.jsp
				// MIME mappings are left untouched, but:
				// - "default" servlet should be replaced by container specific version and we can't use
				//   org.apache.catalina.servlets.DefaultServlet!
				// - "jsp" servlet should be replaced by container agnostic org.ops4j.pax.web.jsp.JspServlet and
				//   we have to be sure that pax-web-jsp bundle is available

				boolean metadataComplete = true;
				WebXml mainWebXml = null;

				// WAB specific web.xml. 128.3.1 WAB Definition - This web.xml must be found with the Bundle
				// findEntries() method at the path /WEB-INF/web.xml. The findEntries() method includes fragments,
				// allowing the web.xml to be provided by a fragment.
				// I don't expect multiple web.xmls, but it's possible in theory
				Enumeration<URL> descriptors = bundle.findEntries("WEB-INF", "web.xml", false);
				if (descriptors != null) {
					while (descriptors.hasMoreElements()) {
						URL next = descriptors.nextElement();
						LOG.debug("Processing {}", next);

						WebXml webXml = new WebXml();
						parser.parseWebXml(next, webXml, false);
						webXml.setURL(next);
						if (mainWebXml == null) {
							mainWebXml = webXml;
						} else {
							mainWebXml.merge(Collections.singleton(webXml));
						}
					}
				} else {
					metadataComplete = false;
				}
				if (mainWebXml == null) {
					// if it was empty, we still need something to merge scanned fragments into
					mainWebXml = new WebXml();
				}

				// at this stage, we don't have javax.servlet.ServletContext available yet. We don't even know
				// where this WAB is going to be deployed (Tomcat? Jetty? Undertow?). We don't even know whether
				// the contextPath for this WAB is available.

				// Now find fragments in Bundle-ClassPath and in transitively reachable bundles

				// fragments from Bundle-ClassPath - these may be subject to fragment ordering
				// these names will map to fragments with WebXml.webappJar = true
				Map<String, URL> wabClassPath = new LinkedHashMap<>();
				// fragments from reachable bundles - not subject to fragment ordering and treated as kind of
				// "container fragments". Also these fragments are being searched for SCIs and never filtered
				// by the ordering mechanism
				// these names will map to fragments with WebXml.webappJar = false
				// that's because while in Tomcat we can have JARs which are in shared loader (and external to the WAR),
				// in OSGi, all bundles outside of Bundle-ClassPath should be treated as container bundles
				// and users should:
				//  - avoid <ordering> such bundles
				//  - expect that web-fragment.xml and SCIs are always loaded from such bundles
				//     - web-fragment.xml from such bundles will be "applied" after main web.xml + fragments from
				//       Bundle-ClassPath. The way merging works means that WABs descriptors have higher priority
				//     - SCIs from such bundles are applied first, so user's SCIs are invoked later - but again
				//       this means that WAB's SCIs may override the changed done by container SCIs
				Map<String, Bundle> reachableBundles = new LinkedHashMap<>();

				Map<String, WebXml> fragments = findFragments(mainWebXml, parser, wabClassPath, reachableBundles);

				// ServletContext, when passed is used to set important "javax.servlet.context.orderedLibs"
				// attribute, but at this stage, there's no real ServletContext yet. We should provide a mocked one
				AttributeCollectingServletContext context = new AttributeCollectingServletContext();

				// Tomcat orders the fragments according to 8.2.2 Ordering of web.xml and web-fragment.xml (Servlet spec)
				// and it orders:
				//  - container and app fragments according to <absolute-ordering> (if present)
				//  - at the end, container fragments (webappJar=false) are put at the end of the list (if any)
				//  - WAR's fragments (from /WEB-INF/lib/*.jar) are placed as first fragments
				Set<WebXml> orderedFragments = WebXml.orderWebFragments(mainWebXml, fragments, context);

				// if parsing was successful (done if it was required, which may be disabled using empty
				// <absolute-ordering>), proceed to scanning for ServletContainerInitializers
				// see org.apache.catalina.startup.ContextConfig.processServletContainerInitializers()

				// Tomcat loads:
				// - all /META-INF/services/javax.servlet.ServletContainerInitializer files from
				//   the parent of webapp's class loader (the "container provided SCIs")
				// - SCIs from the webapp and its /WEB-INF/lib/*.jars (each or those mentioned in
				//   javax.servlet.context.orderedLibs context attribute)
				// - java.lang.ClassLoader.getResources() method is used
				// see: org.apache.catalina.startup.WebappServiceLoader.load()

				// so while web-fragment.xml (and even libs without web-fragment.xml when metadata-complete="false")
				// descriptors are processed in established order for the purpose of manifest building, they are
				// scanned for ServletContainerInitializers in strict order:
				// 1. container libs
				// 2. /WEB-INF/lib/*.jar libs (potentially filtered by the absolute ordering)
				// Tomcat ensures that a WAR may override an SCI implementation by using single
				// javax.servlet.ServletContext.getClassLoader() to load SCI implementations whether the
				// /META-INF/service/javax.servlet.ServletContainerInitializer was loaded from container or webapp lib

				// We can't load the services as entries (without using classloaders), because a bundle may have
				// custom Bundle-ClassPath (potentially with many entries). So while META-INF/web-fragment.xml has
				// fixed location in a bundle (or fragment of WAB's JAR),
				// META-INF/services/javax.servlet.ServletContainerInitializer has to be found using classLoaders, to
				// pick up for example WEB-INF/classes/META-INF/services/javax.servlet.ServletContainerInitializer if
				// WEB-INF/classes is on a Bundle-ClassPath

				// a list of URIs of /META-INF/service/javax.servlet.ServletContainerInitializer from reachable bundles
				Map<Bundle, List<URL>> containerSCIURLs = new LinkedHashMap<>();

				String sciService = "META-INF/services/" + ServletContainerInitializer.class.getName();

				// make list of reachable bundles unique, because there may be multiple web-fragment.xmls from single
				// bundle and bundle fragments
				for (Bundle reachableBundle : new LinkedHashSet<>(reachableBundles.values())) {
					List<URL> urls = ClassPathUtil.getResources(Collections.singletonList(reachableBundle), sciService);
					containerSCIURLs.put(reachableBundle, urls);
				}

				// a list of URIs of /META-INF/service/javax.servlet.ServletContainerInitializer WAB's Bundle-ClassPath
				List<URL> wabSCIURLs = new LinkedList<>();

				@SuppressWarnings("unchecked")
				List<String> orderedLibs = (List<String>) context.getAttribute(ServletContext.ORDERED_LIBS);
				if (orderedLibs == null) {
					// all entries from Bundle-ClassPath, so ClassLoader kind of access - we can't impact the order
					// of Bundle-ClassPath entries, but it doesn't really matter. Tomcat uses
					// javax.servlet.ServletContext.getClassLoader().getResources()
					List<URL> urls = ClassPathUtil.getResources(Collections.singletonList(bundle), sciService);
					wabSCIURLs.addAll(urls);
				} else {
					// before checking ORDERED_LIBS, we (and Tomcat) always checks WEB-INF/classes/META-INF/services
					// Tomcat calls javax.servlet.ServletContext.getResource(), here we have to load the SCI service
					// from the WAB itself, but ensure (which isn't straightforward) that we skip embedded JARs and
					// WAB bundle fragments)
					// - ClassPathUtil.listResources() uses BundleWiring.listResources() which removes duplicates,
					//   so we can't use it
					// - bundle.getResources() is ok, but returns also URLs for fragments and other libs on Bundle-ClassPath
					//   but we want only the URLs for non-jar entries of Bundle-ClassPath (not necessarily only
					//   /WEB-INF/classes!) and (later) from jar entries listed in ORDERED_LIBS and even fragments
					//   we (out of spec) list in ORDERED_LIST
					//   Because we're checking roots from the WAB anyway (later), we can't use bundle.getResources(), as
					//    - bundle://46.0:5/META-INF/services/javax.servlet.ServletContainerInitializer, and
					//    - bundle://45.0:0/META-INF/services/javax.servlet.ServletContainerInitializer
					//   are actually the same resources (first URL is WAB's fragemnt seen through WAB's classloader
					//   and second is an URL of the fragment itself. Same for:
					//    - bundle://46.0:2/META-INF/services/javax.servlet.ServletContainerInitializer, and
					//    - jar:bundle://46.0:0/WEB-INF/lib/the-wab-jar-8.0.0-SNAPSHOT.jar!/META-INF/services/javax.servlet.ServletContainerInitializer

					URL[] urls = ClassPathUtil.getClassPathURLs(bundle);
					// after getting the roots, we no longer have to use classloader acces and we can get entries
					// directly with the roots' bases
					List<URL> nonJarUrls = new ArrayList<>();
					for (URL url : urls) {
						if (!"jar".equals(url.getProtocol())) {
							nonJarUrls.add(url);
						}
					}
					List<URL> wabURLs = ClassPathUtil.findEntries(bundle, nonJarUrls.toArray(new URL[nonJarUrls.size()]),
							"META-INF/services/", ServletContainerInitializer.class.getName(), false);
					wabSCIURLs.addAll(wabURLs);

					// selected (also: none) entries from Bundle-ClassPath - only JARs (JavaEE compliant) and
					// WAB-attached bundle fragments (Pax Web addition - we list them as "<bundle-fragment.id>.bundle"
					// entries)
					// ORDERED_LIBS contains jar names, so we have to translate them back
					// ORDERED_LIBS doesn't contain jar names from the container - only (in JavaEE) from /WEB-INF/lib
					// and potentially shared classloader
					List<URL> roots = new ArrayList<>();
					for (String jarName : orderedLibs) {
						for (WebXml fragment : orderedFragments) {
							if (jarName.equals(fragment.getJarName())) {
								roots.add(wabClassPath.get(fragment.getName()));
							}
						}
					}
					wabURLs = ClassPathUtil.findEntries(bundle, roots.toArray(new URL[wabClassPath.size()]),
							"META-INF/services", ServletContainerInitializer.class.getName(), false);
					wabSCIURLs.addAll(wabURLs);
				}

				// a list of instantiated (using correct bundle) SCIs
				final List<ServletContainerInitializer> detectedSCIs = new LinkedList<>();

				// container SCIs - loaded from respective bundles
				containerSCIURLs.forEach((b, urls) -> {
					for (URL url : urls) {
						loadSCI(url, b, detectedSCIs);
					}
				});

				// WAB SCIs - loaded from WAB itself
				wabSCIURLs.forEach(url -> {
					loadSCI(url, bundle, detectedSCIs);
				});
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		} finally {
			Thread.currentThread().setContextClassLoader(tccl);
		}
	}

	// --- Web Application model access

	public String getContextPath() {
		return contextPath;
	}

	public void setContextPath(String contextPath) {
		this.contextPath = contextPath;
	}

	// --- utility methods

	/**
	 * This method is based on Tomcat's {@code org.apache.catalina.startup.ContextConfig#processJarsForWebFragments()}.
	 * It scans the reachable JARs and bundles to find (and possibly parse) {@code web-fragment.xml} descriptors,
	 * but the returned <em>fragments</em> determine the bundles/libs to scan for SCIs whether or not the metadata is
	 * complete.
	 *
	 * @param mainWebXml the WAB's {@code web.xml} which tells us whether the metadata is complete
	 * @param parser
	 * @param wabClassPath
	 * @param reachableBundles
	 * @return
	 */
	private Map<String, WebXml> findFragments(WebXml mainWebXml, WebXmlParser parser,
			Map<String, URL> wabClassPath, Map<String, Bundle> reachableBundles) {
		// 8.2.2 Ordering of web.xml and web-fragment.xml
		// this greatly impacts the scanning process for the web fragments and even SCIs
		// - A web-fragment.xml may have a top level <name> element, which will impact the ordering
		// - there are two different orderings:
		//   - absolute ordering specified in "main" web.xml (<absolute-ordering> element)
		//     - if there's no <others>, then only named fragments are considered and other are ignored
		//       - Excluded jars are not scanned for annotated servlets, filters or listeners.
		//       - If a discovered ServletContainerInitializer is loaded from an excluded jar, it will be ignored.
		//       - Irrespective of the setting of metadata-complete, jars excluded by <absolute-ordering> elements are
		//         not scanned for classes to be handled by any ServletContainerInitializer.
		//   - relative ordering (without <absolute-ordering> in "main" web.xml, with <ordering> elements in fragments)
		//       - order is determined using <before> and <after> elements in web-fragment.xml
		Set<String> absoluteOrder = mainWebXml.getAbsoluteOrdering();
		boolean parseRequired = absoluteOrder == null || !absoluteOrder.isEmpty();

		Bundle paxWebJspBundle = Utils.getPaxWebJspBundle(bundle);

		// collect all the JARs that need to be scanned for web-fragments (if metadata-complete="false") or
		// that may provide SCIs (regardles of metadata-complete)
		// In JavaEE env it's quite easy:
		//  - /WEB-INF/*.jar files
		//  - the JARs from URLClassLoaders of the ClassLoader hierarchy starting from web context's ClassLoader
		//  - Tomcat doesn't scan WEB-INF/classes/META-INF for fragments
		// In OSGi/PaxWeb it's different, but respectively:
		//  - 128.3.6 Dynamic Content: *.jar files from Bundle-ClassPath (not necessarily including
		//    /WEB-INF/*.jar libs!)
		//  - Arbitrary decision in Pax Web: all the bundles wired to current bundle via osgi.wiring.bundle
		//    (Require-Bundle) and osgi.wiring.package namespaces (Import-Package)
		//
		// I assume that the WAB bundle itself os not one of the bundles to be searched for web-fragment.xml.
		// These will be searched only from Bundle-ClassPath and bundle/package wires

		Map<String, WebXml> fragments = new LinkedHashMap<>();

		// see org.apache.catalina.startup.ContextConfig.processJarsForWebFragments()

		// Scan bundle JARs (WEB-INF/lib in JavaEE). Tomcat doesn't scan WEB-INF/classes for web-fragment.xml
		// these don't have to be transitively checked (though Tomcat has an option to scan JARs from a JAR's
		// Class-Path manifest header - see org.apache.tomcat.util.scan.StandardJarScanner.processManifest())

		// just take JARs. We don't want web-fragment.xml from /WEB-INF/classes and we don't want such entry to be
		// represented as WebXml fragment
		URL[] jars = ClassPathUtil.getClassPathJars(bundle, false);
		for (URL url : jars) {
			if (LOG.isTraceEnabled()) {
				LOG.trace("Scanning WAB ClassPath entry {}", url);
			}
			try {
				// never search for web-fragment.xml in non-jar Bundle-ClassPath entry - Tomcat doesn't
				// scan WEB-INF/classes for web-fragment.xmls
				WebXml fragment = process(parser, url, parseRequired);
				addFragment(fragments, fragment, url.toString());
				wabClassPath.put(fragment.getName(), url);
			} catch (Exception e) {
				LOG.warn("Problem scanning {}: {}", url, e.getMessage(), e);
			}
		}

		// The WAB itself may have attached bundle fragments, which should be treated (my decision) as webAppJars
		// i.e., as /WEB-INF/lib/*.jar libs in JavaEE
		BundleWiring wiring = bundle.adapt(BundleWiring.class);
		if (wiring != null) {
			List<BundleWire> hostWires = wiring.getProvidedWires(HostNamespace.HOST_NAMESPACE);
			if (hostWires != null) {
				for (BundleWire wire : hostWires) {
					Bundle b = wire.getRequirerWiring().getBundle();
					if (LOG.isTraceEnabled()) {
						LOG.trace("Scanning WAB fragment {}", b);
					}
					try {
						// take bundle.getEntry("/") as the URL of the fragment
						URL fragmentRootURL = b.getEntry("/");
						WebXml fragment = process(parser, fragmentRootURL, parseRequired);
						// we have to tweak the "jar name" in such way, that we can find the fragment again later
						fragment.setJarName(String.format("%d.bundle", b.getBundleId()));
						addFragment(fragments, fragment, fragmentRootURL.toString());
						wabClassPath.put(fragment.getName(), fragmentRootURL);
					} catch (Exception e) {
						LOG.warn("Problem scanning {}: {}", b, e.getMessage(), e);
					}
				}
			}
		}

		// Scan reachable bundles (ClassPath hierarchy in JavaEE) - without the WAB itself

		Set<Bundle> processedBundles = new HashSet<>();
		processedBundles.add(bundle);
		Deque<Bundle> bundles = new LinkedList<>();
		// added as already processed, but we need it to get reachable bundles
		bundles.add(bundle);
		if (paxWebJspBundle != null) {
			// this will give us access to Jasper SCI even if WAB doesn't have explicit
			// wires to pax-web-jsp or other JSTL implementations
			bundles.add(paxWebJspBundle);
		}
		while (bundles.size() > 0) {
			// org.apache.tomcat.util.scan.StandardJarScanner.processURLs() - Tomcat traverses CL hierarchy
			// and collects non-filtered (see conf/catalina.properties:
			// "tomcat.util.scan.StandardJarScanFilter.jarsToSkip" property) JARs from all URLClassLoaders
			Bundle scannedBundle = bundles.pop();
			if (IGNORED_BUNDLES.contains(scannedBundle.getSymbolicName()) || scannedBundle.getBundleId() == 0L) {
				continue;
			}

			Set<Bundle> reachable = new HashSet<>();
			ClassPathUtil.getBundlesInClassSpace(scannedBundle, reachable);
			for (Bundle rb : reachable) {
				if (!processedBundles.contains(rb) && !Utils.isFragment(rb)) {
					bundles.add(rb);
				}
			}
			if (processedBundles.contains(scannedBundle)) {
				continue;
			}
			if (LOG.isTraceEnabled()) {
				LOG.trace("Scanning Bundle {}", scannedBundle);
			}

			try {
				List<WebXml> fragmentList = process(parser, scannedBundle, parseRequired);
				for (WebXml fragment : fragmentList) {
					addFragment(fragments, fragment, null);
					reachableBundles.put(fragment.getName(), scannedBundle);
				}
				processedBundles.add(scannedBundle);
			} catch (Exception e) {
				LOG.warn("Problem scanning {}: {}", scannedBundle, e.getMessage(), e);
			}
		}

		return fragments;
	}

	/**
	 * Process a JAR or other URL from Bundle's {@code Bundle-ClassPath} as a web fragment. This is considered (in
	 * Tomcat's terms) as "webapp JAR"
	 *
	 * @param parser
	 * @param url
	 * @param parseRequired
	 * @return
	 * @throws IOException
	 */
	private WebXml process(WebXmlParser parser, URL url, boolean parseRequired) throws IOException {
		WebXml fragment = new WebXml();
		fragment.setWebappJar(true);
		fragment.setDelegate(false);

		URL fragmentURL = null;
		if (parseRequired) {
			List<URL> urls = ClassPathUtil.findEntries(bundle, new URL[] { url }, "META-INF", "web-fragment.xml", false);
			// there should be at most one, because we pass one URL in the array
			if (urls.size() > 0) {
				fragmentURL = urls.get(0);
				fragment.setURL(fragmentURL);
			}
		}

		// see org.apache.tomcat.util.descriptor.web.FragmentJarScannerCallback.scan()
		boolean found = false;
		if (fragmentURL == null) {
			fragment.setDistributable(true);
		} else {
			// this may fail, but we won't stop the parsing
			if (!parser.parseWebXml(fragmentURL, fragment, true)) {
				fragmentParsingOK = false;
			} else {
				found = true;
			}
		}

		if (fragment.getName() == null) {
			fragment.setName(url.toString());
		}
		fragment.setJarName(extractJarFileName(url.toString()));

		if (found) {
			LOG.trace("Found web fragment \"{}\": {}", fragment.getName(), fragmentURL);
		}

		return fragment;
	}

	/**
	 * Process a {@link Bundle} as a web fragment. This is also considered (in Tomcat's terms) as "webapp JAR"
	 * because it's quite hard to split all the runtime bundles into "platform" and "application" bundles.
	 *
	 * @param parser
	 * @param bundle
	 * @param parseRequired
	 * @return
	 * @throws IOException
	 */
	private List<WebXml> process(WebXmlParser parser, Bundle bundle, boolean parseRequired) throws IOException {
		// Tomcat's org.apache.catalina.startup.ContextConfig.webConfig() says that "web-fragment.xml files are ignored
		// for _container provided JARs_.". By "container provided JARs" Tomcat means "JARs are treated as application
		// provided until the common class loader is reached".
		// And common class loader is the CL which loaded org.apache.tomcat.util.scan.StandardJarScanner class, which
		// comes from $CATALINA_HOME/lib/tomcat-util-scan.jar

		// https://tomcat.apache.org/tomcat-9.0-doc/class-loader-howto.html#Advanced_configuration allows to
		// configure (conf/catalina.properties: "shared.loader" property) "shared class loader" for which the
		// "common class loader" is a parent. So all jars inside "shared class loader" are actually application
		// libraries

		// Thus in OSGi, we'd have to treat all reachable bundles either as application bundles or container bundles.
		// For example, user may have installed myfaces-impl.jar as a bundle (it contains META-INF/web-fragment.xml with
		// org.apache.myfaces.webapp.StartupServletContextListener).
		// This is out-of-specification decision, because OSGi CMPN 128 Web Application specification says only
		// about the WAB itself (and its Bundle-ClassPath). It's hard to divide all the bundles into "container bundles"
		// and "application bundles". I know there's 134 Subsystem Service Specification, but I think it'd be
		// an overkill
		// the org.apache.tomcat.util.descriptor.web.WebXml.getWebappJar() determines only whether the JAR providing
		// the web-fragment.xml is subject to javax.servlet.ServletContext.ORDERED_LIBS

		List<URL> fragmentURLs = new LinkedList<>();
		if (parseRequired) {
			List<URL> urls = ClassPathUtil.findEntries(bundle, "META-INF", "web-fragment.xml", false, false);
			// there may be more than one, because we access bundle fragments as well
			fragmentURLs.addAll(urls);
		}

		Map<WebXml, Boolean> fragments = new LinkedHashMap<>();
		Map<WebXml, URL> fragmentURLMap = new LinkedHashMap<>();

		// see org.apache.tomcat.util.descriptor.web.FragmentJarScannerCallback.scan()
		if (fragmentURLs.size() == 0) {
			WebXml fragment = new WebXml();
			// mark as "container fragment", so it's not affected by the ordering mechanism
			fragment.setWebappJar(false);
			fragment.setDelegate(false);
			fragment.setDistributable(true);
			String id = String.format("%s/%s", bundle.getSymbolicName(), bundle.getVersion().toString());
			fragment.setName(id);
			fragment.setJarName(id);
			fragments.put(fragment, Boolean.FALSE);

			// return now to not deal with missing name/jarName again
			return new ArrayList<>(fragments.keySet());
		} else {
			for (URL fragmentURL : fragmentURLs) {
				// this may fail, but we won't stop the parsing
				WebXml fragment = new WebXml();
				// mark as "container fragment", so it's not affected by the ordering mechanism
				fragment.setWebappJar(false);
				fragment.setDelegate(false);
				fragment.setURL(fragmentURL);
				if (!parser.parseWebXml(fragmentURL, fragment, true)) {
					fragmentParsingOK = false;
					fragments.put(fragment, Boolean.FALSE);
				} else {
					fragments.put(fragment, Boolean.TRUE);
				}
				fragmentURLMap.put(fragment, fragmentURL);
			}
		}

		// iterate over fragments that were parsed (successfully or not)
		fragments.forEach((fragment, ok) -> {
			String url = fragmentURLMap.get(fragment).toString();
			if (fragment.getName() == null) {
				fragment.setName(url);
			}
			// this is tricky. In JavaEE it's just simple name of a JAR backing up this web fragment. But in OSGi
			// it may be a "bundle:" URL so we can't extract any good name from it
//			fragment.setJarName(...);

			if (ok) {
				LOG.trace("Found web fragment \"{}\": {}", fragment.getName(), url);
			}
		});

		return new ArrayList<>(fragments.keySet());
	}

	/**
	 * See {@code org.apache.tomcat.util.descriptor.web.FragmentJarScannerCallback.addFragment()}
	 * @param fragments
	 * @param fragment
	 */
	private void addFragment(Map<String, WebXml> fragments, WebXml fragment, String fallbackId) {
		if (fragments.containsKey(fragment.getName())) {
			String duplicateName = fragment.getName();
			fragments.get(duplicateName).setDuplicated(true);
			if (fallbackId != null) {
				// Rename the current fragment so it doesn't clash
				LOG.warn("There already exists a web fragment named {}. Renaming to {}.", duplicateName, fallbackId);
				fragment.setName(fallbackId);
			} else {
				throw new IllegalArgumentException("Can't add a fragment named \"" + fragment.getName() + "\". It is already added.");
			}
		}
		fragments.put(fragment.getName(), fragment);
	}

	private String extractJarFileName(String uri) {
		if (uri.endsWith("!/")) {
			uri = uri.substring(0, uri.length() - 2);
		}
		return uri.substring(uri.lastIndexOf('/') + 1);
	}

	private void loadSCI(URL url, Bundle bundle, List<ServletContainerInitializer> scis) {
		if (LOG.isTraceEnabled()) {
			LOG.trace("Loading {} from {}", url, bundle);
		}
		try (InputStream is = url.openStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			String line = null;
			while ((line = reader.readLine()) != null) {
				String name = line.trim();
				if (name.startsWith("#")) {
					continue;
				}
				int idx = name.indexOf('#');
				if (idx > 0) {
					name = name.substring(0, idx).trim();
				}
				if (name.length() > 0) {
					try {
						Class<?> sciClass = bundle.loadClass(name);
						ServletContainerInitializer sci = (ServletContainerInitializer) sciClass.newInstance();
						LOG.debug("SCI {} loaded from {}", sci, bundle);
						scis.add(sci);
					} catch (ClassNotFoundException | ClassCastException | InstantiationException | IllegalAccessException e) {
						LOG.error("Problem loading SCI class from {}: {}", url, e.getMessage(), e);
					}
				}
			}
		} catch (IOException e) {
			LOG.error("Problem reading SCI service class from {}: {}", url, e.getMessage(), e);
		}
	}

	/**
	 * An internal state of the {@link BundleWebApplication} - there are more such states than
	 * {@link WebApplicationEvent.State events} related to {@link BundleWebApplication} lifecycle. These states
	 * are not directly communicated to observers, but are used to control the lifecycle of the web application.
	 */
	enum State {
		/** Initial state after BundleWebApplication has just been created */
		UNCONFIGURED,

		/** State, where parsing of web.xml, fragments and annotations are processed */
		CONFIGURING,

		/** State after web configuration is ready, but we have to check if the target context (path) is free */
		ALLOCATING_CONTEXT,

		/**
		 * State in which we're actually registering web elements from web.xml/web-fragment.xml/annotations into
		 * allocated context {@link ServletContext context}.
		 */
		DEPLOYING,

		/** Final state - completely and successfully deployed application */
		DEPLOYED,

		/** State where {@link WebContainer} reference is needed, but it's not available */
		WAITING_FOR_WEB_CONTAINER,

		/**
		 * <p>State after web configuration is ready, but simply the target context path is used by another WAB
		 * (or Whiteboard/HttpService context).</p>
		 *
		 * <p>Before Pax Web 8, "WAITING" state was used when a WAB was held due to conflict with existing WAB that
		 * used the same context path (and virtual hosts). Now we have more specialized state and "WAITING" is no
		 * longer used</p>
		 */
		WAITING_FOR_CONTEXT,

		/** The WAB is in the process of undeployment */
		UNDEPLOYING,

		/** The WAB is fully undeployed */
		UNDEPLOYED,

		/**
		 * Failed beyond any repair. This state is used after parsing errors or other validation errors. FAILED
		 * {@link BundleWebApplication} will never be attempted to be deployed again. For a case where WAB is
		 * waiting for a context path to be <em>free</em>, see {@link State#WAITING_FOR_CONTEXT}.
		 */
		FAILED
	}

//	static final Comparator<WebAppServlet> WEB_APP_SERVLET_COMPARATOR =
//			(servlet1, servlet2) -> servlet1.getLoadOnStartup() - servlet2.getLoadOnStartup();
//
//	/**
//	 * The URL to the web.xml for the web app.
//	 */
//	private URL webXmlURL;
//
//	/**
//	 * Application display name.
//	 */
//	private String displayName;
//	/**
//	 * Context name.
//	 */
//	private String contextName;
//	/**
//	 * Root path.
//	 */
//	private String rootPath;
//	/**
//	 * Session timeout.
//	 */
//	private String sessionTimeout;
//
//	/**
//	 * The http context used during registration of error page. Is not set by
//	 * the parser but by the registration visitor during registration.
//	 */
//	private HttpContext httpContext;
//
//	/**
//	 * Servlets.
//	 */
//	private final Map<String, WebAppServlet> servlets;
//	/**
//	 * Mapping between servlet name and servlet mapping.
//	 */
//	private final Map<String, Set<WebAppServletMapping>> servletMappings;
//	/**
//	 * Filters.
//	 */
//	private final Map<String, WebAppFilter> filters;
//	/**
//	 * Mapping between filter name and filter mapping.
//	 */
//	private final Map<String, Set<WebAppFilterMapping>> filterMappings;
//	/**
//	 * Filters order. List in the order the filters should be applied. When read
//	 * from an web xml it should respect SRV.6.2.4 section in servlet specs
//	 * which is the order defined in filter mappings.
//	 */
//	private final List<String> orderedFilters;
//	/**
//	 * Context parameters.
//	 */
//	private final Set<WebAppInitParam> contextParams;
//	/**
//	 * Mime mappings.
//	 */
//	private final Set<WebAppMimeMapping> mimeMappings;
//	/**
//	 * Listeners.
//	 */
//	private final List<WebAppListener> listeners;
//	/**
//	 * Error pages.
//	 */
//	private final List<WebAppErrorPage> errorPages;
//	/**
//	 * Welcome files.
//	 */
//	private final List<String> welcomeFiles;
//	/**
//	 * Virtual Host List.
//	 */
//	private final List<String> virtualHostList;
//	/**
//	 * Connectors List
//	 */
//	private final List<String> connectorList;
//
//	/**
//	 * SecurityConstraints
//	 */
//	private final List<WebAppConstraintMapping> constraintsMapping;
//
//	private final List<WebAppSecurityRole> securityRoles;
//
//	private final List<WebAppLoginConfig> loginConfig;
//
//	private boolean metaDataComplete;
//
//	private final List<WebAppServletContainerInitializer> servletContainerInitializers;
//
//	private URL jettyWebXmlURL;
//
//	private List<URL> webFragments;
//
//	private boolean hasDependencies;
//
//	private List<String> sessionTrackingModes;
//
//	private WebAppCookieConfig sessionCookieConfig;
//
//	private WebAppJspConfig jspConfigDescriptor;
//
//	/**
//	 * Setter.
//	 *
//	 * @param displayName value to set
//	 */
//	public void setDisplayName(final String displayName) {
//		this.displayName = displayName;
//	}
//
//	private WebAppInitParam getWebAppInitParam(String name) {
//		for (WebAppInitParam p : contextParams) {
//			if (name.equals(p.getParamName())) {
//				return p;
//			}
//		}
//		return null;
//	}
//
//	/**
//	 * Setter.
//	 *
//	 * @param contextName value to set. Cannot be null.
//	 * @throws NullArgumentException if context name is null
//	 */
//	public void setContextName(final String contextName) {
//		NullArgumentException.validateNotNull(contextName, "Context name");
//		this.contextName = contextName;
//
//		// remove the previous setting.
//		WebAppInitParam prev = getWebAppInitParam("webapp.context");
//		if (prev != null) {
//			contextParams.remove(prev);
//		}
//
//		// set the context name into the context params
//		final WebAppInitParam initParam = new WebAppInitParam();
//		initParam.setParamName("webapp.context");
//		initParam.setParamValue(contextName);
//		contextParams.add(initParam);
//	}
//
//	public String getContextName() {
//		return contextName;
//	}
//
//	public void setRootPath(final String rootPath) {
//		NullArgumentException.validateNotNull(rootPath, "Root Path");
//		this.rootPath = rootPath;
//
//	}
//
//	public String getRootPath() {
//		return rootPath;
//	}
//
//	/**
//	 * Setter.
//	 *
//	 * @param minutes session timeout
//	 */
//	public void setSessionTimeout(final String minutes) {
//		sessionTimeout = minutes;
//	}
//
//	/**
//	 * Getter.
//	 *
//	 * @return session timeout in minutes
//	 */
//	public String getSessionTimeout() {
//		return sessionTimeout;
//	}
//
//	/**
//	 * Getter.
//	 *
//	 * @return bundle
//	 */
//	public Bundle getBundle() {
//		return bundle;
//	}
//
//	/**
//	 * Setter.
//	 *
//	 * @param bundle value to set
//	 */
//	public void setBundle(Bundle bundle) {
//		this.bundle = bundle;
//	}
//
//	/**
//	 * Add a servlet.
//	 *
//	 * @param servlet to add
//	 * @throws NullArgumentException if servlet, servlet name or servlet class is null
//	 */
//	public void addServlet(final WebAppServlet servlet) {
//		NullArgumentException.validateNotNull(servlet, "Servlet");
//		NullArgumentException.validateNotNull(servlet.getServletName(),
//				"Servlet name");
//		if (servlet instanceof WebAppJspServlet) {
//			NullArgumentException.validateNotNull(
//					((WebAppJspServlet) servlet).getJspPath(), "JSP-path");
//		} else {
//			NullArgumentException.validateNotNull(
//					servlet.getServletClassName(), "Servlet class");
//		}
//		servlets.put(servlet.getServletName(), servlet);
//		// add aliases for servlet mappings added before servlet
//		for (WebAppServletMapping mapping : getServletMappings(servlet
//				.getServletName())) {
//			servlet.addUrlPattern(mapping.getUrlPattern());
//		}
//	}
//
//	/**
//	 * Add a servlet mapping.
//	 *
//	 * @param servletMapping to add
//	 * @throws NullArgumentException if servlet mapping, servlet name or url pattern is null
//	 */
//	public void addServletMapping(final WebAppServletMapping servletMapping) {
//		NullArgumentException
//				.validateNotNull(servletMapping, "Servlet mapping");
//		NullArgumentException.validateNotNull(servletMapping.getServletName(),
//				"Servlet name");
//		NullArgumentException.validateNotNull(servletMapping.getUrlPattern(),
//				"Url pattern");
//		Set<WebAppServletMapping> webAppServletMappings = servletMappings
//				.get(servletMapping.getServletName());
//		if (webAppServletMappings == null) {
//			webAppServletMappings = new HashSet<>();
//			servletMappings.put(servletMapping.getServletName(),
//					webAppServletMappings);
//		}
//		webAppServletMappings.add(servletMapping);
//		final WebAppServlet servlet = servlets.get(servletMapping
//				.getServletName());
//		// can be that the servlet is not yet added
//		if (servlet != null) {
//			servlet.addUrlPattern(servletMapping.getUrlPattern());
//		}
//	}
//
//	/**
//	 * Returns a servlet mapping by servlet name.
//	 *
//	 * @param servletName servlet name
//	 * @return array of servlet mappings for requested servlet name
//	 */
//	public List<WebAppServletMapping> getServletMappings(
//			final String servletName) {
//		final Set<WebAppServletMapping> webAppServletMappings = servletMappings
//				.get(servletName);
//		if (webAppServletMappings == null) {
//			return new ArrayList<>();
//		}
//		return new ArrayList<>(webAppServletMappings);
//	}
//
//	/**
//	 * Add a filter.
//	 *
//	 * @param filter to add
//	 * @throws NullArgumentException if filter, filter name or filter class is null
//	 */
//	public void addFilter(final WebAppFilter filter) {
//		NullArgumentException.validateNotNull(filter, "Filter");
//		NullArgumentException.validateNotNull(filter.getFilterName(),
//				"Filter name");
//		NullArgumentException.validateNotNull(filter.getFilterClass(),
//				"Filter class");
//		filters.put(filter.getFilterName(), filter);
//		// add url patterns and servlet names for filter mappings added before
//		// filter
//		for (WebAppFilterMapping mapping : getFilterMappings(filter
//				.getFilterName())) {
//			if (mapping.getUrlPattern() != null
//					&& mapping.getUrlPattern().trim().length() > 0) {
//				filter.addUrlPattern(mapping.getUrlPattern());
//			}
//			if (mapping.getServletName() != null
//					&& mapping.getServletName().trim().length() > 0) {
//				filter.addServletName(mapping.getServletName());
//			}
//		}
//	}
//
//	/**
//	 * Add a filter mapping.
//	 *
//	 * @param filterMapping to add
//	 * @throws NullArgumentException if filter mapping or filter name is null
//	 */
//	public void addFilterMapping(final WebAppFilterMapping filterMapping) {
//		NullArgumentException.validateNotNull(filterMapping, "Filter mapping");
//		NullArgumentException.validateNotNull(filterMapping.getFilterName(),
//				"Filter name");
//
//		final String filterName = filterMapping.getFilterName();
//		if (!orderedFilters.contains(filterName)) {
//			orderedFilters.add(filterName);
//		}
//		Set<WebAppFilterMapping> webAppFilterMappings = filterMappings
//				.get(filterName);
//		if (webAppFilterMappings == null) {
//			webAppFilterMappings = new HashSet<>();
//			filterMappings.put(filterName, webAppFilterMappings);
//		}
//		webAppFilterMappings.add(filterMapping);
//		final WebAppFilter filter = filters.get(filterName);
//		// can be that the filter is not yet added
//		if (filter != null) {
//			if (filterMapping.getUrlPattern() != null
//					&& filterMapping.getUrlPattern().trim().length() > 0) {
//				filter.addUrlPattern(filterMapping.getUrlPattern());
//			}
//			if (filterMapping.getServletName() != null
//					&& filterMapping.getServletName().trim().length() > 0) {
//				filter.addServletName(filterMapping.getServletName());
//			}
//			if (filterMapping.getDispatcherTypes() != null
//					&& !filterMapping.getDispatcherTypes().isEmpty()) {
//				for (DispatcherType dispatcherType : filterMapping.getDispatcherTypes()) {
//					filter.addDispatcherType(dispatcherType);
//				}
//			}
//		}
//	}
//
//	/**
//	 * Returns filter mappings by filter name.
//	 *
//	 * @param filterName filter name
//	 * @return array of filter mappings for requested filter name
//	 */
//	public List<WebAppFilterMapping> getFilterMappings(final String filterName) {
//		final Set<WebAppFilterMapping> webAppFilterMappings = filterMappings
//				.get(filterName);
//		if (webAppFilterMappings == null) {
//			return new ArrayList<>();
//		}
//		return new ArrayList<>(webAppFilterMappings);
//	}
//
//	/**
//	 * Add a listener.
//	 *
//	 * @param listener to add
//	 * @throws NullArgumentException if listener or listener class is null
//	 */
//	public void addListener(final WebAppListener listener) {
//		NullArgumentException.validateNotNull(listener, "Listener");
//		NullArgumentException.validateNotNull(listener.getListenerClass(),
//				"Listener class");
//		if (!listeners.contains(listener)) {
//			listeners.add(listener);
//		}
//	}
//
//	/**
//	 * Add an error page.
//	 *
//	 * @param errorPage to add
//	 * @throws NullArgumentException if error page is null or both error type and exception code
//	 *                               is null
//	 */
//	public void addErrorPage(final WebAppErrorPage errorPage) {
//		NullArgumentException.validateNotNull(errorPage, "Error page");
//		if (errorPage.getErrorCode() == null
//				&& errorPage.getExceptionType() == null) {
//			throw new NullPointerException(
//					"At least one of error type or exception code must be set");
//		}
//		errorPages.add(errorPage);
//	}
//
//	/**
//	 * Add a welcome file.
//	 *
//	 * @param welcomeFile to add
//	 * @throws NullArgumentException if welcome file is null or empty
//	 */
//	public void addWelcomeFile(final String welcomeFile) {
//		NullArgumentException.validateNotEmpty(welcomeFile, "Welcome file");
//		welcomeFiles.add(welcomeFile);
//	}
//
//	/**
//	 * Return all welcome files.
//	 *
//	 * @return an array of all welcome files
//	 */
//	public String[] getWelcomeFiles() {
//		return welcomeFiles.toArray(new String[welcomeFiles.size()]);
//	}
//
//	/**
//	 * Add a context param.
//	 *
//	 * @param contextParam to add
//	 * @throws NullArgumentException if context param, param name or param value is null
//	 */
//	public void addContextParam(final WebAppInitParam contextParam) {
//		NullArgumentException.validateNotNull(contextParam, "Context param");
//		NullArgumentException.validateNotNull(contextParam.getParamName(),
//				"Context param name");
//		NullArgumentException.validateNotNull(contextParam.getParamValue(),
//				"Context param value");
//		contextParams.add(contextParam);
//	}
//
//	/**
//	 * Return all context params.
//	 *
//	 * @return an array of all context params
//	 */
//	public WebAppInitParam[] getContextParams() {
//		return contextParams.toArray(new WebAppInitParam[contextParams.size()]);
//	}
//
//	/**
//	 * Add a mime mapping.
//	 *
//	 * @param mimeMapping to add
//	 * @throws NullArgumentException if mime mapping, extension or mime type is null
//	 */
//	public void addMimeMapping(final WebAppMimeMapping mimeMapping) {
//		NullArgumentException.validateNotNull(mimeMapping, "Mime mapping");
//		NullArgumentException.validateNotNull(mimeMapping.getExtension(),
//				"Mime mapping extension");
//		NullArgumentException.validateNotNull(mimeMapping.getMimeType(),
//				"Mime mapping type");
//		mimeMappings.add(mimeMapping);
//	}
//
//	/**
//	 * Add a security constraint
//	 *
//	 * @param constraintMapping
//	 * @throws NullArgumentException if security constraint is null
//	 */
//	public void addConstraintMapping(
//			final WebAppConstraintMapping constraintMapping) {
//		NullArgumentException.validateNotNull(constraintMapping,
//				"constraint mapping");
//		constraintsMapping.add(constraintMapping);
//	}
//
//	/**
//	 * @return list of {@link WebAppConstraintMapping}
//	 */
//	public WebAppConstraintMapping[] getConstraintMappings() {
//		return constraintsMapping
//				.toArray(new WebAppConstraintMapping[constraintsMapping.size()]);
//	}
//
//	/**
//	 * Adds a security role
//	 *
//	 * @param securityRole
//	 */
//	public void addSecurityRole(final WebAppSecurityRole securityRole) {
//		NullArgumentException.validateNotNull(securityRole, "Security Role");
//		securityRoles.add(securityRole);
//	}
//
//	/**
//	 * @return list of {@link WebAppSecurityRole}
//	 */
//	public WebAppSecurityRole[] getSecurityRoles() {
//		return securityRoles.toArray(new WebAppSecurityRole[securityRoles
//				.size()]);
//	}
//
//	/**
//	 * Adds a login config
//	 *
//	 * @param webApploginConfig
//	 */
//	public void addLoginConfig(final WebAppLoginConfig webApploginConfig) {
//		NullArgumentException
//				.validateNotNull(webApploginConfig, "Login Config");
//		NullArgumentException.validateNotNull(
//				webApploginConfig.getAuthMethod(),
//				"Login Config Authorization Method");
//		// NullArgumentException.validateNotNull(loginConfig.getRealmName(),
//		// "Login Config Realm Name");
//		loginConfig.add(webApploginConfig);
//	}
//
//	/**
//	 * @return list of {@link WebAppLoginConfig}
//	 */
//	public WebAppLoginConfig[] getLoginConfigs() {
//		return loginConfig.toArray(new WebAppLoginConfig[loginConfig.size()]);
//	}
//
//	/**
//	 * Return all mime mappings.
//	 *
//	 * @return an array of all mime mappings
//	 */
//	public WebAppMimeMapping[] getMimeMappings() {
//		return mimeMappings.toArray(new WebAppMimeMapping[mimeMappings.size()]);
//	}
//
//	/**
//	 * Getter.
//	 *
//	 * @return http context
//	 */
//	public HttpContext getHttpContext() {
//		return httpContext;
//	}
//
//	/**
//	 * Setter.
//	 *
//	 * @param httpContext value to set
//	 */
//	public void setHttpContext(HttpContext httpContext) {
//		this.httpContext = httpContext;
//	}
//
//	/**
//	 * Accepts a visitor for inner elements.
//	 *
//	 * @param visitor visitor
//	 */
//	public void accept(final WebAppVisitor visitor) {
//		visitor.visit(this); // First do everything else
//
//		for (WebAppListener listener : listeners) {
//			visitor.visit(listener);
//		}
//		if (!filters.isEmpty()) {
//			// first visit the filters with a filter mapping in mapping order
//			final List<WebAppFilter> remainingFilters = new ArrayList<>(
//					filters.values());
//			for (String filterName : orderedFilters) {
//				final WebAppFilter filter = filters.get(filterName);
//				visitor.visit(filter);
//				remainingFilters.remove(filter);
//			}
//			// then visit filters without a mapping order in the order they were
//			// added
//			for (WebAppFilter filter : remainingFilters) {
//				visitor.visit(filter);
//			}
//		}
//		if (!servlets.isEmpty()) {
//			for (WebAppServlet servlet : getSortedWebAppServlet()) {
//				// Fix for PAXWEB-205
//				visitor.visit(servlet);
//			}
//		}
//		if (!constraintsMapping.isEmpty()) {
//			for (WebAppConstraintMapping constraintMapping : constraintsMapping) {
//				visitor.visit(constraintMapping);
//			}
//
//		}
//
//		for (WebAppErrorPage errorPage : errorPages) {
//			visitor.visit(errorPage);
//		}
//
//		visitor.end();
//	}
//
//	private Collection<WebAppServlet> getSortedWebAppServlet() {
//		List<WebAppServlet> webAppServlets = new ArrayList<>(
//				servlets.values());
//		Collections.sort(webAppServlets, WEB_APP_SERVLET_COMPARATOR);
//
//		return webAppServlets;
//	}
//
//	public URL getWebXmlURL() {
//		return webXmlURL;
//	}
//
//	public void setWebXmlURL(URL webXmlURL) {
//		this.webXmlURL = webXmlURL;
//	}
//
//	public void setJettyWebXmlURL(URL jettyWebXmlURL) {
//		this.jettyWebXmlURL = jettyWebXmlURL;
//	}
//
//	public URL getJettyWebXmlURL() {
//		return jettyWebXmlURL;
//	}
//
//	public boolean getHasDependencies() {
//		return hasDependencies;
//	}
//
//	public void setHasDependencies(boolean hasDependencies) {
//		this.hasDependencies = hasDependencies;
//	}
//
//	public void setVirtualHostList(List<String> virtualHostList) {
//		this.virtualHostList.clear();
//		this.virtualHostList.addAll(virtualHostList);
//	}
//
//	public List<String> getVirtualHostList() {
//		return virtualHostList;
//	}
//
//	public void setConnectorList(List<String> connectorList) {
//		this.connectorList.clear();
//		this.connectorList.addAll(connectorList);
//	}
//
//	public List<String> getConnectorList() {
//		return connectorList;
//	}
//
//	public void setMetaDataComplete(boolean metaDataComplete) {
//		this.metaDataComplete = metaDataComplete;
//	}
//
//	public boolean getMetaDataComplete() {
//		return metaDataComplete;
//	}
//
//	public WebAppServlet findServlet(String servletName) {
//		if (this.servlets.containsKey(servletName)) {
//			return this.servlets.get(servletName);
//		} else {
//			//PAXWEB-724 special handling
//			Collection<WebAppServlet> values = this.servlets.values();
//			for (WebAppServlet webAppServlet : values) {
//				String servletClassName = webAppServlet.getServletClassName();
//				if (servletName.equals(servletClassName)) {
//					return webAppServlet;
//				}
//			}
//			return null;
//		}
//	}
//
//	public WebAppFilter findFilter(String filterName) {
//		if (this.filters.containsKey(filterName)) {
//			return this.filters.get(filterName);
//		} else {
//			return null;
//		}
//	}
//
//	public void addServletContainerInitializer(
//			WebAppServletContainerInitializer servletContainerInitializer) {
//		NullArgumentException.validateNotNull(servletContainerInitializer,
//				"ServletContainerInitializer");
//		this.servletContainerInitializers.add(servletContainerInitializer);
//	}
//
//	public List<WebAppServletContainerInitializer> getServletContainerInitializers() {
//		return servletContainerInitializers;
//	}
//
//	/**
//	 * @return the webFragments
//	 */
//	public List<URL> getWebFragments() {
//		return webFragments;
//	}
//
//	/**
//	 * @param webFragments the webFragments to set
//	 */
//	public void setWebFragments(List<URL> webFragments) {
//		this.webFragments = webFragments;
//	}
//
//	public void addSessionTrackingMode(String sessionTrackingMode) {
//		NullArgumentException.validateNotNull(sessionTrackingMode,
//				"session tracking mode");
//		sessionTrackingModes.add(sessionTrackingMode);
//	}
//
//	public List<String> getSessionTrackingModes() {
//		return sessionTrackingModes;
//	}
//
//	public void setSessionCookieConfig(WebAppCookieConfig sessionCookieConfig) {
//		this.sessionCookieConfig = sessionCookieConfig;
//	}
//
//	public void setJspConfigDescriptor(WebAppJspConfig webAppJspConfig) {
//		jspConfigDescriptor = webAppJspConfig;
//	}
//
//	public WebAppJspConfig getJspConfigDescriptor() {
//		return jspConfigDescriptor;
//	}
//
//	public WebAppCookieConfig getSessionCookieConfig() {
//		return sessionCookieConfig;
//	}

}
