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
package org.ops4j.pax.web.service.undertow.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletException;
import javax.servlet.SessionCookieConfig;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.UnmarshallerHandler;
import javax.xml.parsers.SAXParserFactory;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.OpenListener;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.accesslog.AccessLogHandler;
import io.undertow.server.handlers.accesslog.AccessLogReceiver;
import io.undertow.server.handlers.accesslog.DefaultAccessLogReceiver;
import io.undertow.servlet.ServletExtension;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.ConfidentialPortManager;
import io.undertow.servlet.api.Deployment;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ErrorPage;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.ListenerInfo;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.api.ServletSessionConfig;
import io.undertow.servlet.api.ServletStackTraces;
import io.undertow.servlet.api.SessionPersistenceManager;
import io.undertow.servlet.core.ContextClassLoaderSetupAction;
import io.undertow.servlet.core.DeploymentImpl;
import io.undertow.servlet.core.ManagedFilter;
import io.undertow.servlet.core.ManagedFilters;
import io.undertow.servlet.core.ManagedListener;
import io.undertow.servlet.core.ManagedServlet;
import io.undertow.servlet.handlers.ServletHandler;
import io.undertow.servlet.util.ImmediateInstanceFactory;
import io.undertow.servlet.util.InMemorySessionPersistence;
import org.ops4j.pax.web.service.spi.config.Configuration;
import org.ops4j.pax.web.service.spi.config.LogConfiguration;
import org.ops4j.pax.web.service.spi.config.SessionConfiguration;
import org.ops4j.pax.web.service.spi.model.OsgiContextModel;
import org.ops4j.pax.web.service.spi.model.ServletContextModel;
import org.ops4j.pax.web.service.spi.model.elements.ContainerInitializerModel;
import org.ops4j.pax.web.service.spi.model.elements.ErrorPageModel;
import org.ops4j.pax.web.service.spi.model.elements.EventListenerModel;
import org.ops4j.pax.web.service.spi.model.elements.FilterModel;
import org.ops4j.pax.web.service.spi.model.elements.ServletModel;
import org.ops4j.pax.web.service.spi.model.elements.SessionConfigurationModel;
import org.ops4j.pax.web.service.spi.model.elements.WelcomeFileModel;
import org.ops4j.pax.web.service.spi.servlet.Default404Servlet;
import org.ops4j.pax.web.service.spi.servlet.DynamicRegistrations;
import org.ops4j.pax.web.service.spi.servlet.OsgiDynamicServletContext;
import org.ops4j.pax.web.service.spi.servlet.OsgiInitializedServlet;
import org.ops4j.pax.web.service.spi.servlet.OsgiServletContext;
import org.ops4j.pax.web.service.spi.servlet.OsgiServletContextClassLoader;
import org.ops4j.pax.web.service.spi.servlet.RegisteringContainerInitializer;
import org.ops4j.pax.web.service.spi.task.BatchVisitor;
import org.ops4j.pax.web.service.spi.task.ContainerInitializerModelChange;
import org.ops4j.pax.web.service.spi.task.ErrorPageModelChange;
import org.ops4j.pax.web.service.spi.task.ErrorPageStateChange;
import org.ops4j.pax.web.service.spi.task.EventListenerModelChange;
import org.ops4j.pax.web.service.spi.task.FilterModelChange;
import org.ops4j.pax.web.service.spi.task.FilterStateChange;
import org.ops4j.pax.web.service.spi.task.OpCode;
import org.ops4j.pax.web.service.spi.task.OsgiContextModelChange;
import org.ops4j.pax.web.service.spi.task.ServletContextModelChange;
import org.ops4j.pax.web.service.spi.task.ServletModelChange;
import org.ops4j.pax.web.service.spi.task.WelcomeFileModelChange;
import org.ops4j.pax.web.service.spi.util.Utils;
import org.ops4j.pax.web.service.undertow.internal.configuration.ResolvingContentHandler;
import org.ops4j.pax.web.service.undertow.internal.configuration.model.IoSubsystem;
import org.ops4j.pax.web.service.undertow.internal.configuration.model.SecurityRealm;
import org.ops4j.pax.web.service.undertow.internal.configuration.model.Server;
import org.ops4j.pax.web.service.undertow.internal.configuration.model.SocketBinding;
import org.ops4j.pax.web.service.undertow.internal.configuration.model.UndertowConfiguration;
import org.ops4j.pax.web.service.undertow.internal.web.FlexibleErrorPages;
import org.ops4j.pax.web.service.undertow.internal.web.OsgiServletContainerInitializerInfo;
import org.ops4j.pax.web.service.undertow.internal.web.UndertowResourceServlet;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xnio.IoUtils;
import org.xnio.StreamConnection;
import org.xnio.XnioWorker;
import org.xnio.channels.AcceptingChannel;

/**
 * Wrapper of actual {@link Undertow} server that can translate generic model changes into Undertow configuration
 * and deployments.
 */
class UndertowServerWrapper implements BatchVisitor {

	private static final Logger LOG = LoggerFactory.getLogger(UndertowServerWrapper.class);

	/** An <em>entry</em> to OSGi runtime to lookup other bundles if needed (to get their ClassLoader) */
	private final Bundle paxWebUndertowBundle;
	/** Outside of OSGi, let's use passed ClassLoader */
	private final ClassLoader classLoader;

	/**
	 * <p>In Tomcat and Jetty we keep here a single reference to actual Tomcat/Jetty embedded server. With Undertow
	 * everything is a bit more flexible. Before Pax Web 8 we just had an instance of {@link Undertow} server, but
	 * after checking that Wildfly doesn't do it (it has similar {@code org.wildfly.extension.undertow.Server} for this
	 * purpose), we'll keep the Undertow internals as pieces - most important is a list of "accepting channels" that
	 * all can invoke single "root handler".</p>
	 *
	 * <p>The "root handler" is common to all connectors/listeners, but each listener may wrap the common "root
	 * handler" in listener-specific wrappers that can be configured using {@code undertow.xml}.</p>
	 *
	 * <p>In terms of Undertow, the "connector" is represented by {@link OpenListener} which is called by
	 * {@link AcceptingChannel} glued together using
	 * {@link org.xnio.ChannelListeners#openListenerAdapter(org.xnio.ChannelListener)}.</p>
	 *
	 * <p>These "listeners" are configured using {@code <server>/<http(s)-listener>} elements in
	 * {@code undertow.xml}.</p>
	 */
	private final Map<String, UndertowFactory.AcceptingChannelWithAddress> listeners = new HashMap<>();

	private final Map<String, XnioWorker> workers = new HashMap<>();
	private final Map<String, ByteBufferPool> bufferPools = new HashMap<>();

	/** Mapping from non-secure ports to secure ports - collected when reading XML listener definitions */
	private final Map<Integer, Integer> securePortMapping = new HashMap<>();

	/**
	 * Root {@link HttpHandler} for Undertow - it may be anything that eventually delegates to
	 * {@link io.undertow.server.handlers.PathHandler}. Single "root handler" is shared by all the listeners, which
	 * may (but don't have to) wrap it in listener-specific handler wrappers.
	 */
	private HttpHandler rootHandler;

	/**
	 * Top-level, or wrapped (inside different {@link #rootHandler}) {@link PathHandler} that contains 1:1
	 * mapping to actual <em>contexts</em>.
	 */
	private PathHandler pathHandler;

	private final UndertowFactory undertowFactory;

	/**
	 * Single <em>container</em> for all Undertow contexts. It can directly map context path to
	 * {@link DeploymentManager} instances.
	 */
	private final ServletContainer servletContainer = Servlets.newContainer();

	// A word of explanation on strange Undertow model...
	//  - io.undertow.servlet.api.ServletContainer - a single "container" to manage deployments through
	//    "deployment managers". This "servlet container" is not used at all by Undertow at runtime! It's kind of
	//    external context path -> context mapping. The actual HttpHandlers created for each "deployment"
	//    should be manually added to running server anyway
	//  - io.undertow.servlet.api.DeploymentInfo - a POJO representing everything related to a servlet context
	//    (i.e., actual web application)
	//  - io.undertow.servlet.api.DeploymentManager - object created by "servlet container" to represent
	//    "deployment info". After adding "deployment info" to "servlet container", it is turned into "deployment
	//    manager" that controls the lifecycle of a "deployment"
	//  - io.undertow.servlet.api.Deployment is something that's managed by "deployment manager" and it's created after
	//    telling "deployment manager" to "deploy()" itself
	//
	// Now the problem is that while "deployment" (io.undertow.servlet.api.Deployment) can be used to
	// io.undertow.servlet.api.Deployment.getServlets() to get io.undertow.servlet.core.ManagedServlets instance
	// to which we can add new servlets, this ManagedServlets class CAN'T be used to remove existing servlets...
	//
	// Because deploy() clones the original deployment, we can't have single DeploymentManager (or Deployment)
	// being used to add/remove servlets (and other web elements)
	//
	// Unfortunately, even io.undertow.servlet.api.DeploymentInfo object can't be used to fully control the
	// "servlet context". While it returns servlets and filters via direct reference to internal map, filter
	// mapping is returned as shallow copy, so we CAN'T remove existing mappings...

	/**
	 * <em>Outer handlers</em> for all the contexts - responsible for setting proper request wrappers. This can
	 * be done in cleaner way in Jetty and Tomcat.
	 */
	private final Map<String, PaxWebOuterHandlerWrapper> wrappingHandlers = new HashMap<>();

	/**
	 * Handlers that call {@link org.osgi.service.http.HttpContext#handleSecurity} and/or
	 * {@link org.osgi.service.http.context.ServletContextHelper#handleSecurity}.
	 */
	private final Map<String, PaxWebSecurityHandler> securityHandlers = new HashMap<>();

	/**
	 * When constructing <em>deployment infos</em> we have to remember them separately before calling
	 * {@link DeploymentManager#deploy()}, as it'll clone the {@link DeploymentInfo} (twice) and hide
	 * the {@link Deployment} implementation in the {@link DeploymentManager} (...). This is important
	 * to implement the "start the context only after first servlet/filter/resource has been registered".
	 */
	private final Map<String, DeploymentInfo> deploymentInfos = new HashMap<>();

	// TODO: the three below fields are the same in Jetty, Tomcat and Undertow

