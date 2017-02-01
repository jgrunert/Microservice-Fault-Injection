package proxy;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.proxy.ProxyServlet.Transparent;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.uni_stuttgart.informatik.rss.msinject.pcs.models.ProxyDropConfig;
import de.uni_stuttgart.informatik.rss.msinject.pcs.models.ProxyStatus;


public class Proxy extends Transparent {
	private static final Logger logger = LoggerFactory.getLogger(Proxy.class);
	
	private static final long serialVersionUID = 1L;

	private boolean started = true;
	private boolean pcsConnected = false;
	private final int proxyPort;
	private final int controlPort;
	private final String proxyTag;
	private final String proxyUuid;
	private final String proxyTo;
	
	// Drop
	private boolean dropEnabled = false;
	private float dropProbability = 0.0f;
	
	// Delay
	private boolean delayEnabled = false;
	private float delayProbability = 0.0f;
	private int delayTimeMin = 0;
	private int delayTimeRandSpan = 0;
	
	// Max active, n-lane bridge
	private boolean maxActiveEnabled = false;
	private int maxActive = 2;
	private Object maxActiveLock = new Object();
	private static AtomicInteger activeRequestsCounter = new AtomicInteger(0);
	
	// Metrics
	private boolean metricsEnabled = true;
	private long requestsServiced = 0;
	private long requestsDelayed = 0;
	private long requestsDropped = 0;

	private final Random rd = new Random();
	private final Server proxyServer;
	
		
	
	public Proxy(Server proxyServer, int proxyPort, int controlPort, String proxyTo, String proxyTag, String proxyUuid) {
		this.proxyServer = proxyServer;
		this.proxyPort = proxyPort;
		this.controlPort = controlPort;
		this.proxyTo = proxyTo;
		this.proxyTag = proxyTag;
		this.proxyUuid = proxyUuid;
	}

	public static Proxy startProxy(int proxyPort, int controlPort, String proxyTo, String proxyTag,
			String proxyUuid) throws Exception {
		Server proxyServerTmp = new Server(proxyPort);
		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
		context.setContextPath("/");
		proxyServerTmp.setHandler(context);
		Proxy proxy = new Proxy(proxyServerTmp, proxyPort, controlPort, proxyTo, proxyTag, proxyUuid);
		ServletHolder helloServletHolder = new ServletHolder(proxy);
		helloServletHolder.setInitParameter("proxyTo", proxyTo);
		helloServletHolder.setInitParameter("prefix", "/");
		context.addServlet(helloServletHolder, "/*");
		proxyServerTmp.start();
		return proxy;
	}

	public void joinProxy() throws InterruptedException {
		proxyServer.join();
	}

	
	public void setDropConfig(ProxyDropConfig config) {
		dropEnabled = config.isEnabled();
		dropProbability = config.getProbability();
		logger.info(String.format("Proxy setDrop %b %f", dropEnabled, dropProbability));
	}
	
	public ProxyDropConfig getDropConfig() {
		return new ProxyDropConfig(dropEnabled, dropProbability);
	}
	
	
	public void setDelay(boolean enabled, float probability, int delayMin, int delayMax) {
		delayEnabled = enabled;
		delayProbability = probability;
		delayTimeMin = delayMin;
		delayTimeRandSpan = delayMax - delayMin + 1;
		logger.info(String.format("Proxy setDelay %b %f %d %d", enabled, probability, delayMin, delayMax));
	}
	
	public void setMaxActiveNLaneBridge(boolean enabled, int maxActiveCount) {
		maxActiveEnabled = enabled;
		maxActive = maxActiveCount;
		logger.info(String.format("Proxy setMaxActive %b %d", enabled, maxActiveCount));
	}
	
	public void setMetrics(boolean enabled) {
		metricsEnabled = enabled;
		logger.info(String.format("Proxy setMetrics %b", enabled));
	}
	

	@Override
	public void init(ServletConfig config) throws ServletException {
		logger.info("Starting init proxy to " + config.getInitParameter("proxyTo"));
		super.init(config);
		logger.info("Proxy init finished");
		started = true;
	}

	@Override
	protected void service(final HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {
		// Drop packages
		if (dropEnabled && rd.nextDouble() <= dropProbability) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			if(logger.isTraceEnabled())
				logger.trace("Dropped request " + request);
			if(metricsEnabled)
				requestsDropped++;
			return; // TODO Better drop?
		}
		super.service(request, response);
		if(logger.isTraceEnabled())
			logger.trace("Serviced request " + request);
		if(metricsEnabled)
			requestsServiced++;
	}

	@Override
	protected void customizeProxyRequest(Request proxyRequest, HttpServletRequest request) {
		
		if (maxActiveEnabled) {
			while(activeRequestsCounter.intValue() >= maxActive) {
				try {
					synchronized (maxActiveLock) {
						maxActiveLock.wait();						
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
					return;
				}
			}
			activeRequestsCounter.incrementAndGet();
		}

		// Delay packages
		if (delayEnabled && rd.nextDouble() <= delayProbability) {
			try {
				int delayTime = delayTimeMin + rd.nextInt(delayTimeRandSpan);
				if (logger.isTraceEnabled())
					logger.trace("Delay request by " + delayTime + " " + request);
				Thread.sleep(delayTime);
				if(metricsEnabled)
					requestsDelayed++;
			} catch (InterruptedException e) {
				e.printStackTrace();
				return;
			}
		}
		super.customizeProxyRequest(proxyRequest, request);
	}	

	
	
	// Observe ProxyResponseListener to know when complete
    protected Response.Listener newProxyResponseListener(HttpServletRequest request, HttpServletResponse response)
    {
        return new ProxyResponseListenerObserved(request, response);
    }
    
    protected class ProxyResponseListenerObserved extends ProxyResponseListener {

		protected ProxyResponseListenerObserved(HttpServletRequest request, HttpServletResponse response) {
			super(request, response);
		}
    	
		@Override
		public void onComplete(Result result) {
			super.onComplete(result);
			if (maxActiveEnabled) {
				activeRequestsCounter.decrementAndGet();
				synchronized (maxActiveLock) {
					maxActiveLock.notify();				
				}
			}
		}
    }
	

	public ProxyStatus getStatus() {
		return new ProxyStatus(controlPort, proxyPort, proxyTag, proxyUuid, proxyTo, 
				started, pcsConnected, requestsServiced, requestsDelayed, requestsDropped);
	}
    
	public boolean isStarted() {
		return started;
	}
	
	public long getRequestsServiced() {
		return requestsServiced;
	}

	public long getRequestsDelayed() {
		return requestsDelayed;
	}

	public long getRequestsDropped() {
		return requestsDropped;
	}
	
	
	public boolean getMetricsEnabled() {
		return metricsEnabled;
	}

	public boolean isPcsConnected() {
		return pcsConnected;
	}

	public void setPcsConnected(boolean pcsConnected) {
		this.pcsConnected = pcsConnected;
	}
}
