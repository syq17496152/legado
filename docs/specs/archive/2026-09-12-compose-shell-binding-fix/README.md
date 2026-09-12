# compose-shell-binding-fix — 合成 ViewBinding 空壳自递归崩溃修复 + 模式收敛

> **状态：设计完成（待用户审核）** ｜ 创建：2026-09-12 ｜ 路径判定：扩展路径

## 功能概述

真机日志（2026-09-12 上交 zip）深度排查发现两类问题：

1. **P0 崩溃**：订阅源统一搜索 → 点结果进 `RssArticleInfoActivity` **必崩**（StackOverflowError）。
   根因已用 dexdump 反编译实锤：匿名 ViewBinding 对象内 `getRoot() = root` 解析到 Java 接口
   `getRoot()` 的 Kotlin 合成属性 `root`（即它自己），而非外层 Activity 的类级属性。
   全仓审计（`object : ViewBinding` 匿名对象 8 处 + 命名类 SimpleViewBinding 1 处，逐一核验）确认同型潜伏雷 **2 处**：`AiImageProviderEditActivity`、
   `AiImageGalleryActivity`（日志期间均 0 次打开，未触发）。
2. **P1 日志污染**：`RssFreeGridLayoutManager` 每帧布局最多输出 3 条诊断日志（全文件 5 处调用点），单会话占比
   43%~68%，严重影响日志分析价值与 AppLog 写入开销。

本次变更：修复 3 处崩溃雷 + 将"合成壳"模式收敛为受控工厂函数（防再犯）+ RssFree 诊断日志降频（不删除，符合 2026-09-10 诊断日志保留铁律）。

**日志全量深扫另发现**（不在本 spec 修复，留后续立项）：
- **P1**：视频缓存链路 `HlsDownloader` 的 MediaMuxer 长时间写 MP4 触发系统 `MPEG4Writer` 整数溢出 native SIGABRT ×2（建议立项 `hls-download-muxer-overflow`）。
- **P2（app bug）**：视频嗅探 5 秒超时失效——阻塞 `execute()` 架空 `withTimeoutOrNull`，实测退化 60 秒，慢源点播卡 1 分钟（建议立项 `sniff-timeout-ineffective`）。
- 已排除的疑似项：header 规则 `Empty JSON string`（源脚本问题且有降级）、播放失败 403（源站鉴权）、ImgDecrypt 突发失败（慢站+高并发）。

## 文档索引

| 文档 | 内容 |
|------|------|
| [spec.md](spec.md) | Intent / Scope / Approach 三要素 / Requirements / Scenarios |
| [design.md](design.md) | 根因分析（字节码证据）/ ADR / Data Flow / File Changes |
| [tasks.md](tasks.md) | 分级任务清单（含验证标准） |

## 状态标记

- [x] 设计完成（红蓝对抗 v2 闭环）
- [x] 开发完成（2026-09-12：L1 编译过 + L2 场景一/二真机 PASS + Grep 审计过）
- [x] 最终验收（2026-09-12 用户验收通过并归档；遗留：场景三占比验证待自由布局空白修复后补、全量 E2E 待提权补跑——见 issues-found.md）

## 变更日志

- 2026-09-12 初始化：基于 2026-09-12 真机日志包（26 appLog + 4 crash + logcat）分析立项
- 2026-09-12 红蓝对抗 v2（7 项采纳）→ 开发 → 真机验证 → 用户验收通过，归档

