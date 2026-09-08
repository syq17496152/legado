# TTS 朗读引擎统一优化（optimize-tts-engine）

> 状态：🔄 设计中 ｜ 创建日期：2026-09-06 ｜ 类型：Bug 修复 + 架构优化 ｜ 预估影响：20-25 文件

## 功能概述

本变更修复"朗读引擎切换完全不生效（只能系统默认）"的 Bug，并对朗读引擎体系做统一架构优化。Bug 根因是写读分裂：UI 层 `SpeakEngineDialog` 写入 `SpeechRoute` JSON，而服务层 `TTSReadAloudService.initTts` 按 legacy `SelectItem` 解析、`ReadAloud.getReadAloudClass` 按纯数字判断，导致系统引擎恒回退默认、HttpTTS 永不生效、朗读中切换不重建服务。本变更做什么：以 `SpeechRoute` 为唯一协议统一引擎路由（修复切换 Bug）、引入脚本引擎协议（JS 三函数契约 + Rhino 沙箱）、深度适配 MultiTTS/CloneTTS 本地引擎、提供在线 TTS 内置模板库。参考三版本的取舍原则：采纳 archive 原版的三态分派与失败自愈、NG 的脚本协议与并发/原子缓存、C 版的系统引擎应用内直选与失败明示；规避 NG 的 DROP 迁移丢数据、C 版的硬编码代理端点、以及共同的大文件巨石倾向。

## 三版本取舍

| 来源 | 采纳 | 规避 |
|------|------|------|
| archive 原版 3.24.x | 三态分派、clearTTS+initTts 重建、speak ERROR 自愈、缓存 key 含语速 | init 无超时静默失效、整章一次性入队、在线串行下载 |
| NG | 脚本引擎协议、双层并发配额、原子缓存写（.part+rename）、能力契约 | DROP TABLE 丢数据、上帝文件、内置代理硬编码端点且默认启用 |
| C 版 | 系统引擎应用内直选、MultiTTS 一等公民、失败明示回退、保留 HttpTTS 实体兼容 | 硬编码第三方 IP、巨石服务文件、脚本无沙箱 |

## 核心能力清单

| # | 能力 | 说明 |
|---|------|------|
| 1 | 引擎切换修复 | 统一 resolveSpeechRoute 解析与 upReadAloudClass 切换语义，系统引擎/在线 TTS 切换均即时生效（stop→重算→续播） |
| 2 | 系统引擎应用内直选 | 枚举系统 TTS 引擎生成结构化 SpeechRoute，每引擎语速/音调/音量独立参数 |
| 3 | 脚本引擎协议 | JS 头注 @schema/@capabilities + options()/voices()/synthesize() 三函数，Rhino 沙箱执行，复用 HttpReadAloudService 合成链 |
| 4 | 内置模板库 | MultiTTS 转发器 / CloneTTS / OpenAI 兼容 / Edge 代理 4 个模板，默认停用、用户确认逐个导入 |
| 5 | CloneTTS 一键导入 | 解析 /api/legado/all 返回的引擎集束 JSON，批量导入为 httpTTS 记录 |
| 6 | init 超时降级明示 | initTts 增加超时看门狗（约 8s），超时/失败回退默认引擎并 toast 明示，禁止静默失效（含降级终止条件防死循环） |
| 7 | 缓存键能力协商 | 缓存键含引擎类型+voice+speed+volume+pitch+capabilities 声明维度，未声明维度不进键；缓存写 .part+rename 原子发布 |
| 8 | 存量数据兼容 | legacy SelectItem/纯数字 id 映射兼容，httpTTS 仅 ALTER TABLE 增列（type/script），不丢用户数据 |
| 9 | 多角色 TTS 范式模板层（AD-09） | 四层架构（基础服务层/适配层/范式模板配置层/AI 多角色）；内置 4 模板默认启用（旁白对白双声/男女对读/MultiTTS 透传/单声）；自定义模板 JSON 导入导出分享；本期同通道约束（模板内声源引擎类型一致）+拆期（期1=模板层+单实例逐段 setVoice+面板入口；期2=HTTP/script 按段换源+书级覆盖+AI 链接入） |

