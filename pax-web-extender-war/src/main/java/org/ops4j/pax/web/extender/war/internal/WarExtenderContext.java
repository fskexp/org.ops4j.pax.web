/*
 * Copyright 2020 OPS4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.extender.war.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.felix.utils.extender.AbstractExtender;
import org.apache.felix.utils.extender.Extension;
import org.ops4j.pax.web.extender.war.internal.model.BundleWebApplication;
import org.ops4j.pax.web.service.PaxWebConstants;
import org.ops4j.pax.web.service.WebContainer;
import org.ops4j.pax.web.service.spi.model.events.WebApplicationEvent;
import org.ops4j.pax.web.service.spi.util.Utils;
import org.ops4j.pax.web.service.spi.util.WebContainerListener;
import org.ops4j.pax.web.service.spi.util.WebContainerManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Similarly to {@code org.ops4j.pax.web.extender.whiteboard.internal.WhiteboardExtenderContext}, this
 * <em>extender context</em> manages interaction between Bundles converted into <em>web applications</em> and
 * dynamically available {@link org.ops4j.pax.web.service.WebContainer} service, where all the web components and
 * web contexts may be installed/registered.</p>
 *
 * <p>This class is the main class of OSGi CMPN R7 128 "Web Applications Specification".</p>
 *
 * <p>Differently than with {@code org.ops4j.pax.web.extender.whiteboard.internal.WhiteboardExtenderContext}, here we
 * have two thread pools configured - one inside {@link WebContainerManager} and one from
 * {@code org.apache.felix.utils.extender.AbstractExtender#executors} where WAB extensions are processed. The
 * pool from Felix extender is used to process lifecycle stages of {@link BundleWebApplication} in a similar way
 * as Aries BlueprintContainers.</p>
 */
public class WarExtenderContext implements WebContainerListener {

	// even the structure of the fields attempts to match the structure of WhiteboardExtenderContext

	private static final Logger LOG = LoggerFactory.getLogger(WarExtenderContext.class);

	private final BundleContext bundleContext;

	/** This is were the lifecycle of {@link WebContainer} is managed. */
	private final WebContainerManager webContainerManager;

	/** Mapping between a {@link Bundle} and a {@link BundleWebApplication} created from the bundle. */
	private final Map<Bundle, BundleWebApplication> webApplications = new HashMap<>();

	/**
	 * This lock prevents concurrent access to a list of {@link #webApplications}. This is similar to how
	 * whiteboard extender context manages a list of <em>bundle applications</em>.
	 */
	private final Lock lock = new ReentrantLock();

	/**
	 * The same pool which is used for entire pax-web-extender-war, to progress through the lifecycle of the
	 * {@link BundleWebApplication}.
	 */
	private final ExecutorService pool;

	/** Used to send events related to entire Web Applications being installed/uninstalled. */
	private final WebApplicationEventDispatcher webApplicationEventDispatcher;








				/** Used to parser {@code web.xml} and fragmnets into a web application model */
//				private final WebAppParser webApplicationParser;
//				private WebObserver webObserver;
//				private final ServiceRegistration<WarManager> registration;







	/**
	 * Construct a {@link WarExtenderContext} with asynchronous (production) {@link WebContainerManager}
	 * @param bundleContext
	 * @param pool
	 */
	public WarExtenderContext(BundleContext bundleContext, ExecutorService pool) {
		this(bundleContext, pool, false);
	}

	/**
	 * Full constructor of {@link WarExtenderContext}
	 * @param bundleContext
	 * @param pool
	 * @param synchronous whether the embedded {@link WebContainerManager} should be synchronous (which is useful
	 *        for testing).
	 */
	public WarExtenderContext(BundleContext bundleContext, ExecutorService pool, boolean synchronous) {
		this.bundleContext = bundleContext;
		this.pool = pool;

		// dispatcher of events related to WAB lifecycle (128.5 Events)
		webApplicationEventDispatcher = new WebApplicationEventDispatcher(bundleContext);

		// web.xml, web-fragment.xml parser
//		webApplicationParser = new WebAppParser(bundleContext);

//		webObserver = new WebObserver(
//				webApplicationParser,
//				new WebAppPublisher(webApplicationEventDispatcher, bundleContext),
//				webApplicationEventDispatcher,
//				new DefaultWebAppDependencyManager(),
//				bundleContext);

//		registration = bundleContext.registerService(
//				WarManager.class, webObserver,
//				new Hashtable<>());

		webContainerManager = synchronous
				? new WebContainerManager(bundleContext, this)
				: new WebContainerManager(bundleContext, this, "HttpService->WarExtender");
		webContainerManager.initialize();

		// TODO: setup a listener to get notified about all the contexts being installed (Whiteboard and HttpService)
		//       so we can retry deployment of FAILED (due to context conflict) WABs
	}

