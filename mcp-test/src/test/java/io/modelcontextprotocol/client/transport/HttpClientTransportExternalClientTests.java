/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.lang.reflect.Field;
import java.net.http.HttpClient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the external-HttpClient injection contract on both HTTP transports.
 *
 * <p>
 * Covers brief acceptance criteria G1 (injected HttpClient is used), G3 (default build
 * stays byte-for-byte compatible with the original clientBuilder path), and the
 * externalClient guard that protects an injected shared client from being closed by
 * {@code closeGracefully()} (G4 precondition). The two transports are tested
 * symmetrically (acceptance #6).
 *
 * <p>
 * Lives in mcp-test (not mcp-core) because {@code build()} resolves the JSON mapper via
 * the McpJsonMapperSupplier SPI, whose implementation ships in mcp-json-jackson2/3 and is
 * only on this module's test classpath. The actual reflective close on internally-built
 * clients (acceptance G2) only takes effect on a JDK 21+ runtime where
 * {@code HttpClient.close()} exists; it is exercised via {@link HttpClientCloserTests} on
 * any JDK and validated manually on JDK 21+.
 *
 * @author inspur fork
 */
class HttpClientTransportExternalClientTests {

	@Test
	void sseInjectedHttpClientIsUsedAndMarkedExternal() throws Exception {
		HttpClient shared = HttpClient.newHttpClient();
		HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder("http://localhost:1")
			.httpClient(shared)
			.build();
		assertThat(field(transport, "httpClient")).isSameAs(shared);
		assertThat(field(transport, "externalClient")).isEqualTo(true);
	}

	@Test
	void sseDefaultBuildUsesInternalClient() throws Exception {
		HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder("http://localhost:1").build();
		assertThat(field(transport, "externalClient")).isEqualTo(false);
	}

	@Test
	void streamableInjectedHttpClientIsUsedAndMarkedExternal() throws Exception {
		HttpClient shared = HttpClient.newHttpClient();
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://localhost:1")
			.httpClient(shared)
			.build();
		assertThat(field(transport, "httpClient")).isSameAs(shared);
		assertThat(field(transport, "externalClient")).isEqualTo(true);
	}

	@Test
	void streamableDefaultBuildUsesInternalClient() throws Exception {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://localhost:1")
			.build();
		assertThat(field(transport, "externalClient")).isEqualTo(false);
	}

	private static Object field(Object target, String name) throws Exception {
		Field f = target.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

}
