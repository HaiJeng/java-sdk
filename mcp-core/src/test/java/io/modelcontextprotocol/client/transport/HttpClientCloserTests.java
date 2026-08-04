/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.net.http.HttpClient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link HttpClientCloser}.
 *
 * <p>
 * The closer resolves {@code HttpClient.close()} reflectively: the method exists only on
 * JDK 21+ (HttpClient implements {@link AutoCloseable} since JDK-8304165). On JDK 17-20
 * the lookup throws {@link NoSuchMethodException} and the closer falls back to GC with a
 * debug log. These tests assert the closer never propagates failures, regardless of the
 * runtime JDK — the graceful-shutdown path of the transports depends on it.
 *
 * @author inspur fork
 */
class HttpClientCloserTests {

	@Test
	void closeShouldNotThrowRegardlessOfJdkVersion() {
		HttpClient httpClient = HttpClient.newHttpClient();
		// JDK 17-20: no close() method -> NoSuchMethodException caught -> debug log, no
		// throw.
		// JDK 21+: close() exists -> client is actually closed.
		assertThatCode(() -> HttpClientCloser.close(httpClient)).doesNotThrowAnyException();
	}

	@Test
	void closeShouldBeIdempotent() {
		HttpClient httpClient = HttpClient.newHttpClient();
		// A second close is a no-op on JDK <21, and HttpClient.close() on JDK 21+
		// tolerates
		// being called on an already-closed client. Either way, no exception must escape.
		assertThatCode(() -> HttpClientCloser.close(httpClient)).doesNotThrowAnyException();
		assertThatCode(() -> HttpClientCloser.close(httpClient)).doesNotThrowAnyException();
	}

}