	/**
	 * 1:1 mapping between {@link OsgiContextModel} and {@link org.osgi.service.http.context.ServletContextHelper}'s
	 * specific {@link javax.servlet.ServletContext}.
	 */
	private final Map<OsgiContextModel, OsgiServletContext> osgiServletContexts = new HashMap<>();

	/**
	 * 1:N mapping between context path and sorted (by ranking rules) set of {@link OsgiContextModel}. This helps
	 * finding proper {@link org.osgi.service.http.context.ServletContextHelper} (1:1 with {@link OsgiContextModel})
	 * to use for filters, when the invocation chain doesn't contain target servlet (which otherwise would
	 * determine the ServletContextHelper to use).
	 */
	private final Map<String, TreeSet<OsgiContextModel>> osgiContextModels = new HashMap<>();

	/**
	 * As with Tomcat and Jetty, we'll manually handle SCIs to be able to add/remove them and call them with
	 * proper {@link ServletContext} implementation.
	 */
	private final Map<String, LinkedHashMap<Integer, OsgiServletContainerInitializerInfo>> initializers = new HashMap<>();

	/**
	 * Keep dynamic configuration and use it during startup only.
	 */
	private final Map<String, DynamicRegistrations> dynamicRegistrations = new HashMap<>();

	/**
	 * Global {@link Configuration} passed from pax-web-runtime through
	 * {@link org.ops4j.pax.web.service.spi.ServerController}
	 */
	private final Configuration configuration;

	/** JAXB context used to unmarshall Undertow XML configuration */
	private JAXBContext jaxb = null;

	/** Servlet to use when no servlet is mapped - to ensure that preprocessors and filters are run correctly. */
	private final Default404Servlet default404Servlet = new Default404Servlet(true);

	private SessionCookieConfig defaultSessionCookieConfig;
	private SessionPersistenceManager globalSessionPersistenceManager;

	// configuration read from undertow.xml
	private UndertowConfiguration undertowConfiguration;

	/**
	 * Map that can be used to recal what error pages we had configured at the time when there's a need
	 * to remove some of them.
	 */
	private final Map<String, FlexibleErrorPages> errorPages = new HashMap<>();

	UndertowServerWrapper(Configuration config, UndertowFactory undertowFactory,
			Bundle paxWebUndertowBundle, ClassLoader classLoader) {
		this.configuration = config;
		this.undertowFactory = undertowFactory;
		this.paxWebUndertowBundle = paxWebUndertowBundle;
		this.classLoader = classLoader;
	}

	// --- lifecycle and configuration methods

	/**
	 * Here's where Undertow is being rebuild at {@link Undertow} level (not {@link ServletContainer} level).
	 * This is were <em>global</em> objects are configured (listeners, global filters, ...)
	 */
	public void configure() throws Exception {
		LOG.info("Creating Undertow server instance using configuration properties.");

		// initially rootHandler == pathHandler without any particular path registered
		pathHandler = Handlers.path();
		rootHandler = pathHandler;

		// apply single (if exists) external undertow.xml file by reading it according to Wildfly XSDs,
		// but using Pax Web specific handlers
		// before Pax Web 8 thre was also etc/undertow.properties with identity manager properties,
		// but it's no longer supported
		try {
			applyUndertowConfiguration();
		} catch (Exception e) {
			throw new IllegalArgumentException("Problem configuring Undertow server: " + e.getMessage(), e);
		}

		// Configure NCSA RequestLogHandler if needed
		if (configuration.logging().isLogNCSAFormatEnabled()) {
			configureRequestLog();
		}

		// If external configuration added some connectors, we have to ensure they match declaration from
		// PID config: org.osgi.service.http.enabled and org.osgi.service.http.secure.enabled
		verifyListenerConfiguration();

		// default session configuration is prepared, but not set in the server instance. It can be set
		// only after first context is created
		this.defaultSessionCookieConfig = configuration.session().getDefaultSessionCookieConfig();

		File dir = configuration.session().getSessionStoreDirectory();
		if (dir != null) {
			// configure or override session persistence manager that could've been configured in undertow.xml
			if (globalSessionPersistenceManager != null) {
				LOG.warn("Overriding session persistence manager {} configured in external XML file with new"
								+ "manager configured using PID and location {}",
						globalSessionPersistenceManager, configuration.session().getSessionStoreDirectory());
			}

			LOG.info("Using file session persistence. Location: " + dir.getCanonicalPath());
			globalSessionPersistenceManager = new FileSessionPersistence(dir);
		} else {
			if (globalSessionPersistenceManager == null) {
				LOG.info("Using in-memory session persistence");
				globalSessionPersistenceManager = new InMemorySessionPersistence();
			}
		}
	}

