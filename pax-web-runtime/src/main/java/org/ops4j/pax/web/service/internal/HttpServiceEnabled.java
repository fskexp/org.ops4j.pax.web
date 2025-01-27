/*
 * Copyright 2007 Niclas Hedhman.
 * Copyright 2007 Alin Dreghiciu.
 * Copyright 2010 Achim Nierbeck.
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
package org.ops4j.pax.web.service.internal;

import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.EventListener;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.servlet.Filter;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletException;
import javax.servlet.SessionCookieConfig;
import javax.servlet.descriptor.JspPropertyGroupDescriptor;
import javax.servlet.descriptor.TaglibDescriptor;

import org.ops4j.pax.web.annotations.PaxWebConfiguration;
import org.ops4j.pax.web.annotations.PaxWebTesting;
import org.ops4j.pax.web.service.MultiBundleWebContainerContext;
import org.ops4j.pax.web.service.PaxWebConstants;
import org.ops4j.pax.web.service.WebContainer;
import org.ops4j.pax.web.service.WebContainerContext;
import org.ops4j.pax.web.service.internal.views.DirectWebContainerView;
import org.ops4j.pax.web.service.spi.ServerController;
import org.ops4j.pax.web.service.spi.config.Configuration;
import org.ops4j.pax.web.service.spi.context.UniqueMultiBundleWebContainerContextWrapper;
import org.ops4j.pax.web.service.spi.context.UniqueWebContainerContextWrapper;
import org.ops4j.pax.web.service.spi.context.WebContainerContextWrapper;
import org.ops4j.pax.web.service.spi.model.OsgiContextModel;
import org.ops4j.pax.web.service.spi.model.ServerModel;
import org.ops4j.pax.web.service.spi.model.ServiceModel;
import org.ops4j.pax.web.service.spi.model.elements.ContainerInitializerModel;
import org.ops4j.pax.web.service.spi.model.elements.ElementModel;
import org.ops4j.pax.web.service.spi.model.elements.ErrorPageModel;
import org.ops4j.pax.web.service.spi.model.elements.EventListenerModel;
import org.ops4j.pax.web.service.spi.model.elements.FilterModel;
import org.ops4j.pax.web.service.spi.model.elements.JspModel;
import org.ops4j.pax.web.service.spi.model.elements.ServletModel;
import org.ops4j.pax.web.service.spi.model.elements.WelcomeFileModel;
import org.ops4j.pax.web.service.spi.model.events.WebElementEvent;
import org.ops4j.pax.web.service.spi.model.events.WebElementEventListener;
import org.ops4j.pax.web.service.spi.model.views.WebAppWebContainerView;
import org.ops4j.pax.web.service.spi.servlet.DefaultJspPropertyGroupDescriptor;
import org.ops4j.pax.web.service.spi.servlet.DefaultSessionCookieConfig;
import org.ops4j.pax.web.service.spi.servlet.DefaultTaglibDescriptor;
import org.ops4j.pax.web.service.spi.model.views.DynamicJEEWebContainerView;
import org.ops4j.pax.web.service.spi.task.Batch;
import org.ops4j.pax.web.service.spi.task.Change;
import org.ops4j.pax.web.service.spi.util.Path;
import org.ops4j.pax.web.service.spi.util.Utils;
import org.ops4j.pax.web.service.spi.whiteboard.WhiteboardWebContainerView;
import org.ops4j.pax.web.service.views.PaxWebContainerView;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//import org.ops4j.pax.web.jsp.JspServletWrapper;

/**
 * <p><em>Enabled</em> {@link org.osgi.service.http.HttpService} means we can register web components. When bundle
 * (for which the Http Service is scoped) is stopped, all available references to this service will switch
 * to <em>disabled</em> {@link org.osgi.service.http.HttpService} delegate to prevent further registration.</p>
 */
public class HttpServiceEnabled implements WebContainer, StoppableHttpService {

	private static final Logger LOG = LoggerFactory.getLogger(HttpServiceEnabled.class);
//	private static final String PAX_WEB_JSP_SERVLET = "jsp";

	// the bundle for which this HttpService was created by ServiceFactory
	final Bundle serviceBundle;

	private final ServerModel serverModel;

	/**
	 * A view into global {@link ServerModel} from the perspective of bundle-scoped
	 * {@link org.osgi.service.http.HttpService} or bundle-related Whiteboard service registrations.
	 */
	private final ServiceModel serviceModel;

	private final ServerController serverController;

	private final WebElementEventListener eventDispatcher;

	private final WhiteboardWebContainerView whiteboardContainerView = new WhiteboardWebContainer();
	private final DirectWebContainerView directContainerView = new DirectWebContainer();
	private final WebAppWebContainerView webAppWebContainer = new WebAppWebContainer();

	private final Configuration configuration;

//	private final Boolean showStacks;

	public HttpServiceEnabled(final Bundle bundle, final ServerController srvController,
			final ServerModel serverModel, final WebElementEventListener eventDispatcher, final Configuration configuration) {
		LOG.debug("Creating active Http Service for: {}", bundle);

		this.serverModel = serverModel;
		this.serviceModel = new ServiceModel(serverModel, srvController, bundle);

		this.serviceBundle = bundle;

		this.serverController = srvController;

		// dispatcher to send events related to web element/context (un)registration
		this.eventDispatcher = eventDispatcher;

		this.configuration = configuration;
	}

	@PaxWebTesting
	public ServiceModel getServiceModel() {
		return serviceModel;
	}

	// --- StoppableHttpService

	@Override
	public void stop() {
		LOG.debug("Stopping http service for: " + serviceBundle);

		// TODO: make it transactional, so removal of two servlets won't restart the servlet context twice

		// strange while loops, because "unregistration" may not necessarily end with removal of single web
		// element. For example, when many servlets were registered by class name
		while (!serviceModel.getFilterModels().isEmpty()) {
			doUnregisterFilter(serviceModel.getFilterModels().iterator().next());
		}
		while (!serviceModel.getServletModels().isEmpty()) {
			doUnregisterServlet(serviceModel.getServletModels().iterator().next());
		}
		while (!serviceModel.getEventListenerModels().isEmpty()) {
			doUnregisterEventListener(serviceModel.getEventListenerModels().iterator().next());
		}
		while (!serviceModel.getErrorPageModels().isEmpty()) {
			doUnregisterErrorPages(serviceModel.getErrorPageModels().iterator().next());
		}
		while (!serviceModel.getContainerInitializerModels().isEmpty()) {
			doUnregisterServletContainerInitializer(serviceModel.getContainerInitializerModels().iterator().next());
		}

		doUnregisterAllWelcomeFiles();

		serverModel.deassociateContexts(serviceBundle, serverController);
	}

	// --- container views

	@Override
	public <T extends PaxWebContainerView> T adapt(Class<T> type) {
		if (type == WhiteboardWebContainerView.class) {
			// gives access to ServerModel and other bundle-agnostic internals of the runtime
			return type.cast(whiteboardContainerView);
		}
		if (type == DirectWebContainerView.class) {
			// direct HttpService-like registration of models - mostly for test purpose, as DirectWebContainerView
			// is not available in any of the exported package
			return type.cast(directContainerView);
		}
		if (type == DynamicJEEWebContainerView.class) {
			// view used by javax.servlet.ServletContext.addServlet/Filter/Listener dynamic methods
			// we'll reuse whiteboardContainerView, but cast it to different interface
			return type.cast(whiteboardContainerView);
		}
		if (type == WebAppWebContainerView.class) {
			// view used when registering WARs (WABs). This is kind of transactional view
			return type.cast(webAppWebContainer);
		}
		return null;
	}

	// --- TODO: transactional access to web container

	@Override
	public void begin(HttpContext context) {
		// marks given context as participating in a transaction
	}

	@Override
	public void end(HttpContext context) {
		// ends a transaction and deassociates given context from a pending transaction
	}

	// --- different methods used to retrieve HttpContext
	//     "102.10.3.1 public HttpContext createDefaultHttpContext()" says that "a new HttpContext object is created
	//     each time this method is called", but we actually don't want "default" context to mean something
	//     different each time it's "created"!
	//     That's why the "contexts" returned from the below methods are unique instances, but underneath they
	//     delegate to something with "the same" concept precisely defined by Pax Web (Http Service specification
	//     doesn't make the concept of "the same" precise).

	@Override
	public WebContainerContext createDefaultHttpContext() {
		return createDefaultHttpContext(PaxWebConstants.DEFAULT_CONTEXT_NAME);
	}

	@Override
	public WebContainerContext createDefaultHttpContext(String contextId) {
		return new UniqueWebContainerContextWrapper(serviceModel.getOrCreateDefaultHttpContext(contextId));
	}

	@Override
	public MultiBundleWebContainerContext createDefaultSharedHttpContext() {
		return createDefaultSharedHttpContext(PaxWebConstants.DEFAULT_SHARED_CONTEXT_NAME);
	}

	@Override
	public MultiBundleWebContainerContext createDefaultSharedHttpContext(String contextId) {
		return new UniqueMultiBundleWebContainerContextWrapper(serviceModel.getOrCreateDefaultSharedHttpContext(contextId));
	}

	// --- methods used to register a Servlet - with more options than in original HttpService.registerServlet()

	@Override
	public void registerServlet(String alias, Servlet servlet, Dictionary<?, ?> initParams, HttpContext httpContext)
			throws ServletException, NamespaceException {
		registerServlet(alias, servlet, initParams, null, null, httpContext);
	}

	@Override
	public void registerServlet(String alias, Servlet servlet, Dictionary<?, ?> initParams,
			Integer loadOnStartup, Boolean asyncSupported, HttpContext httpContext)
			throws ServletException, NamespaceException {
		// final stage of a path where we know about servlet registration using "alias" (the "old" HttpService way)
		ServletModel servletModel = new ServletModel(alias, servlet, initParams, loadOnStartup, asyncSupported);

		doRegisterServlet(Collections.singletonList(httpContext), servletModel);
	}

	@Override
	public void registerServlet(Servlet servlet, String[] urlPatterns, Dictionary<String, String> initParams,
			HttpContext httpContext) throws ServletException {
		registerServlet(servlet, null, urlPatterns, initParams, null, null, null, httpContext);
	}

