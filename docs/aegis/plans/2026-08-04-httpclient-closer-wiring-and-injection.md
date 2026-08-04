# 实现计划: HttpClientCloser 接入 + 外部 HttpClient 注入口

- 日期: `2026-08-04`
- 状态: `待执行`
- 上游 Spec: `docs/aegis/specs/2026-08-04-httpclient-lifecycle-injection-brief.md`
- 优先级（用户明确）: **修 bug（#547 泄漏）> 注入口扩展**

---

## Goal（目标）

把已存在但被搁置的 `HttpClientCloser` 接到两个客户端 transport 的 `closeGracefully()`，
修复 #547（内部 HttpClient 从不被关闭）；同时给两个 transport 的 Builder 加外部 HttpClient
注入口，注入的 HC 由调用方管理生命周期（closeGracefully 不关它）。

## Architecture（架构）

- 分层不变：`McpClientTransport` 契约不变，改的是两个实现类的 lifecycle 行为。
- `HttpClientCloser`（package-private，已存在于 `client/transport`）是反射关闭的唯一 owner；
  两个 transport 复用它，**不内联反射逻辑、不新建 util**。
- transport 新增 `externalClient` 布尔字段区分"外部注入 / 内部构建"，仅驱动 closeGracefully
  是否调 closer。

## Tech Stack

Java 17 编译目标（`pom.xml:57`，**不动**）；反射在运行时 JDK≥21 真正关闭 HttpClient、<21 降级 GC。
Project Reactor（`Mono<Void> closeGracefully()`）。JUnit 6 + AssertJ + Mockito（mcp-test）。

## Baseline / Authority Refs

- `docs/aegis/specs/2026-08-04-httpclient-lifecycle-injection-brief.md` — 已批准 Spec Brief
- `mcp-core/.../client/transport/HttpClientCloser.java` — **plan 阶段新发现的现成 util**（spec 调研漏掉）
- `CLAUDE.md` — Java 17+ / 最小 API 表面 / 集成测试落 mcp-test
- `AGENTS.md` — AI-agent 贡献策略（本 fork 内部使用，不发上游 PR）
- `pom.xml:57` — `java.version=17`（保持）

## Compatibility Boundary（兼容边界，必须守住）

1. **不注入时 `build()` 行为逐字不变** —— 走原 `clientBuilder.connectTimeout(...).build()`。
2. **`closeGracefully()` 对外部 HttpClient 零触碰**（`externalClient=true` 跳过 closer）。
3. **Java 17 编译目标不变**（不引入 `httpClient.close()` 直接调用）。
4. **构造函数不破坏公开 API** —— SSE 构造 package-private、Streamable 构造 private，末尾加参数。
5. **不改**：pom / CI / 文档 / 版本号 / `McpSchema` records / 现有 Builder 方法签名。

## Verification（验证）

- 单测：`./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientTransportLifecycleTests`
- 格式化（每次改完源码）：`./mvnw.cmd spring-javaformat:apply`
- 现有套件不回归：`./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientStreamableHttpTransportTest,HttpClientSseClientTransportTests`

---

## Scope Check（compact 产物）

**Aegis Visibility**: 已写好的 `HttpClientCloser` 被搁置（零引用），接上它即修 #547；plan 的价值是
把"接 closer + externalClient 守卫 + 注入口"拆成可独立验证的 TDD 切片，并守住"注入 HC 不被关"
这个兼容边界。

**Plan Basis**: 已批准 Spec Brief + plan 阶段事实修正（HttpClientCloser 已存在）。

**BaselineUsageDraft**:
- Required baseline refs: Spec Brief、`HttpClientCloser.java`、`CLAUDE.md`、`pom.xml:57`
- Acknowledged before plan refs: `VERSIONING.md`（不升基线）、`AGENTS.md`（不发 PR）
- Cited in plan refs: 见各 Task 行号引用
- Missing refs: 无（HttpClientCloser 已补读）
- Decision: `continue`

**Requirement Ready Check**: `ready`（Spec Brief 已批准，验收边界 §6 明确）。

**Change Necessity**:
- User-visible need: 修 #547 资源泄漏 + 支持外部 HC 复用
- No-change option: 不可行 —— closer 已写好但未接调用点，必须改 transport 源码接入
- Minimum change boundary: `mcp-core` 两个 transport 文件 + 1 个新测试文件
- Decision: `code-change`