	/**
	 * <p>This method parses existing {@code undertow.xml} file, which is the preferred method of Undertow
	 * configuration</p>
	 */
	private void applyUndertowConfiguration() throws Exception {
		File[] locations = configuration.server().getConfigurationFiles();
		if (locations.length == 0) {
			LOG.info("No external Undertow configuration file specified. Default/PID configuration will be used.");
			return;
		} else if (locations.length > 1) {
			LOG.warn("Can't specify Undertow configuration using more than one XML file. Skipping XML configuration.");
			return;
		} else {
			LOG.info("Processing Undertow configuration using file: {}", locations[0]);
		}

		File xmlConfig = locations[0];

//			case PROPERTIES:
//				LOG.info("Using \"" + undertowResource + "\" to read additional configuration for Undertow");
//				configureIdentityManager(undertowResource);
//				// do not break - go to standard PID configuration
//			case PID:
//				LOG.info("Using \"org.ops4j.pax.url.web\" PID to configure Undertow");
//				rootHandler = configureUndertow(configuration, builder, rootHandler);
//				break;
//		}

		jaxb = JAXBContext.newInstance("org.ops4j.pax.web.service.undertow.internal.configuration.model", classLoader);
		Unmarshaller unmarshaller = jaxb.createUnmarshaller();
		UnmarshallerHandler unmarshallerHandler = unmarshaller.getUnmarshallerHandler();

		// indirect unmarshalling with property resolution *inside XML attribute values*
		SAXParserFactory spf = SAXParserFactory.newInstance();
		spf.setNamespaceAware(true);
		XMLReader xmlReader = spf.newSAXParser().getXMLReader();

		xmlReader.setContentHandler(new ResolvingContentHandler(configuration.all(), unmarshallerHandler));
		try (InputStream stream = new FileInputStream(xmlConfig)) {
			xmlReader.parse(new InputSource(stream));
		}

		this.undertowConfiguration = (UndertowConfiguration) unmarshallerHandler.getResult();
//		if (cfg == null
//				|| cfg.getSocketBindings().size() == 0
//				|| cfg.getInterfaces().size() == 0
//				|| cfg.getSubsystem() == null
//				|| cfg.getSubsystem().getServer() == null) {
//			throw new IllegalArgumentException("Problem configuring Undertow server using \"" + xmlConfig
//					+ "\": incomplete XML configuration");
//		}
		undertowConfiguration.init();

		if (LOG.isDebugEnabled()) {
			LOG.debug("Undertow XML configuration parsed correctly: {}", undertowConfiguration);
		}

		// collect extra workers and byte buffer pools
		IoSubsystem io = undertowConfiguration.getIoSubsystem();
		if (io != null) {
			for (IoSubsystem.Worker worker : io.getWorkers()) {
				workers.put(worker.getName(), undertowFactory.createWorker(worker));
			}
			for (IoSubsystem.BufferPool pool : io.getBuferPools()) {
				bufferPools.put(pool.getName(), undertowFactory.createBufferPool(pool));
			}
		}

		// listeners will be checked by verifyConnectorConfiguration() later
		List<Server.HttpListener> httpListeners = null;
		List<Server.HttpsListener> httpsListeners = null;
		if (undertowConfiguration.getSubsystem() != null && undertowConfiguration.getSubsystem().getServer() != null) {
			httpListeners = undertowConfiguration.getSubsystem().getServer().getHttpListeners();
			httpsListeners = undertowConfiguration.getSubsystem().getServer().getHttpsListeners();
		}

		// http listener(s)
		if (httpListeners != null) {
			for (Server.HttpListener http : httpListeners) {
				String listenerName = http.getName();
				if (!http.isEnabled()) {
					LOG.debug("Skipping disabled Undertow http listener \"{}\"", listenerName);
					continue;
				}
				UndertowConfiguration.BindingInfo binding = undertowConfiguration.bindingInfo(http.getSocketBindingName());
				for (String address : binding.getAddresses()) {
					LOG.info("Configuring Undertow http listener for address " + address + ":" + binding.getPort());

					XnioWorker workerForListener = getWorker(http.getWorkerName());
					ByteBufferPool bufferPoolForListener = getBufferPool(http.getBufferPoolName());

					// this is specific to non-secure listener
					// see org.wildfly.extension.undertow.Server#lookupSecurePort
					if (http.getRedirectSocket() != null) {
						SocketBinding secureSocketBinding = undertowConfiguration.socketBinding(http.getRedirectSocket());
						if (secureSocketBinding != null) {
							this.securePortMapping.put(binding.getPort(), secureSocketBinding.getPort());
						}
					}

					InetSocketAddress inetAddress = new InetSocketAddress(address, binding.getPort());
					AcceptingChannel<? extends StreamConnection> listener = undertowFactory.createListener(
							configuration, http, rootHandler, null,
							workerForListener, bufferPoolForListener,
							inetAddress
					);
					listeners.put(http.getName(), new UndertowFactory.AcceptingChannelWithAddress(listener, inetAddress));
				}
			}
		}

		// https listener(s)
		if (httpsListeners != null) {
			for (Server.HttpsListener https : httpsListeners) {
				String listenerName = https.getName();
				if (!https.isEnabled()) {
					LOG.debug("Skipping disabled Undertow https listener \"{}\"", listenerName);
					continue;
				}
				UndertowConfiguration.BindingInfo binding = undertowConfiguration.bindingInfo(https.getSocketBindingName());
				for (String address : binding.getAddresses()) {
					LOG.info("Configuring Undertow https listener for address " + address + ":" + binding.getPort());

					XnioWorker workerForListener = getWorker(https.getWorkerName());
					ByteBufferPool bufferPoolForListener = getBufferPool(https.getBufferPoolName());

					if (https.getSslContext() != null) {
						LOG.warn("ssl-context reference attribute from https-listener listener is not supported"
								+ " in Pax Web. Please use security-realm reference attribute instead.");
					}
					SecurityRealm realm = undertowConfiguration.securityRealm(https.getSecurityRealm());
					if (realm == null) {
						throw new IllegalArgumentException("No security realm with name \"" + https.getSecurityRealm()
								+ "\" available for \"" + https.getName() + "\" https listener.");
					}

					InetSocketAddress inetAddress = new InetSocketAddress(address, binding.getPort());
					AcceptingChannel<? extends StreamConnection> listener = undertowFactory.createListener(
							configuration, https, rootHandler, realm,
							workerForListener, bufferPoolForListener,
							inetAddress
					);
					listeners.put(https.getName(), new UndertowFactory.AcceptingChannelWithAddress(listener, inetAddress));
				}
			}
		}

//		builder.setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, recordRequestStartTime);
//		if (configuration.server().getConnectorIdleTimeout() != null) {
//			builder.setServerOption(UndertowOptions.IDLE_TIMEOUT, configuration.server().getConnectorIdleTimeout());
//		}
//
//		// identity manager - looked up in "default" security realm
//		SecurityRealm defaultRealm = cfg.securityRealm("default");
//		if (defaultRealm != null) {
//			SecurityRealm.JaasAuth jaasAuth = defaultRealm.getAuthentication().getJaas();
//			SecurityRealm.PropertiesAuth propertiesAuth = defaultRealm.getAuthentication().getProperties();
//			if (jaasAuth != null) {
//				String userPrincipalClassName = defaultRealm.getUserPrincipalClassName();
//				if (userPrincipalClassName == null || "".equals(userPrincipalClassName.trim())) {
//					userPrincipalClassName = "java.security.Principal";
//				}
//				Set<String> rolePrincipalClassNames = new LinkedHashSet<>(defaultRealm.getRolePrincipalClassNames());
//				identityManager = new JaasIdentityManager(jaasAuth.getName(),
//						userPrincipalClassName, rolePrincipalClassNames);
//			} else if (propertiesAuth != null) {
//				File userBase = new File(propertiesAuth.getPath());
//				if (!userBase.isFile()) {
//					throw new IllegalArgumentException(userBase.getCanonicalPath() + " is not accessible. Can't load users/groups information.");
//				}
//				Properties userProperties = new Properties();
//				Map<String, String> map = new HashMap<>();
//				try (FileInputStream stream = new FileInputStream(userBase)) {
//					userProperties.load(stream);
//					for (String user : userProperties.stringPropertyNames()) {
//						map.put(user, userProperties.getProperty(user));
//					}
//				}
//				identityManager = new PropertiesIdentityManager(map);
//			}
//		}
//
//		// /undertow/subsystem/server/host/location - file handlers for static context paths.
//		if (cfg.getSubsystem().getServer().getHost() != null) {
//			for (Server.Host.Location location : cfg.getSubsystem().getServer().getHost().getLocation()) {
//				String context = location.getName();
//				String handlerRef = location.getHandler();
//				UndertowSubsystem.FileHandler fileHandler = cfg.handler(handlerRef);
//				if (fileHandler == null) {
//					throw new IllegalArgumentException("No handler with name \"" + location.getHandler() + "\" available for " + location.getName() + " location.");
//				}
//				File base = new File(fileHandler.getPath());
//				if (!base.isDirectory()) {
//					throw new IllegalArgumentException(base.getCanonicalPath() + " is not accessible. Can't configure handler for " + location.getName() + " location.");
//				}
//				// fileHandler.path is simply filesystem directory
//				ResourceHandler rh = new ResourceHandler(new FileResourceManager(base, 4096));
//				if (cfg.getSubsystem().getServletContainer() != null) {
//					rh.setWelcomeFiles();
//					for (org.ops4j.pax.web.service.undertow.internal.configuration.model.ServletContainer.WelcomeFile wf : cfg.getSubsystem().getServletContainer().getWelcomeFiles()) {
//						rh.addWelcomeFiles(wf.getName());
//					}
//				}
//				if (rootHandler instanceof PathHandler) {
//					if (LOG.isDebugEnabled()) {
//						LOG.debug("Adding resource handler for location \"" + context + "\" and base path \"" + base.getCanonicalPath() + "\".");
//					}
//					((PathHandler) rootHandler).addPrefixPath(context, rh);
//				}
//			}
//		}
//
//		// global filters (subsystem/filters/response-header and subsystem/filters/filter)
//		if (cfg.getSubsystem().getServer().getHost() != null) {
//			for (Server.Host.FilterRef fr : cfg.getSubsystem().getServer().getHost().getFilterRef()) {
//				UndertowSubsystem.AbstractFilter filter = cfg.filter(fr.getName());
//				if (filter == null) {
//					throw new IllegalArgumentException("No filter with name \"" + fr.getName() + "\" available.");
//				}
//				rootHandler = filter.configure(rootHandler, fr.getPredicate());
//			}
//		}
//
//		// session configuration and persistence
//		this.defaultSessionTimeoutInMinutes = 30;
//		try {
//			if (cfg.getSubsystem().getServletContainer() != null) {
//				String defaultSessionTimeout = cfg.getSubsystem().getServletContainer().getDefaultSessionTimeout();
//				if (defaultSessionTimeout != null && !"".equals(defaultSessionTimeout)) {
//					this.defaultSessionTimeoutInMinutes = Integer.parseInt(defaultSessionTimeout);
//				}
//			}
//		} catch (NumberFormatException ignored) {
//		}
//
//		org.ops4j.pax.web.service.undertow.internal.configuration.model.ServletContainer.PersistentSessionsConfig persistentSessions = cfg.getSubsystem().getServletContainer() == null ? null
//				: cfg.getSubsystem().getServletContainer().getPersistentSessions();
//		if (persistentSessions == null) {
//			// no sessions, but let's use InMemorySessionPersistence
//			LOG.info("Using in-memory session persistence");
//			sessionPersistenceManager = new InMemorySessionPersistence();
//		} else {
//			if (persistentSessions.getPath() != null && !"".equals(persistentSessions.getPath().trim())) {
//				// file persistence manager
//				File sessionsDir = new File(persistentSessions.getPath());
//				sessionsDir.mkdirs();
//				LOG.info("Using file session persistence. Location: " + sessionsDir.getCanonicalPath());
//				sessionPersistenceManager = new FileSessionPersistence(sessionsDir);
//			} else {
//				// in memory persistence manager
//				LOG.info("No path configured for persistent-sessions. Using in-memory session persistence.");
//				sessionPersistenceManager = new InMemorySessionPersistence();
//			}
//		}
	}

	private XnioWorker getWorker(String workerName) {
		if (workerName != null) {
			if (!workers.containsKey(workerName)) {
				throw new IllegalArgumentException("No worker named \"" + workerName + "\" is configured");
			}
			return workers.get(workerName);
		} else {
			return undertowFactory.getDefaultWorker(configuration);
		}
	}

	private ByteBufferPool getBufferPool(String bufferPoolName) {
		if (bufferPoolName != null) {
			if (!bufferPools.containsKey(bufferPoolName)) {
				throw new IllegalArgumentException("No buffer pool named \"" + bufferPoolName + "\" is configured");
			}
			return bufferPools.get(bufferPoolName);
		} else {
			return undertowFactory.getDefaultBufferPool();
		}
	}

	/**
	 * External configuration may specify listeners but we may have to add default ones if they're missing
	 */
	private void verifyListenerConfiguration() {
		boolean httpEnabled = configuration.server().isHttpEnabled();
		Integer httpPort = configuration.server().getHttpPort();

		boolean httpsEnabled = configuration.server().isHttpSecureEnabled();
		Integer httpsPort = configuration.server().getHttpSecurePort();

		String[] addresses = configuration.server().getListeningAddresses();

		// review connectors possibly configured from jetty.xml and check if they match configadmin configuration
		for (String address : addresses) {
			verifyListener(address, httpPort, httpEnabled, false,
					() -> undertowFactory.createDefaultListener(address, rootHandler, configuration));

			verifyListener(address, httpsPort, httpsEnabled, true,
					() -> undertowFactory.createSecureListener(address, rootHandler, configuration));
		}
	}

	/**
	 * Verify if current server configuration, possibly created from external {@code undertow.xml} matches the
	 * declaration from PID ({@link org.ops4j.pax.web.service.PaxWebConfig#PID_CFG_HTTP_ENABLED} and
	 * {@link org.ops4j.pax.web.service.PaxWebConfig#PID_CFG_HTTP_SECURE_ENABLED}).
	 *
	 * @param address
	 * @param port
	 * @param enabled
	 * @param secure
	 * @param listenerProvider {@link Supplier} used if connector has to be added to match PID configuration
	 */
	private void verifyListener(String address, Integer port,
			boolean enabled, boolean secure, Supplier<UndertowFactory.AcceptingChannelWithAddress> listenerProvider) {
		UndertowFactory.AcceptingChannelWithAddress expectedListener = null;

		boolean listenerFound = false;
		UndertowFactory.AcceptingChannelWithAddress backupListener = null;

		for (Iterator<UndertowFactory.AcceptingChannelWithAddress> iterator = listeners.values().iterator(); iterator.hasNext(); ) {
			UndertowFactory.AcceptingChannelWithAddress listener = iterator.next();
			String className = listener.getAcceptingChannel().getClass().getName();
			boolean secureMatch = secure && className.equals("io.undertow.protocols.ssl.UndertowAcceptingSslChannel");
			boolean nonSecureMatch = !secure && className.equals("org.xnio.nio.QueuedNioTcpServer");
			if (secureMatch || nonSecureMatch) {
				if (match(address, port, listener.getAddress())) {
					listenerFound = true;
					if (enabled) {
						expectedListener = listener;
					} else {
						LOG.warn("Listener defined in external configuration will be removed, "
								+ "because it's not enabled: {}", listener);
						iterator.remove();
					}
				} else {
					backupListener = listener;
				}
			}
		}
		if (expectedListener == null && backupListener != null) {
			expectedListener = backupListener;
		}
		if (listenerFound) {
			if (enabled) {
				LOG.info("Using configured {} as {} listener for address: {}:{}", expectedListener,
						(secure ? "secure" : "non secure"), address, port);
			}
		} else if (enabled) {
			LOG.info("Creating {} connector for address {}:{}", (secure ? "secure" : "non secure"), address, port);
			// we have to create a listener
			UndertowFactory.AcceptingChannelWithAddress listener = listenerProvider.get();
			listeners.put(UUID.randomUUID().toString(), listener);
		}
	}

