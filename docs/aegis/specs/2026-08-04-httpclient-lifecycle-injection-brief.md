# Spec Brief: HttpClient 生命周期管理 + 外部注入

- 日期: `2026-08-04`
- 状态: `待用户 review`
- 类型: Spec Brief（medium，fork 内部使用）
- 涉及模块: `mcp-core` — `client/transport`
- 关联: 上游 issue #547（HttpClient 从不被关闭，资源泄漏）

---

## 1. 背景与问题

两个客户端 transport 类各自在 `Builder.build()` 里 `HttpClient.Builder.build()` 出一个
`java.net.http.HttpClient` 实例，存为 `private final HttpClient httpClient` 字段。问题有二：

1. **无法注入外部 HttpClient**：调用方无法复用已有的 HttpClient（连接池、认证、代理、自定义
   `Executor` 等），每个 transport 强制新建一个。
2. **HttpClient 资源泄漏（#547）**：`closeGracefully()` 从不关闭内部 HttpClient。

   - `HttpClientSseClientTransport#closeGracefully()`（:505-513）：只置 `isClosing` + dispose SSE
     subscription。
   - `HttpClientStreamableHttpTransport#closeGracefully()`（:232-243）：只 close transport session。
   - 全 repo 零处 `httpClient.close()` 调用（已搜证）。对比 `StdioClientTransport#closeGracefully()`
     会 `process.destroy()` + dispose 3 个 scheduler —— 印证 HttpClient 不被关是异常，不是设计意图。

**根因约束**：`java.net.http.HttpClient.close()` 是 **Java 21+** 才有的方法（Java 21 起
HttpClient 才 implements `AutoCloseable`）。本 SDK `pom.xml:57` 锁 `<java.version>17</java.version>`，
所以无法直接写 `httpClient.close()` —— 这正是 #547 至今未修的技术根因之一。

## 2. 目标

| # | 目标 | 验证方式 |
|---|---|---|
| G1 | 支持向两个 transport 注入外部 HttpClient | 单测：注入 mock/spy HttpClient，断言它被实际使用 |
| G2 | 修复内部 HttpClient 的资源泄漏（JDK ≥21 运行时） | 单测：内部构建的 HC，closeGracefully 后反射验证 close() 被调用 |
| G3 | 向后兼容：不注入时行为与现状完全一致 | 现有用例不回归 |

## 3. 非目标

- **不**升 Java 基线（保持 `java.version=17`，不动 pom / CI / 文档 / 版本号）。
- **不**修复 JDK 17–20 运行时的 selector 线程 GC 语义 —— 反射降级为 debug 日志 + 靠 GC，这是
  Java 21 前HttpClient 的既定行为，本 spec 接受。
- **不**向上游发 PR（受 `AGENTS.md` AI-agent 贡献策略约束）。本改动为 fork 内部使用。
- **不**改动 `clientBuilder()` / `customizeClient()` / `connectTimeout()` 的现有语义或签名。

## 4. 方案设计

**核心思路**：`Builder` 新增 `httpClient(HttpClient)` 注入口；transport 持有 `externalClient`
布尔字段区分"外部注入 / 内部构建"；`closeGracefully()` 用反射调 `HttpClient.close()` 关闭内部
HttpClient，外部注入的不关。反射让编译目标保持 Java 17，运行时 ≥21 才真正生效。

### 4.1 两个 transport 的对称改法（每个 3 处 + 字段）

**(a) Builder 加注入口**

```java
// HttpClientSseClientTransport.Builder / HttpClientStreamableHttpTransport.Builder 各加
private HttpClient httpClient; // null = 走原 clientBuilder 路径（默认，向后兼容）

/**
 * 注入一个外部 HttpClient。注入后，{@link #clientBuilder(HttpClient.Builder)}、
 * {@link #customizeClient(Consumer)}、{@link #connectTimeout(Duration)} 将被忽略
 * （注入优先），且该 HttpClient 的生命周期由调用方管理 —— closeGracefully() 不会关闭它。
 */
public Builder httpClient(HttpClient httpClient) {
    Assert.notNull(httpClient, "httpClient must not be null");
    this.httpClient = httpClient;
    return this;
}
```

**(b) `build()` 判断注入 vs 内部构建**