**Existence Check**（关键 —— 修正 spec 的错误前提）:
- Proposed new surface: 无（原 spec 设想的内联反射逻辑 / 新 util 都不需要）
- Existing owner / reuse candidate: `HttpClientCloser`（已存在，package-private，同包，javadoc 已引用 #547）
- Why existing surface is insufficient: 充分 —— 它完整实现了反射 close + 降级 + unwrap + 不 re-throw
- Creation proof: N/A（复用）
- Entropy / retirement impact: 零新增实体
- Decision: **`reuse-existing`** —— spec §4.1d/§8 开放项 1 的"内联"基于不完整调研，plan 改为复用。

**Architecture Integrity Lens**:
- Invariant: closer 是 HttpClient 反射关闭的唯一 owner
- Canonical owner: `HttpClientCloser`
- Responsibility overlap: 无 —— transport 只决定"要不要关"（externalClient），closer 决定"怎么关"
- Higher-level simplification: 无更高级 owner（transport 直接持有 HC）
- Verdict: 干净，无重叠

**Plan Pressure Test**:
- Owner / contract: closer owner 明确；transport 契约不变（只加 close 副作用 + Builder 方法）
- Verification: 单测可覆盖（close 生效需 JDK≥21，CI JDK17 跑"不破坏"档）
- Task executability: 每步有完整代码 + 命令
- Pressure result: `proceed`

**Plan-Time Complexity Check**:
- Complexity Budget: artifact class = 局部 bug 修复 + 小幅 API 扩展；target = 2 源文件 + 1 测试文件；
  当前压力低（SSE 528 行、Streamable 942 行，各加 ~10 行）；projected = within-budget
- Recommendation: `edit-in-place`（两个 transport 各自改，不抽公共基类）

---

## File Map

| 文件 | 操作 | 说明 |
|---|---|---|
| `mcp-core/.../client/transport/HttpClientStreamableHttpTransport.java` | 改 | 字段 + 构造 + build + closeGracefully + Builder |
| `mcp-core/.../client/transport/HttpClientSseClientTransport.java` | 改 | 同上（对称） |
| `mcp-test/.../client/transport/HttpClientTransportLifecycleTests.java` | 新建 | close + 注入口的 TDD 测试 |
| `mcp-core/.../client/transport/HttpClientCloser.java` | **不改** | 复用现成的 |

---

## Task 1 — Streamable transport 接入 HttpClientCloser（修 #547，首要）

**Files**: `HttpClientStreamableHttpTransport.java`（改）、`HttpClientTransportLifecycleTests.java`（新建）
**Why**: 主力 transport（非 deprecated）的内部 HttpClient 在 closeGracefully 时从不关闭，资源泄漏（#547）。
`HttpClientCloser` 已写好，接上即修。
**Change Necessity**: closer 未接调用点，必须改 closeGracefully 源码；最小边界 = 加 1 字段 + 构造参数 + 1 行 closer 调用。
**Impact/Compat**: 向后兼容 —— 不注入时 externalClient=false，closeGracefully 多了"关内部 HC"的副作用（这正是修复本身）。

### 步骤

- [ ] **1.1 写失败测试**（新建 `HttpClientTransportLifecycleTests.java`）：