	/**
	 * Check if the passed address can be treated as one <em>matching</em> the connector
	 * declared using PID properties.
	 *
	 * @param address1
	 * @param port1
	 * @param connector
	 * @return
	 */
	private boolean match(String address1, Integer port1, InetSocketAddress connector) {
		InetSocketAddress isa1 = address1 != null ? new InetSocketAddress(address1, port1)
				: new InetSocketAddress(port1);

		return isa1.equals(connector);
	}

	/**
	 * Configure request logging (AKA <em>NCSA logging</em>) for Undertow, using configuration properties.
	 */
	public void configureRequestLog() throws IOException {
		// XML configuration
		if (undertowConfiguration != null && undertowConfiguration.getSubsystem() != null
				&& undertowConfiguration.getSubsystem().getServer() != null
				&& undertowConfiguration.getSubsystem().getServer().getHost() != null
				&& undertowConfiguration.getSubsystem().getServer().getHost().getAccessLog() != null) {
			Server.Host.AccessLog accessLog = undertowConfiguration.getSubsystem().getServer().getHost().getAccessLog();

			Bundle bundle = FrameworkUtil.getBundle(UndertowServerController.class);
			ClassLoader loader = bundle.adapt(BundleWiring.class).getClassLoader();
			XnioWorker xnioWorker = undertowFactory.createLogWorker();

			AccessLogReceiver logReceiver = DefaultAccessLogReceiver.builder()
					.setLogWriteExecutor(xnioWorker)
					.setOutputDirectory(new File(accessLog.getDirectory()).toPath())
					.setLogBaseName(accessLog.getPrefix())
					.setLogNameSuffix(accessLog.getSuffix())
					.setRotate(Boolean.parseBoolean(accessLog.getRotate()))
					.build();

			rootHandler = new AccessLogHandler(rootHandler, logReceiver, accessLog.getPattern(),
					AccessLogHandler.class.getClassLoader());
			return;
		}

		LogConfiguration lc = configuration.logging();
		if (!lc.isLogNCSAFormatEnabled()) {
			return;
		}

		if (lc.getLogNCSADirectory() == null) {
			throw new IllegalArgumentException("Log directory for NCSA logging is not specified. Please set"
					+ " org.ops4j.pax.web.log.ncsa.directory property.");
		}
		File logDir = new File(lc.getLogNCSADirectory());
		if (logDir.isFile()) {
			throw new IllegalArgumentException(logDir + " is not a valid directory to store request logs");
		}

		LOG.info("NCSARequestlogging is using directory {}", lc.getLogNCSADirectory());

		// properties based log configuration:

		if (lc.isLogNCSAFormatEnabled()) {
			String logNCSADirectory = lc.getLogNCSADirectory();
			String baseName = lc.getLogNCSAFile();

			Bundle bundle = FrameworkUtil.getBundle(UndertowServerController.class);
			ClassLoader loader = bundle == null ? getClass().getClassLoader()
					: bundle.adapt(BundleWiring.class).getClassLoader();
			XnioWorker xnioWorker = undertowFactory.createLogWorker();

			// String logNameSuffix = logNCSAFormat.substring(logNCSAFormat.lastIndexOf("."));
			// String logBaseName = logNCSAFormat.substring(0, logNCSAFormat.lastIndexOf("."));

			AccessLogReceiver logReceiver = DefaultAccessLogReceiver.builder()
					.setLogWriteExecutor(xnioWorker)
					.setOutputDirectory(new File(logNCSADirectory).toPath())
					.setLogBaseName(baseName)
					.setLogNameSuffix("log")
					.setRotate(true)
					.build();

			String format;
			// see io.undertow.server.handlers.accesslog.AccessLogHandler.handleCommonNames
			if (lc.isLogNCSAExtended()) {
				format = "combined";
			} else {
				format = "common";
			}

			rootHandler = new AccessLogHandler(rootHandler, logReceiver, format,
					AccessLogHandler.class.getClassLoader());
		}
	}

	/**
	 * Simply start Undertow server
	 * @throws Exception
	 */
	public void start() throws Exception {
		LOG.info("Starting {}", this);

		this.listeners.values().forEach(l -> l.getAcceptingChannel().resumeAccepts());
	}

	/**
	 * One-time operation that stops Undertow server. We should not be able to start it again.
	 *
	 * @throws Exception
	 */
	public void stop() throws Exception {
		LOG.info("Stopping {}", this);

		this.listeners.values().forEach(l -> IoUtils.safeClose(l.getAcceptingChannel()));

		servletContainer.listDeployments().forEach(d -> {
			DeploymentManager deployment = servletContainer.getDeployment(d);
			String contextPath = deployment.getDeployment().getServletContext().getContextPath();
			try {
				deployment.stop();
				deployment.undeploy();
			} catch (ServletException e) {
				LOG.warn("Problem stopping deployment for context " + contextPath + ": " + e.getMessage(), e);
			}
		});
		deploymentInfos.clear();
		// do not clear osgiContextModels and osgiServletContexts
		// - they'll be cleared individually through HttpServiceEnabled
//		osgiServletContexts.clear();
//		osgiContextModels.clear();
		securityHandlers.clear();
		wrappingHandlers.clear();
		errorPages.clear();

		this.workers.values().forEach(XnioWorker::shutdown);
		this.workers.clear();
		this.bufferPools.values().forEach(ByteBufferPool::close);
		this.bufferPools.clear();
		undertowFactory.closeDefaultPoolAndBuffer();
	}

	/**
	 * If state allows, this methods returns currently configured/started addresses of the listeners.
	 * @param useLocalPort
	 * @return
	 */
	public InetSocketAddress[] getAddresses(boolean useLocalPort) {
		if (listeners.size() == 0) {
			return null;
		}
		final List<InetSocketAddress> result = new ArrayList<>(listeners.size());
		listeners.values().forEach(ac -> result.add(ac.getAddress()));
		return result.toArray(new InetSocketAddress[0]);
	}

	// --- visitor methods for model changes

	@Override
	public void visit(ServletContextModelChange change) {
		ServletContextModel model = change.getServletContextModel();
		String contextPath = model.getContextPath();

		if (change.getKind() == OpCode.ADD) {
			LOG.info("Creating new Undertow context for {}", model);

			// meta info about "servlet context deployment"
			DeploymentInfo deploymentInfo = Servlets.deployment();

			String deploymentName = "/".equals(model.getContextPath())
					? "ROOT" : model.getContextPath().substring(1).replaceAll("/", "_");
			deploymentInfo.setDeploymentName(deploymentName);
			deploymentInfo.setDisplayName(model.getId());
			deploymentInfo.setContextPath(contextPath);
			deploymentInfo.setUrlEncoding(StandardCharsets.UTF_8.name());
			deploymentInfo.setEagerFilterInit(true);
			if (configuration.server().isShowStacks()) {
				deploymentInfo.setServletStackTraces(ServletStackTraces.ALL);
			} else {
				deploymentInfo.setServletStackTraces(ServletStackTraces.NONE);
			}

			// we have no classloader yet to set - it should come from "highest ranked OsgiContextModel" for given
			// servlet context (context path)
//			deploymentInfo.setClassLoader(classLoader);

			deploymentInfo.addServlet(new PaxWebServletInfo("default", default404Servlet, true).addMapping("/"));

			// In Jetty and Tomcat we can operate on FilterChains, here we have to split the OsgiFilterChain's
			// functionality into different HandlerWrappers:
			//  - to wrap request, so proper ServletContext is returned
			//  - to call preprocessors
			//  - to call handleSecurity()/finishSecurity()

			PaxWebOuterHandlerWrapper outerWrapper = new PaxWebOuterHandlerWrapper();
			this.wrappingHandlers.put(contextPath, outerWrapper);
			deploymentInfo.addOuterHandlerChainWrapper(outerWrapper);

			// TODO: ensure preprocessors work
//			PaxWebPreprocessorsHandler preprocessorWrapper = new PaxWebPreprocessorsHandler();
//			this.preprocessorsHandlers.put(contextPath, preprocessorWrapper);
//			deployment.addOuterHandlerChainWrapper(preprocessorWrapper);

			PaxWebSecurityHandler securityWrapper = new PaxWebSecurityHandler();
			this.securityHandlers.put(contextPath, securityWrapper);
			deploymentInfo.addSecurityWrapper(securityWrapper);

//							deployment.setConfidentialPortManager(getConfidentialPortManager());
//							deployment.setResourceManager(this);
//							deployment.setIdentityManager(identityManager);

			// session configuration - based on defaultSessionConfiguration, but may be later overriden in OsgiContext
			SessionConfiguration sc = configuration.session();
			deploymentInfo.setDefaultSessionTimeout(sc.getSessionTimeout() * 60);
			ServletSessionConfig ssc = new ServletSessionConfig();
			ssc.setName(defaultSessionCookieConfig.getName());
			ssc.setDomain(defaultSessionCookieConfig.getDomain());
			// will default to context path if null
			ssc.setPath(defaultSessionCookieConfig.getPath());
			ssc.setMaxAge(defaultSessionCookieConfig.getMaxAge());
			ssc.setHttpOnly(defaultSessionCookieConfig.isHttpOnly());
			ssc.setSecure(defaultSessionCookieConfig.isSecure());
			ssc.setComment(defaultSessionCookieConfig.getComment());
			deploymentInfo.setServletSessionConfig(ssc);

			deploymentInfo.setSessionPersistenceManager(globalSessionPersistenceManager);

			// do NOT add&deploy&start the context here - only after registering first "active" web element
			// only prepare the original (cloned later) DeploymentInfo
			deploymentInfos.put(contextPath, deploymentInfo);

			// prepare mapping of sorted OsgiContextModel collections per context path
			osgiContextModels.put(contextPath, new TreeSet<>());

			// configure ordered map of initializers
			initializers.put(model.getContextPath(), new LinkedHashMap<>());
			dynamicRegistrations.put(model.getContextPath(), new DynamicRegistrations());
		}
	}