	@Override
	public void registerServlet(Servlet servlet, String[] urlPatterns, Dictionary<String, String> initParams,
			Integer loadOnStartup, Boolean asyncSupported, HttpContext httpContext) throws ServletException {
		registerServlet(servlet, null, urlPatterns, initParams, loadOnStartup, asyncSupported, null, httpContext);
	}

	@Override
	public void registerServlet(Servlet servlet, String servletName, String[] urlPatterns,
			Dictionary<String, String> initParams, HttpContext httpContext) throws ServletException {
		registerServlet(servlet, servletName, urlPatterns, initParams, null, null, null, httpContext);
	}

	@Override
	public void registerServlet(Servlet servlet, String servletName, String[] urlPatterns,
			Dictionary<String, String> initParams, Integer loadOnStartup, Boolean asyncSupported,
			HttpContext httpContext) throws ServletException {
		registerServlet(servlet, servletName, urlPatterns, initParams, loadOnStartup, asyncSupported, null, httpContext);
	}

	@Override
	public void registerServlet(Servlet servlet, String servletName, String[] urlPatterns,
			Dictionary<String, String> initParams, Integer loadOnStartup, Boolean asyncSupported,
			MultipartConfigElement multiPartConfig, HttpContext httpContext) throws ServletException {
		// final stage of a path where we know about servlet registration using URL patterns and name
		ServletModel servletModel = new ServletModel(servletName, urlPatterns, servlet,
				initParams, loadOnStartup, asyncSupported, multiPartConfig);

		doRegisterServletWithoutAlias(httpContext, servletModel);
	}

	@Override
	public void registerServlet(Class<? extends Servlet> servletClass, String[] urlPatterns,
			Dictionary<String, String> initParams, HttpContext httpContext) throws ServletException {
		registerServlet(servletClass, urlPatterns, initParams, null, null, null, httpContext);
	}

	@Override
	public void registerServlet(Class<? extends Servlet> servletClass, String[] urlPatterns,
			Dictionary<String, String> initParams, Integer loadOnStartup, Boolean asyncSupported,
			HttpContext httpContext) throws ServletException {
		registerServlet(servletClass, urlPatterns, initParams, loadOnStartup, asyncSupported, null, httpContext);
	}

	@Override
	public void registerServlet(Class<? extends Servlet> servletClass, String[] urlPatterns,
			Dictionary<String, String> initParams, Integer loadOnStartup, Boolean asyncSupported,
			MultipartConfigElement multiPartConfig, HttpContext httpContext) throws ServletException {
		// final stage of a path where we register servlet by class name and URL patterns
		ServletModel servletModel = new ServletModel(urlPatterns, servletClass,
				initParams, loadOnStartup, asyncSupported, multiPartConfig);

		doRegisterServletWithoutAlias(httpContext, servletModel);
	}

	/**
	 * Register servlet knowing we can ignore {@link NamespaceException}.
	 *
	 * @param httpContext
	 * @param model
	 * @throws ServletException
	 */
	private void doRegisterServletWithoutAlias(HttpContext httpContext, ServletModel model) throws ServletException {
		try {
			doRegisterServlet(Collections.singletonList(httpContext), model);
		} catch (NamespaceException ignored) {
		}
	}