```java
/*
 * Copyright 2024 - 2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.lang.reflect.Field;
import java.net.http.HttpClient;

import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle tests: HttpClient close (#547 fix) + external HttpClient injection.
 */
class HttpClientTransportLifecycleTests {

	/** 读 transport 的 private 字段（白盒断言 externalClient / httpClient 归属）。 */
	private static Object field(Object target, String name) {
		try {
			Field f = target.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.get(target);
		}
		catch (NoSuchFieldException | IllegalAccessException e) {
			throw new AssertionError("Unable to read field " + name, e);
		}
	}

	@Nested
	class StreamableTransport {

		@Test
		void closeGracefullyDoesNotBreakShutdownPath() {
			// JDK 无关：closeGracefully 必须正常完成，不抛（HttpClientCloser 降级也不 re-throw）
			var transport = HttpClientStreamableHttpTransport.builder("http://localhost:1").build();
			transport.closeGracefully().block();
			// 关闭后再 sendMessage 应报 session closed（证明 close 已生效、transport 进入终态）
			org.assertj.core.api.Assertions
				.assertThatExceptionOfType(McpTransportSessionClosedException.class)
				.isThrownBy(() -> transport
					.sendMessage(new io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification(
							io.modelcontextprotocol.spec.McpSchema.JSONRPCRequestMethod.INITIALIZE.method(),
							io.modelcontextprotocol.spec.McpSchema.EMPTY_OBJECT))
					.block());
		}

		@Test
		@org.junit.jupiter.api.condition.EnabledForJreRange(
				min = org.junit.jupiter.api.condition.JRE.JAVA_21)
		void internalHttpClientIsClosedOnGracefulClose() throws Exception {
			// JDK≥21：closeGracefully 后，内部 HttpClient 应被关闭（sendAsync 失败）
			var transport = HttpClientStreamableHttpTransport.builder("http://localhost:1").build();
			HttpClient internal = (HttpClient) field(transport, "httpClient");
			transport.closeGracefully().block();
			Awaitility.await().untilAsserted(() -> assertThat(internal).isNotNull());
			// 被关后 executor 已停，sendAsync 的 future 异常完成
			var request = java.net.http.HttpRequest
				.newBuilder(java.net.URI.create("http://localhost:1/mcp")).GET().build();
			org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> internal
					.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding()).join())
				.hasMessageContaining("closed");
		}

	}

	@Nested
	class SseTransport {
		// Task 2 填充
	}
}
```

- [ ] **1.2 跑测试确认 RED**：
```bash
./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientTransportLifecycleTests$StreamableTransport
```
预期：`internalHttpClientIsClosedOnGracefulClose` 在 JDK≥21 失败（HC 未被关，sendAsync 不抛 "closed"）；
`closeGracefullyDoesNotBreakShutdownPath` 可能已通过（现状 closeGracefully 能完成）。

- [ ] **1.3 实现 — 加字段、构造参数、closeGracefully 调 closer**：

字段（在 :94 `private final HttpClient httpClient;` 之后加）：
```java
/** true = 外部注入的 HttpClient，生命周期由调用方管理，closeGracefully 不关它。 */
private final boolean externalClient;
```

构造（:141 签名末尾加 `boolean externalClient`，函数体赋值）：
```java
private HttpClientStreamableHttpTransport(McpJsonMapper jsonMapper, HttpClient httpClient,
		HttpRequest.Builder requestBuilder, String baseUri, String endpoint, boolean resumableStreams,
		boolean openConnectionOnStartup, McpAsyncHttpClientRequestCustomizer httpRequestCustomizer,
		McpHttpClientTransportAuthorizationErrorHandler authorizationErrorHandler,
		List<String> supportedProtocolVersions, boolean externalClient) {
	// ... 现有赋值不变 ...
	this.externalClient = externalClient;
}
```

closeGracefully（:232，在 session 关闭链后追加 closer 调用）：
```java
@Override
public Mono<Void> closeGracefully() {
	return Mono.defer(() -> {
		logger.debug("Graceful close triggered");
		McpTransportSession<Disposable> currentSession = this.activeSession
			.getAndSet(ClosedMcpTransportSession.INSTANCE);
		Mono<Void> sessionClose = (currentSession != null)
				? Mono.from(currentSession.closeGracefully()) : Mono.empty();
		return sessionClose.then(Mono.fromRunnable(() -> {
			if (!externalClient) {
				HttpClientCloser.close(this.httpClient);
			}
		}));
	});
}
```

build()（:933，暂时传 `false` —— Task 3 再接注入口）：
```java
public HttpClientStreamableHttpTransport build() {
	HttpClient httpClient = this.clientBuilder.connectTimeout(this.connectTimeout).build();
	return new HttpClientStreamableHttpTransport(jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper,
			httpClient, requestBuilder, baseUri, endpoint, resumableStreams, openConnectionOnStartup,
			httpRequestCustomizer, authorizationErrorHandler, supportedProtocolVersions, false);
}
```

- [ ] **1.4 格式化 + 跑测试确认 GREEN**：
```bash
./mvnw.cmd spring-javaformat:apply
./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientTransportLifecycleTests$StreamableTransport
```
- [ ] **1.5 commit**：
```bash
git add mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientStreamableHttpTransport.java mcp-test/src/test/java/io/modelcontextprotocol/client/transport/HttpClientTransportLifecycleTests.java
git commit -m "fix: Streamable HTTP transport 关闭时释放 HttpClient 资源 (#547)"
```

