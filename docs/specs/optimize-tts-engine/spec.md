# optimize-tts-engine 规格说明（spec）

> **状态**：🔄 设计中
> **生成日期**：2026-09-06
> **输入依据**：`temp/tts-design-brief.md`（权威设计简报，含根因证据与 AD-01~AD-08 决策草案）+ 本仓源码核实（ReadAloud.kt / TTSReadAloudService.kt / SpeechModels.kt）
> **后续文档**：design.md（ADR 展开）→ plan.md → tasks.md

---

## 1. Intent（意图）

### 1.1 Bug 现象

用户反馈：朗读引擎**无论怎么设置都只能使用系统默认引擎**——选择系统第三方 TTS 引擎、在线 HttpTTS 引擎均不生效，切换后朗读声音毫无变化。

### 1.2 根因：写读格式分裂（已核实证据）

引擎选择的**写入侧**与**读取侧**使用了两种互不兼容的数据格式，导致配置在传递链路上被静默丢弃：

| 环节 | 证据位置 | 问题 |
|------|---------|------|
| 写侧：设置界面写入 SpeechRoute JSON | `SpeakEngineDialog.selectRoute`（SpeakEngineDialog.kt:171-187）写 `AppConfig.ttsEngine = SpeechRoute.toJson()` | 存入的是 SpeechRoute JSON（含 engineType/engineValue 等字段） |
| 读侧：服务按 legacy SelectItem 解析 | `TTSReadAloudService.initTts`（TTSReadAloudService.kt:50-61）按 `SelectItem<String>` 解析取 `.value` | SpeechRoute JSON 中不存在 SelectItem 结构 → `.value` 恒 null → 走两参构造 `TextToSpeech(ctx, listener)` → **永远系统默认引擎** |
| HTTP 路由分派失效 | `ReadAloud.getReadAloudClass`（ReadAloud.kt:29-41）用 `StringUtils.isNumeric(ttsEngine)` 判断 | SpeechRoute JSON 非纯数字 → 恒 false → **HttpTTS 在线引擎永不生效** |
| 切换不重建服务 | 设置路径 `notifyReadAloudEngineChanged → ReadAloud.refreshReadAloudClass`（ReadAloud.kt:82-85） | 只重算 aloudClass 不停旧服务；服务仅在 onCreate 调 initTts → **朗读中切换必然不生效** |

### 1.3 伴生技术债务（简报 §2.1，随本次一并治理）

1. `ReadAloud.kt:35` `runBlocking(IO)` 在主线程查库（getReadAloudClass 可能被 UI 路径调用）。
2. `ReadAloud.httpTTS` 可变单例残留旧值，切换引擎后可能读到脏数据。
3. 书级引擎覆盖 `Book.setTtsEngine` 被两处 UI 强制写 null，功能失效。
4. `SpeechVoiceCatalogRepository.kt:131` 用"SelectItem JSON 嵌套进 engineValue"补丁生成系统引擎路由，语义混乱。
5. `SpeechModels.kt:126-131` `fromTtsEngineValue` 的 legacy 语义地雷（非 JSON 即当包名）。
6. `SpeakEngineViewModel.sysEngines` 死代码（lazy 构造临时 TextToSpeech，浪费资源且无人消费）。
7. `initTts` 无超时看门狗、`onInit` 失败仅 toast，存在"init 僵死静默失效"。

### 1.4 优化目标

1. **修复 Bug**：以 SpeechRoute 为唯一引擎协议完成读侧闭环，任何引擎选择在新朗读会话立即生效。
2. **统一引擎架构**：系统引擎 / 在线 HttpTTS / 脚本引擎收敛到统一路由分派与统一合成链，消除双轨格式。
3. **外部 TTS 生态适配**：应用内直选系统 TTS 引擎；深度适配本地 CloneTTS / MultiTTS；通过脚本引擎协议 + 内置模板库覆盖主流在线 TTS 服务（OpenAI 兼容 / Edge 代理等）。
4. **借鉴三版本优点、规避其缺点**（硬性要求）：吸收 archive 原版三态分派与自愈重建、NG 脚本协议与能力协商、C 版系统引擎直选与失败明示；规避 NG 的 DROP TABLE 迁移丢数据 / 上帝文件 / 硬编码第三方代理 IP，规避 C 版无沙箱 / 巨石 Service。

---

## 2. Scope（范围）

### 2.1 做什么（In Scope，对应设计决策 AD-01~AD-09）

