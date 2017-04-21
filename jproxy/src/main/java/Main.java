
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import proxy.Proxy;
import proxycontrol.MasterHeartbeatSender;
import proxycontrol.MetricsManager;
import proxycontrol.ProxyControl;


public class Main {
	private static final Logger logger = LoggerFactory.getLogger(Main.class);
	
	private static final String DbName = "ProxyMetrics";

	
	public static void main(String... args) throws Exception {
		if (args.length < 6) {
			showHelp();
			return;
		}
		
		Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
		logger.info("--- Start Network interfaces ---");
        for (NetworkInterface netint : Collections.list(nets))
            logInterfaceInformation(netint);
		logger.info("--- End Network interfaces ---");

		Proxy proxy = null;
		Server proxyControlServer = null;
		try {
			final int controlPort = Integer.parseInt(args[0]);
			final int proxyPort = Integer.parseInt(args[1]);
			final String proxyTo = args[2];
			final String proxyTag = args[3];
			final String masterUrl = args[4];
			final String influxdbUrl = args[5];
			
			final String proxyUuid = UUID.randomUUID().toString();
			logger.info("Starting proxy " + proxyTag + "_" + proxyUuid);
			
			// Try to connect to influxdb
			final InfluxDB influxDB = connectToDatabase(influxdbUrl);

			// Start proxy
			logger.info("Proxy starting on port " + proxyPort);
			proxy = Proxy.startProxy(proxyPort, controlPort, proxyTo, proxyTag, proxyUuid);
			
			// Start metrics
			MetricsManager metricsManager = null;
			if(influxDB != null) {
				logger.info("Starting MetricsManager to influxDb");
				metricsManager = new MetricsManager(proxyTag, proxyUuid, influxDB, proxy, DbName);
			}
			else {
				logger.error("Unable to start MetricsManager without influxDb connection");
			}
			
			// Start control server
			ResourceConfig config = new ResourceConfig();
			config.packages("proxycontrol");
			ServletHolder servlet = new ServletHolder(new ServletContainer(config));
			logger.info("Control server starting on port " + controlPort);
			proxyControlServer = new Server(controlPort);
			ServletContextHandler context = new ServletContextHandler(proxyControlServer, "/*");
			context.addServlet(servlet, "/*");
			proxyControlServer.start();
			ProxyControl.setup(proxy, metricsManager);
			
			// Start master message sender
			new MasterHeartbeatSender(proxy, masterUrl);

			proxy.joinProxy();
		} catch (Exception e) {
			logger.error("MAIN exception", e);
			showHelp();
		} finally {
			if (proxyControlServer != null)
				proxyControlServer.destroy();
			if (proxy != null)
				proxy.destroy();
		}
	}
	
	static void logInterfaceInformation(NetworkInterface netint) throws SocketException {
		StringBuilder sb = new StringBuilder();
		sb.append(netint.getDisplayName() + "(" + netint.getName() + "): ");
        Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
        for (InetAddress inetAddress : Collections.list(inetAddresses)) {
        	sb.append(inetAddress + ", ");
        }
        logger.info(sb.toString());
     }
	
	private static InfluxDB connectToDatabase(String influxdbUrl) {
		try {
			// Connect to influxdb database
			logger.info("Start connecting to InfluxDB");
			InfluxDB influxDB = InfluxDBFactory.connect(influxdbUrl, "root", "root");
			String dbName = DbName;
			influxDB.createDatabase(dbName);
			logger.info("Connected to InfluxDB");
			return influxDB;
		} catch (Exception e) {
			logger.error("connectToDatabase exception", e);
			return null;
		}
	}

	private static void showHelp() {
		System.out.println("Usage: [control-port] [proxy-listen-port] [proxyTo] [proxy-id] [master-url] [influxdb-url]");
		System.out.println("Example: 8089 8090 http://0.0.0.0:8080/ ProxyForDatabase http://0.0.0.0:8080/ http://172.17.0.2:8086/");
	}
}