	@Override
	public void visit(OsgiContextModelChange change) {
		if (change.getKind() == OpCode.ASSOCIATE || change.getKind() == OpCode.DISASSOCIATE) {
			return;
		}

		OsgiContextModel osgiModel = change.getOsgiContextModel();
		ServletContextModel servletContextModel = change.getServletContextModel();

		String contextPath = osgiModel.getContextPath();

		if (change.getKind() == OpCode.ADD) {
			LOG.info("Adding {} to deployment info of {}", osgiModel, contextPath);

			// as with Jetty and Tomcat,
			// each unique OsgiServletContext (ServletContextHelper or HttpContext) is a facade for some, sometimes
			// shared by many osgi contexts, real ServletContext
			if (osgiServletContexts.containsKey(osgiModel)) {
				throw new IllegalStateException(osgiModel + " is already registered");
			}

			// this (and similar Jetty and Tomcat places) should be the only place where
			// org.ops4j.pax.web.service.spi.servlet.OsgiServletContext is created and we have everything ready
			// to create proper classloader for this OsgiServletContext
			// unlike in Jetty or Tomcat, getRealServletContext() may return null for not started context
			ClassLoader classLoader = null;
			if (paxWebUndertowBundle != null) {
				// it may not be the case in Test scenario
				OsgiServletContextClassLoader loader = new OsgiServletContextClassLoader();
				loader.addBundle(osgiModel.getOwnerBundle());
				loader.addBundle(paxWebUndertowBundle);
				loader.addBundle(Utils.getPaxWebJspBundle(paxWebUndertowBundle));
				loader.makeImmutable();
				classLoader = loader;
			} else {
				classLoader = this.classLoader;
			}
			OsgiServletContext osgiContext = new OsgiServletContext(getRealServletContext(contextPath), osgiModel, servletContextModel,
					defaultSessionCookieConfig, classLoader);
			osgiServletContexts.put(osgiModel, osgiContext);
			osgiContextModels.get(contextPath).add(osgiModel);
		}

		if (change.getKind() == OpCode.DELETE) {
			LOG.info("Removing {} from {}", osgiModel, contextPath);

			// TOCHECK: are there web elements associated with removed mapping for OsgiServletContext?
			OsgiServletContext removedOsgiServletContext = osgiServletContexts.remove(osgiModel);
			osgiContextModels.get(contextPath).remove(osgiModel);

			removedOsgiServletContext.unregister();
		}

		// there may be a change in what's the "best" (highest ranked) OsgiContextModel for given
		// ServletContextModel. This will became the "fallback" OsgiContextModel for chains without
		// target servlet (with filters only)
		OsgiContextModel highestRankedModel = Utils.getHighestRankedModel(osgiContextModels.get(contextPath));
		if (highestRankedModel != null) {
			OsgiServletContext highestRankedContext = osgiServletContexts.get(highestRankedModel);
			// default "contexts" to handle security and class/resource loading
			if (wrappingHandlers.containsKey(contextPath)) {
				wrappingHandlers.get(contextPath).setDefaultServletContext(highestRankedContext);
			}
			if (securityHandlers.containsKey(contextPath)) {
				securityHandlers.get(contextPath).setDefaultOsgiContextModel(highestRankedModel);
			}

			// we have to ensure that non-highest ranked contexts are unregistered
			osgiServletContexts.forEach((ocm, osc) -> {
				if (ocm.getContextPath().equals(contextPath) && osc != highestRankedContext) {
					osc.unregister();
				}
			});

			// and the highest ranked context should be registered as OSGi service (if it wasn't registered)
			highestRankedContext.register();
		} else {
			// TOCHECK: there should be no more web elements in the context, no OSGi mechanisms, just 404 all the time
			if (wrappingHandlers.containsKey(contextPath)) {
				wrappingHandlers.get(contextPath).setDefaultServletContext(null);
			}
			if (securityHandlers.containsKey(contextPath)) {
				securityHandlers.get(contextPath).setDefaultOsgiContextModel(null);
			}
		}

//			// manager (lifecycle manager of the deployment),
//			DeploymentManager manager = servletContainer.getDeploymentByPath(contextPath);
//			// the managed deployment
//			Deployment deployment = manager.getDeployment();
//			// and the deployment information
//			DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
//
//			// to make the code consistent, we could set DeploymentInfo's class loader here, but in fact, it's
//			// used only in situations we override anyway (like adding servlets to existing ServletContext)
////			ClassLoader classLoader = highestRankedModel.getOwnerBundle().adapt(BundleWiring.class).getClassLoader();
////			servletContainer.getDeploymentByPath(contextPath).undeploy();
////			deploymentInfo.setClassLoader(classLoader);
////			servletContainer.getDeploymentByPath(contextPath).deploy();
	}

	@Override
	public void visit(ServletModelChange change) {
		if ((change.getKind() == OpCode.ADD && !change.isDisabled()) || change.getKind() == OpCode.ENABLE) {
			ServletModel model = change.getServletModel();
			LOG.info("Adding servlet {}", model);

			// see implementation requirements in Jetty version of this visit() method

			Set<String> done = new HashSet<>();

			change.getContextModels().forEach(osgiContext -> {
				String contextPath = osgiContext.getContextPath();
				if (!done.add(contextPath)) {
					return;
				}

				ensureServletContextStarted(contextPath);

				LOG.debug("Adding servlet {} to {}", model.getName(), contextPath);

				// manager (lifecycle manager of the deployment),
				DeploymentManager manager = servletContainer.getDeploymentByPath(contextPath);
				// the managed deployment
				Deployment deployment = manager.getDeployment();
				// and the deployment information
				DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();

				// <servlet> - always associated with one of ServletModel's OsgiContextModels
				OsgiServletContext context = osgiServletContexts.get(osgiContext);

				// new servlet info
				ServletInfo info = new PaxWebServletInfo(model, osgiContext, context);

				boolean isDefaultResourceServlet = model.isResourceServlet();
				for (String pattern : model.getUrlPatterns()) {
					isDefaultResourceServlet &= "/".equals(pattern);
				}
				info.addInitParam("pathInfoOnly", Boolean.toString(!isDefaultResourceServlet));
				info.addInitParam("resolve-against-context-root", Boolean.toString(isDefaultResourceServlet));

				// when only adding new servlet, we can simply alter existing deployment
				// because this is possible (as required by methods like javax.servlet.ServletContext.addServlet())
				// we can't go the easy way when _removing_ servlets
				deploymentInfo.addServlet(info);
				deployment.getServlets().addServlet(info);

				// are there any error page declarations in the model?
				ErrorPageModel epm = model.getErrorPageModel();
				if (epm != null) {
					String location = epm.getLocation();
					FlexibleErrorPages currentState = errorPages.computeIfAbsent(contextPath, cp -> new FlexibleErrorPages());
					for (String ex : epm.getExceptionClassNames()) {
						try {
							ClassLoader cl = model.getRegisteringBundle().adapt(BundleWiring.class).getClassLoader();
							@SuppressWarnings("unchecked")
							Class<Throwable> t = (Class<Throwable>) cl.loadClass(ex);
							currentState.getExceptionMappings().put(t, location);
							deploymentInfo.addErrorPage(new ErrorPage(location, t));
						} catch (ClassNotFoundException e) {
							LOG.warn("Can't load exception class {}: {}", ex, e.getMessage(), e);
						}
					}
					for (int code : epm.getErrorCodes()) {
						currentState.getErrorCodeLocations().put(code, location);
						deploymentInfo.addErrorPage(new ErrorPage(location, code));
					}
					if (epm.isXx4()) {
						for (int c = 400; c < 500; c++) {
							currentState.getErrorCodeLocations().put(c, location);
							deploymentInfo.addErrorPage(new ErrorPage(location, c));
						}
					}
					if (epm.isXx5()) {
						for (int c = 500; c < 600; c++) {
							currentState.getErrorCodeLocations().put(c, location);
							deploymentInfo.addErrorPage(new ErrorPage(location, c));
						}
					}

					// replace the error pages in actual deployment
					if (deployment instanceof DeploymentImpl) {
						((DeploymentImpl) deployment).setErrorPages(currentState);
					}
				}
			});
			return;
		}

		if (change.getKind() == OpCode.DISABLE || change.getKind() == OpCode.DELETE) {
			for (Map.Entry<ServletModel, Boolean> entry : change.getServletModels().entrySet()) {
				ServletModel model = entry.getKey();
				if (!entry.getValue()) {
					continue;
				}
				LOG.info("Removing servlet {}", model);

				Set<String> done = new HashSet<>();

				// proper order ensures that (assuming above scenario), for /c1, ocm2 will be chosen and ocm1 skipped
				model.getContextModels().forEach(osgiContextModel -> {
					String contextPath = osgiContextModel.getContextPath();
					if (!done.add(contextPath)) {
						return;
					}

					// this time we just assume that the servlet context is started

					LOG.debug("Removing servlet {} from context {}", model.getName(), contextPath);

					// take existing deployment manager and the deployment info from its deployment
					DeploymentManager manager = servletContainer.getDeploymentByPath(contextPath);
					DeploymentInfo deploymentInfo = manager.getDeployment().getDeploymentInfo();

					// let's immediately show that given context is no longer mapped
					pathHandler.removePrefixPath(contextPath);

					try {
						// manager needs to stop the deployment and get rid of it, because we
						// can't replace a deployment info within deployment manager
						manager.stop();
						manager.undeploy();
						// swap the deployment info, which will later be used to start the context
						deploymentInfos.put(contextPath, deploymentInfo);
					} catch (ServletException e) {
						throw new RuntimeException("Problem stopping the deployment of context " + contextPath
								+ ": " + e.getMessage(), e);
					}

					// but we can reuse the deployment info - this is the only object from which we can remove
					// servlets
					deploymentInfo.getServlets().remove(model.getName());
					if (model.isResourceServlet() && Arrays.asList(model.getUrlPatterns()).contains("/")) {
						// we need to replace "/" servlet
						PaxWebServletInfo defaultServletInfo = new PaxWebServletInfo("default", default404Servlet, true);
						deploymentInfo.addServlet(defaultServletInfo.addMapping("/"));
					}

					// are there any error page declarations in the model?
					// we'll be redeploying the deployment info, so we don't have to change error pages
					// in existing (the one being undeployed) deployment
					ErrorPageModel epm = model.getErrorPageModel();
					if (epm != null) {
						String location = epm.getLocation();
						FlexibleErrorPages currentState = errorPages.computeIfAbsent(contextPath, cp -> new FlexibleErrorPages());
						for (String ex : epm.getExceptionClassNames()) {
							try {
								ClassLoader cl = model.getRegisteringBundle().adapt(BundleWiring.class).getClassLoader();
								@SuppressWarnings("unchecked")
								Class<Throwable> t = (Class<Throwable>) cl.loadClass(ex);
								currentState.getExceptionMappings().remove(t, location);
							} catch (ClassNotFoundException e) {
								LOG.warn("Can't load exception class {}: {}", ex, e.getMessage(), e);
							}
						}
						for (int code : epm.getErrorCodes()) {
							currentState.getErrorCodeLocations().remove(code, location);
						}
						if (epm.isXx4()) {
							for (int c = 400; c < 500; c++) {
								currentState.getErrorCodeLocations().remove(c, location);
							}
						}
						if (epm.isXx5()) {
							for (int c = 500; c < 600; c++) {
								currentState.getErrorCodeLocations().remove(c, location);
							}
						}

						// keep only remaining, not removed pages
						deploymentInfo.getErrorPages().clear();
						currentState.getErrorCodeLocations()
								.forEach((c, l) -> deploymentInfo.addErrorPage(new ErrorPage(location, c)));
						currentState.getExceptionMappings()
								.forEach((e, l) -> deploymentInfo.addErrorPage(new ErrorPage(location, e)));
					}

					ensureServletContextStarted(contextPath);
				});
			}
		}
	}