1. **AD-01 引擎路由单源化（修 Bug 核心）**：SpeechRoute 成为引擎选择唯一协议。服务层统一经 `resolveSpeechRoute(raw)` **纯字符串同步解析（不查库）**，四态判定：SpeechRoute JSON（新格式）→ 直读分派；SpeechRoute JSON 且 engineType=system 且 engineValue 内嵌 JSON（双嵌套 legacy 存量）→ 解包内层 value 作包名；legacy SelectItem JSON → 取 value 作包名映射 system 路由；纯数字 → legacy HttpTTS id 映射为 http 路由。`getReadAloudClass` 按 route.engineType 同步分派 system/http（script 同走 http 服务；14 处 aloudClass 同步消费点与 4 个调用方零改动）。
2. **AD-02 切换即时生效语义**：所有切换入口（SpeakEngineDialog / 播放面板 selectTtsEngine 等）统一走 `upReadAloudClass()`——续播意图上收数据层（服务运行中捕获 `PendingSwitch(wasPlaying/pageIndex/startPos)` 存 `ReadAloud` 静态记录，面板 STOP 分支从 `ReadAloud.consumePendingSwitch()` 取续播意图）、**先重算（同步）后 stop** 消除竞态窗口；同服务类型切换不下发 STOP，改发新增 `reInitTts` IntentAction 引擎内重建（`@Synchronized` clearTTS()+initTts()）；跨类型 stop→重算→重启；`refreshReadAloudClass` 仅用于未运行时。
3. **AD-03 TTS init 超时与降级明示**：initTts 增加约 8s 超时看门狗，超时/onInit 失败 → clearTTS 回退默认引擎并 toast 明示；回调竞态用主线程 Handler 收敛。
4. **AD-04 脚本引擎协议（SCRIPT_TTS）**：HttpTTS 实体新增 `type`（Int，1=http 模板默认，2=script）与 `script`（String，JS 源码）两列，ALTER TABLE 增量迁移（version 递增，覆盖安装真机验证，实施前核实无 @DatabaseView 引用 httpTTS）。脚本契约：头注 `@name/@schema/@capabilities/@defaultSpeed` + 三函数 `options()/voices()/synthesize(text,voice,params,options,ctx)`，synthesize 返回 URL 或请求对象（本期 HTTP 轮询型）。新增 `help/readaloud/script/TtsScriptEngineClient.kt`（Rhino 执行，强制 P0 沙箱：`RhinoClassShutter` 类白名单 + `SourceSandboxExtensions` 文件沙箱，文件访问走 `BookSourceStorageScope`；注意 HttpTTS 非 BookSource，类策略须显式启用，见 design §3.3）；voices() 结果缓存进既有 speakersJson；脚本路由走现有 HttpReadAloudService 合成链。执行约束：三函数统一包 `withTimeout(10s)`；体积限额（synthesized URL≤8KB、请求体≤256KB、脚本源码≤512KB、voices 目录超限截断）；synthesize 请求对象仅承接 url/method/headers/body 四字段，越界字段拒绝导入并明示；type=2 接入点收口 `getSpeakStream`；候选过滤 type==1（type=2 不进 AI 语音/多角色候选）。
5. **AD-05 系统引擎增强**：SpeechVoiceCatalogRepository 枚举系统 TTS 引擎生成结构化 SpeechRoute（删除 SelectItem 嵌套补丁；fromTtsEngineValue 保留 legacy 兼容但新数据不再产生）；每引擎独立语速/音调/音量配置（新 PreferKey 或 SpeechRoute 扩展字段）；消除 runBlocking（路由解析不查库，httpTTS 装配收敛 HttpReadAloudService 服务内）。
6. **AD-06 内置引擎模板库**：新增 `app/src/main/assets/defaultData/tts/` 四模板：`multitts_forwarder.js`（:8774 /forward + /voices，speed 按 `{{speakSpeed*5}}` 换算（默认 10→50）、volume/pitch 按 MultiTTS 参数域换算，真机校准判据：默认语速下 MultiTTS speed 参数=50）、`clonetts.js`（:8080 /api/tts，speed 换算 `{{speakSpeed/10.0}}` 并 clamp 0.5~2.0（默认 10→1.0），支持 /api/legado/all 批量导入入口）、`openai_compat.js`（OpenAI /v1/audio/speech 兼容）、`edge_proxy_template.js`（Edge-TTS 代理模板，@enabled false、端点留空由用户填、不硬编码任何第三方 IP）。SpeakEngineDialog 增加"内置模板"导入入口（启用需用户确认，逐个导入，冲突策略 OVERWRITE/KEEP_BOTH）。
7. **AD-07 并发与缓存键增强**：保留现有 Channel 并发预下载；增加引擎级并发上限（concurrentRate 既有基建，脚本引擎默认 2）；缓存键扩展为引擎类型+voice+speed+volume+pitch+capabilities 声明维度（未声明维度不进缓存键）；缓存写 .part 临时文件 + rename 原子发布。
8. **AD-08 失败降级链与明示**：合成/引擎失败 → 当前引擎重试 1 次 → 回退默认系统引擎并 toast 明示原因 → 连续失败暂停朗读并通知；路由解析失败明示"引擎配置无效已回退"，禁止静默失效。
9. **AD-09 多角色 TTS 分层架构（2026-09-08 v1.2 用户裁决）**：基础服务层（系统引擎/本地 TTS App HTTP/在线引擎，既有零改动）→ 适配层（TtsVoiceSource 三类声源统一抽象：引擎级/provider 音色级/HTTP 参数级，音色枚举+按句换声统一接口）→ **范式模板配置层（本期新增核心）**：声明式模板 JSON（tag 分段规则+声源映射+韵律，借鉴 tts-server 范式）；**内置模板默认启用**（旁白对白双声-引号规则/男女对读/MultiTTS 对话透传/单声）+ 高级用户自定义模板与 JSON 导入导出分享（类比高亮规则体系）；L-d AI 多角色链修复服务侧路由消费并复用同一模板层。
10. **存量兼容与稳健性治理**：legacy 数据全兼容（R9）；去 runBlocking、死代码清理、RSS/AI 语音回归保障（R10）。

### 2.2 不做什么（Out of Scope，简报"明确不做"逐条）