## 文档索引

| 文档 | 核心内容 |
|------|----------|
| [spec.md](./spec.md) | 需求规格：用户故事、功能需求与验收标准，含全局思考检查清单六维盘点 |
| [design.md](./design.md) | 技术设计：AD-01~09 架构决策（ADR Y-Statement）、SpeechRoute 协议与脚本契约详设、多角色 TTS 范式模板层实施蓝图 |
| [tasks.md](./tasks.md) | 实施任务清单：分阶段任务拆解，含 L1/L2/L3 分级标注与真机验证点 |

## 影响范围

| 层 | 变更内容 |
|----|----------|
| 模型层 | `model/ReadAloud.kt`（路由解析重构、去 runBlocking）、`data/entities/HttpTTS.kt`（type/script 两列）+ `AppDatabase` migration、`help/readaloud/casting/`（AD-09 范式模板层：TtsCastingStore/TtsTagSplitter/TtsVoiceSource）+ `data/entities/TtsCastingTemplate.kt`+Dao |
| 服务层 | `TTSReadAloudService`（init 超时/降级/引擎参数、多人模式单实例逐段 setVoice 消费）、`HttpReadAloudService`（script 接入/缓存键/原子写、按段声源装配·期2）、新增 `help/readaloud/script/TtsScriptEngineClient` |
| UI 层 | `SpeakEngineDialog`/`SpeakEngineViewModel`（模板导入入口+死代码清理）、`ReadAloudPlayerPanel`（统一 upReadAloudClass）、`SpeechVoiceRoutePicker`、`HttpTtsEditDialog`（script 编辑域）、`AiChatSpeechPlayer`/多角色候选消费点过滤（type==1） |
| assets | 新增 `app/src/main/assets/defaultData/tts/` 下 4 个内置模板 JS |

## 变更日志

| 日期 | 变更 | 作者 |
|------|------|------|
| 2026-09-08 | AD-09 v1.2 多角色分层架构（基础服务层/适配层/范式模板配置层/AI 复用层）+实现蓝图+学习融会贯通矩阵+五轮红队修复（2 P0：同通道约束/UtteranceResult sealed） | AI |
| 2026-09-06 | 二轮三方交叉审查修复（3 P0+若干 P1/P2 落盘） | AI |
| 2026-09-06 | 初版设计 | AI |

## 关键约束

1. **无 DROP 迁移**：httpTTS 仅 ALTER TABLE 增列，数据库 version 递增，覆盖安装真机验证，存量数据零丢失
2. **脚本沙箱**：脚本引擎强制走 P0 Rhino 沙箱（`RhinoClassShutter` 类访问白名单 + `SourceSandboxExtensions` 文件沙箱），文件访问收口 `BookSourceStorageScope`；注意 HttpTTS 非 BookSource，类策略须显式启用（见 design §3.3 沙箱接入）；脚本执行 10s 超时 + 体积限额（synthesized URL≤8KB、请求体≤256KB、脚本源码≤512KB）
3. **模板默认策略（两类区分，2026-09-08 AD-09）**：**引擎模板**（type=2 JS：MultiTTS 转发/CloneTTS/OpenAI/Edge）默认停用，不硬编码任何第三方端点/IP，端点留空由用户填写并确认启用；**范式选角模板**（AD-09 casting：旁白对白双声/男女对读/透传/单声）内置 4 个默认启用；多人模板内声源同通道约束（保存/导入校验）
4. **项目惯例**：Coroutine.async{} 链式协程、kotlin.runCatching、NoStackTraceException、AppLog.put 日志、禁 Timber，遵循现有代码风格

## 参考

- 设计简报：`temp/tts-design-brief.md`（temp/ 不入库，四文档定稿后可删除）
- 原版 TTS 架构分析：`temp/forks-analysis/legado-archive/TTS_ANALYSIS_REPORT.md`
- 相关规范：`docs/project-rules/database-migration-safety.md`、`docs/project-flow/architecture/rule-engine.md`