---

## Task 2 — SSE transport 接入 HttpClientCloser（修 #547）

**Files**: `HttpClientSseClientTransport.java`（改）、`HttpClientTransportLifecycleTests.java`（填充 SseTransport 嵌套类）
**Why**: SSE transport 同样泄漏（已 @Deprecated 但 fork 内部仍用）；与 Streamable 对称修复。
**Change Necessity / Impact**: 同 Task 1。

### 步骤

- [ ] **2.1 写失败测试**（填充 `SseTransport` 嵌套类）：
```java
@Nested
class SseTransport {

	@Test
	void closeGracefullyDoesNotBreakShutdownPath() {
		var transport = HttpClientSseClientTransport.builder("http://localhost:1").build();
		transport.closeGracefully().block(); // 不抛即通过
	}

	@Test
	@org.junit.jupiter.api.condition.EnabledForJreRange(
			min = org.junit.jupiter.api.condition.JRE.JAVA_21)
	void internalHttpClientIsClosedOnGracefulClose() {
		var transport = HttpClientSseClientTransport.builder("http://localhost:1").build();
		HttpClient internal = (HttpClient) field(transport, "httpClient");
		transport.closeGracefully().block();
		var request = java.net.http.HttpRequest
			.newBuilder(java.net.URI.create("http://localhost:1/sse")).GET().build();
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> internal
				.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding()).join())
			.hasMessageContaining("closed");
	}

	@Test
	void injectedHttpClientIsNotClosed() {
		// Task 4 填充：注入 HC 后 closeGracefully 不关它
	}

}
```

- [ ] **2.2 跑测试确认 RED**：
```bash
./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientTransportLifecycleTests$SseTransport
```

- [ ] **2.3 实现**（与 Task 1 对称）：

字段（:101 `private final HttpClient httpClient;` 之后）：
```java
private final boolean externalClient;
```

构造（:144 末尾加 `boolean externalClient`，赋值）：
```java
HttpClientSseClientTransport(HttpClient httpClient, HttpRequest.Builder requestBuilder, String baseUri,
		String sseEndpoint, McpJsonMapper jsonMapper, McpAsyncHttpClientRequestCustomizer httpRequestCustomizer,
		SseMessageEndpointValidator messageEndpointValidator, boolean externalClient) {
	// ... 现有 Assert / 赋值不变 ...
	this.externalClient = externalClient;
}
```

closeGracefully（:506，Mono.fromRunnable 末尾追加）：
```java
@Override
public Mono<Void> closeGracefully() {
	return Mono.fromRunnable(() -> {
		isClosing = true;
		Disposable subscription = sseSubscription.get();
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
		}
		if (!externalClient) {
			HttpClientCloser.close(this.httpClient);
		}
	});
}
```

build()（:333，传 `false`，Task 4 再接）：
```java
public HttpClientSseClientTransport build() {
	HttpClient httpClient = this.clientBuilder.connectTimeout(this.connectTimeout).build();
	return new HttpClientSseClientTransport(httpClient, requestBuilder, baseUri, sseEndpoint,
			jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, httpRequestCustomizer,
			messageEndpointValidator, false);
}
```

- [ ] **2.4 格式化 + GREEN**：
```bash
./mvnw.cmd spring-javaformat:apply
./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientTransportLifecycleTests$SseTransport
```
- [ ] **2.5 commit**：
```bash
git add mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientSseClientTransport.java mcp-test/src/test/java/io/modelcontextprotocol/client/transport/HttpClientTransportLifecycleTests.java
git commit -m "fix: SSE transport 关闭时释放 HttpClient 资源 (#547)"
```

**✅ 至此 #547 已修复**（两个 transport 的内部 HC 都在 closeGracefully 被关）。Task 3/4 是注入口配套。

---

## Task 3 — Streamable Builder 加 `httpClient()` 注入口

**Files**: `HttpClientStreamableHttpTransport.java`（改 Builder + build）、`HttpClientTransportLifecycleTests.java`
**Why**: 支持复用外部 HttpClient（连接池/认证/代理/自定义 Executor）。
**Change Necessity**: 无注入口则调用方无法传入；最小边界 = Builder 加字段+方法、build() 设 externalClient。