1. **不重构 AiReadAloudRoleService 多角色 AI 链**（2026-09-08 修订）：AI 分镜/角色绑定链保持既有实现，本期仅修复其服务侧路由消费缺失（复用 AD-09 逐段路由消费机制）并保证兼容回归；**新增的 L1 双声源零配置路径为独立实现，不依赖 AI 链**（原"多角色链零改动"表述废止——服务侧路由消费修复触及该链边界）。
2. **不做 SSE / WS 流式合成**：脚本引擎本期仅支持 HTTP 轮询型，流式登记为后续增强。
3. **不做 SoundTouch 变声**：登记后续增强。
4. **不做无缝章衔接 handoff**：登记后续增强。
5. **不做 NG 式人名级 AI 分镜**（2026-09-08 修订）：L1 仅为本地启发式旁白/对白二分，人名级角色区分依赖 AI 分镜（属 L2 既有链），本期不引入 LLM 依赖。
6. **不 DROP 任何表、不丢用户存量 httpTTS 数据**：数据库只做 ALTER TABLE 增列，规避 NG 缺点。
7. **引擎模板（type=2 JS）默认全部停用**（除用户显式启用），不内置任何第三方代理端点（规避 NG/C 硬编码第三方 IP 缺点）；**AD-09 范式选角模板（casting）内置 4 个默认启用**（其中单声模板=关闭多人）。

### 2.3 影响模块清单（预估 20-25 文件）

| 类别 | 文件 |
|------|------|
| 修改 | `model/ReadAloud.kt`（路由解析重构+去 runBlocking）、`service/TTSReadAloudService.kt`（init 超时/降级/引擎参数/reInitTts）、`service/HttpReadAloudService.kt`（服务内装配+script 引擎接入+缓存键+原子写）、`help/readaloud/speech/SpeechModels.kt`（resolveSpeechRoute）、`help/readaloud/speech/SpeechVoiceCatalogRepository.kt`、`help/readaloud/speech/SpeechVoiceGroupRepository.kt`（第三处生产点+分组 key）、`ui/book/read/config/SpeakEngineDialog.kt` + `SpeakEngineViewModel.kt`（模板导入+死代码清理）、`SpeechVoiceRoutePicker.kt`、`ReadAloudPlayerPanel.kt`（统一 upReadAloudClass+consumePendingSwitch）、`ui/book/read/config/ReadAloudConfigDialog.kt`（fromTtsEngineValue 消费适配）、`SpeechRouteSanitizer.kt`（legacy 波及适配+type=2 清理核实）、`ui/main/ai/AiChatSpeechPlayer.kt` / `help/ai/AiReadAloudRoleService.kt`（候选过滤 type==1）、`ui/book/read/config/HttpTtsEditDialog.kt`（script 编辑域）、`data/entities/HttpTTS.kt`、`data/AppDatabase.kt` + migration、`AppConfig.kt`（如需新 PreferKey） |
| 新增 | `help/readaloud/script/TtsScriptEngineClient.kt`（+P0 沙箱接入）、`help/readaloud/casting/`（TtsCastingModel/CastingRuleSet/TtsCastingStore/TagSplitter/VoiceSource，AD-09 范式模板层），`data/entities/TtsCastingTemplate.kt`+Dao（Room 实体，v110 同版建表）、`assets/defaultData/tts/*.js` 4 个引擎模板+`castingTemplates.json` 内置选角模板、模板管理页+编辑表单（TtsCastingTemplateScreen）、（可选）内置模板导入小弹窗 |
| 同步 | `app/src/main/assets/updateLog.md`、`docs/INDEX.md`、`docs/project-flow/task-navigation.md`（朗读模块锚点；AGENTS.md 不动） |
| 不动 | `model/analyzeRule` 相关（脚本沙箱复用既有 BookSource 沙箱基建，不改动其实现） |

### 2.4 全局影响面盘点（六维检查清单落位）

- **前端入口**：ReadAloudPlayerPanel（引擎快捷切换+设置）、ReadAloudConfigDialog 引擎 tab、SpeakEngineDialog、SpeechVoiceRoutePicker、ReadAloudDialog、SpeakerGroupManageDialog、ReadRssActivity/RSS 朗读、AiChatSpeechPlayer（AI 聊天语音，回归验证）。
- **后端接口**：ReadAloud.ttsEngine / getReadAloudClass / upReadAloudClass、TTSReadAloudService.initTts、HttpReadAloudService 合成链、SpeechRoute 解析。
- **数据库**：httpTTS 表 ALTER TABLE 加 2 列（type/script），AppDatabase version 递增，覆盖安装必须真机验证；实施前核实无 @DatabaseView 引用 httpTTS。
- **使用场景**：文本朗读（系统引擎/在线 TTS/脚本引擎）、RSS 朗读、AI 聊天语音、朗读中切换、书级引擎覆盖（本期修复其失效 = 可选需求，见 R9-P1）。
- **回填点**：引擎目录 UI、服务路由、缓存键、SpeechRouteSanitizer 失效清理、备份链（Backup.kt httpTTS.json 导出自动含 type/script 新字段，恢复往返完整，见 R9）。

---

## 3. Approach（方法）

### 3.1 Selected Approach

**以本仓既有 SpeechRoute 模型为唯一引擎协议完成服务侧闭环（棕地优先），扩展 HttpTTS 实体承载脚本引擎（type=2 + script 列，ALTER TABLE 增量迁移），新增 TtsScriptEngineClient（Rhino + P0 沙箱），内置模板库默认停用。**

理由：最大化复用四项存量资产，规避 NG 全量重构与 C 版平行体系——

