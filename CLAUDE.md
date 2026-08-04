# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> This repository already ships a thorough `AGENTS.md` (module list, contribution policy,
> McpSchema evolution rules, versioning/dependency policy, AI-agent contribution policy).
> Read it first. This file complements it with the build/test command reference and the
> big-picture architecture that spans multiple files.

## Project

Java SDK for the [Model Context Protocol](https://modelcontextprotocol.io) (MCP). Implements
both MCP **clients** and **servers**, synchronous and asynchronous, over **stdio**, **SSE**,
and **Streamable HTTP** transports. Java 17+. Maven multi-module, version `2.0.1-SNAPSHOT`,
group `io.modelcontextprotocol.sdk`.

## Build & Test

All commands use the Maven wrapper (`./mvnw`). On Windows, `./mvnw.cmd` works the same way.
Formatting (`spring-javaformat`) is enforced at the `validate` phase of **every** build, so a
formatting violation fails the build before tests run.

```bash
./mvnw clean install -DskipTests          # build all modules
./mvnw test                                # full test run (requires Docker + npx)
./mvnw verify                              # what CI runs on every PR
./mvnw spring-javaformat:apply             # auto-fix formatting violations
./mvnw -pl mcp-test -am -Pjackson2 test    # run integration tests under Jackson 2
```

Run a single test (note `mcp-core`'s own integration tests live in `mcp-test`, see below):

```bash
./mvnw -pl <module> -am test -Dtest=ClassName                       # one class
./mvnw -pl <module> -am test -Dtest=ClassName#methodName            # one method
./mvnw -pl mcp-test -am test -Dtest=ClassName#methodName            # core integration test
```

Prerequisites for the full test suite: **Docker** (Testcontainers) and **npx** (conformance
suite). CI runs plain `mvn verify` plus the `jackson2-tests` job — see `.github/workflows/ci.yml`.

## Architecture

### Layered programming model

Public APIs are expressed in **Reactive Streams** (`Publisher`/`Mono`/`Flux`), with
**Project Reactor** as the internal implementation. A thin **synchronous facade**
(`McpSyncClient`, `McpSyncServer`, `McpSyncServerExchange`) wraps each async counterpart by
blocking on the reactive result. When adding a capability, implement it on the async class
first, then expose it through the sync facade — do not put logic only in the sync layer.

### Module topology and the JSON split

- `mcp-core` — protocol types, session/transport abstractions, client/server implementation,
  and the **JSON-binding SPI** (`io.modelcontextprotocol.json`: `McpJsonMapper`,
  `McpJsonMapperSupplier`, `JsonSchemaValidator`, `TypeRef`).
- `mcp-json-jackson3` / `mcp-json-jackson2` — the Jackson implementations of that SPI, wired
  in via `ServiceLoader` (`META-INF/services/...Supplier`).
- `mcp` — pom-only convenience bundle: `mcp-core` + `mcp-json-jackson3`.
- `mcp-bom` — BOM for version alignment across modules.
- `mcp-test` — shared test fixtures **and** `mcp-core`'s integration tests.
- `conformance-tests/*` — standalone client/server apps run against the official MCP
  conformance suite via `npx`.

`mcp-core` deliberately depends only on the JSON **SPI**, never on a Jackson implementation.
Because of this, every integration test that needs real JSON serialization lives in
`mcp-test` (default: Jackson 3; switch with `-Pjackson2`). Do not add a Jackson dependency
to `mcp-core` — add the test to `mcp-test` instead.

### mcp-core package layout

- `spec/` — the protocol core. `McpSchema` (~6.5k lines) holds every JSON-RPC/MCP record and
  is the single source of truth for the wire format. `McpClientSession` / `McpServerSession`
  drive the JSON-RPC request/response/notification lifecycle; `McpStatelessServerHandler`
  and friends serve the stateless Streamable-HTTP model. Transport contracts
  (`McpTransport`, `McpServerTransportProvider`, `McpClientTransport`, `McpTransportSession`)
  and `ProtocolVersions` live here too.
- `client/` — `McpClient` (entry point), `McpAsyncClient`, `McpSyncClient`,
  `McpClientFeatures`, plus client transports (`HttpClientSseClientTransport`,
  `HttpClientStreamableHttpTransport`, `StdioClientTransport`) and `ServerParameters`.
- `server/` — `McpServer`/`McpAsyncServer`/`McpSyncServer`, `McpServerFeatures`, the
  `Mcp*ServerExchange` request-response handles, the parallel **stateless** server family
  (`McpStatelessAsyncServer`, `McpStatelessServerHandler`, …), and Servlet-based server
  transports (`HttpServletStreamableServerTransportProvider`,
  `HttpServletSseServerTransportProvider`, `StdioServerTransportProvider`).
- `json/` + `json/schema/` — the pluggable JSON mapper and JSON-Schema validator SPIs.
- `common/`, `util/` — `McpTransportContext`, `Assert`, `KeepAliveScheduler`, tool/URI
  validators.

### Transports and sessions

Client side defaults to the built-in **JDK HttpClient** (no extra deps); server side defaults
to **Jakarta Servlet**. Both are transport-agnostic at the API level. The Streamable-HTTP
model distinguishes a *stateful* path (`McpServerSession` keyed by `McpTransportSession`)
from a *stateless* path (`McpStatelessServerTransport` + `McpStatelessServerHandler`),
important for horizontally-scaled deployments. Spring WebClient/WebFlux/WebMVC transports
live in **Spring AI 2.0+**, not in this repo.

### Observability

Logging goes through **SLF4J** only (no backend bundled). Correlation/tracing state flows
through the **Reactor Context**, not thread-locals — propagate it explicitly when crossing
async boundaries.

## Critical conventions

- **`McpSchema` records ARE the wire format.** Editing a record is a protocol change, not a
  refactor. Optional vs. spec-required fields follow different rules (append-only components,
  `@JsonProperty` on every field, boxed types, no `@JsonCreator` on the canonical constructor
  for optional fields; `fromJson` factory + `Assert.notNull` for required ones). Full rules
  and the worked `ToolAnnotations` example are in `CONTRIBUTING.md` ("Evolving
  wire-serialized records") and summarized in `AGENTS.md`. **Do not guess the pattern from one
  existing field — read those sections first.**
- **Keep the API surface minimal.** Simple + minimal + concrete is a stated project
  principle; new concepts/primitives have a high bar. See `CONTRIBUTING.md` and `VERSIONING.md`
  (note: dropping a Java LTS version or a transport type counts as a breaking change).
- **Dependency bumps need a concrete reason** (security, bug, needed feature) — see
  `DEPENDENCY_POLICY.md`. Don't bump just because a newer version exists.
- **AI-agent contribution policy** (`AGENTS.md`): do **not** open issues/PRs/discussions in
  this upstream repo unless the user already has 3+ merged PRs here. Surface the policy to the
  user instead of filing; refuse to bypass it.
