/*
 * Copyright 2007 Alin Dreghiciu.
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
package org.ops4j.pax.web.service.jetty.internal;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.SessionCookieConfig;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.RequestLogWriter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.FileSessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.ops4j.pax.web.annotations.Review;
import org.ops4j.pax.web.service.jetty.internal.web.JettyResourceServlet;
import org.ops4j.pax.web.service.spi.config.Configuration;
import org.ops4j.pax.web.service.spi.config.LogConfiguration;
import org.ops4j.pax.web.service.spi.config.SessionConfiguration;
import org.ops4j.pax.web.service.spi.model.OsgiContextModel;
import org.ops4j.pax.web.service.spi.model.ServletContextModel;
import org.ops4j.pax.web.service.spi.model.elements.ContainerInitializerModel;
import org.ops4j.pax.web.service.spi.model.elements.ErrorPageModel;
import org.ops4j.pax.web.service.spi.model.elements.EventListenerModel;
import org.ops4j.pax.web.service.spi.model.elements.FilterModel;
import org.ops4j.pax.web.service.spi.model.elements.SecurityConstraintMappingModel;
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
import org.ops4j.pax.web.service.spi.servlet.SCIWrapper;
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
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleWiring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A <em>wrapper</em> or <em>holder</em> of actual Jetty server. This class perform two kinds of tasks:<ul>
 *     <li>controls the state of Jetty by configuring, starting and stopping it</li>
 *     <li>translates model changes into registration of Jetty-specific contexts, holders and handlers</li>
 * </ul></p>
 *
 * <p>This wrapper implements {@link BatchVisitor} to process batch operations related to model changes.</p>
 */
class JettyServerWrapper implements BatchVisitor {

	private static final Logger LOG = LoggerFactory.getLogger(JettyServerWrapper.class);

	/** An <em>entry</em> to OSGi runtime to lookup other bundles if needed (to get their ClassLoader) */
	private final Bundle paxWebJettyBundle;
	/** Outside of OSGi, let's use passed ClassLoader */
	private final ClassLoader classLoader;

	/** Actual instance of {@link org.eclipse.jetty.server.Server} */
	private Server server;

	/** Server's pool which is added as UNMANAGED */
	private QueuedThreadPool qtp;

	/** If JMX support is enabled, this will be the Jetty bean for JMX management */
	private MBeanContainer mbeanContainer;

	/** Main handler collection for Jetty server */
	private ContextHandlerCollection mainHandler;

	/** If {@code jetty*.xml} files create instances of {@link HttpConfiguration}, these are collected here. */
	private final Map<String, HttpConfiguration> httpConfigs = new LinkedHashMap<>();

	private final JettyFactory jettyFactory;