1. **SpeechRoute**：本仓已有结构化路由模型（engineType/engineValue/扩展字段）与 SpeechVoiceCatalogRepository、SpeechRouteSanitizer 生态，只补齐"服务侧读协议闭环"即可修 Bug，无需新表新协议。
2. **HttpTTS 实体**：直接加 `type`/`script` 两列即可承载脚本引擎，继承 concurrentRate / loginUrl / jsLib 等既有字段基建，避免另起炉灶。
3. **HttpReadAloudService 并发缓存基建**：Channel 并发预下载、缓存目录、ExoPlayer 播放链全复用，脚本引擎只负责"产出合成请求"，不重建合成链（规避 NG 1971 行上帝文件模式）。
4. **P0 沙箱**：复用既有 Rhino 沙箱基建（`RhinoClassShutter` 类白名单 + `SourceSandboxExtensions`/`BookSourceStorageScope` 文件沙箱），脚本引擎获得与书源同级的执行安全（规避 C 版无沙箱声明缺点）；注意 HttpTTS 非 BookSource，类策略须显式启用（见 design §3.3）。

### 3.2 Alternatives Considered

| 方案 | 否决理由 |
|------|---------|
| 全量对齐 NG 重构（TtsEngineStore 新表 + 脚本协议全套） | DROP TABLE 迁移丢用户存量数据（硬性禁做）；与本项目 P0 沙箱/命名/协程惯例冲突大；上帝文件模式（HttpReadAloudService 1971 行）违背极简哲学 |
| 全量对齐 C 版（插件注册表 + 5 引擎类型） | 闭源插件割裂；无沙箱声明；75KB 巨石 Service；平行引入第二套引擎体系与 SpeechRoute 冲突，双轨维护成本高 |
| 仅修复解析 Bug（最小改动，不统一架构） | 不满足用户"统一架构 + 外部适配"诉求；legacy SelectItem / 数字 id 双轨继续存留，后续脚本引擎、模板库、能力协商均无落脚点，演进成本更高 |
| 单独新建 TtsEngine 表并存 legacy httpTTS | 双表双轨同步复杂度高（两表数据一致性、迁移期双写）；扩展现有表加列即可继承 concurrentRate/loginUrl/jsLib 基建，无迁移丢失风险 |

### 3.3 Drawbacks（代价与接受理由）

1. **HttpTTS 实体字段语义混载**：加列后同一实体同时承载模板字段（url/header 等）与脚本字段（script），语义不再单一。——接受理由：避免 DROP/新表迁移丢数据 + 最小改动；用 `type` 字段区分语义，并在实体注释中明确约束两种取值下的字段含义。
2. **脚本引擎本期不支持 SSE/WS 流式**：只支持 HTTP 轮询型 synthesize 返回。——接受理由：流式为增强项不阻塞主目标，登记后续演进，契约设计预留扩展（schema/capabilities 头注已含协商位）。
3. **内置模板依赖外部 App/网络可用性**（MultiTTS 需保活、CloneTTS 需启动、在线服务需网络）。——接受理由：模板默认停用 + 失败明示 + AD-08 降级兜底，不会静默失效。
4. **SpeechRoute 全面接管后解析函数成为单点**：resolveSpeechRoute 需兼容四种历史格式。——接受理由：以完整测试矩阵（R9 存量兼容场景）覆盖，且 fromTtsEngineValue 保留 legacy 语义兜底。

### 3.4 Prior Art（先例借鉴）

| 来源 | 借鉴点 | 规避点 |
|------|--------|--------|
| NG（joestar817/legado_NG，2026-09-06 仍更新） | 脚本引擎协议（@name/@schema 头注 + options()/voices()/synthesize() 三函数概念；本项目 @capabilities 为自定义扩展，NG 无该字面量）、双层并发（全局+每引擎配额）、能力协商（未声明维度不进缓存键）、原子缓存写（.part+rename）、导入冲突策略 | DROP TABLE 迁移丢数据、上帝文件、内置 Edge 代理硬编码第三方 IP 且默认启用、多角色强依赖 LLM |
| C 版（CCSSNE/legadoC own 分支） | 系统引擎应用内直选（PackageManager 查 INTENT_ACTION_TTS_SERVICE + TextToSpeech(ctx,cb,enginePackage)）、MultiTTS 转发器一等公民（localhost:8774）、失败明示（unavailableReason/回退通知）、保留 HttpTTS 实体兼容 | next_edge_proxy.js 默认启用硬编码 IP、BaseReadAloudService 75KB 巨石、脚本无沙箱声明 |
| archive 原版 3.24.x（821938089/legado 快照） | 三态分派（空/SelectItem JSON/数字 id）、clearTTS+initTts 重建、speak ERROR 自愈、缓存 key 含语速 | 整章一次性 QUEUE_ADD、init 无超时、在线串行、onError 空、binder 回调竞态 |
| CloneTTS 官方（sipeter/CloneTTS） | `/api/legado/all` 一键返回全部音色的 httpTTS 引擎集束 JSON（官方"网络导入"支持）、speed 倍速语义（0.5~2.0，与 legado 0~100 需 /10.0 换算） | — |

---

## 4. Requirements（需求）

> 优先级：P0 = 本期必须（修 Bug 主线），P1 = 本期应完成（增强项，允许顺延但不删契约）。

### R1 引擎切换修复（P0）