	/**
	 * Cleans up everything related to pax-web-extender-war
	 */
	public void shutdown() {
//		if (webApplicationEventDispatcher != null) {
//			webApplicationEventDispatcher.destroy();
//			webApplicationEventDispatcher = null;
//		}
//
//		if (registration != null) {
//			registration.unregister();
//			registration = null;
//		}

		webContainerManager.shutdown();
	}

	/**
	 * Send a {@link BundleWebApplication} related event.
	 * @param event
	 */
	public void webEvent(WebApplicationEvent event) {
		webApplicationEventDispatcher.webEvent(event);
	}

	/**
	 * A method supporting {@link AbstractExtender#doCreateExtension(org.osgi.framework.Bundle)} for WAR
	 * publishing purposes.
	 *
	 * @param bundle
	 * @return
	 */
	public Extension createExtension(Bundle bundle) {
		if (bundle.getState() != Bundle.ACTIVE) {
			if (LOG.isTraceEnabled()) {
				LOG.trace("Ignoring a bundle {} in non-active state", bundle);
			}
			return null;
		}

		String context = Utils.getManifestHeader(bundle, PaxWebConstants.CONTEXT_PATH_KEY);
		if (context == null) {
			return null;
		}

		if (!context.startsWith("/") || (!"/".equals(context) && context.endsWith("/"))) {
			LOG.warn("{} manifest header of {} specifies invalid context path: {}. This bundle will not be processed.",
					PaxWebConstants.CONTEXT_PATH_KEY, bundle, context);
			return null;
		}

		// before Pax Web 8 there was a check whether the bundle can see javax.servlet.Servlet class. But
		// that's too harsh requirement, because a WAR can be used to serve static content only, without
		// registration of any servlet (at least explicitly - as the "default" server should be added for free)

		BundleWebApplication webApplication = new BundleWebApplication(bundle, webContainerManager, this, pool);
		webApplication.setContextPath(context);
		ServiceReference<WebContainer> ref = webContainerManager.currentWebContainerReference();
		if (ref != null) {
			// let's pass WebContainer reference if it's already available
			webApplication.webContainerAdded(ref);
		}
		lock.lock();
		try {
			webApplications.put(bundle, webApplication);
		} finally {
			lock.unlock();
		}

		// before Pax Web 8, the web application was immediately parsed (which is costly operation) and the WebApp
		// immediately entered DEPLOYING state
		// after the parsing was done, the WebApp was added to DefaultWebAppDependencyManager, where it was
		// given its own tracker of HttpService and when the association (WebApp + HttpService) was available,
		// WebAppDependencyHolder service was registered (a wrapper of a WebApp + HttpService), which in turn was
		// checked by WebAppPublisher during publish() called from the Extension.start().
		// publish() ... didn't publish the WebApp, but set up another tracker for WebAppDependencyHolder services,
		// and only when those were available (when notification arrived to WebAppDependencyListener), a final
		// "visitor" was passed to the WebApp, so the (previously parsed) web elements were registered in
		// a WebContainer
		// TL;DR - too many OSGi services were involved

		// returned extension will be started _asynchronously_. Before Pax Web 8, there were 3 hardcoded threads
		// available in the pax-web-extender-war that handled the above workflow. When HttpService was immediately
		// available, everything:
		//  - adding a WebApp to relevant vhost -> contextName -> list<WebApp>
		//  - WebAppDependencyListener getting notified about the availability of WebAppDependencyHolder (because
		//    it was registered before starting the extension in DefaultWebAppDependencyManager.addWebApp())
		//  - passing RegisterWebAppVisitorWC to a WebApp
		//  - interaction with WebContainer through visit() methods of the visitor
		// happened in the same extender (1 of 3) thread.
		//
		// However parsing of the web.xml (and sending of the DEPLOYING event) happened in a thread calling
		// BundleTrackerCustomizer.modifiedBundle() of the extender, which was usually the FelixStartLevel thread.
		//
		// Pax Web 8 uses original thread notifying about a bundle event only to create the extension, while
		// parsing of web.xml and folowing steps are performed in one of the extender threads from configurable
		// ExecutorService
		// Remember - the process (if we have more WABs to process) is not fully parallel, as it's synchronized
		// using pax-web-config thread (from pax-web-runtime) anyway - to interact with single ServerModel in
		// synchronized and consistent way. At least the parsing can be done in parallel

		return new WabExtension(bundle);
	}

	@Override
	public void webContainerChanged(ServiceReference<WebContainer> oldReference, ServiceReference<WebContainer> newReference) {
		// having received new reference to WebContainer service, we can finally move forward with registration
		// of the bundles which are found to be WABs
		if (oldReference != null) {
			webContainerRemoved(oldReference);
		}
		if (newReference != null) {
			webContainerAdded(newReference);
		}
	}

	@Override
	public void bundleStopped(Bundle bundle) {
		// we don't have to handle this method from WebContainerListener, as the same is delivered through
		// extension.destroy()
	}