	/** Single map of context path to {@link ServletContextHandler} for fast access */
	private final Map<String, PaxWebServletContextHandler> contextHandlers = new HashMap<>();

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
	 * Jetty somehow doesn't manage {@link ServletContainerInitializer}s so nicely (or I didn't find a way to do
	 * it properly), so we keep an ordered list of initializers per physical context here. Each initializer is
	 * associated with single {@link OsgiContextModel}.
	 */
	private final Map<String, LinkedHashMap<Integer, SCIWrapper>> initializers = new HashMap<>();

	/**
	 * Keep dynamic configuration and use it during startup only.
	 */
	private final Map<String, DynamicRegistrations> dynamicRegistrations = new HashMap<>();

	/**
	 * Global {@link Configuration} passed from pax-web-runtime through
	 * {@link org.ops4j.pax.web.service.spi.ServerController}
	 */
	private final Configuration configuration;

	/** Servlet to use when no servlet is mapped - to ensure that preprocessors and filters are run correctly. */
	private final Default404Servlet default404Servlet = new Default404Servlet();

	private SessionCookieConfig defaultSessionCookieConfig;

	JettyServerWrapper(Configuration config, JettyFactory jettyFactory,
			Bundle paxWebJettyBundle, ClassLoader classLoader) {
		this.configuration = config;
		this.jettyFactory = jettyFactory;
		this.paxWebJettyBundle = paxWebJettyBundle;
		this.classLoader = classLoader;
	}

	// --- lifecycle and configuration methods

	/**
	 * One-time configuration of Jetty
	 */
	public void configure() throws Exception {
		// for now, we have nothing. We can do many things using external jetty-*.xml files, but the creation
		// of Server itself should be done manually here.
		LOG.info("Creating Jetty server instance using configuration properties.");
		createServer();

		// No external configuration should replace our "Server" object
		applyJettyConfiguration();

		// If external configuration added some connectors, we have to ensure they match declaration from
		// PID config: org.osgi.service.http.enabled and org.osgi.service.http.secure.enabled
		verifyConnectorConfiguration();

		// PAXWEB-1084 - start QTP before starting the server. When QTP is added as a bean to
		// org.eclipse.jetty.server.Server, it'll become UNMANAGED bean, so we can and have to manage its
		// lifecycle manually, which is exactly what we want.
		// see org.eclipse.jetty.util.component.ContainerLifeCycle for details
		qtp = (QueuedThreadPool) server.getThreadPool();
		LOG.info("Eagerly starting Jetty thread pool {}", qtp);
		qtp.start();

		if (server.getErrorHandler() == null) {
			server.setErrorHandler(new ErrorHandler());
		}

		// Configure NCSA RequestLogHandler if needed
		if (configuration.logging().isLogNCSAFormatEnabled() && server.getRequestLog() == null) {
			configureRequestLog();
		}

		// default session configuration is prepared, but not set in the server instance. It can be set
		// only after first context is created
		this.defaultSessionCookieConfig = configuration.session().getDefaultSessionCookieConfig();

		// global session persistence configuration
		if (configuration.session().getSessionStoreDirectory() != null) {
			FileSessionDataStoreFactory dsFactory = new FileSessionDataStoreFactory();
			dsFactory.setDeleteUnrestorableFiles(true);
			dsFactory.setStoreDir(configuration.session().getSessionStoreDirectory());
			server.addBean(dsFactory);
		}

		mbeanContainer = jettyFactory.enableJmxIfPossible(server);

		// most important part - a handler.
		// TODO: my initial idea was to have this hierarchy:
		//  server:
		//   - handler collection
		//      - handler collection to store custom handlers with @Priority > 0
		//      - context handler collection to store context handlers
		//      - handler collection to store custom handlers with @Priority < 0
		//
		// for now, let's have it like before Pax Web 8
		this.mainHandler = new ContextHandlerCollection();
		server.setHandler(this.mainHandler);
	}

	/**
	 * <p>Create Jetty server using provided {@link Configuration}. The only <em>bean</em> inside Jetty server
	 * will be {@link QueuedThreadPool}, which can be reconfigured later (using XMLs).</p>
	 *
	 * @return
	 */
	private void createServer() throws Exception {
		QueuedThreadPool qtp = jettyFactory.createThreadPool(configuration);

		// actual org.eclipse.jetty.server.Server
		this.server = new Server(qtp);
		this.server.setSessionIdManager(new DefaultSessionIdManager(this.server));
	}

	/**
	 * <p>This method parses existing {@code jetty*.xml} files and should <strong>not</strong> create an instance
	 * of {@link Server}. Existing {@link Server} is passed as server object ID.</p>
	 *
	 * <p>Besides the {@link Server}, XML configuration may alter any aspect of Jetty server. Additionally, if
	 * XML configurations create instances of {@link org.eclipse.jetty.server.HttpConfiguration}, these
	 * are collected and remembered for future use (if there's a need to create a {@link ServerConnector}).</p>
	 */
	private void applyJettyConfiguration() throws Exception {
		File[] locations = configuration.server().getConfigurationFiles();
		if (locations.length == 0) {
			LOG.info("No external Jetty configuration files specified. Default/PID configuration will be used.");
			// no return, we'll handle the below mentioned TCCL leaks
		} else {
			LOG.info("Processing Jetty configuration from files: {}", Arrays.asList(locations));
		}

		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try {
			// PAXWEB-1112 - classloader leak prevention:
			// first find XmlConfiguration class' CL to set it as TCCL when performing static
			// initialization of XmlConfiguration in order to not leak
			// org.eclipse.jetty.xml.XmlConfiguration.__factoryLoader.loader
			ClassLoader jettyXmlCl = null;
			if (paxWebJettyBundle != null) {
				for (Bundle b : paxWebJettyBundle.getBundleContext().getBundles()) {
					if ("org.eclipse.jetty.xml".equals(b.getSymbolicName())) {
						jettyXmlCl = b.adapt(BundleWiring.class).getClassLoader();
						break;
					}
				}
			}

			// TCCL to perform static initialization of XmlConfiguration with proper TCCL
			Thread.currentThread().setContextClassLoader(jettyXmlCl);
			XmlConfiguration configuration
					= new XmlConfiguration(Resource.newResource(getClass().getResource("/jetty-empty.xml")));

			// When parsing, TCCL will be set to CL of pax-web-jetty bundle
			Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

			XmlConfiguration previous = null;
			Map<String, Object> objects = new LinkedHashMap<>();

			// the only existing objects. Names as found in JETTY_HOME/etc/jetty*.xml files
			objects.put("Server", server);
			objects.put("threadPool", server.getThreadPool());

			for (File location : locations) {
				LOG.debug("Parsing {}", location);
				configuration = new XmlConfiguration(Resource.newResource(location));

				// add objects created in previous file, so they're available when parsing next one
				configuration.getIdMap().putAll(previous == null ? objects : previous.getIdMap());
				// configuration will be available for Jetty when using <Property />
				configuration.getProperties().putAll(this.configuration.all());

				configuration.configure();

				// collect all created objects
				objects.putAll(configuration.getIdMap());

				// collect all created HttpConfigurations
				configuration.getIdMap().forEach((id, v) -> {
					if (HttpConfiguration.class.isAssignableFrom(v.getClass())) {
						httpConfigs.put(id, (HttpConfiguration) v);
					}
				});

				previous = configuration;
			}

			if (locations.length > 0) {
				// the "Server" object should not be redefined
				objects.values().forEach(bean -> {
					if (Server.class.isAssignableFrom(bean.getClass())) {
						if (bean != server) {
							String msg = "Can't create instance of Jetty server in external configuration files.";
							throw new IllegalArgumentException(msg);
						}
					}
				});

				// summary about found connectors
				Connector[] connectors = server.getConnectors();
				if (connectors != null && connectors.length > 0) {
					for (Connector connector : connectors) {
						String host = ((ServerConnector) connector).getHost();
						if (host == null) {
							host = "0.0.0.0";
						}
						int port = ((ServerConnector) connector).getPort();
						LOG.info("Found configured connector \"{}\": {}:{}", connector.getName(), host, port);
					}
				} else {
					LOG.debug("No connectors configured in external Jetty configuration files.");
				}
			}
		} finally {
			Thread.currentThread().setContextClassLoader(loader);
		}
	}

	/**
	 * External configuration may specify connectors (as in JETTY_HOME/etc/jetty-http.xml)
	 * but we may have to add default ones if they're missing
	 */
	private void verifyConnectorConfiguration() {
		boolean httpEnabled = configuration.server().isHttpEnabled();
		Integer httpPort = configuration.server().getHttpPort();

		boolean httpsEnabled = configuration.server().isHttpSecureEnabled();
		Integer httpsPort = configuration.server().getHttpSecurePort();

		String[] addresses = configuration.server().getListeningAddresses();

		// review connectors possibly configured from jetty.xml and check if they match configadmin configuration
		for (String address : addresses) {
			// If configured (org.osgi.service.http.enabled), Jetty should have a connector
			// with org.eclipse.jetty.server.HttpConnectionFactory
			verifyConnector(address, HttpConnectionFactory.class, httpPort, httpEnabled, false,
					() -> jettyFactory.createDefaultConnector(server, httpConfigs, address, configuration));

			// If configured (org.osgi.service.http.secure.enabled), Jetty should have a connector
			// with org.eclipse.jetty.server.SslConnectionFactory
			verifyConnector(address, SslConnectionFactory.class, httpsPort, httpsEnabled, true,
					() -> jettyFactory.createSecureConnector(server, httpConfigs, address, configuration));
		}
	}

	/**
	 * Verify if current server configuration, possibly created from external {@code jetty.xml} matches the
	 * declaration from PID ({@link org.ops4j.pax.web.service.PaxWebConfig#PID_CFG_HTTP_ENABLED} and
	 * {@link org.ops4j.pax.web.service.PaxWebConfig#PID_CFG_HTTP_SECURE_ENABLED}).
	 *
	 * @param address
	 * @param cfClass {@link ConnectionFactory} class to determine the <em>nature</em> of the connector
	 * @param port
	 * @param enabled
	 * @param secure
	 * @param connectorProvider {@link Supplier} used if connector has to be added to match PID configuration
	 */
	private void verifyConnector(String address, Class<? extends ConnectionFactory> cfClass, Integer port,
			boolean enabled, boolean secure, Supplier<Connector> connectorProvider) {
		ServerConnector expectedConnector = null;

		boolean connectorFound = false;
		ServerConnector backupConnector = null;

		Connector[] currentConnectors = server.getConnectors();
		if (currentConnectors == null) {
			currentConnectors = new Connector[0];
		}

		for (Connector connector : currentConnectors) {
			if (connector.getConnectionFactory(cfClass) != null) {
				if (match(address, port, connector)) {
					if (expectedConnector == null) {
						expectedConnector = (ServerConnector) connector;
					}
					connectorFound = true;
				} else {
					if (backupConnector == null) {
						backupConnector = (ServerConnector) connector;
					}
				}
			}
		}
		if (expectedConnector == null && backupConnector != null) {
			expectedConnector = backupConnector;
		}
		if (connectorFound) {
			if (enabled) {
				LOG.info("Using configured {} as {} connector for address: {}:{}", expectedConnector,
						(secure ? "secure" : "non secure"), address, port);
			} else {
				for (Connector connector : currentConnectors) {
					if (connector.getConnectionFactory(cfClass) != null) {
						LOG.warn("Connector defined in external configuration will be removed, "
								+ "because it's not enabled: {}", connector);
						server.removeConnector(connector);
					}
				}
			}
		} else if (enabled) {
			LOG.info("Creating {} connector for address {}:{}", (secure ? "secure" : "non secure"), address, port);
			// we have to create a connector
			Connector connector = connectorProvider.get();
			server.addConnector(connector);
		}
	}

	/**
	 * Check if the passed {@link Connector} can be treated as one <em>matching</em> the connector
	 * declared using PID properties.
	 *
	 * @param address1
	 * @param port1
	 * @param connector
	 * @return
	 */
	private boolean match(String address1, Integer port1, Connector connector) {
		if (!(connector instanceof ServerConnector)) {
			// strange, but lets do it like it was done before
			LOG.warn(connector + " is not an instance of ServerConnector");
			return false;
		}

		ServerConnector sc = (ServerConnector) connector;

		String address2 = sc.getHost();
		int port2 = sc.getPort();

		InetSocketAddress isa1 = address1 != null ? new InetSocketAddress(address1, port1)
				: new InetSocketAddress(port1);
		InetSocketAddress isa2 = address2 != null ? new InetSocketAddress(address2, port2)
				: new InetSocketAddress(port2);

		return isa1.equals(isa2);
	}

	/**
	 * Configure request logging (AKA <em>NCSA logging</em>) for Jetty, using configuration properties.
	 */
	public void configureRequestLog() {
		LogConfiguration lc = configuration.logging();

		if (lc.getLogNCSADirectory() == null) {
			throw new IllegalArgumentException("Log directory for NCSA logging is not specified. Please set"
					+ " org.ops4j.pax.web.log.ncsa.directory property.");
		}
		File logDir = new File(lc.getLogNCSADirectory());
		if (logDir.isFile()) {
			throw new IllegalArgumentException(logDir + " is not a valid directory to store request logs");
		}

		RequestLogWriter writer = new RequestLogWriter();

		// org.eclipse.jetty.util.RolloverFileOutputStream._append
		writer.setAppend(lc.isLogNCSAAppend());
		// org.eclipse.jetty.util.RolloverFileOutputStream._filename, should contain "yyyy_mm_dd"
		if (lc.getLogNCSAFile() != null) {
			writer.setFilename(new File(lc.getLogNCSADirectory(), lc.getLogNCSAFile()).getAbsolutePath());
		} else {
			writer.setFilename(new File(lc.getLogNCSADirectory(), "yyyy_mm_dd.request.log").getAbsolutePath());
		}
		// org.eclipse.jetty.util.RolloverFileOutputStream._fileDateFormat, defaults to "yyyy_mm_dd"
		writer.setFilenameDateFormat(lc.getLogNCSAFilenameDateFormat());
		// org.eclipse.jetty.util.RolloverFileOutputStream._retainDays
		writer.setRetainDays(lc.getLogNCSARetainDays());
		// org.eclipse.jetty.server.RequestLogWriter._timeZone, defaults to "GMT"
		writer.setTimeZone(lc.getLogNCSATimeZone());

		CustomRequestLog requestLog = new CustomRequestLog(writer,
				lc.isLogNCSAExtended() ? CustomRequestLog.EXTENDED_NCSA_FORMAT : CustomRequestLog.EXTENDED_NCSA_FORMAT);

		// original approach from PAXWEB-269 - http://wiki.eclipse.org/Jetty/Howto/Configure_Request_Logs:
//		server.getRootHandlerCollection().addHandler(requestLogHandler);
		// since https://bugs.eclipse.org/bugs/show_bug.cgi?id=446564 we can do better:
		server.setRequestLog(requestLog);

		LOG.info("NCSARequestlogging is using directory {}", lc.getLogNCSADirectory());
	}

	/**
	 * Simply start Jetty server
	 * @throws Exception
	 */
	public void start() throws Exception {
		LOG.info("Starting {}", server);

		server.start();
	}

	/**
	 * One-time operation. After stopping Jetty, we should not be able to start it again, so it has to be
	 * terminal operation with full clean up of resources.
	 *
	 * @throws Exception
	 */
	public void stop() throws Exception {
		LOG.info("Stopping {}", server);

		if (mbeanContainer != null) {
			LOG.info("Destroying Jetty JMX MBean container");
			// see https://github.com/eclipse/jetty.project/issues/851 for explanation
			server.removeBean(mbeanContainer);
			mbeanContainer.destroy();
			mbeanContainer = null;
		}

		server.stop();

		// PAXWEB-1127 - stop qtp after stopping server, as we've started it manually
		LOG.info("Stopping Jetty thread pool {}", qtp);
		qtp.stop();

		Handler[] childHandlers = server.getChildHandlers();
		for (Handler handler : childHandlers) {
			handler.stop();
		}

		LOG.info("Destroying Jetty server {}", server);
		server.destroy();
	}

	/**
	 * If state allows, this methods returns currently configured/started addresses of the listeners.
	 * @param useLocalPort
	 * @return
	 */
	public InetSocketAddress[] getAddresses(boolean useLocalPort) {
		if (server == null || server.getConnectors() == null || server.getConnectors().length == 0) {
			return null;
		}
		final List<InetSocketAddress> result = new ArrayList<>(server.getConnectors().length);
		for (Connector connector : server.getConnectors()) {
			if (connector instanceof ServerConnector) {
				ServerConnector sc = (ServerConnector)connector;
				int port = useLocalPort ? sc.getLocalPort() : sc.getPort();
				if (sc.getHost() == null) {
					result.add(new InetSocketAddress(port));
				} else {
					result.add(new InetSocketAddress(sc.getHost(), port));
				}
			}
		}

		return result.toArray(new InetSocketAddress[0]);
	}

	// --- connector/handler/customizer methods

	//	@Override
	@Review("Log says about opened port, but the port is not yet opened")
	public void addConnector(final Connector connector) {
		LOG.info("Pax Web available at [{}]:[{}]",
				((ServerConnector) connector).getHost() == null ? "0.0.0.0"
						: ((ServerConnector) connector).getHost(),
				((ServerConnector) connector).getPort());
		server.addConnector(connector);
//		if (priorityComparator != null) {
//			Connector[] connectors = server.getConnectors();
//			@SuppressWarnings("unchecked")
//			Comparator<Connector> comparator = (Comparator<Connector>) priorityComparator;
//			Arrays.sort(connectors, comparator);
//		}
	}