- **描述**：设置界面与播放面板选择任意系统引擎/在线 TTS 引擎后，新朗读会话立即生效。读侧（TTSReadAloudService.initTts）统一经 SpeechRoute 解析（resolveSpeechRoute 四态，纯同步不查库），兼容 legacy SelectItem JSON、双嵌套 legacy 与数字 id，消除写读格式分裂。
- **验收要点**：
  1. 选择系统引擎后新朗读会话按包名初始化（TextToSpeech(ctx, listener, enginePackage)），声音为所选引擎；
  2. 选择在线 HttpTTS 后分派启动 HttpReadAloudService 并使用该引擎合成；
  3. legacy SelectItem JSON 与纯数字 id 配置解析正确（system / http 映射）；
  4. 第4态双嵌套 legacy（SpeechRoute JSON 且 engineType=system 且 engineValue 内嵌 SelectItem 的存量补丁格式）解析正确（解包内层 value 作包名）；
  5. 不再出现"无论怎么设置只能系统默认引擎"。

### R2 朗读中切换（P0）

- **描述**：朗读进行中切换引擎时停止并重建服务，支持续播语义；续播意图上收数据层（`ReadAloud.consumePendingSwitch()`），先重算（同步）后 stop 消除竞态；同服务类型走 `reInitTts` IntentAction 引擎内重建。
- **验收要点**：
  1. 所有切换入口统一走 `upReadAloudClass()`（先重算（同步）→ stop → 重启；同服务类型 reInitTts 引擎内重建不下发 STOP）；
  2. 续播位置正确（从当前朗读位置继续，非从头播）；
  3. 系统引擎内切换走 `@Synchronized` clearTTS()+initTts()，无旧实例泄漏。

### R3 系统 TTS 引擎应用内直选（P0）

- **描述**：枚举系统已装 TTS 引擎（PackageManager 查 INTENT_ACTION_TTS_SERVICE）生成结构化 SpeechRoute 路由，按包名初始化；每引擎独立语速/音调/音量配置。
- **验收要点**：
  1. 引擎列表完整列出系统已装 TTS 引擎（含默认引擎标识）；
  2. 删除"SelectItem JSON 嵌套 engineValue"补丁（SpeechVoiceCatalogRepository.kt:131），新数据不再产生 legacy 嵌套格式；fromTtsEngineValue 保留 legacy 兼容但仅用于旧数据；
  3. 每引擎语速/音调/音量独立保存与生效（新 PreferKey 或 SpeechRoute 扩展字段，落实现 design.md 决策）。

### R4 脚本引擎协议（P0）

- **描述**：HttpTTS type=2 走 JS 契约：头注 `@name/@schema/@capabilities/@defaultSpeed` + 三函数 `options()/voices()/synthesize(text,voice,params,options,ctx)`；synthesize 返回 URL 或请求对象（本期 HTTP 轮询型）。voices() 动态音色目录缓存进既有 speakersJson 字段。执行强制 P0 沙箱（`RhinoClassShutter` 类白名单 + `SourceSandboxExtensions` 文件沙箱，文件访问走 `BookSourceStorageScope`；HttpTTS 非 BookSource，类策略须显式启用）。脚本引擎路由走现有 HttpReadAloudService 合成链（并发预下载/缓存/ExoPlayer 全复用）。执行约束：三函数统一包 `withTimeout(10s)`（Rhino 指令观察器仅协作式取消，防不住死循环）；体积限额（synthesized URL≤8KB、请求体≤256KB、脚本源码≤512KB、voices 目录超限截断并明示）；synthesize 请求对象仅承接 url/method/headers/body 四字段（AnalyzeUrl.kt:244-298 原生支持），NG 多出的 transport/audioExtract/responseType 等越界字段拒绝导入并明示；type=2 接入点收口 `getSpeakStream`（流式/非流式两路径共用）；候选过滤 type==1（type=2 不进 AI 聊天语音/多角色候选）。
- **验收要点**：
  1. type=2 记录被 TtsScriptEngineClient 正确解析与执行，头注缺省时有合理默认；
  2. voices() 目录进 speakersJson，音色选择 UI 可见可选；
  3. 沙箱拦截文件系统访问与 Java 反射/类逃逸（RhinoClassShutter 白名单 + SourceSandboxExtensions 目录约束）；**脚本本体无直接网络/文件/反射 API——HTTP 一律由宿主 AnalyzeUrl 统一发起，脚本仅产出合成请求**（受四字段边界、10s 超时与体积限额约束）；**残余风险如实声明**：合成 URL 可指向任意地址（含本机/内网），与用户自配 httpTTS URL 同级信任，缓解见 design AD-04 安全边界；
  4. 合成链复用无重复建设，行为与 http 引擎一致。

### R5 内置模板库（P1）

- **描述**：`app/src/main/assets/defaultData/tts/` 内置四模板：MultiTTS 转发器（:8774 /forward + /voices，speed 按 `{{speakSpeed*5}}` 换算（默认 10→50）、volume/pitch 按 MultiTTS 参数域换算，真机校准判据：默认语速下 MultiTTS speed 参数=50）、CloneTTS（:8080 /api/tts，speed 换算 `{{speakSpeed/10.0}}` 并 clamp 0.5~2.0（默认 10→1.0），支持 /api/legado/all 批量导入入口）、OpenAI 兼容（/v1/audio/speech 自定义端点）、Edge 代理模板（端点留空，@enabled false，不硬编码任何第三方 IP）。SpeakEngineDialog 增加"内置模板"导入入口。
- **验收要点**：
  1. 模板默认全部停用，启用需用户逐个确认导入；
  2. 导入冲突时提供 OVERWRITE（覆盖）/ KEEP_BOTH（共存）策略；
  3. 四模板文件中无任何硬编码第三方 IP/端点；
  4. 导入的脚本引擎可在 HttpTtsEditDialog 查看/编辑 script（CONFIG 区端点/密钥）与删除。

