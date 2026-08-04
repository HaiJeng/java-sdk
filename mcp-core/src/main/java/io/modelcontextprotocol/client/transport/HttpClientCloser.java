/*
 * Copyright 2024 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.lang.reflect.InvocationTargetException;
import java.net.http.HttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal helper that closes a {@link HttpClient} via reflection.
 *
 * <p>
 * {@code HttpClient.close()} exists only on JDK 21+, where {@link HttpClient} implements
 * {@link AutoCloseable} (see
 * <a href= "https://bugs.openjdk.org/browse/JDK-8304165">JDK-8304165</a>). This SDK
 * compiles against Java 17, so the close call is resolved reflectively at runtime: on JDK
 * &lt;21 the method is absent and cleanup falls back to GC (the documented pre-21
 * behavior); on JDK &ge;21 the client is actually closed, releasing its selector thread
 * and connection pool — fixing the resource leak described in
 * <a href="https://github.com/modelcontextprotocol/java-sdk/issues/547">upstream issue
 * #547</a> for internally-built clients.
 *
 * <p>
 * Package-private on purpose: this is a fork-internal utility shared by the SSE and
 * Streamable HTTP transports, not part of the public API surface.
 *
 * @author inspur fork
 */
final class HttpClientCloser {

	private static final Logger logger = LoggerFactory.getLogger(HttpClientCloser.class);

	private HttpClientCloser() {
	}

	/**
	 * Closes the given {@link HttpClient} if the runtime JDK exposes a {@code close()}
	 * method (JDK 21+); otherwise logs at debug level and relies on GC. Exceptions thrown
	 * by the close call are unwrapped and warned, never re-thrown — close failures must
	 * not break the transport's graceful-shutdown path.
	 * @param httpClient the HttpClient to close (must not be null)
	 */
	static void close(HttpClient httpClient) {
		try {
			// Java 21+: 先 shutdown 停止接受新请求，再 awaitTermination 等待 in-flight 请求完成，
			// 避免直接 close() abort 正在进行的请求（如 session 清理 DELETE）触发 "closed" 错误。
			HttpClient.class.getMethod("shutdown").invoke(httpClient);
			HttpClient.class.getMethod("awaitTermination", java.time.Duration.class)
				.invoke(httpClient, java.time.Duration.ofSeconds(2));
		}
		catch (NoSuchMethodException e) {
			logger.debug("HttpClient graceful shutdown unavailable on JDK {}; relying on GC",
					System.getProperty("java.version"));
		}
		catch (InvocationTargetException e) {
			logger.debug("HttpClient graceful shutdown threw", e.getCause());
		}
		catch (Exception e) {
			logger.debug("HttpClient graceful shutdown failed", e);
		}
	}

}