	@Override
	public void visit(FilterModelChange change) {
		// no op here - will be handled with FilterStateChange
	}

	@Override
	public void visit(FilterStateChange change) {
		Map<String, TreeMap<FilterModel, List<OsgiContextModel>>> contextFilters = change.getContextFilters();

		for (Map.Entry<String, TreeMap<FilterModel, List<OsgiContextModel>>> entry : contextFilters.entrySet()) {
			String contextPath = entry.getKey();
			Map<FilterModel, List<OsgiContextModel>> filtersMap = entry.getValue();
			Set<FilterModel> filters = filtersMap.keySet();

			ensureServletContextStarted(contextPath);

			LOG.info("Changing filter configuration for context {}", contextPath);

			// take existing deployment manager and the deployment info from its deployment
			DeploymentManager manager = servletContainer.getDeploymentByPath(contextPath);
			DeploymentManager.State state = manager.getState();
			DeploymentInfo deploymentInfo = manager.getDeployment().getDeploymentInfo();

			boolean quick = canQuicklyAddFilter(deploymentInfo, filters);
			quick &= filtersMap.values().stream().noneMatch(Objects::nonNull);

			if (!quick) {
				// let's immediately show that given context is no longer mapped
				pathHandler.removePrefixPath(contextPath);

				try {
					// manager needs to stop the deployment and get rid of it, because we
					// can't replace a deployment info within deployment manager
					LOG.trace("Stopping and undelopying the deployment for {}", contextPath);
					manager.stop();
					manager.undeploy();
					state = manager.getState();
				} catch (ServletException e) {
					throw new RuntimeException("Problem stopping the deployment of context " + contextPath
							+ ": " + e.getMessage(), e);
				}

				// remove all existing filters
				deploymentInfo = undertowFactory.clearFilters(deploymentInfo);
				deploymentInfos.put(contextPath, deploymentInfo);
			}

			// this time we don't have to care about filters which are not "changed" or which should
			// be destroyed, because unlike in Jetty and Tomcat, in Undertow we simply destroy entire
			// context (redeploy it)

			List<FilterInfo> added = new LinkedList<>();

			int pos = 1;
			for (FilterModel model : filters) {
				// we need highest ranked OsgiContextModel for current context path - chosen not among all
				// associated OsgiContextModels, but among OsgiContextModels of the FilterModel
				OsgiContextModel highestRankedModel = null;
				// remember, this contextModels list is properly sorted - and it comes either from model or
				// (if configured) from associated list of models which are being changed in the model
				List<OsgiContextModel> contextModels = filtersMap.get(model) != null
						? filtersMap.get(model) : model.getContextModels();
				for (OsgiContextModel ocm : contextModels) {
					if (ocm.getContextPath().equals(contextPath)) {
						highestRankedModel = ocm;
						break;
					}
				}
				if (highestRankedModel == null) {
					LOG.warn("(dev) Can't find proper OsgiContextModel for the filter. Falling back to "
							+ "highest ranked OsgiContextModel for given ServletContextModel");
					highestRankedModel = osgiContextModels.get(contextPath).iterator().next();
				}
				OsgiServletContext context = osgiServletContexts.get(highestRankedModel);

				// filter definition
				FilterInfo info = new PaxWebFilterInfo(model, context);

				if (quick) {
					// we can operate on existing ManagedFilters object from current deployment
					// if the deployment is not yet started, it's like normal, full redeployment
					if (state == DeploymentManager.State.STARTED) {
						ManagedFilters currentFilters = manager.getDeployment().getFilters();
						ManagedFilter managedFilter = currentFilters.getManagedFilter(info.getName());
						if (managedFilter == null) {
							// add only if not already there
							currentFilters.addFilter(info);
							added.add(info);
						} else {
							FilterInfo currentFilter = managedFilter.getFilterInfo();
							if (!(currentFilter instanceof PaxWebFilterInfo
									&& ((PaxWebFilterInfo) currentFilter).getFilterModel().equals(model))) {
								// add only if no filter for given FilterModel exists
								currentFilters.addFilter(info);
								added.add(info);
							}
						}
					}
				}
				if (!quick || added.size() > 0 || state != DeploymentManager.State.STARTED) {
					deploymentInfo.addFilter(info);

					String filterName = model.getName();

					// filter mapping
					for (String type : model.getDispatcherTypes()) {
						DispatcherType dt = DispatcherType.valueOf(type);

						if (model.getRegexMapping() != null && model.getRegexMapping().length > 0) {
							// TODO: handle regexp filter mapping
							deploymentInfo.addFilterUrlMapping(filterName, "/*", dt);
						} else if (model.getUrlPatterns() != null) {
							for (String pattern : model.getUrlPatterns()) {
								deploymentInfo.addFilterUrlMapping(filterName, pattern, dt);
							}
						}
						if (model.getServletNames() != null) {
							for (String name : model.getServletNames()) {
								deploymentInfo.addFilterServletNameMapping(filterName, name, dt);
							}
						}
					}
				}
			}

			if (added.size() > 0) {
				// just start newly added filters
				for (ManagedFilter filter : manager.getDeployment().getFilters().getFilters().values()) {
					try {
						new ContextClassLoaderSetupAction(deploymentInfo.getClassLoader()).create((exchange, context) -> {
							filter.createFilter();
							return null;
						}).call(null, null);
					} catch (Exception e) {
						throw new IllegalStateException("Can't start filter " + filter + ": " + e.getMessage(), e);
					}
				}
			} else if (!quick || state != DeploymentManager.State.STARTED) {
				if (state == DeploymentManager.State.STARTED) {
					LOG.trace("Redeploying {}", contextPath);
				}
				ensureServletContextStarted(contextPath);
			}
		}
	}

	@Override
	public void visit(EventListenerModelChange change) {
		if (change.getKind() == OpCode.ADD) {
			EventListenerModel eventListenerModel = change.getEventListenerModel();
			List<OsgiContextModel> contextModels = change.getContextModels();
			Set<String> done = new HashSet<>();
			contextModels.forEach((context) -> {
				String contextPath = context.getContextPath();
				DeploymentManager manager = getDeploymentManager(contextPath);
				DeploymentInfo deploymentInfo = manager == null ? deploymentInfos.get(context.getContextPath())
						: manager.getDeployment().getDeploymentInfo();

				EventListener eventListener = eventListenerModel.resolveEventListener();
				if (eventListener instanceof ServletContextAttributeListener) {
					// add it to accessible list to fire per-OsgiContext attribute changes
					OsgiServletContext c = osgiServletContexts.get(context);
					c.addServletContextAttributeListener((ServletContextAttributeListener) eventListener);
				}
				if (!done.add(contextPath)) {
					return;
				}

				// add the listener to real context - even ServletContextAttributeListener (but only once - even
				// if there are many OsgiServletContexts per ServletContext)
				ListenerInfo info = new ListenerInfo(eventListener.getClass(), new ImmediateInstanceFactory<>(eventListener));

				deploymentInfo.addListener(info);
				if (manager != null) {
					manager.getDeployment().getApplicationListeners().addListener(new ManagedListener(info, true));
				}
			});
		}
		if (change.getKind() == OpCode.DELETE) {
			List<EventListenerModel> eventListenerModels = change.getEventListenerModels();
			for (EventListenerModel eventListenerModel : eventListenerModels) {
				List<OsgiContextModel> contextModels = eventListenerModel.getContextModels();
				contextModels.forEach((context) -> {
					String contextPath = context.getContextPath();
					DeploymentManager manager = servletContainer.getDeploymentByPath(contextPath);
					DeploymentInfo deploymentInfo = manager.getDeployment().getDeploymentInfo();

					EventListener eventListener = eventListenerModel.resolveEventListener();
					if (eventListener instanceof ServletContextAttributeListener) {
						// remove it from per-OsgiContext list
						OsgiServletContext c = osgiServletContexts.get(context);
						c.removeServletContextAttributeListener((ServletContextAttributeListener) eventListener);
					}
					// remove the listener from real context - even ServletContextAttributeListener
					// unfortunately, one does not simply remove EventListener from existing context in Undertow

					// let's immediately show that given context is no longer mapped
					pathHandler.removePrefixPath(contextPath);

					try {
						manager.stop();
						manager.undeploy();
						// swap the deployment info, which will later be used to start the context
						deploymentInfos.put(contextPath, deploymentInfo);
					} catch (ServletException e) {
						throw new RuntimeException("Problem stopping the deployment of context " + contextPath
								+ ": " + e.getMessage(), e);
					}

					deploymentInfo.getListeners().removeIf(li -> {
						try {
							return li.getInstanceFactory() instanceof ImmediateInstanceFactory
									&& ((ImmediateInstanceFactory<?>) li.getInstanceFactory()).createInstance().getInstance() == eventListener;
						} catch (InstantiationException ignored) {
							return false;
						}
					});
					eventListenerModel.ungetEventListener(eventListener);

					ensureServletContextStarted(contextPath);
				});
			}
		}
	}