	/**
	 * <p>Main, internal (but public for testing purpose) method to register given, fully defined {@link ServletModel}
	 * within an {@link OsgiContextModel} associated with given list of {@link HttpContext}.</p>
	 *
	 * <p>Method checks if the association is possible or creates one if there no {@link OsgiContextModel}
	 * available yet.</p>
	 *
	 * <p>Method should run semi transactionally - in single configuration/registration thread of Pax Web runtime.</p>
	 *
	 * @param httpContexts
	 * @param model
	 * @throws ServletException
	 * @throws NamespaceException
	 */
	private void doRegisterServlet(Collection<HttpContext> httpContexts, ServletModel model) throws ServletException, NamespaceException {
		LOG.debug("Passing registration of {} to configuration thread", model);

		if (model.getRegisteringBundle() == null) {
			// HttpService case. In Whiteboard, bundle is always provided with the model up front
			model.setRegisteringBundle(this.serviceBundle);
		}

		try {
			event(WebElementEvent.State.DEPLOYING, model);

			final Batch batch = new Batch("Registration of " + model);

			serverModel.run(() -> {
				translateContexts(httpContexts, model, batch);

				// this can be done only after translating the contexts ...
				if (model.isJspServlet()) {
					model.configureJspServlet(configuration.jsp());
				}

				try {
					// ... that's why the validation is moved to the task
					model.performValidation();
				} catch (Exception e) {
					throw new RuntimeException(e.getMessage(), e);
				}

				LOG.info("Registering {}", model);

				// adding servlet model may lead to unregistration of some other, lower-ranked models, so batch
				// may have some unregistration changes added
				serverModel.addServletModel(model, batch);

				// no exception, so we can alter the batch if needed (for JSPs)
				Batch newBatch = batch;
				if (model.isJspServlet()) {
					// we need JSP SCI if it's not already there
					boolean found = false;
					for (ContainerInitializerModel cim : serviceModel.getContainerInitializerModels()) {
						if (PaxWebConstants.DEFAULT_JSP_SCI_CLASS.equals(cim.getContainerInitializer().getClass().getName())) {
							// fine, but is it for correct context?
							found = true;
							for (OsgiContextModel cm : cim.getContextModels()) {
								if (!model.getContextModels().contains(cm)) {
									found = false;
									break;
								}
							}
							if (found) {
								break;
							}
						}
					}
					if (!found) {
						ContainerInitializerModel cim = serverModel.createJSPServletContainerInitializerModel(serviceBundle);
						cim.setRegisteringBundle(model.getRegisteringBundle());
						cim.setRelatedModel(model);
						model.getContextModels().forEach(cim::addContextModel);
						newBatch = new Batch("JSP Configuration and registration of " + model);
						serverModel.addContainerInitializerModel(cim, newBatch);
						for (Change operation : batch.getOperations()) {
							newBatch.getOperations().add(operation);
						}
					}
				}

				// only if validation was fine, pass the batch to ServerController, where the batch may fail again
				serverController.sendBatch(newBatch);

				// if server runtime has accepted the changes (hoping it'll be in clean state if it didn't), lets
				// actually apply the changes to global model (through ServiceModel)
				newBatch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.DEPLOYED, model);
		} catch (ServletException | NamespaceException | RuntimeException e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw e;
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// --- methods used to unregister a Servlet

	@Override
	public void unregister(final String alias) {
		ServletModel model = new ServletModel(alias, null, null, null, (ServiceReference<? extends Servlet>) null);
		doUnregisterServlet(model);
	}

	@Override
	public void unregisterServlet(final Servlet servlet) {
		ServletModel model = new ServletModel(null, null, servlet, null, null);
		doUnregisterServlet(model);
	}

	@Override
	public void unregisterServlet(String servletName) {
		ServletModel model = new ServletModel(null, servletName, null, null, null);
		doUnregisterServlet(model);
	}

	@Override
	public void unregisterServlets(Class<? extends Servlet> servletClass) {
		ServletModel model = new ServletModel(null, null, null, servletClass, null);
		doUnregisterServlet(model);
	}

	/**
	 * Actual servlet unregistration methods - for all supported criteria
	 * @param model
	 */
	private void doUnregisterServlet(ServletModel model) {
		final String alias = model.getAlias();
		final String name = model.getName();
		final Servlet instance = model.getServlet();
		final Class<? extends Servlet> servletClass = model.getServletClass();
		final ServiceReference<? extends Servlet> reference = model.getElementReference();

		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(serviceBundle);
		}
		Bundle registeringBundle = model.getRegisteringBundle();

		try {
			event(WebElementEvent.State.UNDEPLOYING, model);

			serverModel.run(() -> {
				List<ServletModel> toUnregister = new LinkedList<>();

				// This loop checks Whiteboard unregistration - reference identity
				for (ServletModel existing : serviceModel.getServletModels()) {
					if (existing == model) {
						// Whiteboard scenario, where actual "customized" object is unregistered
						LOG.info("Unregistering servlet model \"{}\"", model);
						toUnregister.add(existing);
						break;
					}
				}
				if (toUnregister.isEmpty()) {
					// search by criteria
					if (alias != null) {
						LOG.info("Unregistering servlet by alias \"{}\"", alias);

						ServletModel found = null;
						for (ServletModel existing : serviceModel.getServletModels()) {
							if (alias.equals(existing.getAlias())) {
								found = existing;
								break;
							}
						}
						Map<String, ServletModel> mapping = serviceModel.getAliasMapping().get(alias);
						if (mapping == null || mapping.size() == 0 || found == null) {
							throw new IllegalArgumentException("Alias \"" + alias + "\" was never registered by "
									+ registeringBundle);
						}
						toUnregister.add(found);
					} else if (servletClass != null) {
						LOG.info("Unregistering servlet by class \"{}\"", servletClass);

						for (ServletModel existing : serviceModel.getServletModels()) {
							if (existing.getServletClass().equals(servletClass)) {
								toUnregister.add(existing);
							}
						}
						if (toUnregister.size() == 0) {
							throw new IllegalArgumentException("Servlet of \"" + servletClass.getName() + "\" class "
									+ "was never registered by " + registeringBundle);
						}
					} else if (instance != null) {
						LOG.info("Unregistering servlet \"{}\"", instance);

						for (ServletModel existing : serviceModel.getServletModels()) {
							if (existing.getServlet() != null && existing.getServlet().equals(instance)) {
								toUnregister.add(existing);
							}
						}
						if (toUnregister.size() == 0) {
							throw new IllegalArgumentException("Servlet \"" + instance + "\" "
									+ "was never registered by " + registeringBundle);
						}
					} else if (reference != null) {
						LOG.info("Unregistering servlet by refernce \"{}\"", reference);

						for (ServletModel existing : serviceModel.getServletModels()) {
							if (existing.getElementReference().equals(reference)) {
								toUnregister.add(existing);
							}
						}
						if (toUnregister.size() == 0) {
							throw new IllegalArgumentException("Servlet with reference \"" + reference + "\" "
									+ "was never registered by " + registeringBundle);
						}
					} else if (name != null) {
						LOG.info("Unregistering servlet by name \"{}\"", name);

						for (ServletModel existing : serviceModel.getServletModels()) {
							if (existing.getName().equals(name)) {
								toUnregister.add(existing);
							}
						}
						if (toUnregister.size() == 0) {
							throw new IllegalArgumentException("Servlet named \"" + name + "\" was never registered by "
									+ registeringBundle);
						}
					}
				}
				if (toUnregister.isEmpty()) {
					throw new IllegalArgumentException("Can't find a servlet to unregister using criteria from " + model);
				}

				final Batch batch = new Batch("Unregistration of servlets: " + toUnregister);

				if (model.isJspServlet()) {
					// probably we have to unregister SCI for JSP
					for (ContainerInitializerModel cim : serviceModel.getContainerInitializerModels()) {
						if (model == cim.getRelatedModel()) {
							batch.removeContainerInitializerModels(serverModel, Collections.singletonList(cim));
						}
					}
				}

				// removing servlet model may lead to reactivation of some previously disabled models
				serverModel.removeServletModels(toUnregister, batch);

				// only if validation was fine, pass the batch to ServerController, where the batch may fail again
				serverController.sendBatch(batch);

				// if server runtime has accepted the changes (hoping it'll be in clean state if it didn't), lets
				// actually apply the changes to global model (through ServiceModel)
				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.UNDEPLOYED, model);
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// --- methods used to register resources

	@Override
	public void registerResources(String alias, String name, HttpContext context) throws NamespaceException {
		// "resources" is server-specific "default servlet" registered (in more flexible way than in JavaEE)
		// under some "alias", which is effectively a "/path/*" URL pattern
		// With Whiteboard, it's even more flexible, as we can have "resource" servlet registered for exteension
		// or "/" patterns as well
		//
		// "default servlets" are tightly related to "welcome files" which are handled separately
		ResourceServlet resourceServlet = createResourceServlet(new String[] { alias }, name);

		ServletModel servletModel = new ServletModel.Builder()
				.withAlias(alias)
				.withServletName("/".equals(alias) ? "default" : String.format("default-%s", UUID.randomUUID().toString()))
				.withServletSupplier(resourceServlet.supplier)
				.withLoadOnStartup(1)
				.withAsyncSupported(true)
				.resourceServlet(true)
				.build();

		// "name" is very misleading term here as it's the "base path" or "resource prefix". Also Pax Web allows it
		// to be a file: URL to make it easier to expose a directory as the resource directory (web root directory)
		if (resourceServlet.urlBase != null) {
			LOG.info("Configuring resource servlet to serve resources from {}", resourceServlet.urlBase);
			servletModel.setBaseFileUrl(resourceServlet.urlBase);
		} else {
			LOG.info("Configuring resource servlet to serve resources from WebContainerContext");
			servletModel.setBasePath(resourceServlet.chrootBase);
		}

		// TODO: think about resource cache sharing between resource servlets. Now all resource servlets
		//       configure their own resource cache

		try {
			doRegisterServlet(Collections.singletonList(context), servletModel);
		} catch (ServletException e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	/**
	 * Helper method to create a <em>resource servlet</em> using a <em>base</em> which may be either a <em>chroot</em>
	 * for bundle-resource access of {@code file:} URL.
	 * @param urlPatterns
	 * @param rawBase
	 * @return
	 */
	private ResourceServlet createResourceServlet(String[] urlPatterns, String rawBase) {
		URL urlBase = ServletModel.getFileUrlIfAccessible(rawBase);

		// We'll ask dedicated server controller to create "default servlet" for us. This servlet will later
		// be managed as normal servlet with proper lifecycle. All that's needed now is the "name" (resource base)
		// to configure the underlying "resource manager" for given "resource/default servlet"
		// as Pax Web extension, we accept name to be absolute file: URL representing "web dir" (root dir) where
		// resources can be served from directly

		// if urlBase is null, then we have normal Http Service / Whiteboard Service scenario, where the "name"
		// is actually a "base" to prepend to actual name to be passed to ServletContextHelper.getResource()
		// this base should be "" == "/" or e.g., "/www" or "www"
		// accessing "/alias/hello.txt" for these bases should result in ServletContextHelper.getResource() params:
		// - base = "": hello.txt
		// - base = "/": hello.txt
		// - base = "www": www/hello.txt
		// - base = "/www": www/hello.txt
		// - base = "www/": www/hello.txt
		// - and so on
		// tl;dr: we have to normalize the base

		String chrootBase = Path.securePath(rawBase);
		if (urlBase == null) {
			if (chrootBase == null) {
				LOG.warn("Can't use {} as resource base, changed to root of the bundle providing resources", rawBase);
				chrootBase = "";
			} else {
				// yes - we will replace "/" with "" which means "root of the bundle" or "just pass incoming
				// path directly to ServletContextHelper.getResource()
				if (chrootBase.startsWith("/")) {
					chrootBase = chrootBase.substring(1);
				}
				if (chrootBase.endsWith("/")) {
					chrootBase = chrootBase.substring(0, chrootBase.length() - 1);
				}
			}
			LOG.info("Registering resources with {} mapping(s) and resource base \"{}\"",
					Arrays.asList(urlPatterns), chrootBase);
		} else {
			LOG.info("Registering resources with {} mapping(s) and absolute directory \"{}\"",
					Arrays.asList(urlPatterns), urlBase);
			chrootBase = null;
		}

		final String chroot = chrootBase;
		return new ResourceServlet(() -> serverController.createResourceServlet(urlBase, chroot), urlBase, chrootBase);
	}

	// --- methods used to register a Filter

	@Override
	public void registerFilter(Filter filter, String[] urlPatterns, String[] servletNames,
			Dictionary<String, String> initParams, HttpContext httpContext) throws ServletException {
		registerFilter(filter, null, urlPatterns, servletNames, initParams, null, httpContext);
	}

	@Override
	public void registerFilter(Filter filter, String filterName, String[] urlPatterns, String[] servletNames,
			Dictionary<String, String> initParams, Boolean asyncSupported,
			HttpContext httpContext) throws ServletException {
		// final stage of a path where we know about filter registration using an instance
		FilterModel filterModel = new FilterModel(filterName, urlPatterns, servletNames, null, filter,
				initParams, asyncSupported);

		doRegisterFilter(Collections.singletonList(httpContext), filterModel);
	}

	@Override
	public void registerFilter(Class<? extends Filter> filterClass, String[] urlPatterns, String[] servletNames,
			Dictionary<String, String> initParams, HttpContext httpContext) throws ServletException {
		registerFilter(filterClass, null, urlPatterns, servletNames, initParams, null, httpContext);
	}

	@Override
	public void registerFilter(Class<? extends Filter> filterClass, String filterName, String[] urlPatterns,
			String[] servletNames, Dictionary<String, String> initParams, Boolean asyncSupported,
			HttpContext httpContext) throws ServletException {
		// final stage of a path where we register filter using a class name
		FilterModel filterModel = new FilterModel(filterName, urlPatterns, servletNames, null, filterClass,
				initParams, asyncSupported);

		doRegisterFilter(Collections.singletonList(httpContext), filterModel);
	}

	/**
	 * <p>Main, internal method to register given, fully defined {@link FilterModel} within an {@link OsgiContextModel}
	 * associated with given {@link HttpContext}.</p>
	 *
	 * <p>Method checks if the association is possible or creates one if there no {@link OsgiContextModel}
	 * available yet.</p>
	 *
	 * <p>Method runs semi transactionally - in single configuration/registration thread of Pax Web runtime.</p>
	 *
	 * @param httpContexts
	 * @param model
	 * @throws ServletException
	 */
	private void doRegisterFilter(Collection<HttpContext> httpContexts, final FilterModel model) throws ServletException {
		LOG.debug("Passing registration of {} to configuration thread", model);

		if (model.getRegisteringBundle() == null) {
			// HttpService case. In Whiteboard, bundle is always provided with the model up front
			model.setRegisteringBundle(this.serviceBundle);
		}

		try {
			event(WebElementEvent.State.DEPLOYING, model);

			model.performValidation();

			final Batch batch = new Batch("Registration of " + model);

			serverModel.run(() -> {
				translateContexts(httpContexts, model, batch);

				LOG.info("Registering {}", model);

				// batch change of entire model
				serverModel.addFilterModel(model, batch);

				// send batch to Jetty/Tomcat/Undertow
				serverController.sendBatch(batch);

				// process the batch if server accepted == apply changes to the model
				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.DEPLOYED, model);
		} catch (NamespaceException cantHappenWheAddingFilters) {
		} catch (RuntimeException e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw e;
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// --- methods used to unregister a Filter

	@Override
	public void unregisterFilter(Filter filter) {
		FilterModel model = new FilterModel(null, filter, null, null);
		doUnregisterFilter(model);
	}

	@Override
	public void unregisterFilter(String filterName) {
		FilterModel model = new FilterModel(filterName, null, null, null);
		doUnregisterFilter(model);
	}

	@Override
	public void unregisterFilters(Class<? extends Filter> filterClass) {
		FilterModel model = new FilterModel(null, null, filterClass, null);
		doUnregisterFilter(model);
	}

	/**
	 * Main method for {@link Filter} unregistration
	 * @param model
	 */
	private void doUnregisterFilter(final FilterModel model) {
		final String name = model.getName();
		final Filter instance = model.getFilter();
		final Class<? extends Filter> filterClass = model.getFilterClass();
		ServiceReference<? extends Filter> reference = model.getElementReference();

		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(serviceBundle);
		}
		Bundle registeringBundle = model.getRegisteringBundle();

		try {
			event(WebElementEvent.State.UNDEPLOYING, model);

			serverModel.run(() -> {
				List<FilterModel> toUnregister = new LinkedList<>();

				// This loop checks Whiteboard unregistration - reference identity
				for (FilterModel existing : serviceModel.getFilterModels()) {
					if (existing == model) {
						// Whiteboard scenario, where actual "customized" object is unregistered
						LOG.info("Unregistering filter model \"{}\"", model);
						toUnregister.add(existing);
						break;
					}
				}
				if (toUnregister.isEmpty()) {
					// search by criteria
					if (name != null) {
						LOG.info("Unregistering filter by name \"{}\"", name);

						for (FilterModel existing : serviceModel.getFilterModels()) {
							if (existing.getName().equals(name)) {
								toUnregister.add(existing);
							}
						}
						if (toUnregister.size() == 0) {
							throw new IllegalArgumentException("Filter named \"" + name + "\" was never registered by "
									+ registeringBundle);
						}
					} else if (filterClass != null) {
						LOG.info("Unregistering filter by class \"{}\"", filterClass);

						for (FilterModel existing : serviceModel.getFilterModels()) {
							if (existing.getFilterClass().equals(filterClass)) {
								toUnregister.add(existing);
							}
						}
						if (toUnregister.size() == 0) {
							throw new IllegalArgumentException("Filter of \"" + filterClass.getName() + "\" class "
									+ "was never registered by " + registeringBundle);
						}
					} else if (reference != null) {
						LOG.info("Unregistering filter by refernce \"{}\"", reference);

						for (FilterModel existing : serviceModel.getFilterModels()) {
							if (existing.getElementReference().equals(reference)) {
								toUnregister.add(existing);
							}
						}
						if (toUnregister.size() == 0) {
							throw new IllegalArgumentException("Filter with reference \"" + reference + "\" "
									+ "was never registered by " + registeringBundle);
						}
					} else if (instance != null) {
						LOG.info("Unregistering filter \"{}\"", instance);

						for (FilterModel existing : serviceModel.getFilterModels()) {
							if (existing.getFilter().equals(instance)) {
								toUnregister.add(existing);
							}
						}
						if (toUnregister.size() == 0) {
							throw new IllegalArgumentException("Filter \"" + instance + "\" "
									+ "was never registered by " + registeringBundle);
						}
					}
				}
				if (toUnregister.isEmpty()) {
					throw new IllegalArgumentException("Can't find a filter to unregister using criteria from " + model);
				}

				final Batch batch = new Batch("Unregistration of filters: " + toUnregister);

				serverModel.removeFilterModels(toUnregister, batch);

				serverController.sendBatch(batch);

				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.UNDEPLOYED, model);
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// --- methods used to register an EventListener

	@Override
	public void registerEventListener(final EventListener listener, final HttpContext httpContext) {
		doRegisterEventListener(Collections.singletonList(httpContext), new EventListenerModel(listener));
	}

	private void doRegisterEventListener(Collection<HttpContext> httpContexts, EventListenerModel model) {
		LOG.debug("Passing registration of {} to configuration thread", model);

		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(this.serviceBundle);
		}

		final Batch batch = new Batch("Registration of " + model);

		try {
			event(WebElementEvent.State.DEPLOYING, model);

			model.performValidation();

			serverModel.run(() -> {
				translateContexts(httpContexts, model, batch);

				LOG.info("Registering {}", model);

				serverModel.addEventListenerModel(model, batch);

				serverController.sendBatch(batch);

				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.DEPLOYED, model);
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// --- methods used to unregister an EventListener

	@Override
	public void unregisterEventListener(final EventListener listener) {
		EventListenerModel model = new EventListenerModel(listener);
		doUnregisterEventListener(model);
	}

	private void doUnregisterEventListener(EventListenerModel model) {
		final EventListener listener = model.getEventListener();
		ServiceReference<? extends EventListener> reference = model.getElementReference();

		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(serviceBundle);
		}
		Bundle registeringBundle = model.getRegisteringBundle();

		try {
			event(WebElementEvent.State.UNDEPLOYING, model);

			serverModel.run(() -> {
				List<EventListenerModel> toUnregister = new LinkedList<>();

				// This loop checks Whiteboard unregistration - reference identity
				for (EventListenerModel existing : serviceModel.getEventListenerModels()) {
					if (existing == model) {
						// Whiteboard scenario, where actual "customized" object is unregistered
						toUnregister.add(model);
						break;
					}
				}
				if (toUnregister.isEmpty()) {
					// search by criteria
					if (listener != null) {
						for (EventListenerModel existing : serviceModel.getEventListenerModels()) {
							if (existing.getEventListener() == listener) {
								toUnregister.add(existing);
							}
						}
					} else if (reference != null) {
						for (EventListenerModel existing : serviceModel.getEventListenerModels()) {
							if (existing.getElementReference() == reference) {
								toUnregister.add(existing);
							}
						}
					}
				}
				if (toUnregister.isEmpty()) {
					throw new IllegalArgumentException("Can't find an event listener to unregister using criteria from " + model);
				}

				final Batch batch = new Batch("Unregistration of EventListener: " + toUnregister);

				serverModel.removeEventListenerModels(toUnregister, batch);

				serverController.sendBatch(batch);

				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.UNDEPLOYED, model);
		} catch (ServletException | NamespaceException e) {
			// if toUnregister is null, IllegalArgumentException is thrown anyway
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// --- methods used to register welcome pages

	@Override
	public void registerWelcomeFiles(String[] welcomeFiles, boolean redirect, HttpContext httpContext) {
		doRegisterWelcomeFiles(Collections.singletonList(httpContext), new WelcomeFileModel(welcomeFiles, redirect));
	}

	/**
	 * Actual registration of {@link WelcomeFileModel}
	 *
	 * @param httpContexts
	 * @param model
	 */
	private void doRegisterWelcomeFiles(List<HttpContext> httpContexts, WelcomeFileModel model) {
		LOG.debug("Passing registration of {} to configuration thread", model);

		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(this.serviceBundle);
		}

		final Batch batch = new Batch("Registration of " + model);

		try {
			event(WebElementEvent.State.DEPLOYING, model);

			model.performValidation();

			serverModel.run(() -> {
				translateContexts(httpContexts, model, batch);

				LOG.info("Registering {}", model);

				serverModel.addWelcomeFileModel(model, batch);

				serverController.sendBatch(batch);

				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.DEPLOYED, model);
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// --- methods used to unregister welcome pages

	@Override
	public void unregisterWelcomeFiles(String[] welcomeFiles, HttpContext httpContext) {
		// "redirect" flag is irrelevant when unregistering and the model may not be the one which is
		// kept at ServiceModel level - it's used only to carry relevant information
		// user may simply register 10 pages and then unregister 7 of them
		WelcomeFileModel model = null;
		for (WelcomeFileModel wfm : serviceModel.getWelcomeFileModels()) {
			if (Arrays.equals(wfm.getWelcomeFiles(), welcomeFiles)) {
				model = wfm;
			}
		}
		if (model == null) {
			model = new WelcomeFileModel(welcomeFiles, false);
		}
		doUnregisterWelcomeFiles(Collections.singletonList(httpContext), model);
	}

	private void doUnregisterWelcomeFiles(List<HttpContext> httpContexts, WelcomeFileModel model) {
		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(serviceBundle);
		}
		Bundle registeringBundle = model.getRegisteringBundle();

		final Batch batch = new Batch("Unregistration of " + model);

		try {
			event(WebElementEvent.State.UNDEPLOYING, model);

			serverModel.run(() -> {
				// we have to "translate" contexts again, as unregistration by array doesn't tell us
				// the actual context to which given "model" was registered, so we rely on the context passed
				// to unregister() method
				translateContexts(httpContexts, model, batch);

				LOG.info("Unregistering {}", model);

				serverModel.removeWelcomeFileModel(model, batch);

				serverController.sendBatch(batch);

				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.UNDEPLOYED, model);
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	private void doUnregisterAllWelcomeFiles() {
		for (WelcomeFileModel model : serviceModel.getWelcomeFileModels()) {
			// context(s) will be picked from the model
			doUnregisterWelcomeFiles(Collections.emptyList(), model);
		}
	}

	// --- methods used to register error pages

	@Override
	public void registerErrorPage(String error, String location, HttpContext httpContext) {
		registerErrorPages(new String[] { error }, location, httpContext);
	}

	@Override
	public void registerErrorPages(String[] errors, String location, HttpContext httpContext) {
		ErrorPageModel model = new ErrorPageModel(errors, location);
		doRegisterErrorPages(Collections.singletonList(httpContext), model);
	}

	private void doRegisterErrorPages(Collection<HttpContext> httpContexts, ErrorPageModel model) {
		LOG.debug("Passing registration of {} to configuration thread", model);

		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(this.serviceBundle);
		}

		try {
			event(WebElementEvent.State.DEPLOYING, model);

			model.performValidation();

			final Batch batch = new Batch("Registration of " + model);

			serverModel.run(() -> {
				translateContexts(httpContexts, model, batch);

				LOG.info("Registering {}", model);

				// error page models are a bit like servlets - there may be mapping conflicts and shadowing
				// of error page models by service ranking
				serverModel.addErrorPageModel(model, batch);

				serverController.sendBatch(batch);

				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.DEPLOYED, model);
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// --- methods used to unregister error pages

	@Override
	public void unregisterErrorPage(String error, HttpContext httpContext) {
		unregisterErrorPages(new String[] { error }, httpContext);
	}

	@Override
	public void unregisterErrorPages(String[] errors, HttpContext httpContext) {
		// remember - the constructed ErrorPageModel will not be equal() to any remembered ErrorPageModels. It's
		// used only to carry the information, which is used to find actual (remembered) ErrorPageModel to unregister
		ErrorPageModel model = new ErrorPageModel(errors);
		doUnregisterErrorPages(model);
	}

	private void doUnregisterErrorPages(ErrorPageModel model) {
		final String[] errorPages = model.getErrorPages();
		final Set<String> errorPagesSet = new HashSet<>(Arrays.asList(errorPages));

		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(serviceBundle);
		}
		Bundle registeringBundle = model.getRegisteringBundle();

		try {
			event(WebElementEvent.State.UNDEPLOYING, model);

			// passed "error pages" will help us find actual ErrorPageModel objects registered so far for given
			// httpService instance - both enabled and disabled (shadowed)

			serverModel.run(() -> {
				List<ErrorPageModel> toUnregister = new LinkedList<>();

				LOG.info("Unregistering error page models for pages \"{}\"", Arrays.asList(errorPages));

				// This loop checks Whiteboard unregistration - reference identity
				for (ErrorPageModel existing : serviceModel.getErrorPageModels()) {
					if (existing == model) {
						// Whiteboard scenario, where actual "customized" object is unregistered
						toUnregister.add(existing);
						break;
					}
				}
				// This loop checks if some model contains ALL error pages passed to unregistration method
				// It's the http service unregistration scenario
				if (toUnregister.isEmpty()) {
					for (ErrorPageModel existing : serviceModel.getErrorPageModels()) {
						if (existing == model) {
							// Whiteboard scenario, where actual "customized" object is unregistered
							toUnregister.add(existing);
							break;
						} else {
							Set<String> existingPages = new HashSet<>(Arrays.asList(existing.getErrorPages()));
							if (existingPages.containsAll(errorPagesSet)) {
								toUnregister.add(existing);
							}
						}
					}
				}
				if (toUnregister.isEmpty()) {
					throw new IllegalArgumentException("Can't find error pages to unregister using criteria from " + model);
				}

				final Batch batch = new Batch("Unregistration of error pages: " + toUnregister);

				serverModel.removeErrorPageModels(toUnregister, batch);

				serverController.sendBatch(batch);

				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.UNDEPLOYED, model);
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// methods used to register / configure JSPs

	@Override
	public void registerJsps(String[] urlPatterns, Dictionary<String, String> initParams, HttpContext context) {
		try {
			ServletModel jspServletModel = serverModel.createJspServletModel(serviceBundle,
					PaxWebConstants.DEFAULT_JSP_SERVLET_NAME, null, urlPatterns, Utils.toMap(initParams),
					configuration.jsp());
			// there can be only one such servlet because of its fixed "jsp" name
			doRegisterServlet(Collections.singletonList(context), jspServletModel);
		} catch (NamespaceException | ServletException e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Override
	public void registerJspServlet(String jspFile, String[] urlPatterns, Dictionary<String, String> initParams, HttpContext context) {
		try {
			ServletModel model = serverModel.createJspServletModel(serviceBundle,
					jspFile, jspFile, urlPatterns, Utils.toMap(initParams), configuration.jsp());
			// "jsp servlet" is special servlet mapped to anything, but actually implemented using JSP file.
			// Such servlet actually requires full JSP engine to be configured and ready, but it'll be added
			// automatically
			// jspFile will be an init param of JSP servlet - see org.apache.catalina.startup.ContextConfig.convertJsp()
			doRegisterServlet(Collections.singletonList(context), model);
		} catch (NamespaceException | ServletException e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Override
	public void registerJspConfigTagLibs(String taglibLocation, String tagLibUri, HttpContext httpContext) {
		TaglibDescriptor descriptor = new DefaultTaglibDescriptor(tagLibUri, taglibLocation);
		registerJspConfigTagLibs(Collections.singletonList(descriptor), httpContext);
	}

	@Override
	public void registerJspConfigTagLibs(Collection<TaglibDescriptor> tagLibs, HttpContext httpContext) {
		// This method contributes new TLD mappings to an OsgiServletContext associated with the passed httpContext.
		// This is ineffective after the real context has started, but still the new list should be visible
		// after calling javax.servlet.ServletContext.getJspConfigDescriptor().getTaglibs()

		// this method (and #registerJspConfigPropertyGroup() and the methods that configure session parameters)
		// do not have to pass anything to ServerController, because all they do is configuration of
		// the OsgiContextModel associated with the passed httpContext. The OsgiContextModel will
		// get the JSP configuration and will be used inside the actual OsgiServletContext associated with
		// this OsgiContextModel.
		//
		// The actual place where this information is needed is in JasperInitializer.onStartup(), where the SCI
		// checks the context for JSP configuration.
		//
		// ServerModel keeps the association between HttpContext (shared or not) and rank-sorted list
		// of OsgiContextModels, so remember that this scenario is possible:
		//  1. ctx1 = this.createDefaultHttpContext()
		//  2. this.registerJspConfigTagLibs(..., ctx1)
		//  3. somehwere else a HttpContext[Mapping] is registered for given bundle and name, but higher rank
		//  4. this.registerJsps(..., ctx1)
		// but because HttpContext arg is translated into actual OsgiContextModel, in step #4 it may turn out
		// that the actual OsgiContextModel and later - OsgiServletContext do NOT contain the passed JSP config
		// because the OsgiContextModel is related to higher ranked "context" that shadowed "ctx1"
		//
		// In case of WABs, where the OsgiContextModel is created at the start of web.xml parsing we
		// can block the configuration thread, so entire web.xml parsing will be done with exactly the same OCM,
		// but without WABs (and without not-yet-implemented (as of 2020-09-17) transactions), we can't
		// ensure that the above scenario won't happen

		serverModel.runSilently(() -> {
			final Batch batch = new Batch("Taglibs configuration");

			WebContainerContext ctx = unify(httpContext);

			// there's some chance that we'll actually get NEW OsgiContextModel, so we have to send it
			// to ServerController. But the taglibs information is not relevant - only context creation
			OsgiContextModel contextModel = serverModel.getOrCreateOsgiContextModel(ctx, serviceBundle,
					PaxWebConstants.DEFAULT_CONTEXT_PATH, batch);

			// we're in configuration thread, so no harm can be done
			contextModel.addTagLibs(tagLibs);

			// if there's a need to actually create the context
			serverController.sendBatch(batch);

			// no need to visit() the batch at service/serverModel level
			return null;
		});
	}

	@Override
	public void registerJspConfigPropertyGroup(List<String> includeCodas, List<String> includePreludes,
			List<String> urlPatterns, Boolean elIgnored, Boolean scriptingInvalid, Boolean isXml,
			HttpContext httpContext) {
		DefaultJspPropertyGroupDescriptor descriptor = new DefaultJspPropertyGroupDescriptor();
		descriptor.setIncludeCodas(includeCodas);
		descriptor.setIncludePreludes(includePreludes);
		descriptor.setUrlPatterns(urlPatterns);
		descriptor.setElIgnored(elIgnored == null ? "false" : elIgnored.toString());
		descriptor.setScriptingInvalid(scriptingInvalid == null ? "false" : scriptingInvalid.toString());
		descriptor.setScriptingInvalid(isXml == null ? "false" : isXml.toString());

		registerJspConfigPropertyGroup(descriptor, httpContext);
	}

	@Override
	public void registerJspConfigPropertyGroup(JspPropertyGroupDescriptor descriptor, HttpContext httpContext) {
		serverModel.runSilently(() -> {
			final Batch batch = new Batch("JSP configuration");

			WebContainerContext ctx = unify(httpContext);

			OsgiContextModel contextModel = serverModel.getOrCreateOsgiContextModel(ctx, serviceBundle,
					PaxWebConstants.DEFAULT_CONTEXT_PATH, batch);

			// we're in configuration thread, so no harm can be done
			contextModel.addJspPropertyGroupDescriptor(descriptor);

			// if there's a need to actually create the context
			serverController.sendBatch(batch);

			// no need to visit() the batch at service/serverModel level
			return null;
		});
	}

	// methods used to unregister JSPs

	@Override
	public void unregisterJsps(HttpContext httpContext) {
	}

	@Override
	public void unregisterJspServlet(String jspFile, HttpContext httpContext) {
	}

	// methods used to register ServletContainerInitializers

	@Override
	public void registerServletContainerInitializer(ServletContainerInitializer initializer, Class<?>[] classes, HttpContext httpContext) {
		ContainerInitializerModel model = new ContainerInitializerModel(initializer, classes);
		doRegisterServletContainerInitializer(Collections.singletonList(httpContext), model);
	}

	private void doRegisterServletContainerInitializer(List<HttpContext> httpContexts, ContainerInitializerModel model) {
		LOG.debug("Passing registration of {} to configuration thread", model);

		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(this.serviceBundle);
		}

		final Batch batch = new Batch("Registration of " + model);

		try {
			event(WebElementEvent.State.DEPLOYING, model);

			model.performValidation();

			serverModel.run(() -> {
				translateContexts(httpContexts, model, batch);

				LOG.info("Registering {}", model);

				serverModel.addContainerInitializerModel(model, batch);

				serverController.sendBatch(batch);

				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.DEPLOYED, model);
		} catch (Exception e) {
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// methods used to unregister ServletContainerInitializers

	@Override
	public void unregisterServletContainerInitializer(ServletContainerInitializer initializer, HttpContext httpContext) {
		doUnregisterServletContainerInitializer(new ContainerInitializerModel(initializer, null));
	}

	private void doUnregisterServletContainerInitializer(ContainerInitializerModel model) {
		final ServletContainerInitializer initializer = model.getContainerInitializer();

		if (model.getRegisteringBundle() == null) {
			model.setRegisteringBundle(serviceBundle);
		}
		Bundle registeringBundle = model.getRegisteringBundle();

		try {
			event(WebElementEvent.State.UNDEPLOYING, model);

			serverModel.run(() -> {
				List<ContainerInitializerModel> toUnregister = new LinkedList<>();

				// This loop checks Whiteboard unregistration - reference identity (though we don't support
				// Whiteboard registration of ServletContainerInitializer(s) (yet?)
				for (ContainerInitializerModel existing : serviceModel.getContainerInitializerModels()) {
					if (existing == model) {
						// Whiteboard scenario, where actual "customized" object is unregistered
						toUnregister.add(existing);
						break;
					}
				}
				if (toUnregister.isEmpty()) {
					// search by criteria
					if (initializer != null) {
						for (ContainerInitializerModel existing : serviceModel.getContainerInitializerModels()) {
							if (existing.getContainerInitializer() == initializer) {
								toUnregister.add(existing);
							}
						}
					}
				}
				if (toUnregister.isEmpty()) {
					throw new IllegalArgumentException("Can't find an servlet container initializer to unregister using criteria from " + model);
				}

				final Batch batch = new Batch("Unregistration of ServletContainerInitializer: " + toUnregister);

				serverModel.removeContainerInitializerModels(toUnregister, batch);

				serverController.sendBatch(batch);

				batch.accept(serviceModel);

				return null;
			});

			event(WebElementEvent.State.UNDEPLOYED, model);
		} catch (ServletException | NamespaceException e) {
			// if toUnregister is null, IllegalArgumentException is thrown anyway
			event(WebElementEvent.State.FAILED, model, e);
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	// methods used to configure session

	@Override
	public void setSessionTimeout(Integer minutes, HttpContext httpContext) {
		serverModel.runSilently(() -> {
			final Batch batch = new Batch("Session timeout configuration");

			WebContainerContext ctx = unify(httpContext);

			OsgiContextModel contextModel = serverModel.getOrCreateOsgiContextModel(ctx, serviceBundle,
					PaxWebConstants.DEFAULT_CONTEXT_PATH, batch);

			LOG.info("Setting session timeout for {}", contextModel);

			// we're in configuration thread, so no harm can be done
			contextModel.setSessionTimeout(minutes);

			// if there's a need to actually create the context
			serverController.sendBatch(batch);

			// no need to visit() the batch at service/serverModel level
			return null;
		});
	}

	@Override
	public void setSessionCookieConfig(String domain, String name, Boolean httpOnly, Boolean secure, String path,
			Integer maxAge, HttpContext httpContext) {
		DefaultSessionCookieConfig config = new DefaultSessionCookieConfig();
		config.setDomain(domain);
		config.setName(name);
		config.setHttpOnly(httpOnly);
		config.setSecure(secure);
		config.setPath(path);
		config.setMaxAge(maxAge);
		setSessionCookieConfig(config, httpContext);
	}

	@Override
	public void setSessionCookieConfig(SessionCookieConfig config, HttpContext httpContext) {
		serverModel.runSilently(() -> {
			final Batch batch = new Batch("Session Cookie configuration");

			WebContainerContext ctx = unify(httpContext);

			OsgiContextModel contextModel = serverModel.getOrCreateOsgiContextModel(ctx, serviceBundle,
					PaxWebConstants.DEFAULT_CONTEXT_PATH, batch);

			LOG.info("Setting session cookie configuration for {}", contextModel);

			// we're in configuration thread, so no harm can be done
			contextModel.setSessionCookieConfig(config);

			// if there's a need to actually create the context
			serverController.sendBatch(batch);

			// no need to visit() the batch at service/serverModel level
			return null;
		});
	}

	// methods used to alter context init parameters

	@Override
	public void setContextParams(Dictionary<String, Object> params, HttpContext httpContext) {
		serverModel.runSilently(() -> {
			final Batch batch = new Batch("Context init parameters configuration");

			WebContainerContext ctx = unify(httpContext);

			OsgiContextModel contextModel = serverModel.getOrCreateOsgiContextModel(ctx, serviceBundle,
					PaxWebConstants.DEFAULT_CONTEXT_PATH, batch);

			LOG.info("Setting context init parameters in {}", contextModel);

			// we're in configuration thread, so no harm can be done
			contextModel.getContextParams().putAll(Utils.toMap(params));

			// if there's a need to actually create the context
			serverController.sendBatch(batch);

			// no need to visit() the batch at service/serverModel level
			return null;
		});
	}

	// --- private support methods

	private void event(WebElementEvent.State type, ElementModel<?, ?> model) {
		if (eventDispatcher != null) {
			eventDispatcher.registrationEvent(new WebElementEvent(type, model.asEventData()));
		}
	}

	private void event(WebElementEvent.State type, ElementModel<?, ?> model, Exception exception) {
		if (eventDispatcher != null) {
			eventDispatcher.registrationEvent(new WebElementEvent(type, model.asEventData(), exception));
		}
	}

	/**
	 * <p>In Http Service scenario, user passes an instance of {@link HttpContext} with the registration. Internally,
	 * each such context has to be translated into {@link OsgiContextModel} - the same model as it's tracked by
	 * Whiteboard Service. This method converts passed collection of {@link HttpContext} contexts and sets them
	 * in passed {@link ElementModel}.</p>
	 *
	 * <p>In Whiteboard Service scenario there's no such translation - {@link OsgiContextModel} instances are
	 * passed already in the model.</p>
	 *
	 * <p>This method creates {@link OsgiContextModel} instances - but only as batch operations to be invoked later.</p>
	 *
	 * @param httpContexts
	 * @param model
	 * @param batch
	 */
	@PaxWebConfiguration
	private void translateContexts(Collection<HttpContext> httpContexts, ElementModel<?, ?> model, Batch batch) {
		if (httpContexts.size() > 0 && !model.hasContextModels()) {
			// Http Service scenario - HttpContext(s)/WebContainerContext(s) are passed with the registration
			final Collection<WebContainerContext> webContexts
					= httpContexts.stream().map(this::unify).collect(Collectors.toList());
			webContexts.forEach(wc -> {
				// HttpService scenario, so only "/" context path if there's a need to actually create
				// an OsgiContextModel
				OsgiContextModel contextModel = serverModel.getOrCreateOsgiContextModel(wc, serviceBundle,
						PaxWebConstants.DEFAULT_CONTEXT_PATH, batch);
				model.addContextModel(contextModel);
			});
			model.getContextModels(); // to make the list immutable
		}
		// DON'T register OsgiContextModels carried with Whiteboard-registered WebElement. These should
		// be registered/configured explicitly by pax-web-extender-whiteboard, before servlet is registered
	}

	/**
	 * Ensure that proper {@link WebContainerContext} is used before operating on internal Pax Web model.
	 *
	 * @param httpContext
	 * @return
	 */
	private WebContainerContext unify(HttpContext httpContext) {
		// org.osgi.service.http.HttpContext -> org.ops4j.pax.web.service.WebContainerContext
		// to ensure an identity of the context
		final WebContainerContext context;
		if (httpContext == null) {
			context = createDefaultHttpContext();
		} else if (!(httpContext instanceof WebContainerContext)) {
			context = new WebContainerContextWrapper(serviceBundle, httpContext);
		} else {
			context = (WebContainerContext) httpContext;
		}

		return context;
	}

	/**
	 * Private <em>view class</em> for Whiteboard registration of web elements.
	 */
	private class WhiteboardWebContainer implements WhiteboardWebContainerView, DynamicJEEWebContainerView {

		@Override
		public void registerServlet(ServletModel model) {
			try {
				doRegisterServlet(Collections.emptyList(), model);
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}

		@Override
		public void unregisterServlet(ServletModel servletModel) {
			doUnregisterServlet(servletModel);
		}

		@Override
		public void registerResources(ServletModel model) {
			String[] mapping = model.getAlias() != null ? new String[] { model.getAlias() }
					: model.getUrlPatterns();
			ResourceServlet resourceServlet = createResourceServlet(mapping, model.getRawPath());

			// unlike with HttpService.registerResources(), we already have a model here, but the servlet
			// doesn't yet have a name
			String name = Arrays.asList(mapping).contains("/")
					? "default" : String.format("default-%s", UUID.randomUUID().toString());
			model.setName(name);
			model.setElementSupplier(resourceServlet.supplier);

			try {
				doRegisterServlet(Collections.emptyList(), model);
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}

		@Override
		public void unregisterResources(ServletModel servletModel) {
			doUnregisterServlet(servletModel);
		}

		@Override
		public void registerFilter(FilterModel model) {
			try {
				doRegisterFilter(Collections.emptyList(), model);
			} catch (ServletException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}

		@Override
		public void unregisterFilter(FilterModel filterModel) {
			doUnregisterFilter(filterModel);
		}

		@Override
		public void registerWelcomeFiles(WelcomeFileModel model) {
			doRegisterWelcomeFiles(Collections.emptyList(), model);
		}

		@Override
		public void unregisterWelcomeFiles(WelcomeFileModel model) {
			doUnregisterWelcomeFiles(Collections.emptyList(), model);
		}

		@Override
		public List<OsgiContextModel> getOsgiContextModels(Bundle bundle) {
			return serverModel.getOsgiContextModels(bundle);
		}

		@Override
		public void registerErrorPages(ErrorPageModel model) {
			doRegisterErrorPages(Collections.emptyList(), model);
		}

		@Override
		public void unregisterErrorPages(ErrorPageModel model) {
			doUnregisterErrorPages(model);
		}

		@Override
		public void registerListener(EventListenerModel model) {
			doRegisterEventListener(Collections.emptyList(), model);
		}

		@Override
		public void unregisterListener(EventListenerModel model) {
			doUnregisterEventListener(model);
		}

		@Override
		public void addWhiteboardOsgiContextModel(OsgiContextModel model) {
			serverModel.runSilently(() -> {
				Batch batch = new Batch("Registration of " + model);
				serverModel.registerOsgiContextModelIfNeeded(model, serviceModel, batch);
				serverController.sendBatch(batch);
				batch.accept(serviceModel);
				return null;
			});
		}

		@Override
		public void removeWhiteboardOsgiContextModel(OsgiContextModel model) {
			serverModel.runSilently(() -> {
				Batch batch = new Batch("Unregistration of " + model);
				serverModel.unregisterOsgiContextModel(model, serviceModel, batch);
				serverController.sendBatch(batch);
				batch.accept(serviceModel);
				return null;
			});
		}

		@Override
		public void registerJsp(JspModel model) {
			String file = model.getJspFile();
			ServletModel jspServletModel = null;
			if (file != null) {
				jspServletModel = serverModel.createJspServletModel(serviceBundle,
						file, file,
						model.getMappings(), model.getInitParams(), configuration.jsp());
			} else {
				jspServletModel = serverModel.createJspServletModel(serviceBundle,
						PaxWebConstants.DEFAULT_JSP_SERVLET_NAME, null,
						model.getMappings(), model.getInitParams(), configuration.jsp());
			}
			for (OsgiContextModel ocm : model.getContextModels()) {
				jspServletModel.addContextModel(ocm);
			}
			model.setServletModel(jspServletModel);
			registerServlet(jspServletModel);
		}

		@Override
		public void unregisterJsp(JspModel model) {
			if (model.getServletModel() != null) {
				unregisterServlet(model.getServletModel());
			} else {
				HttpServiceEnabled.this.unregisterServlet(model.getJspFile() == null ?
						PaxWebConstants.DEFAULT_JSP_SERVLET_NAME : model.getJspFile());
			}
		}
	}

	/**
	 * Private view class for direct control (from tests) of the {@link WebContainer}.
	 */
	private class DirectWebContainer implements DirectWebContainerView {

		@Override
		public void registerServlet(Collection<HttpContext> contexts, ServletModel model) throws ServletException, NamespaceException {
			doRegisterServlet(contexts, model);
		}

		@Override
		public void unregisterServlet(ServletModel model) {
			doUnregisterServlet(model);
		}

		@Override
		public void registerFilter(Collection<HttpContext> contexts, FilterModel model) throws ServletException {
			doRegisterFilter(contexts, model);
		}

		@Override
		public void unregisterFilter(FilterModel model) {
			doUnregisterFilter(model);
		}

		@Override
		public void registerErrorPages(Collection<HttpContext> contexts, ErrorPageModel model) {
			doRegisterErrorPages(contexts, model);
		}

		@Override
		public void unregisterErrorPages(ErrorPageModel model) {
			doUnregisterErrorPages(model);
		}
	}

	/**
	 * Private view class to use the HttpService in kind of transactional way for full web applications.
	 */
	private class WebAppWebContainer implements WebAppWebContainerView {

		@Override
		public void justDoIt(String contextPath) {
		}

		@Override
		public boolean allocateContext(Bundle bundle, String contextPath) {
			return true;
		}

		@Override
		public void releaseContext(Bundle bundle, String contextPath) {

		}
	}












//	@Override
//	public void registerJspServlet(final String[] urlPatterns,
//								   Dictionary<String, ?> initParams, final HttpContext httpContext,
//								   final String jspFile) {
//		if (!SupportUtils.isJSPAvailable()) {
//			throw new UnsupportedOperationException(
//					"Jsp support is not enabled. Is org.ops4j.pax.web.jsp bundle installed?");
//		}
//		final Servlet jspServlet = new JspServletWrapper(serviceBundle, jspFile);
//		final ContextModel contextModel = getOrCreateContext(httpContext);
//		//CHECKSTYLE:OFF
//		initParams = createInitParams(contextModel, initParams == null ? new Hashtable<>() : initParams);
//		//CHECKSTYLE:ON
//		serviceModel.addContextModel(contextModel);
//		try {
//			registerServlet(jspServlet, getJspServletName(jspFile),
//					urlPatterns == null ? new String[]{"*.jsp"}
//							: urlPatterns, initParams, httpContext);
//		} catch (ServletException ignore) {
//			// this should never happen
//			LOG.error("Internal error. Please report.", ignore);
//		}
//		Map<Servlet, String[]> jspServlets = contextModel.getJspServlets();
//		jspServlets.put(jspServlet, urlPatterns);
//
//	}

//	@SuppressWarnings("unchecked")
//	private Dictionary<String, ?> createInitParams(ContextModel contextModel,
//												   Dictionary<String, ?> initParams) {
//		NullArgumentException.validateNotNull(initParams, "Init params");
//		Queue<Configuration> configurations = new LinkedList<>();
//		Configuration serverControllerConfiguration = serverController.getConfiguration();
//
//		PropertyResolver propertyResolver = new DictionaryPropertyResolver(initParams);
//		Configuration c = new ConfigurationImpl(propertyResolver);
//		configurations.add(c);
//
//		configurations.add(serverControllerConfiguration);
//		for (Configuration configuration : configurations) {
//			String scratchDir = configuration.jsp().getJspScratchDir();
//			if (scratchDir == null) {
//				File temporaryDirectory = configuration.server().getTemporaryDirectory();
//				if (temporaryDirectory != null) {
//					scratchDir = temporaryDirectory.toString();
//				}
//			}
//			if (configuration.equals(serverControllerConfiguration)) {
//				// [PAXWEB-225] creates a bundle specific scratch dir
//				File tempDir = new File(scratchDir, contextModel.getContextName());
//				if (!tempDir.exists()) {
//					tempDir.mkdirs();
//				}
//				scratchDir = tempDir.toString();
//			}
//
//			Integer jspCheckInterval = configuration.jsp().getJspCheckInterval();
//			Boolean jspClassDebugInfo = configuration.jsp().getJspClassDebugInfo();
//			Boolean jspDevelopment = configuration.jsp().getJspDevelopment();
//			Boolean jspEnablePooling = configuration.jsp().getJspEnablePooling();
//			String jspIeClassId = configuration.jsp().getJspIeClassId();
//			String jspJavaEncoding = configuration.jsp().getJspJavaEncoding();
//			Boolean jspKeepgenerated = configuration.jsp().getJspKeepgenerated();
//			String jspLogVerbosityLevel = configuration.jsp().getJspLogVerbosityLevel();
//			Boolean jspMappedfile = configuration.jsp().getJspMappedfile();
//			Integer jspTagpoolMaxSize = configuration.jsp().getJspTagpoolMaxSize();
//			Boolean jspPrecompilation = configuration.jsp().getJspPrecompilation();
//
//			// TODO: fix this with PAXWEB-226
//			Map<String, Object> params = new HashMap<>(12);
//			params.put("checkInterval", jspCheckInterval);
//			params.put("classdebuginfo", jspClassDebugInfo);
//			params.put("development", jspDevelopment);
//			params.put("enablePooling", jspEnablePooling);
//			params.put("ieClassId", jspIeClassId);
//			params.put("javaEncoding", jspJavaEncoding);
//			params.put("keepgenerated", jspKeepgenerated);
//			params.put("logVerbosityLevel", jspLogVerbosityLevel);
//			params.put("mappedfile", jspMappedfile);
//			params.put("scratchdir", scratchDir);
//			params.put("tagpoolMaxSize", jspTagpoolMaxSize);
//			params.put("usePrecompiled", jspPrecompilation);
//
//			params.keySet().removeAll(Collections.list(initParams.keys()));
//			for (Map.Entry<String, Object> entry : params.entrySet()) {
//				Object param = entry.getValue();
//				if (param != null) {
//					String initParam = entry.getKey();
//					((Hashtable<String, Object>) initParams).put(initParam, param.toString());
//				}
//			}
//
//		}
//		LOG.debug("JSP scratchdir: " + initParams.get("scratchdir"));
//		return initParams;
//	}
//
//	/**
//	 * @see WebContainer#unregisterJsps(HttpContext)
//	 */
//	@Override
//	public void unregisterJsps(final HttpContext httpContext) {
//		if (!SupportUtils.isJSPAvailable()) {
//			throw new UnsupportedOperationException(
//					"Jsp support is not enabled. Is org.ops4j.pax.web.jsp bundle installed?");
//		}
//		NullArgumentException.validateNotNull(httpContext, "Http context");
//		final OsgiContextModel contextModel = serviceModel
//				.getContextModel(httpContext);
//		if (contextModel == null) {
//			throw new IllegalArgumentException(
//					"Jsp support is not enabled for http context ["
//							+ httpContext + "]");
//		}
//		for (Iterator<Servlet> jspServlets = contextModel.getJspServlets()
//				.keySet().iterator(); jspServlets.hasNext(); ) {
//			Servlet jspServlet = jspServlets.next();
//			try {
//				unregisterServlet(jspServlet);
//			} finally {
//				jspServlets.remove();
//			}
//		}
//	}
//
//	/**
//	 * @see WebContainer#unregisterJsps(HttpContext)
//	 */
//	@Override
//	public void unregisterJsps(final String[] urlPatterns,
//							   final HttpContext httpContext) {
//		if (!SupportUtils.isJSPAvailable()) {
//			throw new UnsupportedOperationException(
//					"Jsp support is not enabled. Is org.ops4j.pax.web.jsp bundle installed?");
//		}
//		NullArgumentException.validateNotNull(httpContext, "Http context");
//		final ContextModel contextModel = serviceModel
//				.getContextModel(httpContext);
//		if (contextModel == null) {
//			throw new IllegalArgumentException(
//					"Jsp support is not enabled for http context ["
//							+ httpContext + "]");
//		}
//		for (Iterator<Map.Entry<Servlet, String[]>> jspServlets = contextModel
//				.getJspServlets().entrySet().iterator(); jspServlets.hasNext(); ) {
//			Map.Entry<Servlet, String[]> entry = jspServlets.next();
//			String[] candidateUrlPatterns = entry.getValue();
//			if (Arrays.equals(urlPatterns, candidateUrlPatterns)) {
//				Servlet jspServlet = entry.getKey();
//				try {
//					unregisterServlet(jspServlet);
//				} finally {
//					jspServlets.remove();
//				}
//			}
//		}
//	}
//
//	@Override
//	public void registerLoginConfig(String authMethod, String realmName,
//									String formLoginPage, String formErrorPage, HttpContext httpContext) {
//		NullArgumentException.validateNotNull(httpContext, "Http context");
//		final ContextModel contextModel = getOrCreateContext(httpContext);
//		String currentAuthMethod = contextModel.getAuthMethod();
//		String currentRealmName = contextModel.getRealmName();
//		String currentFormLoginPage = contextModel.getFormLoginPage();
//		String currentFormErrorPage = contextModel.getFormErrorPage();
//		List<String> currentConfiguration = Arrays.asList(currentAuthMethod, currentRealmName, currentFormLoginPage, currentFormErrorPage);
//		List<String> newConfiguration = Arrays.asList(authMethod, realmName, formLoginPage, formErrorPage);
//		if (!currentConfiguration.equals(newConfiguration)) {
//			if (!serviceModel.canBeConfigured(httpContext)) {
//				try {
//					LOG.debug("Stopping context model {} to register login configuration", contextModel);
//					serverController.getContext(contextModel).stop();
//				} catch (Exception e) {
//					LOG.error(e.getMessage(), e);
//				}
//			}
//			contextModel.setAuthMethod(authMethod);
//			contextModel.setRealmName(realmName);
//			contextModel.setFormLoginPage(formLoginPage);
//			contextModel.setFormErrorPage(formErrorPage);
//		}
//		serviceModel.addContextModel(contextModel);
//	}
//
//	@Override
//	public void unregisterLoginConfig(final HttpContext httpContext) {
//		NullArgumentException.validateNotNull(httpContext, "Http context");
//		final ContextModel contextModel = serviceModel.getContextModel(httpContext);
//		if (contextModel == null || contextModel.getAuthMethod() == null || contextModel.getRealmName() == null) {
//			throw new IllegalArgumentException(
//					"Security Realm and authorization method are not registered for http context ["
//							+ httpContext + "]");
//		}
//
//		try {
//			LOG.debug("Stopping context model {} to unregister login configuration", contextModel);
//			serverController.getContext(contextModel).stop();
//		} catch (Exception e) {
//			LOG.error(e.getMessage(), e);
//		}
//
//		contextModel.setRealmName(null);
//		contextModel.setAuthMethod(null);
//		contextModel.setFormLoginPage(null);
//		contextModel.setFormErrorPage(null);
//	}
//
//	@Override
//	public void registerConstraintMapping(String constraintName, String mapping,
//										  String url, String dataConstraint, boolean authentication,
//										  List<String> roles, HttpContext httpContext) {
//		final ContextModel contextModel = getOrCreateContext(httpContext);
//		LOG.debug("Register constraint mapping (name={}). Using context [{}]", constraintName, contextModel);
//		SecurityConstraintMappingModel secConstraintMapModel = new SecurityConstraintMappingModel(
//				contextModel, constraintName, mapping, url, dataConstraint,
//				authentication, roles);
//		serviceModel.addSecurityConstraintMappingModel(secConstraintMapModel);
//		serverController.addSecurityConstraintMapping(secConstraintMapModel);
//	}
//
//	@Override
//	public void unregisterConstraintMapping(final HttpContext httpContext) {
//		NullArgumentException.validateNotNull(httpContext, "Http context");
//		final ContextModel contextModel = serviceModel.getContextModel(httpContext);
//
//		try {
//			LOG.debug("Stopping context model {} to unregister constraint mapping", contextModel);
//			serverController.getContext(contextModel).stop();
//		} catch (Exception e) {
//			LOG.error(e.getMessage(), e);
//		}
//
//		// Without changing WebContainer interface, we can't remove individual constraints...
//		SecurityConstraintMappingModel[] mappings = serviceModel.getSecurityConstraintMappings();
//		if (mappings != null) {
//			for (SecurityConstraintMappingModel model: mappings) {
//				if (model != null) {
//					serviceModel.removeSecurityConstraintMappingModel(model);
//					serverController.removeSecurityConstraintMapping(model);
//				}
//			}
//		}
//	}
//
//	@Override
//	public void registerJettyWebXml(URL jettyWebXmlURL, HttpContext httpContext) {
//		NullArgumentException.validateNotNull(httpContext, "Http context");
//		final ContextModel contextModel = getOrCreateContext(httpContext);
//		LOG.debug("Using context [" + contextModel + "]");
//		URL contextModelJettyWebXmlURL = contextModel.getJettyWebXmlURL();
//		if (!(contextModelJettyWebXmlURL == jettyWebXmlURL || contextModelJettyWebXmlURL != null
//				&& contextModelJettyWebXmlURL.equals(jettyWebXmlURL))) {
//			if (!serviceModel.canBeConfigured(httpContext)) {
//				throw new IllegalStateException(
//						"Http context already used. jetty-web.xml URL can be set/changed only before first usage");
//			}
//			contextModel.setJettyWebXmlUrl(jettyWebXmlURL);
//		}
//		serviceModel.addContextModel(contextModel);
//
//	}
//
//	@Override
//	public void begin(HttpContext httpContext) {
//		final ContextModel contextModel = getOrCreateContext(httpContext);
//		LOG.debug("Beginning and ssing context [" + contextModel + "]");
//		try {
//			serverController.getContext(contextModel);
//			contextModel.setWebBundle(true);
//			//CHECKSTYLE:OFF
//		} catch (RuntimeException e) {
//			LOG.error("Exception starting HttpContext registration", e);
//			throw e;
//		}
//		//CHECKSTYLE:ON
//	}
//
//	@Override
//	public void end(HttpContext httpContext) {
//		final ContextModel contextModel = getOrCreateContext(httpContext);
//		LOG.debug("Using context [" + contextModel + "]");
//		try {
//			serverController.getContext(contextModel).start();
//			//CHECKSTYLE:OFF
//		} catch (Exception e) {
//			if (e instanceof RuntimeException) {
//				throw (RuntimeException) e;
//			}
//			LOG.error("Exception finalizing HttpContext registration", e);
//		}
//		//CHECKSTYLE:ON
//	}

//	@Override
//	public void setConnectorsAndVirtualHosts(List<String> connectors, List<String> virtualHosts,
//											 HttpContext httpContext) {
//		NullArgumentException.validateNotNull(httpContext, "Http context");
//		if (!serviceModel.canBeConfigured(httpContext)) {
//			throw new IllegalStateException(
//					"Http context already used. Connectors and VirtualHosts can be set only before first usage");
//		}
//
//		final ContextModel contextModel = getOrCreateContext(httpContext);
//		LOG.debug("Using context [" + contextModel + "]");
//		List<String> realVirtualHosts = new LinkedList<>(virtualHosts);
//		if (connectors.size() > 0) {
//			for (String connector : connectors) {
//				realVirtualHosts.add("@" + connector);
//			}
//		}
//		if (realVirtualHosts.size() == 0) {
//			realVirtualHosts = this.serverController.getConfiguration()
//					.server().getVirtualHosts();
//		}
//		if (LOG.isDebugEnabled()) {
//			StringBuilder sb = new StringBuilder("VirtualHostList=[");
//			for (String virtualHost : realVirtualHosts) {
//				sb.append(virtualHost).append(",");
//			}
//			sb.append("]");
//			LOG.debug(sb.toString());
//		}
//		contextModel.setVirtualHosts(realVirtualHosts);
//		serviceModel.addContextModel(contextModel);
//	}
//
//	@Override
//	public void registerWebSocket(final Object webSocket, final HttpContext httpContext) {
//		NullArgumentException.validateNotNull(httpContext, "Http Context");
//		NullArgumentException.validateNotNull(webSocket, "WebSocket");
//
//		ContextModel contextModel = getOrCreateContext(httpContext);
//
//		WebSocketModel model = new WebSocketModel(contextModel, webSocket);
//
//		boolean controllerSuccess = false;
//		boolean serviceSuccess = false;
//		try {
//			contextModel.addContainerInitializer(new ServletContainerInitializer() {
//
//				private Integer maxTry = 20;
//
//				@Override
//				public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException {
//					Callable<Boolean> task = () -> registerWebSocket(ctx, 1);
//
//					ExecutorService executor = Executors.newSingleThreadExecutor();
//					Future<Boolean> future = executor.submit(task);
//
//					try {
//						Boolean success = future.get(maxTry * 500 + 2000, TimeUnit.MILLISECONDS);
//						if (success) {
//							LOG.info("registered WebSocket");
//						} else {
//							LOG.error("Failed to create WebSocket, obviosly the endpoint couldn't be registered");
//						}
//					} catch (InterruptedException | ExecutionException | TimeoutException e) {
//						LOG.error("Failed to wait for registering of WebSocket", e);
//					}
//
//				}
//
//				private boolean registerWebSocket(ServletContext ctx, int registerTry) {
//					if (registerTry == maxTry) {
//						LOG.error("Tried to Register Websocket for {} times, will stop now", registerTry);
//						return false;
//					}
//					javax.websocket.server.ServerContainer serverContainer = (javax.websocket.server.ServerContainer) ctx.getAttribute(javax.websocket.server.ServerContainer.class.getName());
//					if (serverContainer != null) {
//						try {
//							serverContainer.addEndpoint(webSocket.getClass());
//							return true;
//						} catch (DeploymentException e) {
//							LOG.error("Failed to register WebSocket", e);
//							return false;
//						}
//					} else {
//						try {
//							LOG.debug("couldn't find ServerContainer, will try again in 500ms");
//							LOG.debug("this is the {} try", registerTry);
//							Thread.sleep(500);
//							return registerWebSocket(ctx, registerTry + 1);
//						} catch (InterruptedException e) {
//							LOG.error("Failed to register WebSocket due to: ", e);
//							return false;
//						}
//					}
//				}
//			}, null);
//			controllerSuccess = true;
//
//			serviceModel.addWebSocketModel(model);
//			serviceSuccess = true;
//		} finally {
//			// as this compensatory actions to work the remove methods should
//			// not throw exceptions.
//			if (!controllerSuccess) {
//				if (serviceSuccess) {
//					serviceModel.removeWebSocketModel(webSocket);
//				}
//			}
//		}
//
//		if (!isWebAppWebContainerContext(contextModel)) {
//			try {
//				serverController.getContext(contextModel).start();
//				// CHECKSTYLE:OFF
//			} catch (Exception e) {
//				LOG.error("Could not start the servlet context for context path ["
//						+ contextModel.getContextName() + "]", e);
//			} //CHECKSTYLE:ON
//		}
//
//	}
//
//	@Override
//	public void unregisterWebSocket(Object webSocket, HttpContext httpContext) {
//		// TODO Auto-generated method stub
//	}

//	@Override
//	public RequestInfoDTO calculateRequestInfoDTO(String path, Iterator<WhiteboardElement> iterator) {
//		return withWhiteboardDtoService(service -> service.calculateRequestInfoDTO(path, iterator, serverModel, serviceModel));
//	}
//
//	@Override
//	public RuntimeDTO createWhiteboardRuntimeDTO(Iterator<WhiteboardElement> iterator) {
//		return withWhiteboardDtoService(service -> service.createWhiteboardRuntimeDTO(iterator, serverModel, serviceModel));
//	}

//	/**
//	 * WhiteboardDtoService is registered as DS component. Should be removed if this class gets full DS support
//	 * @param function a function which is applied against WhiteboardDtoService
//	 * @param <T> Type of the functions return value
//	 * @return value provided by given function
//	 */
//	private <T> T withWhiteboardDtoService(Function<WhiteboardDtoService, T> function) {
//		final BundleContext bundleContext = serviceBundle.getBundleContext();
//		ServiceReference<WhiteboardDtoService> ref = bundleContext.getServiceReference(WhiteboardDtoService.class);
//		if (ref != null) {
//			WhiteboardDtoService service = bundleContext.getService(ref);
//			if (service != null) {
//				try {
//					return function.apply(service);
//				} finally {
//					bundleContext.ungetService(ref);
//				}
//			}
//		}
//		throw new IllegalStateException(String.format("Service '%s' could not be retrieved!", WhiteboardDtoService.class.getName()));
//	}

	@Override
	public String toString() {
		return super.toString() + " for bundle " + serviceBundle;
	}

//    @Override
//    public WebContainerDTO getWebcontainerDTO() {
//        WebContainerDTO dto = new WebContainerDTO();
//
//        dto.port = serverController.getHttpPort();
//        dto.securePort = serverController.getHttpSecurePort();
//        dto.listeningAddresses = serverController.getConfiguration().server().getListeningAddresses();
//
//        return dto;
//    }

	private static class ResourceServlet {

		public final Supplier<Servlet> supplier;
		public final URL urlBase;
		public final String chrootBase;

		ResourceServlet(Supplier<Servlet> supplier, URL urlBase, String chrootBase) {
			this.supplier = supplier;
			this.urlBase = urlBase;
			this.chrootBase = chrootBase;
		}
	}

}