### R6 CloneTTS 一键导入（P1）

- **描述**：输入 CloneTTS `/api/legado/all` 地址，批量导入返回的全部音色为引擎记录（官方支持的"网络导入"集束）。
- **验收要点**：
  1. 导入数量与接口返回一致，导入结果明示（成功/跳过/冲突计数）；
  2. 导入记录立即可选、可朗读；
  3. 端口可自定义（默认 8080），冲突按 R5 策略处理。

### R7 init 超时与降级明示（P0）

- **描述**：initTts 增加约 8s 超时看门狗，超时/onInit 失败 → clearTTS 后回退默认引擎并 toast 明示"引擎 X 初始化失败已回退"；回调竞态用主线程 Handler 收敛。合成失败降级链：当前引擎重试 1 次 → 回退默认系统引擎并 toast 明示 → 连续失败暂停朗读并通知；路由解析失败明示"引擎配置无效已回退"，禁止静默失效。
- **验收要点**：
  1. 引擎初始化僵死时 8s 内自动回退，UI 有明确提示，无静默无音；
  2. 合成失败按"重试 1 次 → 回退默认 → 连续失败暂停+通知"链路执行；
  3. 回调竞态收敛到主线程，无双 onInit 双初始化。

### R8 并发与缓存键（P1）

- **描述**：保留现有 Channel 并发预下载；增加引擎级并发上限（concurrentRate 既有基建，脚本引擎默认 2）；缓存键维度 = 引擎类型 + voice + speed + volume + pitch + capabilities 声明维度（未声明的参数维度不进缓存键，防能力协商污染）；缓存写 .part 临时文件 + rename 原子发布。
- **验收要点**：
  1. 不同引擎/音色/参数组合的缓存互不命中污染；
  2. 引擎级并发不超过上限（脚本引擎默认 2）；
  3. 原子写保证不播放到 .part 半成品文件。

### R9 存量兼容（P0，其中书级覆盖子项 P1）

- **描述**：legacy httpTTS 记录 / legacy SelectItem 配置 / 数字 id 全部可继续工作；不 DROP 表、不丢数据。书级引擎覆盖恢复有效（Book.setTtsEngine 不再被两处 UI 强制写 null）——子项标注 P1 可选。
- **验收要点**：
  1. 升级覆盖安装后存量 httpTTS 记录、legacy SelectItem 配置、数字 id 配置全部可用；
  2. migration 为 ALTER TABLE 增列（type/script），存量行默认 type=1，覆盖安装真机验证通过；
  3. （P1）为某本书设置书级引擎后该书朗读优先使用书级引擎；
  4. legacy httpTTS JSON（无 type/script 字段）导入反序列化 type 默认 1 往返正确；备份→恢复往返新字段完整（Backup.kt httpTTS.json 自动覆盖）。

### R10 稳健性（P0）

- **描述**：去 runBlocking（路由解析纯同步不查库，httpTTS 装配收敛至 HttpReadAloudService 服务内，链路无主线程查库）；死代码清理（SpeakEngineViewModel.sysEngines）；RSS 朗读 / AI 聊天语音回归不受影响；httpTTS 可变单例残留旧值治理。
- **验收要点**：
  1. getReadAloudClass 链路无主线程 runBlocking 查库；
  2. sysEngines 死代码移除，无临时 TextToSpeech 构造残留；
  3. ReadRssActivity 朗读、AiChatSpeechPlayer 语音回归通过；
  4. 引擎切换后 ReadAloud.httpTTS 单例无旧值残留；
  5. AI 聊天语音/多角色候选过滤（前置：库中已存在 type=2 记录）：type=2 引擎不出现在 AI 语音/多角色候选（type==1 过滤生效）；书级引擎覆盖回归（与 tasks 3.8 联动）。

### R11 多角色 TTS 范式模板层（P1，2026-09-08 v1.2）

- **描述**：按"基础服务层/适配层/范式模板配置层"分层构建多角色听书：适配层提供三类声源统一抽象（引擎级/provider 音色级/HTTP 参数级，音色枚举+按句换声统一接口）；范式模板配置层内置 4 模板默认启用（旁白对白双声-引号规则/男女对读/MultiTTS 对话透传/单声），支持自定义模板与 JSON 导入导出分享（类比高亮规则体系）；播放服务侧按段消费 tag→模板→声源映射（句级轮换）；L2 AI 多角色链复用同一模板层并修复服务侧路由消费缺失；模板支持全局默认+书级覆盖（与书级引擎覆盖同构）。
- **验收要点**（期次标注：期1=模板层+系统声源+面板入口；期2=HTTP/script 声源+书级覆盖+AI 链接入，见 design §3.5.6）：
  1. 【期1】零配置（无 AI 模型、无 httpTTS）下选内置"旁白/对白双声"模板→引号对白段与旁白段声源不同（听感+日志双证）；仅默认引擎单音色时明示后单声播放（零第二声源终态）；
  2. 【期2】自定义模板：新建/编辑规则与声源映射→即时生效；JSON 导出→另一设备幂等导入→行为一致；
  3. 【期1】MultiTTS 透传模板：MultiTTS 作系统引擎时整段透传，其 App 内对话配置生效（适配层不挡路）；
  4. 【期1】声源失败段级回退 fallbackSource（AD-08 链），朗读不中断；国产引擎音色枚举不全→按引擎级降级并明示；
  5. 【期2】L2 AI 链：配置模型后角色 tag 经模板层路由真实生效于播放（修复 routeForCue 零消费缺陷，type==1 过滤维持）；
  6. 【期2】书级模板覆盖：某书选择与全局不同的模板→该书朗读用书级模板，其他书用全局默认；导入模板时校验声源可达性，缺失项标记"待绑定"不静默生效。
  7. 【期2】音色试听：引擎/音色选择处可对单段文本试听（TtsVoiceSource.utterance 单段调用，防抖）。