```java
// SSE build()（原 :333-338）
public HttpClientSseClientTransport build() {
    boolean externalClient = this.httpClient != null;
    HttpClient httpClient = externalClient ? this.httpClient
            : this.clientBuilder.connectTimeout(this.connectTimeout).build();
    return new HttpClientSseClientTransport(httpClient, requestBuilder, baseUri, sseEndpoint,
            jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, httpRequestCustomizer,
            messageEndpointValidator, externalClient); // 末尾新增参数
}
```

Streamable 的 `build()`（原 :933-938）同理，`externalClient` 传入私有构造的末尾参数。

**(c) transport 加 `externalClient` 字段 + 构造参数**

```java
private final boolean externalClient; // true = 外部注入，不归本 transport 管 lifecycle
// 构造函数末尾新增 boolean externalClient 参数并赋值
```

- SSE 构造是 package-private（:144），Streamable 构造是 `private`（:141）—— 加参数**不破坏公开 API**。

**(d) `closeGracefully()` 反射关内部 HttpClient**

反射逻辑示意（每个 transport 在自己的 `closeGracefully()` 末尾内联；是否抽公共 util 见 §8
开放项 1）：

```java
// SSE closeGracefully()（原 :505-513），在现有 isClosing / dispose 逻辑后追加：
// Streamable closeGracefully()（原 :232-243），在 session.closeGracefully() 链后同理追加：
if (!externalClient) {
	try {
		HttpClient.class.getMethod("close").invoke(this.httpClient);
	}
	catch (NoSuchMethodException e) {
		logger.debug("HttpClient.close() unavailable on JDK {}; relying on GC for cleanup",
				System.getProperty("java.version"));
	}
	catch (java.lang.reflect.InvocationTargetException e) {
		logger.warn("HttpClient.close() threw", e.getCause());
	}
	catch (Exception e) {
		logger.warn("Failed to close HttpClient", e);
	}
}
```

### 4.2 已确认的设计决策

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| D1 | 注入 HC 时 `clientBuilder`/`customizeClient`/`connectTimeout` 的处理 | **静默忽略 + javadoc 声明优先级**（注入优先） | `clientBuilder` 字段有默认值（非 null），无法用 null 判断"是否被设过"；加 Assert 需引入额外 flag，违反最小 API 表面 |
| D2 | JDK<21 反射找不到 `close()` 时 | **`debug` 日志 + 靠 GC** | 预期降级非错误；21+ 运行时日志干净，17–20 运行时 debug 可见 |
| D3 | `externalClient` 字段位置 | **进 transport 构造函数** | 让 `closeGracefully` 守卫语义自解释，不靠注释；构造非 public，无 API 破坏 |
| D4 | Java 基线 | **保持 17，走反射**（方案 B） | 避免升基线的连锁改动（pom/CI/文档/版本号）；fork 内部使用 |

## 5. 影响文件清单（scope 边界）

| 文件 | 改动 | 行数估计 |
|---|---|---|
| `mcp-core/.../HttpClientSseClientTransport.java` | Builder 字段+方法、build()、构造参数、externalClient 字段、closeGracefully 反射 | ~15-20 行 |
| `mcp-core/.../HttpClientStreamableHttpTransport.java` | 同上（对称） | ~15-20 行 |
| **不改**: `pom.xml`、CI workflow、`CLAUDE.md`/`AGENTS.md`/`CONTRIBUTING.md`、版本号 | — | — |

**改动量估计**：两个文件合计约 30-40 行（含 javadoc 与反射样板）。反射样板若抽到公共 util 可更省，
但会新增一个公开/包内入口 —— 留待 writing-plans 阶段权衡（见 §6）。

## 6. 验收标准

1. **注入路径**：`.httpClient(hc)` 后，transport 内部 `sendAsync` 等调用走传入的 `hc` 实例。
2. **向后兼容**：不调 `.httpClient(...)` 时，`build()` 行为与现状逐字一致（走 `clientBuilder`）。
3. **泄漏修复（内部 HC，JDK≥21 运行时）**：`closeGracefully()` 后，反射验证 `HttpClient.close()`
   被调用一次。
4. **外部 HC 不被关**：`.httpClient(hc)` 注入后，`closeGracefully()` **不**调用 `hc.close()`。
5. **JDK<21 降级**：反射抛 `NoSuchMethodException` 时打 `debug` 日志，**不**抛异常、**不** `warn`。
6. **两 transport 对称**：上述 1–5 行为在 SSE 和 Streamable 上一致。