### 步骤

- [ ] **3.1 写失败测试**（StreamableTransport 嵌套类加方法）：
```java
@Test
void injectedHttpClientIsUsedAndNotClosed() {
	HttpClient injected = HttpClient.newHttpClient();
	var transport = HttpClientStreamableHttpTransport.builder("http://localhost:1")
		.httpClient(injected).build();
	assertThat(field(transport, "httpClient")).isSameAs(injected);
	assertThat(field(transport, "externalClient")).isEqualTo(true);
	transport.closeGracefully().block();
	// JDK≥21：注入的 HC 不应被关（仍可发请求）—— 用 EnabledForJreRange 单独验证，此处仅断言字段
}
```

- [ ] **3.2 RED**：
```bash
./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientTransportLifecycleTests$StreamableTransport#injectedHttpClientIsUsedAndNotClosed
```
（预期：编译失败 —— `httpClient(...)` 方法不存在）

- [ ] **3.3 实现**：

Builder 加字段（:728 `authorizationErrorHandler` 字段附近）与方法：
```java
/** 注入的外部 HttpClient；null = 走 clientBuilder 内部构建（默认）。 */
private HttpClient httpClient;
```
```java
/**
 * 注入一个外部 HttpClient。注入后，{@link #clientBuilder(HttpClient.Builder)}、
 * {@link #customizeClient(Consumer)}、{@link #connectTimeout(Duration)} 将被忽略
 * （注入优先），且该 HttpClient 的生命周期由调用方管理 —— {@code closeGracefully()} 不会关闭它。
 * @param httpClient 外部 HttpClient，不能为 null
 * @return this builder
 */
public Builder httpClient(HttpClient httpClient) {
	Assert.notNull(httpClient, "httpClient must not be null");
	this.httpClient = httpClient;
	return this;
}
```

build()（:933，替换 Task 1 的临时 `false`）：
```java
public HttpClientStreamableHttpTransport build() {
	boolean externalClient = this.httpClient != null;
	HttpClient httpClient = externalClient ? this.httpClient
			: this.clientBuilder.connectTimeout(this.connectTimeout).build();
	return new HttpClientStreamableHttpTransport(jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper,
			httpClient, requestBuilder, baseUri, endpoint, resumableStreams, openConnectionOnStartup,
			httpRequestCustomizer, authorizationErrorHandler, supportedProtocolVersions, externalClient);
}
```

- [ ] **3.4 格式化 + GREEN**：
```bash
./mvnw.cmd spring-javaformat:apply
./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientTransportLifecycleTests$StreamableTransport
```
- [ ] **3.5 commit**：
```bash
git add mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientStreamableHttpTransport.java mcp-test/src/test/java/io/modelcontextprotocol/client/transport/HttpClientTransportLifecycleTests.java
git commit -m "feat: Streamable HTTP transport 支持注入外部 HttpClient"
```

---

## Task 4 — SSE Builder 加 `httpClient()` 注入口

**Files**: `HttpClientSseClientTransport.java`（改 Builder + build）、`HttpClientTransportLifecycleTests.java`（填充 SseTransport 的注入测试）
**Why / Change Necessity**: 与 Task 3 对称。

### 步骤

- [ ] **4.1 写失败测试**（填充 2.1 留空的 `injectedHttpClientIsNotClosed`）：
```java
@Test
void injectedHttpClientIsNotClosed() {
	HttpClient injected = HttpClient.newHttpClient();
	var transport = HttpClientSseClientTransport.builder("http://localhost:1")
		.httpClient(injected).build();
	assertThat(field(transport, "httpClient")).isSameAs(injected);
	assertThat(field(transport, "externalClient")).isEqualTo(true);
	transport.closeGracefully().block();
}
```

- [ ] **4.2 RED**：`./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientTransportLifecycleTests$SseTransport`

- [ ] **4.3 实现**（与 Task 3 对称）：

Builder 加字段（:196 `messageEndpointValidator` 字段附近）：
```java
private HttpClient httpClient;
```
与方法（含注入优先的 javadoc，同 Task 3 措辞）：
```java
public Builder httpClient(HttpClient httpClient) {
	Assert.notNull(httpClient, "httpClient must not be null");
	this.httpClient = httpClient;
	return this;
}
```