	@Override
	public void visit(WelcomeFileModelChange change) {
		WelcomeFileModel model = change.getWelcomeFileModel();

		OpCode op = change.getKind();
		if (op == OpCode.ADD || op == OpCode.DELETE) {
			List<OsgiContextModel> contextModels = op == OpCode.ADD ? change.getContextModels()
					: model.getContextModels();
			contextModels.forEach((context) -> {
				OsgiServletContext osgiServletContext = osgiServletContexts.get(context);

				Deployment deployment = getDeployment(osgiServletContext.getContextPath());

				Set<String> currentWelcomeFiles = osgiServletContext.getWelcomeFiles() == null
						? new LinkedHashSet<>()
						: new LinkedHashSet<>(Arrays.asList(osgiServletContext.getWelcomeFiles()));

				if (op == OpCode.ADD) {
					currentWelcomeFiles.addAll(Arrays.asList(model.getWelcomeFiles()));
				} else {
					if (model.getWelcomeFiles().length == 0) {
						// special case of "remove all welcome files"
						currentWelcomeFiles.clear();
					} else {
						currentWelcomeFiles.removeAll(Arrays.asList(model.getWelcomeFiles()));
					}
				}

				// set welcome files at OsgiServletContext level. NOT at ServletContextHandler level
				String[] newWelcomeFiles = currentWelcomeFiles.toArray(new String[0]);
				osgiServletContext.setWelcomeFiles(newWelcomeFiles);
				osgiServletContext.setWelcomeFilesRedirect(model.isRedirect());

				LOG.info("Reconfiguration of welcome files for all resource servlet in context \"{}\"", context);

				// reconfigure welcome files in resource servlets
				if (deployment != null) {
					for (ServletHandler sh : deployment.getServlets().getServletHandlers().values()) {
						ManagedServlet ms = sh.getManagedServlet();
						if (ms != null && ms.getServletInfo() != null &&
								ms.getServletInfo() instanceof PaxWebServletInfo) {
							PaxWebServletInfo info = (PaxWebServletInfo) ms.getServletInfo();
							if (info.getServletModel() != null && info.getServletModel().isResourceServlet()
									&& context == info.getOsgiContextModel()) {
								if (ms.isStarted()) {
									try {
										Servlet servlet = ms.getServlet().getInstance();
										if (servlet instanceof UndertowResourceServlet) {
											((UndertowResourceServlet) servlet).setWelcomeFiles(newWelcomeFiles);
											((UndertowResourceServlet) servlet).setWelcomeFilesRedirect(model.isRedirect());
										} else if (servlet instanceof OsgiInitializedServlet) {
											((UndertowResourceServlet) ((OsgiInitializedServlet) servlet).getDelegate()).setWelcomeFiles(newWelcomeFiles);
											((UndertowResourceServlet) ((OsgiInitializedServlet) servlet).getDelegate()).setWelcomeFilesRedirect(model.isRedirect());
										}
									} catch (ServletException e) {
										LOG.warn("Problem reconfiguring welcome files in servlet {}", ms, e);
									}
								} else {
									// no need to set it, because servlet is not yet initialized
									LOG.debug("Welcome files will be set in {} when init() is called", ms);
								}
							}
						}
					}
				}
			});
		}
	}

	@Override
	public void visit(ErrorPageModelChange change) {
		// no op here
	}

	@Override
	public void visit(ErrorPageStateChange change) {
		Map<String, TreeMap<ErrorPageModel, List<OsgiContextModel>>> contextErrorPages = change.getContextErrorPages();

		for (Map.Entry<String, TreeMap<ErrorPageModel, List<OsgiContextModel>>> entry : contextErrorPages.entrySet()) {
			String contextPath = entry.getKey();
			TreeMap<ErrorPageModel, List<OsgiContextModel>> errorPageModelsMap = entry.getValue();
			Set<ErrorPageModel> errorPageModels = errorPageModelsMap.keySet();

			LOG.info("Changing error page configuration for context {}", contextPath);

			// take existing deployment manager and the deployment info from its deployment
			Deployment deployment = getDeployment(contextPath);
			DeploymentInfo deploymentInfo = deployment == null ? deploymentInfos.get(contextPath)
					: deployment.getDeploymentInfo();

			deploymentInfo.getErrorPages().clear();

			Map<Integer, String> errorCodeLocations = new HashMap<>();
			Map<Class<? extends Throwable>, String> exceptionMappings = new HashMap<>();
			FlexibleErrorPages pages = new FlexibleErrorPages(errorCodeLocations, exceptionMappings, null);
			// after adding ErrorPage(s) to "deployment info" they'll be changed into non-flexible ErrorPages
			// at deployment time, but at least we can keep it cached at the wrapper level
			errorPages.put(contextPath, pages);

			for (ErrorPageModel model : errorPageModels) {
				String location = model.getLocation();
				for (String ex : model.getExceptionClassNames()) {
					try {
						ClassLoader cl = model.getRegisteringBundle().adapt(BundleWiring.class).getClassLoader();
						@SuppressWarnings("unchecked")
						Class<Throwable> t = (Class<Throwable>) cl.loadClass(ex);
						exceptionMappings.put(t, location);
						deploymentInfo.addErrorPage(new ErrorPage(location, t));
					} catch (ClassNotFoundException e) {
						LOG.warn("Can't load exception class {}: {}", ex, e.getMessage(), e);
					}
				}
				for (int code : model.getErrorCodes()) {
					errorCodeLocations.put(code, location);
					deploymentInfo.addErrorPage(new ErrorPage(location, code));
				}
				if (model.isXx4()) {
					for (int c = 400; c < 500; c++) {
						errorCodeLocations.put(c, location);
						deploymentInfo.addErrorPage(new ErrorPage(location, c));
					}
				}
				if (model.isXx5()) {
					for (int c = 500; c < 600; c++) {
						errorCodeLocations.put(c, location);
						deploymentInfo.addErrorPage(new ErrorPage(location, c));
					}
				}
			}

			if (deployment instanceof DeploymentImpl) {
				((DeploymentImpl) deployment).setErrorPages(pages);
			}
		}
	}

	@Override
	public void visit(ContainerInitializerModelChange change) {
		if (change.getKind() == OpCode.ADD) {
			ContainerInitializerModel model = change.getContainerInitializerModel();
			List<OsgiContextModel> contextModels = change.getContextModels();
			contextModels.forEach((context) -> {
				String path = context.getContextPath();
				DeploymentManager manager = getDeploymentManager(path);
				if (manager != null) {
					LOG.warn("ServletContainerInitializer {} can't be added, as the context \"{}\" is already started",
							model.getContainerInitializer(), path);
				} else {
					// even if there's org.apache.catalina.core.StandardContext.addServletContainerInitializer(),
					// there's no "remove" equivalent and also we want to be able to pass correct implementation
					// of ServletContext there
					ServletContainerInitializer initializer = model.getContainerInitializer();

					// because of the quirks related to Undertow's deploymentInfo vs. deployment (and their
					// clones), we'll prepare specia SCIInfo here
					OsgiServletContext osgiServletContext = osgiServletContexts.get(context);
					DynamicRegistrations registrations = this.dynamicRegistrations.get(path);
					OsgiDynamicServletContext dynamicContext = new OsgiDynamicServletContext(osgiServletContexts.get(context), registrations);
					OsgiServletContainerInitializerInfo info = new OsgiServletContainerInitializerInfo(model, dynamicContext);
					initializers.get(path).put(System.identityHashCode(initializer), info);
				}
			});
		}
		if (change.getKind() == OpCode.DELETE) {
			List<ContainerInitializerModel> models = change.getContainerInitializerModels();
			for (ContainerInitializerModel model : models) {
				List<OsgiContextModel> contextModels = model.getContextModels();
				contextModels.forEach((context) -> {
					String path = context.getContextPath();
					ServletContainerInitializer initializer = model.getContainerInitializer();
					LinkedHashMap<Integer, OsgiServletContainerInitializerInfo> initializers = this.initializers.get(path);
					if (initializers != null) {
						// just remove the ServletContainerInitializerInfo without _cleaning_ it, because it was
						// _cleaned_ just after io.undertow.servlet.core.DeploymentManagerImpl.deploy() called
						// javax.servlet.ServletContainerInitializer.onStartup()
						initializers.remove(System.identityHashCode(initializer));
					}
				});
			}
		}
	}

