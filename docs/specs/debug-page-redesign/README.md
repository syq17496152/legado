# 调试源页与调试日志重构（debug-page-redesign）

> 状态：**开发完成（L1 编译+单测通过，L2 真机待做）** ｜ 创建：2026-09-11 ｜ 路径：扩展路径
> 背景：用户裁决——原批次E只在旧调试页加导出菜单"改的什么玩意"；要求学习阅读系同类软件（合集页 momoa.cc.cd）调试页/调试日志的做法，完善编辑源调试源功能与页面本身。

## 功能概述

对标 **MD3阅读（HapeLee/legado-with-MD3）** 的调试页重构成果（已逐文件精读其 Debug.kt / BookSourceDebugScreen.kt / BookSourceDebugViewModel.kt），把本项目书源/订阅源调试页从「SearchView + 魔法前缀（++/--/::）+ 无分类日志流 + 关闭即丢」重构为「结构化事件 + 分类过滤 + 类型着色卡片 + 目标 Chips + 启停 FAB」的现代调试体验；日志核心（model/Debug.kt）同步升级为结构化事件流（StateFlow），保留校验链兼容。

## MD3 对标要点（学到的优点）

| # | MD3 做法 | 本项目现状 | 采纳 |
|---|---------|-----------|------|
| 1 | Event(kind/message/timestamp/elapsedMillis) 结构化 + StateFlow/Flow 下发 | String 列表 + callback | ✅ |
| 2 | 日志过滤 Chips（全部/过程/响应/错误） | 无过滤 | ✅ |
| 3 | 按类型着色卡片（错误 errorContainer/响应 primaryContainer/完成 tertiaryContainer） | 纯文本行 | ✅ |
| 4 | 每条显示相对耗时 "+3.210s" + 绝对时间戳 | 仅相对 mm:ss.SSS 前缀 | ✅ |
| 5 | 目标 Chips（搜索/发现/详情/目录/正文）替代魔法前缀 | SearchView 里手输 ++/--/:: 前缀 | ✅ |
| 6 | 示例快捷 Chips（checkKeyWord/我的/系统/发现分类，按 target 过滤） | 帮助面板 TextView | ✅ |
| 7 | FAB 开始/停止状态切换 | 无停止按钮（只能退出页） | ✅ |
| 8 | 条目点击弹全文（MarkdownSheet） | 响应源码藏顶栏菜单 | ✅（TextDialog） |
| 9 | 自动滚动到底（仅 Running）+ 空态区分 | 无 | ✅ |
| 10 | MAX_LOG_ENTRIES=1000 takeLast | 无上限 | ✅ |
| 11 | 清空日志按钮 | 无 | ✅ |
| 12 | 会话取消（session.cancel）→ Cancelled 状态 | cancelDebug 已有 | ✅ 接入状态机 |
| 13 | 日志导出/分享 | 无（本项目批次E已加，保留并入） | ✅ 本项目独有 |

## 文档索引

| 文档 | 内容 |
|------|------|
| [spec.md](./spec.md) | 需求规范 |
| [design.md](./design.md) | 技术设计（对标分析/ADR/文件变更） |
| [tasks.md](./tasks.md) | 任务清单 |

## 变更日志

- 2026-09-11：用户批评批次E敷衍 → 拉合集页锁定对标 fork → 精读 MD3 三文件 → 创建本 spec，直接实施（用户明确指令）
