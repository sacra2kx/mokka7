/*
 * PROJECT Mokka7 (fork of Snap7/Moka7)
 *
 * Copyright (c) 2017 J.Zimmermann (comtel2000)
 *
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Mokka7 is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE whatever license you
 * decide to adopt.
 *
 * Contributors: J.Zimmermann - Mokka7 fork
 *
 */
package org.comtel2000.mokka7.client.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.PreDestroy;

import org.slf4j.LoggerFactory;

/**
 * Ping watchdog to check host availability of host name or ip address
 *
 * @author comtel
 *
 */
public class PingWatchdogService implements AutoCloseable {

	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(PingWatchdogService.class);

	private static final int DEFAULT_TIMEOUT = 1000;

	private final ScheduledExecutorService service;
	private String host;
	private int timeout = DEFAULT_TIMEOUT;

	private ScheduledFuture<?> future;

	private Consumer<Throwable> consumer;

	private final boolean isWindowsOS;

	/**
	 * Initiate the ping service with a internal
	 * {@link ScheduledExecutorService}
	 */
	public PingWatchdogService() {
		this(Executors.newSingleThreadScheduledExecutor((r) -> {
			Thread th = new Thread(r);
			th.setName("ping-watchdog-" + th.getId());
			th.setDaemon(true);
			return th;
		}));
	}

	/**
	 * Initiate the ping service with a external
	 * {@link ScheduledExecutorService}
	 *
	 * @param es
	 *            external {@link ScheduledExecutorService}
	 */
	public PingWatchdogService(ScheduledExecutorService es) {
		this.service = Objects.requireNonNull(es);
		this.isWindowsOS = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows");
	}

	/**
	 *
	 * @param host
	 * @param millis
	 *            Ping interval in milli seconds
	 * @throws UnknownHostException
	 *             on no or invalid host/ip
	 */
	public void start(String host, long millis) throws UnknownHostException {
		setHost(host);
		start(millis);
	}

	/**
	 * Start ping service
	 *
	 * @param millis
	 *            Ping interval delay in milliseconds (must be greater than 10)
	 * @throws UnknownHostException
	 *             on no or invalid host/ip
	 */
	public void start(long millis) throws UnknownHostException {
		stop();
		if (host == null || host.isEmpty()) {
			throw new IllegalArgumentException("host must not be null");
		}
		if (millis < 11) {
			throw new IllegalArgumentException("delay must be greater 10 ms");
		}

		final InetAddress address = InetAddress.getByName(host);
		final int tout = timeout;
		final Consumer<Throwable> cons = consumer;
		if (cons == null) {
			logger.warn("no registered ping failed consumer");
		}
		future = service.scheduleWithFixedDelay(() -> ping(address, tout, cons), millis, millis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Stop the ping service
	 */
	@PreDestroy
	public void stop() {
		if (future == null) {
			return;
		}
		future.cancel(true);
		future = null;
	}

	/**
	 * Check the running state
	 *
	 * @return service is running
	 */
	public boolean isRunning() {
		if (future == null) {
			return false;
		}
		return !future.isDone();
	}

	private void ping(InetAddress address, int tout, Consumer<Throwable> cons) {
		try {
			long time = 0;
			if (logger.isTraceEnabled()) {
				time = System.currentTimeMillis();
			}
			boolean reachable = isReachable(address, tout);
			if (logger.isTraceEnabled()) {
				time = System.currentTimeMillis() - time;
				logger.trace("ping [{}] time {}ms", address.getHostName(), time);
			}
			if (!reachable) {
				logger.warn("[{}] not reachable ({})", address.getHostName(), tout);
				if (cons != null) {
					cons.accept(
							new IOException(String.format("host: %s not reachable (%s)", address.getHostName(), tout)));
				}
				stop();
			}
		} catch (Exception e) {
			if (cons != null) {
				cons.accept(e);
			} else {
				logger.error(e.getMessage(), e);
			}
			stop();
		}
	}

	private boolean isReachable(InetAddress address, int timeOutMillis) throws Exception {
		if (isWindowsOS) {
			return address.isReachable(timeOutMillis);
		}
		Process p = new ProcessBuilder("ping", "-c", "1", address.getHostAddress()).start();
		try {
			p.waitFor(timeOutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			return false;
		}
		if (p.isAlive()){
			return false;
		}
		int exitValue = p.exitValue();
		logger.trace("exit value: {}", exitValue);

		return exitValue == 0;
	}

	/**
	 * Set a ping failed consumer that triggers on ping timeout
	 *
	 * @param c
	 *            the ping failed consumer
	 */
	public void setOnPingFailed(Consumer<Throwable> c) {
		this.consumer = c;
	}

	/**
	 * Get the host/ip to ping
	 *
	 * @return host/ip
	 */
	public String getHost() {
		return host;
	}

	/**
	 * Set the host/ip to ping
	 *
	 * @param host
	 *            the host/ip
	 */
	public void setHost(String host) {
		this.host = host;
	}

	/**
	 * Get the timeout in milliseconds to wait for ping response
	 *
	 * @return timeout in milliseconds
	 */
	public int getTimeout() {
		return timeout;
	}

	/**
	 * Set the timeout in milliseconds to wait for ping response
	 *
	 * @param timeout
	 *            milliseconds to wait for ping response
	 */
	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	/**
	 * Shutdown the ping service
	 */
	@Override
	public void close() throws Exception {
		service.shutdown();
		try {
			if (!service.awaitTermination(1, TimeUnit.SECONDS)) {
				service.shutdownNow();
				if (!service.awaitTermination(1, TimeUnit.SECONDS))
					logger.error("Pool did not terminate");
			}
		} catch (InterruptedException ie) {
			service.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

}