build()（:333，替换 Task 2 的临时 `false`）：
```java
public HttpClientSseClientTransport build() {
	boolean externalClient = this.httpClient != null;
	HttpClient httpClient = externalClient ? this.httpClient
			: this.clientBuilder.connectTimeout(this.connectTimeout).build();
	return new HttpClientSseClientTransport(httpClient, requestBuilder, baseUri, sseEndpoint,
			jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, httpRequestCustomizer,
			messageEndpointValidator, externalClient);
}
```

- [ ] **4.4 格式化 + GREEN**：
```bash
./mvnw.cmd spring-javaformat:apply
./mvnw.cmd -pl mcp-test -am test -Dtest=HttpClientTransportLifecycleTests
```
（全量跑 lifecycle 测试）

- [ ] **4.5 commit**：
```bash
git add mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientSseClientTransport.java mcp-test/src/test/java/io/modelcontextprotocol/client/transport/HttpClientTransportLifecycleTests.java
git commit -m "feat: SSE transport 支持注入外部 HttpClient"
```

---

## Risks / Rollback / Retirement

**风险**:
1. **CI 跑 JDK 17，`internalHttpClientIsClosed*` 测试被 `@EnabledForJreRange(JAVA_21)` 跳过** —— 修复
   的"真生效"只在本地 JDK≥21 验证。缓解：`closeGracefullyDoesNotBreakShutdownPath`（JDK 无关）在 CI
   守住"不破坏 shutdown 路径"；closer 逻辑本身已被其 javadoc 和反射降级路径覆盖。
2. **`HttpClient.close()` 在 JDK≥21 可能短暂阻塞**（等 in-flight）—— 未加 `subscribeOn`（YAGNI，保持
   现状 closeGracefully 模式）。若实测阻塞，后续 task 加 `subscribeOn(Schedulers.boundedElastic())`。
3. **反射读 private 字段的白盒测试**脆弱于字段改名 —— 接受（字段是 fork 内部契约，改名概率低）。

**Rollback**: 每个 Task 独立 commit；回滚单个 transport 用 `git revert <sha>`。Task 1/2 是修复（回滚
= 恢复泄漏），Task 3/4 是扩展（回滚 = 失去注入口）。

**Retirement Track**:
- 旧 owner/fallback: 无 —— `HttpClientCloser` 是新接入（之前零引用），不存在旧 close 路径要退役。
- 现状"closeGracefully 不关 HC"的行为被替换为"关内部 HC" —— 这是 bug 修复本身，非兼容性破坏
  （closeGracefully 关闭自有资源是 transport 的应有职责，见 `StdioClientTransport` 先例）。

**ADR 信号**（沿用 spec 附录 D）: 反射而非升 Java 基线、externalClient 语义边界 —— fork 内部不建正式 ADR。

---

## Self-Review

1. **Spec coverage**: G1 注入口 → Task 3/4；G2 修泄漏 → Task 1/2；G3 向后兼容 → build() 未注入走原路径（Task 1/2 传 false 不变行为，Task 3/4 引入条件但 null 时回退）。✅
2. **Placeholder**: 2.1 的 `injectedHttpClientIsNotClosed` 留空 → Task 4.1 填充，已标。无 TBD。✅
3. **Type consistency**: 构造末尾 `boolean externalClient` 在两个 transport 一致；build() 传值一致。✅
4. **Compatibility**: 边界 1–5 在 Compatibility Boundary 段标注；Task 1/2 的 build 临时传 false 保证未注入零行为变化。✅
5. **Change necessity**: Change Necessity 段 + 每 Task 说明。✅
6. **Existence check**: `reuse-existing`（HttpClientCloser），已修正 spec 错误前提。✅
7. **Complexity/minimality**: edit-in-place，两文件各 ~10 行，不抽基类。✅
8. **Architecture integrity**: closer 唯一 owner，transport 只决策"要不要关"。✅
9. **Verification**: 每 Task 有 exact `./mvnw.cmd` 命令。✅
10. **Dual-track/ADR**: 修复 + 扩展分 Task；ADR 信号保留。✅

**spec 勘误**: spec §4.1d 的内联反射代码块、§8 开放项 1 的"倾向内联"基于"无现成 util"的错误前提。
plan 改为复用 `HttpClientCloser`（已存在、零引用）。建议用户在 spec 同步加一行注记指向本 plan 的
Existence Check。
