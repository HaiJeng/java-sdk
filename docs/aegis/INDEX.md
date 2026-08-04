# Aegis Workspace Index

本 fork 的 Aegis 设计产物索引。workspace 采用精简模式（仅 specs + index），不建完整
baseline 治理文件 —— fork 内部使用，scope 局部。

## specs/

- [HttpClient 生命周期管理 + 外部注入](specs/2026-08-04-httpclient-lifecycle-injection-brief.md)
  — 反射方案修复 #547 泄漏 + Builder 注入外部 HC，保持 Java 17 基线

## plans/

- [HttpClientCloser 接入 + 外部 HttpClient 注入口](plans/2026-08-04-httpclient-closer-wiring-and-injection.md)
  — TDD 实现：Task 1/2 接 HttpClientCloser 修 #547（修 bug 优先），Task 3/4 加注入口