	// --- Handling registration/unregistration of target WebContainer, where we want to register WAB applications

	public void webContainerAdded(ServiceReference<WebContainer> ref) {
		lock.lock();
		try {
			// We're setting a "current" reference to a WebContainer service for each WAB
			// The only thing we can guarantee is that we're not setting a reference when it's already set - every
			// time a reference changes, we first have to unset previous reference (if exists), so the lifecycle
			// is consistent
			// It's easier in pax-web-extender-whiteboard, because there we just have to remember whether the element
			// was already registered (when valid reference was available since the BundleWhiteboardApplication was
			// created) or not. Here each BundleWebApplication maybe be at different stage of their lifecycle, e.g.,
			// the web.xml parsing is performed (which actually doesn't require any available WebContainer service)
			webApplications.values().forEach(wab -> wab.webContainerAdded(ref));
		} finally {
			lock.unlock();
		}
	}

	public void webContainerRemoved(ServiceReference<WebContainer> ref) {
		lock.lock();
		try {
			// as with webContainerAdded, we can't remove a reference when it's not set, but it's still true that
			// the WAB may be at any stage of its lifecycle
			webApplications.values().forEach(wab -> wab.webContainerRemoved(ref));
		} finally {
			lock.unlock();
		}

		webContainerManager.releaseContainer(bundleContext, ref);
	}

	/**
	 * <p>The {@link Extension} representing a "WAB" (Web Application Bundle) which (according to 128.3 Web Application
	 * Bundle) is a {@link Bundle} with {@code Web-ContextPath} manifest header.</p>
	 *
	 * <p>No other constraints are put onto the WAB (like visibility of {@link javax.servlet.Servlet} class or the
	 * content). Also it doesn't matter whether the bundle originally contained the required manifest header or
	 * the header was applied using some URL handler (like pax-url-war).</p>
	 *
	 * <p>We will use this class only as a wrapper of {@link BundleWebApplication} (pointed to by a {@link Bundle}) and the
	 * responsibility of this extension is only to react to {@link Extension#start()} and {@link Extension#destroy()}
	 * methods. The lifecycle of the actual WAB will be managed through {@link BundleWebApplication} contract.</p>
	 */
	private class WabExtension implements Extension {

		private final Bundle bundle;

		WabExtension(Bundle bundle) {
			this.bundle = bundle;
		}

		@Override
		public void start() throws Exception {
			// start() method is called within a thread of the war extender pool (that's explicit configuration
			// in pax-web-extender-war)

			BundleWebApplication webApp;
			lock.lock();
			try {
				webApp = webApplications.get(bundle);
				if (webApp == null) {
					return;
				}
				// Pax Web before version 8 checked non-standard "Webapp-Deploy" manifest header. I don't think it's
				// necessary now
			} finally {
				lock.unlock();
			}

			// Aries Blueprint container may be processed by several reschedules of "this" into a configured
			// thread pool. Rescheduling happens at some stages of Blueprint container lifecycle, when it has to
			// wait (for namespace handlers or mandatory services). But ideally, when everything is available and
			// there's no need to wait, we should try doing everything in single thread.
			// starting a webapp immediately (in current thread) will process with parsing and deployment. Only
			// if the WebContainer reference is not available, we'll reschedule the lifecycle processing
			webApp.start();
		}

		@Override
		public void destroy() throws Exception {
			// destroy() method is called outside of the pax-web-extender-war pool - usually when the bundle of the
			// webapp is stopped (in FelixStartLevel thread or e.g., in Karaf Shell thread) or the bundle of
			// pax-web-extender-war itself is stopped
			// 128.3.8 "Stopping the Web Application Bundle" says this explicitly:
			//     This undeploying must occur synchronously with the WAB's stopping event

			BundleWebApplication webApp = null;
			lock.lock();
			try {
				if (bundle.getState() == Bundle.UNINSTALLED) {
					// the bundle has be uninstalled and can't be started again. We'll never start the associated WAB
					webApp = webApplications.remove(bundle);
				} else if (bundle.getState() == Bundle.STOPPING || bundle.getState() == Bundle.RESOLVED) {
					// the bundle is or has stopped, but it can be started again - in theory we could start the
					// associated WAB again with already parsed web.xml (and web fragments and annotated classes),
					// but the bundle may have stopped because it's being refreshed or new bundle fragments
					// were attached. So it's wiser and safer to start from scratch. So use remove() and not get().
//					webApp = webApplications.get(bundle);
					webApp = webApplications.remove(bundle);
				}
				if (webApp == null) {
					return;
				}

				// Pax Web before 8 interacted here with dependency manager in order to tell the WebApp to unregister
				// a WebAppDependencyHolder registration. But we're handling the lifecycle in different way now.
				webApp.stop();
			} finally {
				lock.unlock();
			}
		}
	}

}