测试落位：`mcp-test` 模块（按 `CLAUDE.md`，需要真实 JSON 序列化的集成测试放 mcp-test；纯反射逻辑的
单测可放 `mcp-core` 的 test，但 HttpClient 行为验证倾向 mcp-test）。具体落位在 writing-plans 定。

## 7. 风险与权衡

| 风险 | 严重度 | 缓解 |
|---|---|---|
| `HttpClient.close()`（Java 21）可能短暂阻塞等待 in-flight 请求 | 中 | `closeGracefully()` 返回 `Mono<Void>`，调用方可异步订阅；是否需 `subscribeOn(boundedElastic)` 在 writing-plans 定 |
| 反射 `InvocationTargetException` 吞掉真实异常 | 低 | 显式 unwrap `getCause()` 并 `warn`（见 4.1d 代码） |
| SSE transport 已 `@Deprecated` | 低 | fork 内部仍在用，改动值得；与 Streamable 保持对称降低维护负担 |
| 运行时 JDK<21 时修复"名义生效实际靠 GC" | 中 | spec 据实声明（§3 非目标）；用户未指定运行时版本，反射方案跨版本自适应 |

## 8. 开放项（留待 writing-plans）

- **反射样板抽不抽公共 util**：两处重复 ~10 行。抽 util 新增一个包内/公开入口（需过"最小 API 表面"
  审视）；内联则两份重复。倾向先内联，若 review 认为重复刺眼再抽。
- **closeGracefully 是否切线程**：见 §7 风险 1。
- **测试落位**：mcp-core test vs mcp-test，见 §6。

---

## 附录 A: TaskIntentDraft

- **结果（Outcome）**：两个 transport 支持注入外部 HttpClient；内部 HttpClient 在 JDK≥21 运行时被
  正确关闭。
- **目标（Goal）**：G1 注入口 + G2 修泄漏 + G3 向后兼容（见 §2）。
- **成功证据（Success evidence）**：§6 验收标准 1–6 全部通过。
- **停止条件（Stop condition）**：§6 六条验收达成 + 现有用例不回归。
- **非目标（Non-goals）**：§3。
- **范围（Scope）**：仅两个 transport 类（§5）。
- **风险（Risks）**：§7。

## 附录 B: BaselineUsageDraft

- **Required baseline refs**: `CLAUDE.md`（Java 17+ / 模块拓扑 / 最小 API 表面）、`AGENTS.md`
  （AI-agent 贡献策略）、`pom.xml:57`（java.version=17）。
- **Delivered context refs**: 两个 transport 源文件全文、`StdioClientTransport#closeGracefully()`、
  全 repo `httpClient.close` 搜索结果（零匹配）。
- **Acknowledged before plan refs**: `VERSIONING.md`（升 LTS 算破坏性 → 本方案规避）、
  `CONTRIBUTING.md`（record 演进规则 → 本方案不触及 McpSchema，不适用）。
- **Cited in design refs**: 见 §1、§4 行号引用。
- **Missing refs**: 上游 #547 issue 的确切描述（repo 内无引用，基于代码行为推断；与用户陈述一致）。
- **Decision**: `continue`（约束已坐实，设计可推进）。

## 附录 C: ImpactStatementDraft

- **Affected layers**: `mcp-core` / `client/transport`，两个文件。
- **Owners**: fork 内部，无跨 owner 协调。
- **Invariants**:
  - 不注入时 `build()` 行为逐字不变（向后兼容）。
  - `closeGracefully()` 对外部 HttpClient 零触碰。
  - Java 17 编译目标不变。
- **Compat**: 向后兼容（新增 Builder 方法 + 构造末尾参数；构造非 public）。无签名删除、无语义反转。
- **Non-goals**: §3。

## 附录 D: ADR 信号

- **信号类型**: 轻量公开契约扩展（新增 `Builder.httpClient(HttpClient)` 公开方法 + transport
  私有 `externalClient` 字段）。
- **是否需正式 ADR**: 否。fork 内部使用、Spec Brief 级、向后兼容（无签名删除/语义反转）。
- **决策回填点**（若将来 fork 对外发布或升 Java 基线时补 ADR）：
  - **为何反射而非升 `java.version` 到 21**：升基线连锁 pom/CI/文档/版本号，fork 内部不需要；
    反射方案编译目标保 17、运行时 ≥21 确定生效、<21 优雅降级。
  - **`externalClient` 字段的语义边界**：外部注入 = 调用方全权管理 HC lifecycle，transport
    `closeGracefully()` 零触碰。