	/**
	 * <p>This method is always (should be) called withing the "configuration thread" of Pax Web Runtime, because
	 * it's called in visit() methods for servlets (including resources) and filters, so we can safely access
	 * {@link org.ops4j.pax.web.service.spi.model.ServerModel}.</p>
	 * @param contextPath
	 */
	private void ensureServletContextStarted(final String contextPath) {
		DeploymentManager manager = getDeploymentManager(contextPath);

		if (manager != null) {
			return;
		}
		try {
			OsgiContextModel highestRanked = securityHandlers.get(contextPath).getDefaultOsgiContextModel();
			OsgiServletContext highestRankedContext = osgiServletContexts.get(highestRanked);

			LOG.info("Starting Undertow context \"{}\" with default Osgi Context {}",
					(contextPath.equals("") ? "/" : contextPath), highestRanked);

			// take previously created deployment (possibly with listeners and other "passive" configuration)
			DeploymentInfo deployment = deploymentInfos.get(contextPath);

			// SCIs require working ServletContext inside OsgiServletContext, but Undertow's ServletContext
			// is created only later
			deployment.getServletExtensions().removeIf(e -> e instanceof ContextLinkingServletExtension);
			deployment.addServletExtension(new ContextLinkingServletExtension(contextPath));

			// first thing - only NOW we can set ServletContext's class loader! It affects many things, including
			// the TCCL used for example by javax.el.ExpressionFactory.newInstance()
			if (highestRankedContext.getClassLoader() != null) {
				deployment.setClassLoader(highestRankedContext.getClassLoader());
			}

			// when starting (or, which is possible only with Pax Web, not Undertow itself - restarting), we'll
			// clear all the SCIs in the deploymentInfo and add new ones (because some of them may have been removed)
			deployment.getServletContainerInitializers().clear();

			// add all configured initializers, but as special wrappers
			Collection<OsgiServletContainerInitializerInfo> initializers = new LinkedList<>(this.initializers.get(contextPath).values());
			// Initially I thought we should take only these SCIs, which are associated with highest ranked OCM,
			// but it turned out that just as we take servlets registered to different OsgiContextModels, but
			// the same ServletContextModel, we have to do the same with SCIs.
			// otherwise, by default (with HttpService scenario), SCIs from the OsgiContextModel related to
			// pax-web-extender-whiteboard would be taken (probably 0), simply because this bundle is usually
			// the first that grabs an instance of bundle-scoped HttpService
			// so please do not uncomment and keep for educational purposes!
//			initializers.removeIf(info -> !info.getModel().getContextModels().contains(highestRanked));

			for (OsgiServletContainerInitializerInfo info : initializers) {
				// with no Whiteboard support, we can have only one OsgiContextModel per ContainerInitializerModel
				// but we'll still act as if there could be many
				deployment.addServletContainerInitializers(info);
			}
			if (initializers.size() > 0) {
				// and finally add the registering initializer
				RegisteringContainerInitializer registeringSCI = new RegisteringContainerInitializer(this.dynamicRegistrations.get(contextPath));
				deployment.addServletContainerInitializers(new OsgiServletContainerInitializerInfo(registeringSCI));
			}

			// alter session configuration
			SessionConfigurationModel sc = highestRanked.getSessionConfiguration();
			if (sc != null) {
				if (sc.getSessionTimeout() != null) {
					deployment.setDefaultSessionTimeout(sc.getSessionTimeout() * 60);
				}
				SessionCookieConfig scc = sc.getSessionCookieConfig();
				ServletSessionConfig ssc = deployment.getServletSessionConfig();
				if (scc != null) {
					if (ssc == null) {
						ssc = new ServletSessionConfig();
						deployment.setServletSessionConfig(ssc);
					}
					if (scc.getName() != null) {
						ssc.setName(scc.getName());
					}
					if (scc.getDomain() != null) {
						ssc.setDomain(scc.getDomain());
					}
					if (scc.getPath() != null) {
						ssc.setPath(scc.getPath());
					}
					ssc.setMaxAge(scc.getMaxAge());
					ssc.setHttpOnly(scc.isHttpOnly());
					ssc.setSecure(scc.isSecure());
					ssc.setComment(scc.getComment());
				}
			}

			manager = servletContainer.addDeployment(deployment);

			// here's where Undertow-specific instance of javax.servlet.ServletContext is created
			manager.deploy();

			HttpHandler handler = manager.start();

			// actual registration of "context" in Undertow's path handler. There are no servlets,
			// filters and anything yet
			pathHandler.addPrefixPath(contextPath, handler);
		} catch (ServletException e) {
			throw new IllegalStateException("Can't start Undertow context "
					+ contextPath + ": " + e.getMessage(), e);
		}
	}

	/**
	 * If the servlet context is already started ({@link DeploymentManager#deploy()} was called), return
	 * it from current {@link Deployment}.
	 * @param contextPath
	 * @return
	 */
	private ServletContext getRealServletContext(String contextPath) {
		Deployment deployment = getDeployment(contextPath);
		return deployment == null ? null : deployment.getServletContext();
	}

	/**
	 * Get {@link Deployment} associated with given context path - but only if related {@link DeploymentManager}
	 * is started.
	 * @param contextPath
	 * @return
	 */
	private Deployment getDeployment(String contextPath) {
		DeploymentManager deploymentManager = getDeploymentManager(contextPath);

		if (deploymentManager == null || deploymentManager.getDeployment() == null) {
			return null;
		}

		return deploymentManager.getDeployment();
	}

	/**
	 * Get {@link DeploymentManager} for given context path - if available.
	 * @param contextPath
	 * @return
	 */
	private DeploymentManager getDeploymentManager(String contextPath) {
		String path = contextPath.equals("") ? "/" : contextPath;
		DeploymentManager deploymentManager = servletContainer.getDeploymentByPath(path);

		if (deploymentManager == null || deploymentManager.getDeployment() == null) {
			return null;
		}
		if (!deploymentManager.getDeployment().getDeploymentInfo().getContextPath().equals(path)) {
			// io.undertow.servlet.api.ServletContainer.getDeploymentByPath "traverses up" the request path...
			return null;
		}

		return deploymentManager;
	}

	/**
	 * Check if new set of filters contains only existing filters and possibly some new. When new filters come
	 * in different order or there are removed filters, we'll have to recreate entire context...
	 *
	 * @param deploymentInfo
	 * @param filters
	 * @return
	 */
	private boolean canQuicklyAddFilter(DeploymentInfo deploymentInfo, Set<FilterModel> filters) {
		FilterInfo[] existingFilters = deploymentInfo.getFilters().values().toArray(new FilterInfo[0]);
		FilterModel[] newFilters = filters.toArray(new FilterModel[0]);
		int pos = 0;
		boolean quick = existingFilters.length <= newFilters.length;
		while (quick) {
			if (pos >= existingFilters.length) {
				break;
			}
			if (!(existingFilters[pos] instanceof PaxWebFilterInfo
					&& ((PaxWebFilterInfo) existingFilters[pos]).getFilterModel().equals(newFilters[pos]))) {
				quick = false;
				break;
			}
			pos++;
		}

		return quick;
	}

//	/**
//	 * Loads additional properties and configure {@link UndertowServerController#identityManager}
//	 * @param undertowResource
//	 */
//	private void configureIdentityManager(URL undertowResource) {
//		try {
//			Properties props = new Properties();
//			try (InputStream is = undertowResource.openStream()) {
//				props.load(is);
//			}
//			Map<String, String> config = new LinkedHashMap<>();
//			for (Map.Entry<Object, Object> entry : props.entrySet()) {
//				config.put(entry.getKey().toString(), entry.getValue().toString());
//			}
//			identityManager = (IdentityManager)createConfigurationObject(config, "identityManager");
//
////			String listeners = config.get("listeners");
////			if (listeners != null) {
////				String[] names = listeners.split("(, )+");
////				for (String name : names) {
////					String type = config.get("listeners." + name + ".type");
////					String address = config.get("listeners." + name + ".address");
////					String port = config.get("listeners." + name + ".port");
////					if ("http".equals(type)) {
////						builder.addHttpListener(Integer.parseInt(port), address);
////					}
////				}
////			}
//		} catch (Exception e) {
//			LOG.error("Exception while starting Undertow", e);
//			throw new RuntimeException("Exception while starting Undertow", e);
//		}
//	}
//
//	private Context findOrCreateContext(final OsgiContextModel contextModel) {
//		NullArgumentException.validateNotNull(contextModel, "contextModel");
//		synchronized (contextMap) {
//			if (contextMap.containsKey(contextModel.getHttpContext())) {
//				return contextMap.get(contextModel.getHttpContext());
//			}
//			Context newCtx = new Context(this, path, contextModel);
//			newCtx.setConfiguration(configuration);
//			newCtx.setDefaultSessionTimeoutInMinutes(defaultSessionTimeoutInMinutes);
//			newCtx.setSessionPersistenceManager(sessionPersistenceManager);
//			contextMap.put(contextModel.getHttpContext(), newCtx);
//			final Servlet servlet = createResourceServlet(contextModel, "/", "default");
//			final ResourceModel model = new ResourceModel(contextModel, servlet, "/", "default");
//			try {
//				newCtx.addServlet(model);
//			} catch (ServletException e) {
//				LOG.warn(e.getMessage(), e);
//			}
//			return newCtx;
//		}
//	}
//
//	@Override
//	public void addSecurityConstraintMapping(SecurityConstraintMappingModel model) {
//		assertNotState(State.Unconfigured);
//		try {
//			final Context context = findOrCreateContext(model.getContextModel());
//			context.addSecurityConstraintMapping(model);
//		} catch (ServletException e) {
//			throw new RuntimeException("Unable to add welcome files", e);
//		}
//	}
//
//	@Override
//	public void removeSecurityConstraintMapping(SecurityConstraintMappingModel model) {
//		assertNotState(State.Unconfigured);
//		try {
//			final Context context = findOrCreateContext(model.getContextModel());
//			context.removeSecurityConstraintMapping(model);
//		} catch (ServletException e) {
//			throw new RuntimeException("Unable to add welcome files", e);
//		}
//	}
//
//	@Override
//	public void addContainerInitializerModel(ContainerInitializerModel model) {
//		assertNotState(State.Unconfigured);
//		try {
//			final Context context = findOrCreateContext(model.getContextModel());
//			context.addContainerInitializerModel(model);
//		} catch (ServletException e) {
//			throw new RuntimeException("Unable to add welcome files", e);
//		}
//	}

	// see org.wildfly.extension.undertow.deployment.UndertowDeploymentInfoService#getConfidentialPortManager
	private class SimpleConfidentialPortManager implements ConfidentialPortManager {

		@Override
		public int getConfidentialPort(HttpServerExchange exchange) {
			int port = exchange.getConnection().getLocalAddress(InetSocketAddress.class).getPort();
			if (port < 0) {
				LOG.debug("Confidential port not defined for port {}", port);
			}
			return UndertowServerWrapper.this.securePortMapping.get(port);
		}
	}

	/**
	 * An internal {@link ServletExtension} that propagates "container servlet context" to all related
	 * {@link OsgiServletContext}s.
	 */
	private class ContextLinkingServletExtension implements ServletExtension {

		private final String contextPath;

		ContextLinkingServletExtension(String contextPath) {
			this.contextPath = contextPath;
		}

		@Override
		public void handleDeployment(DeploymentInfo deploymentInfo, ServletContext servletContext) {
			osgiServletContexts.forEach((ocm, osc) -> {
				if (ocm.getContextPath().equals(contextPath)) {
					osc.setContainerServletContext(servletContext);
				}
			});
		}
	}

}