//	//	@Override
//	public void addHandler(Handler handler) {
//		HandlerCollection handlerCollection = server.getRootHandlerCollection();
//		handlerCollection.addHandler(handler);
//		if (priorityComparator != null) {
//			Handler[] handlers = handlerCollection.getHandlers();
//			@SuppressWarnings("unchecked")
//			Comparator<Handler> comparator = (Comparator<Handler>) priorityComparator;
//			Arrays.sort(handlers, comparator);
//		}
//	}

//	//	@Override
//	public Handler[] getHandlers() {
//		return server.getRootHandlerCollection().getHandlers();
//	}
//
//	//	@Override
//	public void removeHandler(Handler handler) {
//		server.getRootHandlerCollection().removeHandler(handler);
//	}

	// --- visitor methods for model changes

	@Override
	public void visit(ServletContextModelChange change) {
		ServletContextModel model = change.getServletContextModel();
		if (change.getKind() == OpCode.ADD) {
			LOG.info("Creating new Jetty context for {}", model);

			PaxWebServletContextHandler sch = new PaxWebServletContextHandler(null, model.getContextPath(), true, true, configuration);
			// special, OSGi-aware org.eclipse.jetty.servlet.ServletHandler
			sch.setServletHandler(new PaxWebServletHandler(default404Servlet));
			// setting "false" here will trigger 302 redirect when browsing to context without trailing "/"
			sch.setAllowNullPathInfo(false);
			// welcome files will be handled at default/resource servlet level and OsgiServletContext
			sch.setWelcomeFiles(new String[0]);

			// error page handler will be configured later (optionally)
			ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
			errorHandler.setShowStacks(configuration.server().isShowStacks());
			sch.setErrorHandler(errorHandler);

			// for future (optional) resource servlets, let's define some common context init properties
			// which are read in org.eclipse.jetty.servlet.DefaultServlet.init()
			sch.setInitParameter(DefaultServlet.CONTEXT_INIT + "dirAllowed", "false");
			sch.setInitParameter(DefaultServlet.CONTEXT_INIT + "etags", "true");
			// needed to comply with Servlets specification
			sch.setInitParameter(DefaultServlet.CONTEXT_INIT + "welcomeServlets", "true");

			// cache properties for default servlet (see org.eclipse.jetty.server.CachedContentFactory) passed
			// through context init params
			Integer totalCacheSize = configuration.resources().maxTotalCacheSize(); // kB
			Integer maxEntrySize = configuration.resources().maxCacheEntrySize(); // kB
			Integer maxEntries = configuration.resources().maxCacheEntries();
			// the defaults in Jetty are quite high (256MB total, 128MB max entry size), but we can have more
			// resource servlets, so we'll divide the defaults by 64
			sch.setInitParameter(DefaultServlet.CONTEXT_INIT + "maxCacheSize",
					totalCacheSize != null ? Integer.toString(totalCacheSize * 1024) : Integer.toString(256 * 1024 * 1024 / 64));
			sch.setInitParameter(DefaultServlet.CONTEXT_INIT + "maxCachedFileSize",
					maxEntrySize != null ? Integer.toString(maxEntrySize * 1024) : Integer.toString(128 * 1024 * 1024 / 64));
			sch.setInitParameter(DefaultServlet.CONTEXT_INIT + "maxCachedFiles",
					maxEntries != null ? Integer.toString(maxEntries) : "2048");

			// many OsgiContextModels may refer to single ServletContextModel and servlets, when calling
			// ServletContext.getServletContextName() will be getting OsgiContextModel specific name
//			sch.setDisplayName(model.getContextPath());

			mainHandler.addHandler(sch);
			mainHandler.mapContexts();

			// session configuration - based on defaultSessionConfiguration, but may be later overriden in OsgiContext
			SessionHandler sh = sch.getSessionHandler();
			if (sh != null) {
				SessionConfiguration sc = configuration.session();
				sh.setMaxInactiveInterval(sc.getSessionTimeout() * 60);
				sh.setSessionCookie(defaultSessionCookieConfig.getName());
				sh.getSessionCookieConfig().setDomain(defaultSessionCookieConfig.getDomain());
				// will default to context path if null
				sh.getSessionCookieConfig().setPath(defaultSessionCookieConfig.getPath());
				sh.getSessionCookieConfig().setMaxAge(defaultSessionCookieConfig.getMaxAge());
				sh.getSessionCookieConfig().setHttpOnly(defaultSessionCookieConfig.isHttpOnly());
				sh.getSessionCookieConfig().setSecure(defaultSessionCookieConfig.isSecure());
				sh.getSessionCookieConfig().setComment(defaultSessionCookieConfig.getComment());
				if (sc.getSessionUrlPathParameter() != null) {
					sh.setSessionIdPathParameterName(sc.getSessionUrlPathParameter());
				}
				if (sc.getSessionWorkerName() != null) {
					SessionIdManager sidManager = server.getSessionIdManager();
					if (sidManager instanceof DefaultSessionIdManager) {
						((DefaultSessionIdManager) sidManager).setWorkerName(sc.getSessionWorkerName());
					}
				}
			}

			// explicit no check for existing mapping under given physical context path
			contextHandlers.put(model.getContextPath(), sch);
			osgiContextModels.put(model.getContextPath(), new TreeSet<>());

			// configure ordered map of initializers - Jetty doesn't let us configure it in a "context"...
			initializers.put(model.getContextPath(), new LinkedHashMap<>());
			dynamicRegistrations.put(model.getContextPath(), new DynamicRegistrations());

			// do NOT start the context here - only after registering first "active" web element
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
		ServletContextHandler sch = contextHandlers.get(contextPath);

		if (sch == null) {
			throw new IllegalStateException(osgiModel + " refers to unknown ServletContext for path " + contextPath);
		}

		if (change.getKind() == OpCode.ADD) {
			LOG.info("Adding {} to {}", osgiModel, sch);

			// new OsgiContextModelChange was created 1:1 with some HttpContext/ServletContextHelper.
			// because Whiteboard ServletContext is scoped to single ServletContextHelper and has unique
			// set of attributes, that's the good place to remember such association.
			// Later, when servlets will be registered with such OsgiContextModel, they need to get
			// special facade for ServletContext
			if (osgiServletContexts.containsKey(osgiModel)) {
				throw new IllegalStateException(osgiModel + " is already registered");
			}

			// this (and similar Tomcat and Undertow places) should be the only place where
			// org.ops4j.pax.web.service.spi.servlet.OsgiServletContext is created and we have everything ready
			// to create proper classloader for this OsgiServletContext
			ClassLoader classLoader = null;
			// TODO: handle classloaders already prepared in pax-web-extender-war
			if (paxWebJettyBundle != null) {
				// it may not be the case in Test scenario
				OsgiServletContextClassLoader loader = new OsgiServletContextClassLoader();
				loader.addBundle(osgiModel.getOwnerBundle());
				loader.addBundle(paxWebJettyBundle);
				loader.addBundle(Utils.getPaxWebJspBundle(paxWebJettyBundle));
				loader.makeImmutable();
				classLoader = loader;
			} else {
				classLoader = this.classLoader;
			}
			OsgiServletContext osgiContext = new OsgiServletContext(sch.getServletContext(), osgiModel, servletContextModel,
					defaultSessionCookieConfig, classLoader);
			osgiServletContexts.put(osgiModel, osgiContext);

			// a physical context just got a new OSGi context
			osgiContextModels.get(contextPath).add(osgiModel);
		}

		if (change.getKind() == OpCode.DELETE) {
			LOG.info("Removing {} from {}", osgiModel, sch);

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
			((PaxWebServletHandler) sch.getServletHandler()).setDefaultOsgiContextModel(highestRankedModel);
			((PaxWebServletHandler) sch.getServletHandler()).setDefaultServletContext(highestRankedContext);

			// we have to ensure that non-highest ranked contexts are unregistered
			osgiServletContexts.forEach((ocm, osc) -> {
				if (ocm.getContextPath().equals(contextPath) && osc != highestRankedContext) {
					osc.unregister();
				}
			});

			// and the highest ranked context should be registered as OSGi service (if it wasn't registered)
			highestRankedContext.register();

			// TODO: very important question - here the classloader of entire context should be changed to the one
			//       built around a bundle registering the highest ranked OsgiContextModel. But this class loader's
			//       "space" should also include (somehow) all the reachable bundles (for example the bundles
			//       that have registered any web elements into this context)...
		} else {
			// TOCHECK: there should be no more web elements in the context, no OSGi mechanisms, just 404 all the time
			((PaxWebServletHandler) sch.getServletHandler()).setDefaultOsgiContextModel(null);
			((PaxWebServletHandler) sch.getServletHandler()).setDefaultServletContext(null);
		}
	}

	@Override
	public void visit(ServletModelChange change) {
		if ((change.getKind() == OpCode.ADD && !change.isDisabled()) || change.getKind() == OpCode.ENABLE) {
			ServletModel model = change.getServletModel();
			LOG.info("Adding servlet {}", model);

			// the same servlet should be added to all relevant contexts
			// for each unique ServletContextModel, a servlet should be associated with the "best"
			// OsgiContextModel. Usually there should be one, but here's example of a conflict:
			// ServletContextModel with "/c1"
			//     OsgiContextModel with name "ocm1" and rank 1
			//     OsgiContextModel with name "ocm2" and rank 2
			//     OsgiContextModel with name "ocm3" and rank 3
			// ServletContextModel with "/c2"
			//     OsgiContextModel with name "ocm4" and rank 1 and service.id 1
			//     OsgiContextModel with name "ocm5" and rank 1 and service.id 2
			// ServletContextModel with "/c3"
			//     OsgiContextModel with name "ocm6"
			//
			// now ServletModel with /s1 mapping is associated with ocm1, ocm2, ocm4 and ocm5 osgi contexts. It should
			// be available under:
			//  - /c1/s1 and associated with ocm2 (due to rank of ocm2)
			//  - /c2/s1 and associated with ocm4 (due to service.id of ocm4)
			//
			//    140.2 The Servlet Context
			//    [...] In the case of two Servlet Context Helpers with the same path, the service with the highest
			//    ranking is searched first for a match. In the case of a tie, the lowest service ID is searched first.
			//
			// The above is only about locating "matching servlet or resource" when processing requests, not
			// about initial registration...

			Set<String> done = new HashSet<>();

			// proper order ensures that (assuming above scenario), for /c1, ocm2 will be chosen and ocm1 skipped
			change.getContextModels().forEach(osgiContextModel -> {
				String contextPath = osgiContextModel.getContextPath();

				if (!done.add(contextPath)) {
					// servlet was already added to given ServletContextHandler
					// in association with the highest ranked OsgiContextModel (and its supporting
					// ServletContextHelper or HttpContext)
					//
					// there may be other servlets registered only to this OsgiContextModel (which, in this case,
					// turned out to be "lower ranked" within given ServletContextModel), so at any moment we
					// may have many "active" OsgiContextModels associated with single physical context path
					// we remember them in this.osgiContextModels
					return;
				}

				LOG.debug("Adding servlet {} to {}", model.getName(), contextPath);

				// there may be many instances of ServletHolder using the same instance of servlet (added to
				// different org.eclipse.jetty.servlet.ServletContextHandler._servletHandler in different context
				// paths, so we have the opportunity to provide specification-defined behavior at _this_ level
				//
				// 140.3 Common Whiteboard Properties says:
				//     ...
				//     If multiple Servlet Context Helper services match the osgi.http.whiteboard.context.select
				//     property the servlet, filter, resource or listener will be registered with all these Servlet
				//     Context Helpers. To avoid multiple init and destroy calls on the same instance, servlets and
				//     filters should be registered as Prototype Service Factory.
				//
				// we just have to create an instance of ServletHolder with proper OsgiContextModel, which in
				// turn may specify related HttpContext/ServletContextHelper directly or as ServiceReference, so
				// actual instance can be obtained as bundle-scoped service if the ServletContextHelper service
				// was registered as service factory
				//
				// same for the servlet itself. Specification recommends registration of Whiteboard servlets as
				// prototype service factories to avoid duplicate init() methods.

				// there should already be a ServletContextHandler
				PaxWebServletContextHandler sch = contextHandlers.get(contextPath);

				// <servlet> - always associated with one of ServletModel's OsgiContextModels
				OsgiServletContext context = osgiServletContexts.get(osgiContextModel);
				PaxWebServletHolder holder = new PaxWebServletHolder(model, osgiContextModel, context);

				// <servlet-mapping>
				ServletMapping mapping = new ServletMapping();
				mapping.setServletName(model.getName());
				mapping.setPathSpecs(model.getUrlPatterns());

				boolean isDefaultResourceServlet = model.isResourceServlet();
				for (String pattern : model.getUrlPatterns()) {
					isDefaultResourceServlet &= "/".equals(pattern);
				}
				if (model.isResourceServlet()) {
					holder.setInitParameter("pathInfoOnly", Boolean.toString(!isDefaultResourceServlet));
				}
				mapping.setDefault(isDefaultResourceServlet);

				((PaxWebServletHandler) sch.getServletHandler()).addServletWithMapping(holder, mapping);

				// are there any error page declarations in the model?
				ErrorPageModel epm = model.getErrorPageModel();
				if (epm != null) {
					// location will be the first URL mapping (even if there may be more)
					// in pure OSGi CMPN Whiteboard case, initially there could be no mapping at all, but in such
					// case a default would be generated for us to use
					String location = epm.getLocation();

					ErrorPageErrorHandler eph = (ErrorPageErrorHandler) sch.getErrorHandler();
					// TODO: If there are many servlets (mapped to non conflicting URIs), they still may define
					//       conflicting error pages, and these conflicts are NOT resolved at ServletModel
					//       resolution time. For now, we simply override existing error pages
					configureErrorPages(location, eph, epm);
				}

				if (!change.isDynamic()) {
					ensureServletContextStarted(sch);
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

					LOG.debug("Removing servlet {} from context {}", model.getName(), contextPath);

					// there should already be a ServletContextHandler
					ServletContextHandler sch = contextHandlers.get(contextPath);

					((PaxWebServletHandler) sch.getServletHandler()).removeServletWithMapping(model);

					// are there any error page declarations in the model?
					ErrorPageModel epm = model.getErrorPageModel();
					if (epm != null) {
						// location will be the first URL mapping (even if there may be more)
						// in pure OSGi CMPN Whiteboard case, initially there could be no mapping at all, but in such
						// case a default would be generated for us to use
						String location = epm.getLocation();

						ErrorPageErrorHandler eph = (ErrorPageErrorHandler) sch.getErrorHandler();
						// TODO: If there are many servlets (mapped to non conflicting URIs), they still may define
						//       conflicting error pages, and these conflicts are NOT resolved at ServletModel
						//       resolution time. For now, we simply remove existing error pages
						Map<String, String> existing = eph.getErrorPages();
						for (String ex : epm.getExceptionClassNames()) {
							existing.entrySet().removeIf(e -> e.getKey().equals(ex) && e.getValue().equals(location));
						}
						for (int code : epm.getErrorCodes()) {
							existing.entrySet().removeIf(e -> e.getKey().equals(Integer.toString(code)) && e.getValue().equals(location));
						}
						if (epm.isXx4() || epm.isXx5()) {
							// hmm, can't change existing ErrorPageErrorHandler
							eph = new ErrorPageErrorHandler();
							sch.setErrorHandler(eph);
							if (epm.isXx4()) {
								existing.entrySet().removeIf(e -> e.getKey().startsWith("4") && e.getKey().length() == 3
								&& e.getValue().equals(location));
							}
							if (epm.isXx5()) {
								existing.entrySet().removeIf(e -> e.getKey().startsWith("5") && e.getKey().length() == 3
								&& e.getValue().equals(location));
							}
						}
						// leave remaining (not removed) mappings
						for (Map.Entry<String, String> e : existing.entrySet()) {
							eph.addErrorPage(e.getKey(), e.getValue());
						}
					}
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
		// there's no separate add filter, add filter, remove filter, ... set of operations
		// everything is passed in single "change"

		Map<String, TreeMap<FilterModel, List<OsgiContextModel>>> contextFilters = change.getContextFilters();

		for (Map.Entry<String, TreeMap<FilterModel, List<OsgiContextModel>>> entry : contextFilters.entrySet()) {
			String contextPath = entry.getKey();
			Map<FilterModel, List<OsgiContextModel>> filtersMap = entry.getValue();
			Set<FilterModel> filters = filtersMap.keySet();

			LOG.info("Changing filter configuration for context {}", contextPath);

			// there should already be a ServletContextHandler
			PaxWebServletContextHandler sch = contextHandlers.get(contextPath);

			// all the filters should be added to org.ops4j.pax.web.service.jetty.internal.PaxWebServletHandler
			// of ServletContextHandler - regardles of the "OSGi context" with which the filter was registered.
			//
			//    140.5 Registering Servlet Filters
			//    [...] Servlet filters are only applied to servlet requests if they are bound to the same Servlet
			//    Context Helper and the same Http Whiteboard implementation.
			//
			// this means that we don't know upfront which filters are actually needed during request processing.
			// Having some servlet registered with ServletContextHelper sch1 and two filters mapped to '/*' URL but
			// registered with sch1 and sch2, only one filter should be invoked even if both sch1 and sch2 may lead
			// to single ServletContext (and single unique context path)
			// however, when there's no servlet in a chain, both filters should be invoked - at least I think so
			//
			// neither specification, nor felix.http implementation handle single filter scenario - such
			// filter is not called. That's quite logical - ServletContext associated with a filter (through
			// FilterConfig or request.getServletContext()) should be the same as the one associated with target
			// servlet. If there's no servlet, we can still have filters associated with different
			// ServletContextHelpers. If those ServletContextHelpers are associated with single physical
			// context path, we can't tell which actual ServletContext given filter should use.
			//
			// For Pax Web purposes, we'll try to handle such scenario and all the filters in a chain without servlet
			// will use OsgiServletContext which is "best" (wrt service ranking) for given physical context path

			FilterHolder[] filterHolders = sch.getServletHandler().getFilters();
			FilterMapping[] filterMappings = sch.getServletHandler().getFilterMappings();

			PaxWebFilterHolder[] newFilterHolders = new PaxWebFilterHolder[filters.size()];
			@SuppressWarnings("unchecked")
			List<PaxWebFilterMapping>[] newFilterMappings = (List<PaxWebFilterMapping>[]) new LinkedList<?>[filters.size()];

			// filters are sorted by ranking. for Jetty, this order should be reflected in the array of FilterMappings
			// order of FilterHolders is irrelevant
			int pos = 0;
			boolean noQuick = false;
			for (FilterModel model : filters) {
				// <filter> - FilterModel's OsgiContextModels only determine with which servlets such filter may
				// be associated.
				//  - when filter is running in a chain ending with some servlet, it'll get this servlet's OsgiContextModel
				//    and associated HttpContext/ServletContextHelper
				//  - when filter is running in a chain without target servlet, it'll get the "best" OsgiContextModel
				//    for given physical context path taken from this.osgiContextModels - this case (filters without
				//    servlet doesn't seem to be described by Whiteboard Service spec and isn't implemented by felix.http)

				// Filter and servlets conflict related to different target OsgiContextModels. Imagine:
				// ServletContextModel with "/c1"
				//     OsgiContextModel with name "ocm1" and rank 1
				//     OsgiContextModel with name "ocm2" and rank 2
				//     OsgiContextModel with name "ocm3" and rank 3
				//
				// if there's servlet registered under /s with ocm1 and ocm2 and a filter mapped to this servlet
				// (by name or /* path) with ocm1 only, this filter:
				//  - should get an OsgiServletContext associated with ocm1 during filter.init()
				//  - should not be invoked when processing a request targeted at servlet /s, because /s is chosen
				//    to be associated with higher ranked ocm2
				//
				// however, if there was some /s1 servlet associated with ocm1 only, filter should be invoked
				// when targeting /s1 servlet

				// if there's out-of-band list of new filters, there's no way the change will be "quick"
				noQuick |= filtersMap.get(model) != null;

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

				PaxWebFilterHolder holder = new PaxWebFilterHolder(model, context);
				PaxWebFilterMapping mapping = new PaxWebFilterMapping(model);

				newFilterHolders[pos] = holder;
				final LinkedList<PaxWebFilterMapping> paxWebFilterMappings = new LinkedList<>();
				newFilterMappings[pos] = paxWebFilterMappings;
				if (model.getDynamicServletNames().size() > 0 || model.getDynamicUrlPatterns().size() > 0) {
					// this FilterModel was created in SCI using ServletContext.addFilter(), so it has ONLY
					// dynamic mappings (potentially more than one)
					model.getDynamicServletNames().forEach(dm -> {
						if (!dm.isAfter()) {
							paxWebFilterMappings.add(new PaxWebFilterMapping(model, dm));
						}
					});
					model.getDynamicUrlPatterns().forEach(dm -> {
						if (!dm.isAfter()) {
							paxWebFilterMappings.add(new PaxWebFilterMapping(model, dm));
						}
					});
					model.getDynamicServletNames().forEach(dm -> {
						if (dm.isAfter()) {
							paxWebFilterMappings.add(new PaxWebFilterMapping(model, dm));
						}
					});
					model.getDynamicUrlPatterns().forEach(dm -> {
						if (dm.isAfter()) {
							paxWebFilterMappings.add(new PaxWebFilterMapping(model, dm));
						}
					});
				} else {
					// normal OSGi mapping
					paxWebFilterMappings.add(new PaxWebFilterMapping(model));
				}
				pos++;
			}

			if (noQuick || !quickFilterChange(sch.getServletHandler(), newFilterHolders, newFilterMappings)) {
				// the hard way - recreate entire array of filters/filter-mappings
				for (FilterHolder holder : sch.getServletHandler().getFilters()) {
					try {
						holder.stop();
					} catch (Exception e) {
						LOG.error(e.getMessage(), e);
					}
				}

				sch.getServletHandler().setFilters(newFilterHolders);
				FilterMapping[] flatMappings = Arrays.stream(newFilterMappings)
						.flatMap(Collection::stream).toArray(FilterMapping[]::new);
				sch.getServletHandler().setFilterMappings(flatMappings);
			}

			if (!change.isDynamic()) {
				ensureServletContextStarted(sch);
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
				ServletContextHandler servletContextHandler = contextHandlers.get(contextPath);
				EventListener eventListener = eventListenerModel.resolveEventListener();
				if (eventListener instanceof ServletContextAttributeListener) {
					// add it to accessible list to fire per-OsgiContext attribute changes
					OsgiServletContext c = osgiServletContexts.get(context);
					c.addServletContextAttributeListener((ServletContextAttributeListener)eventListener);
				}
				if (!done.add(contextPath)) {
					return;
				}

				// add the listener to real context - even ServletContextAttributeListener (but only once - even
				// if there are many OsgiServletContexts per ServletContext)
				servletContextHandler.addEventListener(eventListener);
			});
		}
		if (change.getKind() == OpCode.DELETE) {
			List<EventListenerModel> eventListenerModels = change.getEventListenerModels();
			for (EventListenerModel eventListenerModel : eventListenerModels) {
				List<OsgiContextModel> contextModels = eventListenerModel.getContextModels();
				contextModels.forEach((context) -> {
					ServletContextHandler servletContextHandler = contextHandlers.get(context.getContextPath());
					EventListener eventListener = eventListenerModel.resolveEventListener();
					if (eventListener instanceof ServletContextAttributeListener) {
						// remove it from per-OsgiContext list
						OsgiServletContext c = osgiServletContexts.get(context);
						c.removeServletContextAttributeListener((ServletContextAttributeListener)eventListener);
					}
					// remove the listener from real context - even ServletContextAttributeListener
					servletContextHandler.removeEventListener(eventListener);
					eventListenerModel.ungetEventListener(eventListener);
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
			// we have to configure all contexts, or rather - all resource servlets in all the contexts.
			// for Tomcat and Undertow we had to implement welcome file handling in "resource servlets" ourselves,
			// but we could have them settable.
			// in Jetty, initially we had no choice but to re-init the resource servlets after changing welcome files
			// in context handler, but eventually we've shaded the DefaultServlet, so welcome files are
			// settable
			contextModels.forEach((context) -> {
				// this time we don't alter single ServletContext for path of the highest ranked OsgiContextModel -
				// - we have to update all OsgiServletContexts because that's where welcome files are stored and
				// that's where "resource servlets" take the welcome files from
				OsgiServletContext osgiServletContext = osgiServletContexts.get(context);
				ServletContextHandler servletContextHandler = contextHandlers.get(context.getContextPath());

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

				LOG.info("Reconfiguration of welcome files for all resource servlets in context \"{}\"", context);

				// reconfigure welcome files in resource servlets without reinitialization (Pax Web 8 change)
				for (ServletHolder sh : servletContextHandler.getServletHandler().getServlets()) {
					PaxWebServletHolder pwsh = (PaxWebServletHolder) sh;
					// reconfigure the servlet ONLY if its holder uses given OsgiContextModel
					if (pwsh.getServletModel() != null && pwsh.getServletModel().isResourceServlet()
							&& context == pwsh.getOsgiContextModel()) {
						try {
							Servlet servlet = sh.getServlet();
							if (servlet instanceof JettyResourceServlet) {
								((JettyResourceServlet) servlet).setWelcomeFiles(newWelcomeFiles);
								((JettyResourceServlet) servlet).setWelcomeFilesRedirect(model.isRedirect());
							} else if (servlet instanceof OsgiInitializedServlet) {
								((JettyResourceServlet) ((OsgiInitializedServlet) servlet).getDelegate()).setWelcomeFiles(newWelcomeFiles);
								((JettyResourceServlet) ((OsgiInitializedServlet) servlet).getDelegate()).setWelcomeFilesRedirect(model.isRedirect());
							}
						} catch (Exception e) {
							LOG.warn("Problem reconfiguring welcome files in servlet {}", sh, e);
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

			// there should already be a ServletContextHandler
			ServletContextHandler sch = contextHandlers.get(contextPath);

			ErrorPageErrorHandler eph = (ErrorPageErrorHandler) sch.getErrorHandler();
			eph.getErrorPages().clear();

			for (ErrorPageModel model : errorPageModels) {
				String location = model.getLocation();
				configureErrorPages(location, eph, model);
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
				ServletContextHandler sch = contextHandlers.get(context.getContextPath());
				if (sch.isStarted()) {
					// Jetty, Tomcat and Undertow all disable "addServlet()" method (and filter and listener
					// equivalents) after the context has started, so we'll just print an error here
					LOG.warn("ServletContainerInitializer {} can't be added, as the context {} is already started",
							model.getContainerInitializer(), sch);
				} else {
					// no need to check whether there are more OsgiContextModels associated with
					// ContainerInitializerModel, because (for now) there's no Whiteboard support, thus there can
					// be only one OsgiContextModel

					ServletContainerInitializer initializer = model.getContainerInitializer();
					// Jetty doesn't handle ServletContainerInitializers directly... There's something in
					// org.eclipse.jetty.annotations.AnnotationConfiguration and
					// org.eclipse.jetty.servlet.listener.ContainerInitializer which turns initializer into a
					// listener, but looks like in this case, ordering should be somehow managed manually
					//
					// but that's actually good, because we don't want Jetty to call the initializers - we want to
					// call them ourselves - we want to pass correct ServletContext implementation there
					DynamicRegistrations registrations = this.dynamicRegistrations.get(path);
					OsgiDynamicServletContext dynamicContext = new OsgiDynamicServletContext(osgiServletContexts.get(context), registrations);
					SCIWrapper wrapper = new SCIWrapper(dynamicContext, model);
					initializers.get(path).put(System.identityHashCode(initializer), wrapper);
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
					LinkedHashMap<Integer, SCIWrapper> wrappers = this.initializers.get(path);
					if (wrappers != null) {
						wrappers.remove(System.identityHashCode(initializer));
					}
				});
			}
		}
	}

	/**
	 * <p>Registration of <em>active web element</em> should always start the context. On the other hand,
	 * registration of <em>passive web element</em> should <strong>not</strong> start the context.</p>
	 *
	 * <p>This method is always (should be) called withing the "configuration thread" of Pax Web Runtime, because
	 * it's called in visit() methods for servlets (including resources) and filters, so we can safely access
	 * {@link org.ops4j.pax.web.service.spi.model.ServerModel}.</p>
	 * @param sch
	 */
	private void ensureServletContextStarted(PaxWebServletContextHandler sch) {
		if (sch.isStarted()) {
			return;
		}
		try {
			String contextPath = sch.getContextPath().equals("") ? "/" : sch.getContextPath();
			OsgiContextModel highestRanked = ((PaxWebServletHandler) sch.getServletHandler()).getDefaultOsgiContextModel();
			OsgiServletContext highestRankedContext = ((PaxWebServletHandler) sch.getServletHandler()).getDefaultServletContext();

			LOG.info("Starting Jetty context \"{}\" with default Osgi Context {}", contextPath, highestRanked);

			// first thing - only NOW we can set ServletContext's class loader! It affects many things, including
			// the TCCL used for example by javax.el.ExpressionFactory.newInstance()
			sch.setClassLoader(highestRankedContext.getClassLoader());

			// this is when already collected initializers may be added as ordered collection to the servlet context
			// handler (Pax Web specific) - we need control over them, because we have to pass correct
			// ServletContext implementation there
			Collection<SCIWrapper> initializers = new LinkedList<>(this.initializers.get(contextPath).values());
			// Initially I thought we should take only these SCIs, which are associated with highest ranked OCM,
			// but it turned out that just as we take servlets registered to different OsgiContextModels, but
			// the same ServletContextModel, we have to do the same with SCIs.
			// otherwise, by default (with HttpService scenario), SCIs from the OsgiContextModel related to
			// pax-web-extender-whiteboard would be taken (probably 0), simply because this bundle is usually
			// the first that grabs an instance of bundle-scoped HttpService
			// so please do not uncomment and keep for educational purposes!
//			initializers.removeIf(w -> !w.getModel().getContextModels().contains(highestRanked));
			if (initializers.size() > 0) {
				// add a final initializer that will take care of actual registration of potentially collected
				// dynamic servlets, filters and listeners
				initializers.add(new RegisteringContainerInitializer(this.dynamicRegistrations.get(contextPath)));
				sch.setServletContainerInitializers(initializers);
			}

			// alter session configuration
			SessionHandler sh = sch.getSessionHandler();
			SessionConfigurationModel sc = highestRanked.getSessionConfiguration();
			if (sc != null) {
				if (sc.getSessionTimeout() != null) {
					sh.setMaxInactiveInterval(sc.getSessionTimeout() * 60);
				}
				SessionCookieConfig scc = sc.getSessionCookieConfig();
				if (scc != null) {
					if (scc.getName() != null) {
						sh.setSessionCookie(scc.getName());
					}
					if (scc.getDomain() != null) {
						sh.getSessionCookieConfig().setDomain(scc.getDomain());
					}
					if (scc.getPath() != null) {
						sh.getSessionCookieConfig().setPath(scc.getPath());
					}
					sh.getSessionCookieConfig().setMaxAge(scc.getMaxAge());
					sh.getSessionCookieConfig().setHttpOnly(scc.isHttpOnly());
					sh.getSessionCookieConfig().setSecure(scc.isSecure());
					sh.getSessionCookieConfig().setComment(scc.getComment());
				}
			}

			sch.start();
		} catch (Exception e) {
			LOG.error(e.getMessage(), e);
		}
	}

	private void configureErrorPages(String location, ErrorPageErrorHandler eph, ErrorPageModel epm) {
		for (String ex : epm.getExceptionClassNames()) {
			eph.addErrorPage(ex, location);
		}
		for (int code : epm.getErrorCodes()) {
			eph.addErrorPage(code, location);
		}
		if (epm.isXx4()) {
			eph.addErrorPage(400, 499, location);
		}
		if (epm.isXx5()) {
			eph.addErrorPage(500, 599, location);
		}
	}

	/**
	 * <p>This method tries to check if it's ok to just add new filters from {@code newFilterHolders} <em>at the end</em>
	 * of current list of filters. This is quite special case, but not that uncommon - when new filters are
	 * lower-ranked than all existing ones and there are no filters to be removed.</p>
	 *
	 * <p>TODO: with {@link ServletHandler#insertFilterMapping(FilterMapping, int, boolean)} we could
	 *          optimize even more</p>
	 *
	 * @param servletHandler
	 * @param newFilterHolders
	 * @param newFilterMappings
	 * @return
	 */
	private boolean quickFilterChange(ServletHandler servletHandler, PaxWebFilterHolder[] newFilterHolders, List<PaxWebFilterMapping>[] newFilterMappings) {
		PaxWebFilterHolder[] existingFilterHolders = (PaxWebFilterHolder[]) servletHandler.getFilters();

		int pos = 0;
		boolean quick = newFilterHolders.length >= existingFilterHolders.length;

		// by "quick" we mean - there are no removed filters and new filters come last
		while (quick) {
			if (pos >= existingFilterHolders.length) {
				break;
			}
			if (!existingFilterHolders[pos].getFilterModel().equals(newFilterHolders[pos].getFilterModel())) {
				quick = false;
				break;
			}
			pos++;
		}

		if (quick) {
			for (int i = pos; i < newFilterHolders.length; i++) {
				// each holder may have many mappings
				for (PaxWebFilterMapping paxWebFilterMapping : newFilterMappings[pos]) {
					servletHandler.addFilter(newFilterHolders[pos], paxWebFilterMapping);
				}
			}
			return true;
		}

		return false;
	}

	// PAXWEB-210: create security constraints
//	@Override
	public void addSecurityConstraintMappings(
			final SecurityConstraintMappingModel model) {
//					final ServletContextHandler context = server.getOrCreateContext(model);
//					final SecurityHandler securityHandler = context.getSecurityHandler();
//					if (securityHandler == null) {
//						throw new IllegalStateException(
//								"Internal error: Cannot find the security handler. Please report.");
//					}
//					String mappingMethod = model.getMapping();
//					String constraintName = model.getConstraintName();
//					String url = model.getUrl();
//					String dataConstraint = model.getDataConstraint();
//					List<String> roles = model.getRoles();
//					boolean authentication = model.isAuthentication();
//
//					ConstraintMapping newConstraintMapping = new ConstraintMapping();
//					newConstraintMapping.setMethod(mappingMethod);
//					newConstraintMapping.setPathSpec(url);
//					Constraint constraint = new Constraint();
//					constraint.setAuthenticate(authentication);
//					constraint.setName(constraintName);
//					constraint.setRoles(roles.toArray(new String[roles.size()]));
//
//					if (dataConstraint == null || "NONE".equals(dataConstraint)) {
//						constraint.setDataConstraint(Constraint.DC_NONE);
//					} else if ("INTEGRAL".equals(dataConstraint)) {
//						constraint.setDataConstraint(Constraint.DC_INTEGRAL);
//					} else if ("CONFIDENTIAL".equals(dataConstraint)) {
//						constraint.setDataConstraint(Constraint.DC_CONFIDENTIAL);
//					} else {
//						LOG.warn("Unknown user-data-constraint:" + dataConstraint);
//						constraint.setDataConstraint(Constraint.DC_CONFIDENTIAL);
//					}
//
//					newConstraintMapping.setConstraint(constraint);
//
//					((ConstraintSecurityHandler) securityHandler)
//							.addConstraintMapping(newConstraintMapping);
	}

	//	@Override
	public void removeSecurityConstraintMappings(
			final SecurityConstraintMappingModel model) {
//		final ServletContextHandler context = server.getContext(model
//				.getContextModel().getHttpContext());
//		if (context == null) {
//			return; // context already gone
//		}
//		final SecurityHandler securityHandler = context.getSecurityHandler();
//		if (securityHandler == null) {
//			throw new IllegalStateException(
//					"Internal error: Cannot find the security handler. Please report.");
//		}
//
//		List<ConstraintMapping> constraintMappings = ((ConstraintSecurityHandler) securityHandler)
//				.getConstraintMappings();
//		for (ConstraintMapping constraintMapping : constraintMappings) {
//			boolean urlMatch = constraintMapping.getPathSpec()
//					.equalsIgnoreCase(model.getUrl());
//			boolean methodMatch = (constraintMapping.getMethod() == null && model.getMapping() == null)
//					|| (constraintMapping.getMethod().equalsIgnoreCase(model.getMapping()));
//			if (urlMatch && methodMatch) {
//				constraintMappings.remove(constraintMapping);
//			}
//		}
//		removeContext(model.getContextModel().getHttpContext());
	}

}