---

## 5. Scenarios（场景）

### R1 引擎切换修复

#### Scenario: 选择系统引擎后新朗读生效
- **WHEN** 用户在朗读设置（SpeakEngineDialog）选择某个系统 TTS 引擎，随后开始新的朗读会话
- **THEN** initTts 经 resolveSpeechRoute 解析出 system 路由并按包名构造 `TextToSpeech(ctx, listener, enginePackage)`，朗读声音为所选引擎，不再回落系统默认

#### Scenario: 选择在线 TTS 引擎后走 HTTP 服务
- **WHEN** 用户选择一个 HttpTTS 在线引擎并开始朗读
- **THEN** resolveSpeechRoute 识别为 http 路由，getReadAloudClass 按 engineType 分派启动 HttpReadAloudService，使用该引擎音色完成合成朗读

#### Scenario: legacy 配置兼容解析
- **WHEN** 存量用户的 ttsEngine 配置为 legacy SelectItem JSON、双嵌套 legacy（SpeechRoute 内嵌 SelectItem）或纯数字 id
- **THEN** resolveSpeechRoute（纯同步不查库）将 SelectItem/双嵌套解包内层 value 映射为 system 路由（包名）、数字 id 映射为 http 路由（HttpReadAloudService 服务内按 id 查库装配记录），朗读行为与升级前一致

### R2 朗读中切换

#### Scenario: 朗读中切换引擎续播
- **WHEN** 朗读进行中用户在播放面板切换引擎
- **THEN** 统一走 upReadAloudClass()：先同步重算路由 → 停止当前服务 → 经 PendingSwitch 数据层机制（consumePendingSwitch）重建服务并从当前位置继续朗读；同服务类型切换经 reInitTts 引擎内重建；无从头播、无旧服务残留、无读旧路由竞态

### R3 系统 TTS 引擎应用内直选

#### Scenario: 系统引擎枚举直选与独立参数
- **WHEN** 用户打开引擎选择列表并为某系统引擎单独设置语速/音调/音量
- **THEN** 系统已装 TTS 引擎被枚举为结构化 SpeechRoute 条目；选择后按包名初始化；该引擎的独立参数仅作用于该引擎，其他引擎参数不受影响；列表数据不再含 SelectItem JSON 嵌套补丁格式

### R4 脚本引擎协议

#### Scenario: 脚本引擎合成全链路
- **WHEN** 用户导入 type=2 脚本引擎并选择其朗读
- **THEN** TtsScriptEngineClient 在 P0 沙箱中执行 voices() 获取音色目录并缓存进 speakersJson；synthesize() 返回 URL 或请求对象后走既有 HttpReadAloudService 合成链（并发预下载/缓存/ExoPlayer）完成朗读

#### Scenario: 脚本执行异常沙箱拦截并明示
- **WHEN** 脚本尝试访问文件系统/反射等被沙箱禁止的能力，或执行过程中抛出异常
- **THEN** P0 沙箱（RhinoClassShutter 类白名单 + SourceSandboxExtensions 文件约束）拦截该调用或捕获异常，进入 AD-08 降级链并 toast 明示"脚本引擎执行失败"，AppLog 记录异常信息，应用不崩溃、不静默失效

#### Scenario: 空音色目录
- **WHEN** 脚本引擎 voices() 返回空列表或音色目录请求失败
- **THEN** 引擎条目仍可选但音色目录为空并给出明示（如"未获取到音色"），不产生空指针崩溃，不向 speakersJson 写入脏数据

### R5 内置模板库

#### Scenario: 内置模板默认停用逐个导入
- **WHEN** 用户打开 SpeakEngineDialog 的"内置模板"入口
- **THEN** 四个模板（MultiTTS 转发器/CloneTTS/OpenAI 兼容/Edge 代理）默认全部未启用；用户逐个确认后导入；与现有记录冲突时可选 OVERWRITE（覆盖）或 KEEP_BOTH（共存）；Edge 模板端点为空待用户填写

#### Scenario: MultiTTS 转发服务未启动失败明示
- **WHEN** 已导入 MultiTTS 转发器模板但 MultiTTS App 未启动（:8774 无响应）
- **THEN** 合成请求失败进入 AD-08 降级链：重试 1 次 → 回退默认引擎并 toast 明示"无法连接转发服务"，不出现静默无音

### R6 CloneTTS 一键导入

#### Scenario: CloneTTS 一键批量导入
- **WHEN** 用户输入 CloneTTS 的 /api/legado/all 地址并触发导入
- **THEN** 返回的全部音色被批量创建为引擎记录，导入结果（成功/跳过/冲突计数）明示，记录立即可选、可朗读

#### Scenario: CloneTTS 端口自定义
- **WHEN** 用户部署的 CloneTTS 使用非默认端口
- **THEN** 模板端点端口可编辑（默认 8080），修改后合成与批量导入均按自定义端口正常工作

### R7 init 超时与降级明示

