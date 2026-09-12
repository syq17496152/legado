# 日志合规清理与规范机制化（log-compliance-cleanup）

> 状态：**开发完成（编译验收中，L2 真机待做）**（检查点 1 已通过，2026-09-11） ｜ 创建：2026-09-11 ｜ 路径：扩展路径
> 来源：2026-09-11 日志规范深度审计（Explore 结论见对话记录与 `.workbuddy/memory/2026-09-11.md`）

## 功能概述

对 2026-09-11 审计发现的五类日志违规项做一次性清理收编，并把「临时日志治理」从人肉纪律升级为机制约束：

1. **PageDebug 临时日志残留**（5 文件 7 处活跃调用，其中 5 处为 ERROR 级 `AppLog.put`）——`real-device-bugfix-0911` tasks 2.19 声称"已删"与代码现实矛盾，属未闭环交付。
2. **裸 `android.util.Log` 违反 logging_rules 规则 4**（核心模块 7 文件 23 处；上游遗留文件另列白名单不动）。
3. **CronetInterceptor 自建日志去重轮子**（lastLoggedError/lastCertError 手写 60s 去重）——F9 条款三点名的收编对象，实证为纯日志去重、与降级状态机无耦合，可安全收编 `putThrottled`。
4. **HttpHelper DNS 路径级别纪律**（retry/过滤路径走默认 ERROR 级且未节流）。
5. **字面量 tag 未收编**（AppWebDav×3、RssSourceEdit、CrashReport、DeviceInfo×2——C5 预登记清单落地）。

同时落两项规范机制化（审计建议①②）：
- **A**：验证期临时日志从"裸 Log.d + 人肉 grep 清理"改为**强制 DebugLog**（BuildConfig.DEBUG 守卫，忘清理 release 也零输出）。
- **B**：tasks.md 完成声称必须附 Grep 校验证据（防 2.19 式"声称已删实际残留"）。

## 核心能力

| 能力 | 说明 |
|------|------|
| 临时日志清零 | PageDebug 5 文件 7 处全删（含 AnalyzeUrl 的 putDebugWithTag 变体），回归 Grep 清零 |
| 裸 Log 收编 | 核心模块 23 处按语义分流：删除（验证残留）/ putDebugWithTag（过程状态）/ putWarn/putError（错误路径） |
| 频控单点化 | CronetInterceptor 三处手写去重 → AppLog.putThrottled；级别按 F9 语义表修正（降级= WARN） |
| DNS 路径治理 | retry/negative cache hit/无效地址过滤改 putThrottled/putWarn，退出 ERROR 默认 |
| 字面量 tag 收编 | 7 处字面量 → TAG 常量；AppLog 新增 5 个 TAG 常量（31→36）并同步 logging_rules 两表 |
| 规范机制化 | logging_rules.md 增补"验证期日志强制 DebugLog"条款 + 完成声称 Grep 证据门禁 + 上游遗留白名单登记 |

## 文档索引

| 文档 | 内容 |
|------|------|
| [spec.md](./spec.md) | 需求规范（Intent/Scope/Approach 三要素/Requirements/Scenarios） |
| [design.md](./design.md) | 技术设计（ADR 决策/数据流/文件变更） |
| [tasks.md](./tasks.md) | 任务清单（含 Grep 证据列） |

## 变更日志

- 2026-09-11：创建四文档（Explore=当日审计，实证齐备），待红队审查与检查点 1
