/*
 * Copyright 2024 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle tests for the HTTP client transports: HttpClient close on graceful shutdown
 * (#547 fix) and external HttpClient injection. Covers both the Streamable HTTP and the
 * (deprecated) SSE transports.
 *
 * @author inspur fork
 */
class HttpClientTransportLifecycleTests {

	private static final URI STREAMABLE_ANY = URI.create("http://localhost:1/mcp");

	private static final URI SSE_ANY = URI.create("http://localhost:1/sse");

	/** 读 transport 的 private 字段（白盒断言 httpClient 归属 / externalClient 标志）。 */
	private static Object field(Object target, String name) {
		try {
			Field f = target.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.get(target);
		}
		catch (ReflectiveOperationException e) {
			throw new AssertionError("Unable to read field " + name, e);
		}
	}

	@Nested
	class StreamableTransport {

		@Test
		void closeGracefullyCompletesWithoutError() {
			// JDK 无关：closeGracefully 必须正常完成（HttpClientCloser 降级也绝不 re-throw）
			var transport = HttpClientStreamableHttpTransport.builder("http://localhost:1").build();
			assertThatCode(() -> transport.closeGracefully().block()).doesNotThrowAnyException();
		}

		@Test
		@EnabledForJreRange(min = JRE.JAVA_21)
		void internalHttpClientIsClosedOnGracefulClose() {
			// JDK>=21：closeGracefully 后内部 HttpClient 应已关闭（sendAsync 失败）
			var transport = HttpClientStreamableHttpTransport.builder("http://localhost:1").build();
			HttpClient internal = (HttpClient) field(transport, "httpClient");
			transport.closeGracefully().block();
			HttpRequest request = HttpRequest.newBuilder(STREAMABLE_ANY).GET().build();
			assertThatThrownBy(() -> internal.sendAsync(request, HttpResponse.BodyHandlers.discarding()).join())
				.hasCauseInstanceOf(java.io.IOException.class);
		}

		@Test
		void injectedHttpClientIsUsedAndNotClosed() {
			// 注入的 HttpClient 必须被 transport 直接持有，且 externalClient=true（closeGracefully
			// 不关它）
			HttpClient injected = HttpClient.newHttpClient();
			var transport = HttpClientStreamableHttpTransport.builder("http://localhost:1")
				.httpClient(injected)
				.build();
			assertThat(field(transport, "httpClient")).isSameAs(injected);
			assertThat(field(transport, "externalClient")).isEqualTo(true);
			assertThatCode(() -> transport.closeGracefully().block()).doesNotThrowAnyException();
		}

	}

	@Nested
	class SseTransport {

		@Test
		void closeGracefullyCompletesWithoutError() {
			var transport = HttpClientSseClientTransport.builder("http://localhost:1").build();
			assertThatCode(() -> transport.closeGracefully().block()).doesNotThrowAnyException();
		}

		@Test
		@EnabledForJreRange(min = JRE.JAVA_21)
		void internalHttpClientIsClosedOnGracefulClose() {
			var transport = HttpClientSseClientTransport.builder("http://localhost:1").build();
			HttpClient internal = (HttpClient) field(transport, "httpClient");
			transport.closeGracefully().block();
			HttpRequest request = HttpRequest.newBuilder(SSE_ANY).GET().build();
			assertThatThrownBy(() -> internal.sendAsync(request, HttpResponse.BodyHandlers.discarding()).join())
				.hasCauseInstanceOf(java.io.IOException.class);
		}

		@Test
		void injectedHttpClientIsUsedAndNotClosed() {
			HttpClient injected = HttpClient.newHttpClient();
			var transport = HttpClientSseClientTransport.builder("http://localhost:1").httpClient(injected).build();
			assertThat(field(transport, "httpClient")).isSameAs(injected);
			assertThat(field(transport, "externalClient")).isEqualTo(true);
			assertThatCode(() -> transport.closeGracefully().block()).doesNotThrowAnyException();
		}

	}

}