#### Scenario: init 超时回退明示
- **WHEN** 所选系统引擎初始化超过约 8s 仍未回调 onInit
- **THEN** 看门狗触发 clearTTS，回退默认引擎并 toast 明示"引擎 X 初始化失败已回退"，朗读不僵死；onInit 返回 FAILURE 时同样处理

#### Scenario: 合成失败降级链
- **WHEN** 在线引擎合成连续出错
- **THEN** 当前引擎重试 1 次 → 仍失败回退默认系统引擎并 toast 明示原因 → 连续失败暂停朗读并发通知；路由解析失败时明示"引擎配置无效已回退"，全程无静默失效

### R8 并发与缓存键

#### Scenario: 缓存键维度隔离与原子写
- **WHEN** 同一文本在不同引擎/音色/语速/音调/音量或能力声明组合下合成
- **THEN** 缓存键按引擎类型+voice+speed+volume+pitch+capabilities 声明维度区分（未声明维度不进键），互不命中污染；缓存文件以 .part 临时写 + rename 原子发布，无半成品文件被播放；并发预下载不超过引擎级上限（脚本引擎默认 2）

### R9 存量兼容

#### Scenario: 覆盖安装 migration 增列与存量兼容
- **WHEN** 老版本用户覆盖安装新版本（含 httpTTS 表 migration）
- **THEN** 以 ALTER TABLE 增加 type/script 两列，存量记录默认 type=1 继续可用；legacy SelectItem 配置与数字 id 均可继续工作；无 DROP TABLE、无任何用户数据丢失

#### Scenario: 书级引擎覆盖恢复（P1）
- **WHEN** 用户为某本书设置书级 TTS 引擎（Book.setTtsEngine）
- **THEN** 该书朗读优先使用书级引擎（不再被 UI 强制写 null），换书后引擎跟随书级配置

#### Scenario: 引擎记录被删除后 SpeechRouteSanitizer 清理
- **WHEN** 某个被当前配置引用的 httpTTS 引擎记录被用户删除
- **THEN** SpeechRouteSanitizer 将指向失效记录的路由清理并回退到默认引擎，下次进入引擎选择时明示，不出现选中空引擎导致的无音

### R10 稳健性

#### Scenario: 连续快速切换引擎
- **WHEN** 朗读中用户短时间内连续切换多个引擎
- **THEN** 每次切换均正确停止旧服务/重建新路由，无服务泄漏、无 binder 回调竞态导致的双实例并发朗读，最终以最后一次选择为准并正常朗读

#### Scenario: RSS 朗读与 AI 聊天语音回归
- **WHEN** 用户使用 RSS 朗读（ReadRssActivity）与 AI 聊天语音（AiChatSpeechPlayer）
- **THEN** 两者沿既有 SpeechRoute 消费链路正常工作，不受本次路由重构影响（回归验证项）

### R11 多角色 TTS 范式模板层

#### Scenario: 零配置使用内置双声模板
- **WHEN** 小白用户（未配 AI 模型、无 httpTTS 记录）在朗读菜单选择内置"旁白/对白双声"模板并开启多人听书
- **THEN** 内置引号规则将引号内文本标记为对白段、引号外为旁白段，两段经适配层映射到不同声源播放（听感+日志双证），全程零额外配置

#### Scenario: 自定义模板与 JSON 分享
- **WHEN** 高级用户新建模板（regex 规则+声源映射+韵律）并导出 JSON，另一设备导入
- **THEN** 导入幂等（重复导入不产生重复模板），两设备同一模板 ID 行为一致；编辑保存后朗读即时生效

#### Scenario: MultiTTS 对话透传
- **WHEN** 用户将 MultiTTS 设为系统引擎并在其 App 内配置"合成对话/角色管理"，选择内置"MultiTTS 对话透传"模板
- **THEN** 适配层整段透传文本不拆段不强制音色，多角色由 MultiTTS 自行实现（适配层不挡路）

#### Scenario: 声源失败段级回退
- **WHEN** 某段按模板映射的声源合成失败（引擎 init 失败/HTTP 报错）
- **THEN** 该段回退模板 fallbackSource 或默认引擎并 toast 明示，逐段推进不中断；连续失败按 AD-08 链暂停朗读并通知

#### Scenario: L2 AI 多角色经模板层生效
- **WHEN** 用户配置 AI 模型开启多角色（L2 链），书内角色 tag 与模板规则/绑定匹配
- **THEN** 服务侧按段消费角色路由（routeForCue 接入播放链），角色按各自声源播放；未命中模板的角色走兜底链（角色绑定→性别→旁白→默认），修复"路由仅 UI 展示"的历史缺陷

---

## 附：需求-场景覆盖矩阵

| Requirement | 场景数 | 覆盖类型 |
|-------------|--------|---------|
| R1 | 3 | 正常（系统引擎/在线 TTS）+ 边界（legacy 兼容） |
| R2 | 1 | 正常（朗读中续播） |
| R3 | 1 | 正常（枚举直选+独立参数） |
| R4 | 3 | 正常（全链路）+ 异常（沙箱拦截）+ 边界（空音色目录） |
| R5 | 2 | 正常（导入流程）+ 异常（转发服务未启动） |
| R6 | 2 | 正常（批量导入）+ 边界（端口自定义） |
| R7 | 2 | 异常（init 超时回退 / 合成降级链） |
| R8 | 1 | 边界（缓存键隔离+原子写+并发上限） |
| R9 | 3 | 边界（migration 增列 / 失效清理）+ 可选（书级覆盖） |
| R10 | 2 | 边界（连续快速切换）+ 回归（RSS/AI 语音） |
