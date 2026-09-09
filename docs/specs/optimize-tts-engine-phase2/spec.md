# optimize-tts-engine-phase2 · spec（TTS 多角色体验完善与生态扩展·期2 完整版）

> 状态：✅ 设计完成 v2.0 ｜ 🔨 实施完成（编码+单测+模拟器 L2 全通过，语音出声项留真机） ｜ 日期：2026-09-09 ｜ 版本：v2.0
>
> **实施进度登记（2026-09-09）**：2.x 核心实现全部完成（R1 模板选择器/R2-R3 管理页+编辑器/R4 音色级声源/R5 试听/R6 书级覆盖/R7 AI 链/R9 批量预合成 TtsPrebuildManager+TtsPrebuildService）；3.2 单测 275 项通过（3 个已知 flaky）；3.26.090912 测试包打包+覆盖安装+Migration 109→110 冒烟通过；模拟器 L2 验证通过（主链/引擎切换双向路由/降级链/多角色路由，证据见 issues-found.md）；真实人声/试听真声/预合成 HTTP 端到端留真机（模拟器无中文 TTS 语音数据）。详见 `issues-found.md` L2 验证结论表。
>
> 权威输入：`docs/specs/optimize-tts-engine/design.md`（v1.3 定稿，§0 阅读指南术语表 + §8 实施进度登记）｜`temp/phase2-design-brief.md`（期2 设计简报）｜本仓源码核实（`help/readaloud/casting/TtsCastingModel.kt`、`TtsCastingStore.kt`、`TtsTagSplitter.kt`、`TtsVoiceSource.kt`）。
>
> 术语沿用上游 §0.1（选角模板/同通道约束/tag 分段五元组/降级链/引擎模板等），本文不重复定义；实施 AI 须按上游阅读顺序（design.md → 本文 R 条款 → tasks）执行，不得凭经验臆测。

## 变更日志

| 日期 | 版本 | 说明 |
|------|------|------|
| 2026-09-09 | v0.1 | 初稿：Intent/Scope/Approach（Selected+4 Alternatives+Drawbacks+复盘+数据契约）/R1-R8/32 场景+覆盖矩阵 |
| 2026-09-09 | v0.2 | 检查点扩围：S7 批量预合成/缓存管理纳入本期（用户裁决）→ 新增 R9+7 场景（S9-1~S9-7），P2-6 回归保护、P2-7 预合成；登记后续清零（S1-S7 全闭环）；Room v110 冻结维持（队列内存态） |
| 2026-09-09 | v0.4 | 红队专项+五轮（R1 需求完备性/R2 可实施性/R3 可靠性并发/R4 兼容回归/R5 对抗终审 GO-WITH-NOTES，累计 100+ 条全量处置，明细见 README 变更日志）：新增 S2-5/S3-7/S9-8/S9-9 场景、R9 验收要点重写、键升版有意失配裁决、S7-4 AI×模板共存口径修正 |

---

## 1. Intent（意图）

上游期1 交付"零配置双声"最小闭环后（引擎路由单源化 + 选角模板层四类 + speakMultiRole 逐段驱动 + 内置 4 模板幂等导入），模板层处于"能用但不完整"状态，构成五处体验断层：

1. **模板不可编辑**：仅内置 4 模板（builtin_dual_voice / builtin_male_female / builtin_multitts_passthrough / builtin_mono），用户无法自定义规则、声源、韵律；
2. **清单不可见**：循环切换（cycleTtsCastingTemplate）无列表，发现性差、误触率高，看不到"有哪些模板/当前是哪个/摘要是什么"；
3. **无试听**：配错声源要整段朗读才能发现，选型漏斗断裂；
4. **无书级覆盖**：模板切换全局生效（`TtsCastingStore.setBookOverrideTemplateId` 能力就绪无 UI），多本书不同偏好需来回切；
5. **AI 未接入**：`routeForCue` 服务侧零消费（上游 §1.2 铁证：AiReadAloudRoleService 既有 AI 链未触模板层），AI 分镜说话人无法进模板层，L-d 与 L-c 之间断层。

本期目标：补全 **L-c 范式模板层的完整产品形态**，形成完整漏斗——选择器（发现）→ 管理页（治理）→ 编辑器（自定义）→ 试听（验证）→ 书级覆盖（场景化）→ AI 消费（生态）→ 批量预合成（离线加速，v1.1 扩围）。四层模型（L-a 基础服务 / L-b 适配层 / L-c 范式模板层 / L-d AI 复用，AD-09）不动，新 UI 只是 L-c 的展示层；数据流仍以 TtsCastingStore 为 Room 单一权威源，快照失效走既有 `invalidateSnapshot()` 链。

**验收总口径**：期2 交付门禁 = R1-R9 全部 PASS + §5 场景 43 条全绿 + R8 回归清单（上游 §1.8-F C1-C11）登记完成；编译走 `build-legado.bat` 测试包，真机验证用测试包 `io.legado.miss.app.debug`（书源/Skill 类真机测试才用正式包）。

### 1.1 期次拆分背景（期1 简化原因复盘）

期1 优先交付"小白零配置双声"最小价值闭环，裁剪决策依据（来自期2 设计简报 §2 复盘，本期据此定处置方向）：

- **用户核心痛点**=引擎切换失效 + 小白多角色门槛，期1 以内置模板默认启用+零 AI 双声直接命中；管理/编辑/试听/书级 UI 属"已有可用替代路径"（内置模板开箱即用 + 循环切换可换模板），故裁剪；
- **AI 深度接入**因 LLM 分镜缓存与段落坐标映射复杂度高、且主痛点已被模板层覆盖而延后；
- **批量预合成**为独立大子系统（§1.8-D-1 定型项已预留零重构接入），不与 UI 完整化混期——**v1.1 变更：经检查点用户裁决扩围纳入本期（P2-7/R9）**，沿九条定型契约实装。

本期处置原则：先补"用户可感知的产品面"（S1-S6），再沿 §1.8-D-1 契约实装预合成（S7→2.10 前置键收敛重构）；所有简化处均在 §3.4 登记升级路径，避免后续返工数据层。

## 2. Scope（范围）

### 2.1 做什么（In Scope）

| # | 交付项 | 内容 | 需求 |
|---|--------|------|------|
| P2-1 | 模板选择列表器 | 列表单选（模板名+摘要+单选+Scene 闸位联动摘要）替代循环切换；**保留循环为兼容入口**；选择即 `setActiveTemplateId` → `invalidateSnapshot()` | R1 |
| P2-2 | 选角模板管理页+编辑器 | 管理页：内置只读/复制为自定义/删除/启停（排序可延后）；编辑器：规则行 tag×matchType×source×prosody + fallbackSourceJson；同通道校验强制；JSON 导入导出（ignoreUnknownKeys 容错 + schemaVersion） | R2/R3 |
| P2-3 | 音色级声源+试听 | 声源从引擎级扩展到音色级：SpeechVoiceRoutePicker 复用 + script voices 目录映射 + MultiTTS /voices 归一化；试听：600ms 防抖 + 双 token + 临时文件 + ExoPlayer + beforePreview 暂停朗读 | R4/R5 |
| P2-4 | 书级模板覆盖 UI | 书级覆盖行（当前书上下文）+ 清除回退；书级优先全局（resolveActiveTemplateId 六级 resolve 链） | R6 |
| P2-5 | AI 链接入 | AI 分镜 tag 前缀 `ai:` → 模板层消费（resolveSourceForTag）；routeForCue 修复接入播放链；type==1 过滤维持 | R7 |
| P2-6 | 回归保护 | 上游 §1.8-F C1-C11 清单执行与登记（伴随全部交付项） | R8 |
| P2-7 | 批量预合成+缓存管理 | 缓存键纯函数收敛（§1.8-D-1 契约 1/2/8 落地）+合成纯函数与 cacheSynthesizer 能力接口（契约 5/6/9）+TtsPrebuildManager 内存队列+TtsPrebuildService 前台服务（dataSync）+选章 UI（阅读菜单起止章节）+缓存管理页 TTS 维度验证增强 | R9 |

### 2.2 不做什么（Out of Scope，继承上游 Out-of-Scope）

- **预合成任务持久化/跨设备迁移**：P2-7 任务队列仅内存态（StateFlow），不做 Room 实体化与重启恢复（缓存产物才是资产、任务可重建，Room v110 冻结）；预合成音频归档/跨设备迁移不做（上游 §1.8-D-1 契约 8 已为未来留 key 可持久化前提）；
- **SSE/WS 流式合成**：不引入新传输协议（脚本引擎协议维持 options/voices/synthesize 三函数边界）；
- **SoundTouch 音频后处理**：不做变速变调后处理链（韵律仍走 CastingProsody 参数域）；
- **跨通道混排**：同通道约束是硬约束（`sameChannel()` 拒绝保存/导入并明示），本期不开放；
- **Room 结构变更**：v110 冻结不再 bump（无新表/列需求），`TtsCastingTemplate` 实体与 Dao 仅消费不改；
- **NG 三页角色级绑定 dock / 每音色参数滑杆完整版**：属期后增强，本期书级覆盖走 C 版 MVP 蓝本（§3.2 备选 3）。

### 2.3 影响模块清单

| 模块 | 动作 | 说明 |
|------|------|------|
| `ui/book/read/config/ReadAloudConfigDialog.kt` | 修改 | "多人听书模板"入口扩展：列表器入口 + 循环兼容 + 书级覆盖行（SettingItemSpec itemsForGroup 模式） |
| `ui/book/read/config/casting/TtsCastingManageFragment.kt`（新增） | 新增 | 单 Fragment 三态路由（LIST/EDITOR/IMPORT）：管理页/编辑器/JSON 导入导出（配套 TtsCastingListScreen/TtsCastingEditorScreen/TtsCastingImportScreen） |
| `ui/book/read/config/SpeechVoiceRoutePicker.kt` | 复用 | 规则声源音色级选择（签名不改：title/groups/currentRoute/onRouteSelected） |
| `help/readaloud/casting/TtsCastingStore.kt` | 消费为主 | setBookOverrideTemplateId/resolveSourceForTag/resolveActiveTemplateId 既有 API；如需新查询优先 suspend（KSP2 限制） |
| `help/readaloud/casting/TtsCastingModel.kt` | 消费 | 编辑器直接读写 CastingRuleSet/CastingRule 模型，不新造中间层 |
| `service/TTSReadAloudService.kt` | 小改 | speakMultiRole 五元组契约不动；试听 `preview_` utteranceId 前缀通道隔离 |
| `service/AiReadAloudRoleService.kt` | 修改 | routeForCue 修复接入模板层消费 ai: tag（R7） |
| `help/readaloud/speech/`（voices 目录链） | 复用 | 引擎模板 /voices 目录拉取缓存（fetchVoicesCatalog 动态 URL 解析）供 R4 归一化 |
| `service/HttpReadAloudService.kt` | 修改 | 缓存键收敛为单一纯函数（md5SpeakFileName+KEY_VERSION，§1.8-D-1 契约 1/2/8 落地）+合成纯函数/cacheSynthesizer 能力接口抽取（契约 5/6/9）+单一原子写缓存提交（契约 5）；播放链对外行为零变化 |
| `help/readaloud/prebuild/TtsPrebuildManager.kt`（新增） | 新增 | 批量预合成队列单例：单线程 executor+StateFlow 进度+取消标志+has() 幂等跳过（对齐 ui/book/cache/AudioCacheTaskManager.kt 模式） |
| `service/TtsPrebuildService.kt`（新增） | 新增 | 前台服务壳（foregroundServiceType=dataSync，对齐 CacheBookService 先例）：通知进度+完成/失败明示+全部结束 stopSelf |
| `ui/book/read/ReadBookActivity.kt` | 小改 | 阅读菜单新增"批量预合成"入口（对齐 menu_download/showDownloadDialog 范式），能力门控禁用态 |
| `ui/book/cache/CacheManageViewModel.kt` | 修改 | httpTTS 清理联动取消预合成任务+清空保留名单；音频维度播放中清理保护对齐视频（deleteStorageTarget 现仅挡视频） |
| `app/src/main/AndroidManifest.xml` | 修改 | 注册 TtsPrebuildService（dataSync，exported=false） |
| `res/values/strings.xml` | 修改 | 新页面文案（中文优先） |
| `app/src/main/assets/updateLog.md` | 修改 | 编译前按 git diff 追加用户可见条目（version-delivery-sync 门禁） |
| `docs/INDEX.md` / `docs/project-flow/task-navigation.md` | 修改 | 文档锚点同步 |

### 2.4 与上游简化项 S1-S7 对应关系

| 简化项 | 本期承接需求 | 承接交付项 | 残余登记 |
|--------|--------------|-----------|----------|
| S1 模板选择入口 | R1 | P2-1 | 无 |
| S2 管理页/编辑器 | R2/R3 | P2-2 | 排序（拖动）可延后 |
| S3 音色选择 | R4 | P2-3 | 无 |
| S4 书级覆盖 UI | R6 | P2-4 | 角色级绑定 dock 属期后 |
| S5 试听 | R5 | P2-3 | 进程被杀临时文件清扫属期后 |
| S6 AI 链消费 | R7 | P2-5 | LLM 分镜缓存/段落坐标映射深化属期后 |
| S7 批量预合成/缓存管理 | R9 | P2-7 | 无（登记后续清零，S1-S7 全闭环） |

## 3. Approach（方案）

### 3.1 Selected（选定方案）

1. **复用本仓组件族**：
   - `SpeechVoiceRoutePicker`（音色选择器，title/groups/currentRoute/onRouteSelected 签名已满足）→ 规则声源音色级选择直接复用，不自研列表；
   - `SettingItemSpec`（`ReadAloudConfigDialog.itemsForGroup` 模式）→ 朗读设置入口扩展位（列表器入口/书级覆盖行/循环兼容项同组编排）；
   - `AppDialogStyle`/`AppShapes` 组件族 → 弹窗/圆角基线，LegadoTheme 取色，不硬编码色；导入确认走既有弹窗组件族（ImportChoiceRow 先例）。
2. **NG 单 Fragment 路由枚举模式**：以上游 NG 蓝本 `TtsEngineConfigRoute`（5 态 + `backDestination()` 定义返回栈 + 模板/引擎 id 存 Fragment 状态不走 Intent）为模板，新增 casting 管理路由（列表/编辑器/导入导出等态），替代多 Activity 导航；返回栈行为与引擎管理页一致。
3. **TtsVoicePreviewController 契约移植+依赖适配**：600ms 防抖（previewKey=`engineId|system|voiceId`，同 key 再点=停止切换）+ 双 token（脚本 `++requestToken` 响应校验 + 系统 `systemPreviewToken`）+ 临时文件 `cacheDir/voice_preview_{engineId}_{ts}.audio` → ExoPlayer + 100ms 轮询/末端 300ms 宽限 + `stopActivePreview` 兜底（token++/cancelJob/release/删文件）；宿主 dismiss/onDestroyView 调 release；`beforePreview` 回调暂停正在朗读；NG help/tts 依赖包映射为 SpeechVoiceOption/HttpTTS+SpeechRoute，script 声源自写描述符执行器。
4. **C pickEngineThenVoice 两段式 MVP 快速版蓝本**：书级覆盖行的声源选择用"先引擎后音色（可清除）"alert 两段式，快速验证 `setBookOverrideTemplateId` 数据层能力；UI 后续升级三页 dock 时数据层零返工。
5. **编辑器直接编辑 JSON 模型**：规则行直接读写 `TtsCastingModel`/`CastingRuleSet`/`CastingRule`（data class + GSON 序列化），不新造 ViewModel 中间模型；保存时强制校验链：
   - `sameChannel()` 同通道校验（跨通道组合拒绝保存/导入并明示）；
   - regex 规则预编译校验（坏正则拦截并定位到行）；
   - 保留字 tag 拦截（`CastingTag.isReserved`：narration/dialogue_male/dialogue_female）；
   - prosody 范围校验（0=跟随全局，有效 0.5~2.0）；
   - 导入经 `ignoreUnknownKeys` 容错（`CastingRulesWrapper.schemaVersion` 识别，当前 SCHEMA_VERSION=1）。

### 3.2 Alternatives（备选对比）

| # | 决策点 | 方案 A | 方案 B | 选定与理由 |
|---|--------|--------|--------|------------|
| 1 | 页面导航 | 独立多 Activity（管理页/编辑器各一，Intent 传参） | 单 Fragment + 路由枚举（NG TtsEngineConfigRoute 蓝本） | **B**：backDestination 定义返回栈、模板 id 存 Fragment 状态不走 Intent、避免多 Activity 抢占与跳转开销；与本仓引擎管理蓝本一致 |
| 2 | 音色选择 UI | 自研音色列表（新 Sheet/搜索/分组逻辑） | 复用 SpeechVoiceRoutePicker | **B**：签名/分组/搜索/当前项回显已满足且有三处复用先例（听书/默认声音/书角色）；自研重复造轮子且风格漂移、维护双份筛选逻辑 |
| 3 | 书级覆盖形态 | 完整 NG 三页 dock（BookCharacterTtsScreen：FORMAL/TEMPORARY/DEFAULTS + 角色卡/长按多选） | C 两段式 MVP（pickEngineThenVoice alert，先引擎后音色可清除） | **B**：本期仅模板级覆盖+清除，三页 dock 面向角色级绑定属期后；MVP 快速验证 Store 能力，数据层（setBookOverrideTemplateId）不变 UI 可后升 |
| 4 | AI 接入位置 | 播放服务（TTSReadAloudService）侧消费模板 | AiReadAloudRoleService 侧消费（routeForCue 修复接入） | **B**：播放链保持 speakMultiRole 五元组契约与分段驱动不变，回归面最小；AI 侧将分镜说话人归一为 `ai:*` tag 交模板层，职责边界清晰（播放链只认五元组，不感知 AI） |

### 3.3 Drawbacks（代价与取舍）

1. **编辑器并发保存复杂度**：规则行即时保存需 revision 串行 Job 防旧响应覆盖（NG 表单模式）；本期取"显式保存按钮 + 保存时全量校验"降低并发面，代价是编辑过程无自动暂存（简化说明，升级路径见 §3.4）。
2. **试听资源管理**：临时文件/ExoPlayer/token 生命周期点多，异常路径遗漏会泄漏文件或抢占音频焦点；依赖 stopActivePreview 与 dismiss/onDestroyView 兜底；残余风险=进程被杀时缓存目录残留临时文件（启动清扫本期不做）。
3. **AI 接入回归面**：即使收敛到 AiReadAloudRoleService 侧，`ai:` tag 进模板层仍改变 AI 朗读既有路径，需 R8 回归清单兜底；AI 未配模板时必须保持与期1 完全一致行为（含未命中单声兜底）。
4. **预合成任务不持久化**：S7 已扩围纳入（P2-7），但队列仅内存态——进程被杀任务丢失需重发（缓存产物不受影响）；§1.8-D-1 契约 8（key 全输入可枚举可持久化）已留未来持久化前提。

### 3.4 简化处置与升级路径（Discussion）

| 简化点 | 本期处置 | 已知上限 | 升级路径 |
|--------|----------|----------|----------|
| 编辑器无自动暂存 | 显式保存+全量校验 | 编辑中途退出丢未存修改（弹确认缓解） | NG 表单 revision 串行 Job + 返回/切 Tab/销毁前兜底保存 |
| 书级覆盖 MVP 两段式 | 先引擎后音色 alert | 无角色级粒度 | BookCharacterTtsScreen 三页 dock（数据层 setBookOverrideTemplateId 不变） |
| 试听临时文件无启动清扫 | dismiss/onDestroyView 兜底 | 进程被杀残留缓存文件 | App 启动按 `voice_preview_` 前缀清扫 |
| AI 分镜缓存/坐标映射不深化 | tag 归一进模板层 | 分镜缓存命中与段落坐标映射复杂度保留 | 期后专项（S6 残余登记） |

### 3.5 关键数据契约（本仓源码核实，编辑器直接读写的模型）

**CastingRule（单条分段规则，`TtsCastingModel.kt`）**：

| 字段 | 类型/默认 | 说明 |
|------|-----------|------|
| tag | String = "narration" | 分段 tag；保留字 narration/dialogue_male/dialogue_female（`CastingTag.isReserved`）；AI 命名空间前缀 `ai:`（RESERVED 集合外自定义） |
| matchType | String = "builtin_quote" | `CastingMatchType`：builtin_quote（内置引号规则，忽略 pattern）/regex/keyword |
| pattern | String = "" | regex/keyword 表达式；builtin_quote 忽略 |
| sourceJson | String = "" | 声源 SpeechRoute JSON；`engineType=current` 哨兵=跟随当前生效路由（解析时由 `TtsCastingStore.substituteCurrentSentinel` 替换） |
| prosody | CastingProsody | rate/pitch/volume，0=跟随全局不下发，有效 0.5~2.0（valid=任一非 0） |

**CastingRuleSet（模板运行时模型）**：

| 字段 | 类型/默认 | 说明 |
|------|-----------|------|
| templateId / name | String | 模板 id 与名称（内置 4 模板 id 常量见模型 companion） |
| builtin | Boolean = false | 内置只读标记（R2 管理页依据） |
| schemaVersion | Int = 1 | 模型演进锚点（§1.8-D-2 定型项 3），结构变更时递增 |
| rules | List<CastingRule> | 规则集；`sameChannel()`：全部声源 engineType 去重后 ≤1（同通道校验入口） |
| fallbackSourceJson | String = "" | 全局兜底声源：规则未命中/声源失败段级回退目标 |

- 导入容错：`CastingRulesWrapper(schemaVersion, rules)` + ignoreUnknownKeys（未知字段忽略）；`fromEntity` 对 rulesJson 空或 rules 空判无效返回 null（编辑器/导入复用同判定，不落半成品）。

### 3.6 NG/C 交互蓝本对照（实施锚点，源自简报 §3）

| 能力 | 蓝本 | 本期落点 |
|------|------|----------|
| 页面导航 | NG TtsEngineConfigRoute（5 态+backDestination） | casting 管理路由枚举（R2/R3） |
| 音色选择 | TtsVoiceSelectionSheet 先备后显（show 先 IO buildVoiceSnapshot 再弹窗） | R4 复用 SpeechVoiceRoutePicker（签名已满足，Sheet 形态不引入） |
| 试听流 | TtsVoicePreviewController（防抖/双 token/临时文件/轮询宽限） | R5 契约移植 |
| 书级绑定 | C pickEngineThenVoice 两段式 alert（先引擎后音色可清除） | R6 MVP 蓝本 |
| 编辑表单 | NG TtsEngineFormScreen（options→控件映射/revision 串行） | 本期显式保存简化（§3.4 登记升级路径） |

## 4. Requirements（需求）

> 期次标注：全部为**期2**；"期1 基线"指上游已交付行为，回归锚点见 R8。
>
> 每条 R 含 描述/验收要点/涉及模块/期次 四要素；验收以 §5 场景与 §6 覆盖矩阵为准；实施顺序建议 P2-1 → P2-2 → P2-3 → P2-4 → P2-5，R8 回归全程伴随、交付前门禁执行。

### R1 模板选择列表器（P2-1）

**描述**：朗读设置"多人听书模板"由循环切换升级为列表选择器：每项展示模板名 + 摘要（规则构成/声源通道/启停态），单选即生效（`TtsCastingStore.setActiveTemplateId` → `invalidateSnapshot()` 快照失效链，朗读中切换即时生效且当前段不中断）；Scene 闸位联动摘要（`multiRoleAvailable = AI 多角色开启 || 选角模板激活`）；**保留循环切换为兼容入口**，与列表器选中态双向同步。

**验收要点**：
- 列表项含名称+摘要+当前选中标记；选择后经 invalidateSnapshot 立即生效；
- 空模板列表（全部禁用/删除）显示空态引导（恢复内置模板导入入口），不空白不崩溃；
- 循环切换行为与期1 一致且同步列表选中态。

**涉及模块**：ReadAloudConfigDialog（入口扩展）/ TtsCastingStore（既有 API）。**期次**：期2

### R2 选角模板管理页（P2-2）

**描述**：新增管理页（单 Fragment 路由列表态）列出全部模板（内置+自定义）：内置只读 + **复制为自定义**；自定义可编辑/删除（带确认）/启停；排序可延后（本期不做拖动，登记 §3.4）。

**验收要点**：
- 内置项无编辑/删除入口；复制生成新 templateId（名称加"副本"后缀）且 rulesJson/fallbackSourceJson 完整拷贝；
- 删除被引用模板（全局激活/书级覆盖）时确认弹窗明示影响，删除后引用自动回退；
- 启停切换即时刷新管理页与 R1 选择器（invalidateSnapshot 链）。

**涉及模块**：TtsCastingManageFragment（新增宿主）。**期次**：期2

### R3 选角模板编辑器（P2-2）

**描述**：规则行编辑 `tag × matchType(builtin_quote|regex|keyword) × pattern × sourceJson × prosody(rate/pitch/volume)` + `fallbackSourceJson` 全局兜底声源；**同通道校验强制**（`CastingRuleSet.sameChannel()`，跨通道组合拒绝保存/导入并明示）；**导入输入通道=SAF 文件选择器+剪贴板粘贴双通道**（对齐导出）；JSON 导入导出（`ignoreUnknownKeys` 容错）；`schemaVersion` 字段维护（导入时识别，当前=1；**高于当前版本拒绝导入并明示需升级**）。

**验收要点**：
- regex 规则保存前预编译校验，坏正则拦截并定位到行明示；
- 自定义 tag 命中保留字（narration/dialogue_male/dialogue_female）拒绝保存；`ai:` 前缀为 AI 命名空间，编辑器自定义角色 tag 不撞保留字；
- 导入坏 JSON/含未知字段文件：未知字段忽略不崩，结构性损坏给出失败提示且不落库半成品；
- 导出再导入幂等（schemaVersion 保持，规则/韵律/兜底声源逐字段一致）；
- prosody 0=跟随全局不下发，有效范围 0.5~2.0 越界拦截。

**涉及模块**：TtsCastingModel（消费，序列化校验链）/ TtsCastingManageFragment。**期次**：期2

### R4 音色级声源（P2-3）

**描述**：规则声源从引擎级扩展到音色级：复用 `SpeechVoiceRoutePicker` 选择（system 引擎音色 / script 引擎 voices 目录）；script voices 经引擎模板 `/voices` 目录映射（MultiTTS `/voices` 归一化为统一目录条目，复用 fetchVoicesCatalog 拉取缓存链）；`engineType=current` 哨兵语义保持（解析时由 TtsCastingStore `substituteCurrentSentinel` 替换为实际生效路由）。

**验收要点**：
- system 声源可选具体 voiceId 并写入 sourceJson；script 声源可从 voices 目录选择；
- voices 拉取失败/超时降级为引擎级声源（toneID 空缺省）并提示，不阻塞保存与朗读；
- 声源有效性判定沿用 `CastingRule.sourceValid`（哨兵 current 视为有效）。

**涉及模块**：SpeechVoiceRoutePicker / speech voices 目录链 / TtsCastingStore。**期次**：期2

### R5 试听（P2-3）

**描述**：`TtsVoicePreviewController` 模式移植：600ms 防抖（previewKey=`engineId|system|voiceId`，同 key 再点=停止切换）+ 双 token（脚本 `++requestToken` 响应校验+删临时文件，系统独立 `systemPreviewToken`）+ 临时文件 `cacheDir/voice_preview_{engineId}_{ts}.audio` → ExoPlayer 播放 + `beforePreview` 暂停正在朗读；试听走**独立通道**（utteranceId 前缀 `preview_`）不占用朗读通道。

**验收要点**：
- 播放结束（STATE_ENDED）或错误自动 finish；100ms 轮询 + 末端 300ms 宽限；
- dismiss/onDestroyView 触发 `stopActivePreview`：token++/cancelJob/release/删临时文件；
- 试听期间朗读段落进度不受影响（恢复后原位继续）；试听文本优先级 voice.sampleText → engine.sampleText → DEFAULT。

**涉及模块**：TtsCastingManageFragment（宿主）/ TTSReadAloudService（preview_ 隔离）。**期次**：期2

### R6 书级模板覆盖 UI（P2-4）

**描述**：消费 `TtsCastingStore.setBookOverrideTemplateId` 既有能力：朗读设置内提供书级覆盖行（当前书上下文：书名 + 模板选择 + 清除），**书级优先全局**（`resolveActiveTemplateId(bookKey)` 六级 resolve 链）；显示当前生效来源（书级覆盖/全局）。

**验收要点**：
- 覆盖后本书朗读用书级模板，其他书不受影响；
- 清除后回退全局并经 invalidateSnapshot 即时刷新；
- 无当前书上下文（如全局设置入口）时该行不可用或隐藏，不产生脏数据。

**涉及模块**：ReadAloudConfigDialog（覆盖行）/ TtsCastingStore（既有 API）。**期次**：期2

### R7 AI 链接入（P2-5）

**描述**：AI 分镜说话人 tag 以 `ai:` 前缀进入模板层消费：`AiReadAloudRoleService` 侧将分镜 cue 归一为 `ai:{角色}` tag，经 `resolveSourceForTag` 消费（含哨兵替换）；`routeForCue` 修复接入播放链；`type==1` httpTTS 过滤语义维持不变（调用侧过滤，type==1 不进 script 消费分支）。

**验收要点**：
- `ai:xxx` 命中模板规则 → 按规则声源+韵律分段朗读，五元组分段契约（text/tag/paragraphIndex/offsetInParagraph/length）不变；
- 未命中任何规则 → 段级回退 `fallbackSourceJson`，仍不可用维持既有单声兜底（与期1 一致）；
- AI 未启用/未配模型时行为与期1 完全一致（零回归）。

**涉及模块**：AiReadAloudRoleService（routeForCue 接入）/ TtsCastingStore（resolveSourceForTag）。**期次**：期2

### R8 回归保护（P2-6 伴随）

**描述**：上游 design.md §1.8-F C1-C11 回归清单全部执行并登记；降级链（AD-08：当前引擎重试 1 次 → 回退默认引擎+toast 明示 → 连续失败暂停朗读+通知）与熔断语义不变；存量 type=1 httpTTS 模板流不动；Room v110 冻结；脚本沙箱（RhinoClassShutter/BookSourceStorageScope）不弱化。

**验收要点**：
- C1-C11 逐项 PASS 并登记（含期1 验收口径"零 AI 零 httpTTS 双声可听感"不回退）；
- 循环切换兼容路径可用（R1）；
- 新增 UI 不触碰四层模型边界与 §1.8-D 定型项（voiceParamsJson 键结构/同通道校验入口/Room 单源）。

**涉及模块**：全部改动面。**期次**：期2

### R9 批量预合成与缓存管理（P2-7，扩围）

**描述**：对具备落盘合成能力的引擎开放选章批量预合成：后台单线程队列逐段合成并落盘（复用播放侧同源缓存键），播放时缓存命中零等待；前台服务承载进度通知；缓存管理页 TTS 维度保持统计/清理能力并纳入预合成产物。系统 TTS 引擎无落盘产物（UtteranceResult.SpeakSubmitted 流式播放），经 cacheSynthesizer 能力接口门控：入口禁用并明示。

**验收要点**：
- 阅读菜单入口（对齐 menu_download/showDownloadDialog 范式）弹出起止章节选择+流量提示；入口仅当 **cacheSynthesizer 门控三条件**全满足时可用（①type∈{1,2} 系统引擎禁用；②非流式模式 streamReadAloudAudio；③多角色模板未激活——多角色链不经键缓存），否则禁用+明示原因；
- **全局单队列 FIFO**（跨书排队追加，State 携带 bookKey）；**参数快照**：入队锁定键参数/模板/切分 flag 全集，任务期内变更不影响进行中任务，完成通知带参数摘要；
- 单线程串行+ensureActive/cancelFlag 取消协作+has() 幂等跳过（入队 has() 全量预扫描，账目=剩余/总单元）；单单元失败重试 1 次后跳过计入失败明细，AD-08 降级链不适用批量链；
- 前台服务 foregroundServiceType=dataSync（FOREGROUND_SERVICE_DATA_SYNC 权限已存在），完成/失败通知明示，全部结束 stopSelf；onDestroy/onTaskRemoved→任务 cancelled；
- **缓存键 TtsCacheKeys 纯函数单源+KEY_VERSION 首升=有意失配**（存量期1 httpTTS 缓存一次性重合成，updateLog 面向用户告知；对拍=同代际算法等价性）；键 stem 含章节 index，engineKey=引擎 id；
- **预合成保留名单**：产物不被播放侧 10 分钟清理驱逐（removeCacheFile 跳过 prebuildReservedKeys）；租约=ReadBook.curTextChapter/durChapterPos 数据源+当前章/下一章范围+延后 3 轮上限；temp+rename 原子提交+.part 约定；
- Room v110 冻结：队列仅内存态不持久化（Android 15 dataSync 6h 时限取舍声明：超时终止后重发幂等续跑）；
- 缓存管理页 TTS 维度统计/清理覆盖预合成产物；清理联动取消进行中任务+播放中保护对齐视频维度。

**涉及模块**：service/HttpReadAloudService.kt、help/readaloud/prebuild/TtsCacheKeys.kt（新增）、help/readaloud/prebuild/TtsPrebuildManager.kt（新增）、service/TtsPrebuildService.kt（新增）、ui/book/read/ReadBookActivity.kt、ui/book/cache/CacheManageViewModel.kt、AndroidManifest.xml。**期次**：期2（检查点扩围；红队专项审查记录见 design.md §8）

## 5. Scenarios（场景）

> 格式：WHEN/THEN；每条 R ≥2 个（正常 + 异常/边界），共 43 条。强制覆盖项：空模板列表态（S1-2）、编辑器坏正则（S3-1）、跨通道拒绝（S3-2）、导入坏 JSON 容错（S3-3）、试听资源释放（S5-2）、书级覆盖优先级（S6-1）、AI tag 未命中模板兜底（S7-2）、循环切换兼容路径（S1-3）、预合成能力门控禁用态（S9-3）、播放与预合成双向并发防护（S9-6）、停用模板覆盖引用（S2-5）、超前版本导入拒绝（S3-7）。

### R1 场景

- **S1-1 正常选择**：WHEN 用户打开模板选择列表器并单选"男女对读" THEN 该模板 setActiveTemplateId 生效、列表标记选中、Scene 摘要联动更新、朗读中经 invalidateSnapshot 立即生效且当前段不中断（**前置约束**：本书存在书级覆盖时，选择仅改全局层且界面即时提示"实际生效=本书覆盖"）。
- **S1-2 空模板列表态**：WHEN 模板列表为空（全部禁用或删除） THEN 显示空态与"恢复内置模板"引导，不出现空白页或崩溃；返回后朗读回退既有行为。
- **S1-3 循环切换兼容路径**：WHEN 用户继续使用期1 循环切换入口 THEN 行为与期1 一致（点一下切换下一个），切换结果与列表器选中态双向同步。
- **S1-4 朗读中切换即时生效（章粒度口径）**：WHEN 朗读进行中用户在列表器切换模板 THEN 经 invalidateSnapshot 快照失效，**当前章按旧模板完整读完，下一章起按新模板分段**（章粒度快照为期1 既有实现，禁改段粒度重读防进度字符账漂移）。

### R2 场景

- **S2-1 内置只读+复制**：WHEN 用户在内置模板上操作 THEN 无编辑/删除入口；点"复制为自定义"生成新 id 副本，副本可完整编辑。
- **S2-2 删除被引用模板**：WHEN 删除当前全局激活或书级覆盖中的自定义模板 THEN 弹确认明示影响；确认后引用回退（全局回退默认/内置，书级清除覆盖），朗读不中断。
- **S2-3 启停当前激活模板**：WHEN 用户停用当前全局激活的模板 THEN 激活态回退（内置默认/上一可用项），Scene 摘要与列表器同步刷新。
- **S2-4 复制副本独立演进**：WHEN 用户修改复制副本的规则/声源 THEN 原内置模板保持只读不变，副本删除不影响内置模板可用性。
- **S2-5 停用模板被书级覆盖引用**：WHEN 书级覆盖指向的模板被停用（enabled=false） THEN resolve 链检查 enabled，本书朗读回退全局激活模板（全局不可用则内置默认/单声兜底），覆盖行标注"已停用"来源。

### R3 场景

- **S3-1 编辑器坏正则**：WHEN 保存 matchType=regex 且 pattern 为非法正则的规则 THEN 拦截保存并定位到该行明示错误，模板数据保持不变。
- **S3-2 跨通道拒绝**：WHEN 规则集中出现两种 engineType 的声源 THEN sameChannel() 校验拒绝保存/导入，并明示"同通道约束"（全部声源须同一消费路径），**同时列出冲突规则行及各自 engineType**（actionable 对齐 S3-1）。
- **S3-3 导入坏 JSON 容错**：WHEN 导入含未知字段或结构损坏的模板 JSON THEN ignoreUnknownKeys 忽略未知字段正常导入；结构性损坏给出失败提示且不崩溃、不落库半成品。
- **S3-4 导出导入幂等**：WHEN 导出模板再导入 THEN schemaVersion 识别保持，规则/韵律/兜底声源逐字段一致。
- **S3-5 保留字 tag 拦截**：WHEN 自定义 tag 填入保留字（narration/dialogue_male/dialogue_female） THEN 拒绝保存并明示。
- **S3-6 keyword 空 pattern 拦截**：WHEN 保存 matchType=keyword/regex 且 pattern 为空 THEN 拦截保存并明示（空表达式规则无匹配意义）。
- **S3-7 超前 schemaVersion 拒绝**：WHEN 导入 schemaVersion 高于当前版本的模板 JSON THEN 拒绝导入并明示"需升级 App"，不静默丢字段落库。

### R4 场景

- **S4-1 音色级选择正常流**：WHEN 规则声源选择 system 引擎并经 SpeechVoiceRoutePicker 选定音色 THEN sourceJson 的 toneID 携带 voiceId（speakerName 仅显示名），保存后该规则段按音色朗读（含摘要/校验链 toneID 判定通过）。
- **S4-2 voices 目录降级**：WHEN script 引擎 /voices 目录拉取失败或超时 THEN 声源降级为引擎级（toneID 空缺省）并提示，模板保存与朗读不被阻塞。
- **S4-3 current 哨兵替换**：WHEN 规则声源为 engineType=current 哨兵且运行时生效路由为系统引擎 X THEN 解析期哨兵被替换为 X 实际路由，切引擎后跟随新路由。

### R5 场景

- **S5-1 防抖+双 token 正常流**：WHEN 用户快速点击不同音色试听 THEN 600ms 防抖合并短连点；切换目标时旧 token 失效、旧请求与播放作废，仅最新目标发声。
- **S5-2 试听资源释放**：WHEN 弹窗 dismiss/onDestroyView 或播放出错 THEN stopActivePreview 执行（token++/cancelJob/release/删临时文件），朗读通道不受试听影响，缓存目录无残留增长。
- **S5-3 beforePreview 暂停恢复**：WHEN 朗读进行中用户点击试听 THEN beforePreview 先暂停朗读；WHEN 试听成功结束/合成失败（脚本链 10s 超时、临时 TTS init 失败）/用户不试听直接 dismiss THEN 三条路径均经 stopActivePreview/finish 统一路径恢复朗读原位，进度零丢失，无恢复死角。
- **S5-4 试听独立通道**：WHEN 试听合成/播放进行中 THEN 试听 utteranceId 带 `preview_` 前缀独立于朗读通道，朗读 onDone-onStop-onError 推进闸不受试听事件污染。

### R6 场景

- **S6-1 书级覆盖优先级**：WHEN 本书设置书级覆盖模板 A 且全局激活模板 B THEN 本书朗读用 A，其他书仍用 B；覆盖行明示"书级覆盖"来源。
- **S6-2 清除回退全局**：WHEN 清除本书书级覆盖 THEN 本书回退全局 B 并经 invalidateSnapshot 即时生效。
- **S6-3 无当前书上下文**：WHEN 从无书上下文入口（如全局设置）进入 THEN 书级覆盖行不可用或隐藏，不写入脏 override。
- **S6-4 覆盖模板被删除**：WHEN 本书覆盖的自定义模板被删除 THEN 覆盖引用自动清除并回退全局，覆盖行状态即时刷新。

### R7 场景

- **S7-1 AI tag 命中消费**：WHEN AI 分镜 tag 为 `ai:张三` 且激活模板含 `ai:张三` 规则 THEN 该段按规则声源+韵律朗读，五元组分段契约不变。
- **S7-2 AI tag 未命中兜底**：WHEN `ai:张三` 未命中任何规则 THEN 段级回退 fallbackSourceJson；仍不可用维持既有单声兜底，不中断朗读。
- **S7-3 type==1 过滤维持**：WHEN AI/模板链枚举候选 httpTTS 声源 THEN type==1（URL 模板）维持既有过滤语义，不进入 script 消费分支。
- **S7-4 AI×模板共存口径**：WHEN AI 分镜开启但**未激活任何选角模板**（期1 存量典型态） THEN 全部段落维持期1 既有单声兜底行为，与期1 输出一致；WHEN AI 分镜开启且激活模板含 narration/dialogue 规则 THEN 分镜旁白/对白段命中模板规则换声（**预期行为非回归**，升级前 updateLog 面向用户告知）。

### R8 场景

- **S8-1 回归清单执行**：WHEN 期2 交付前执行上游 §1.8-F 回归清单 THEN C1-C11 全部 PASS 并登记结果。
- **S8-2 降级链语义不变**：WHEN 声源合成连续失败 THEN 降级链与熔断行为与上游一致（重试 → 回退默认引擎+toast → 暂停+通知），本期 UI 改动未引入偏差。
- **S8-3 存量模板流不动**：WHEN 存量 type=1 httpTTS 模板与期1 内置 4 引擎模板在期2 版本上使用 THEN 合成路径/降级行为/模板数据流与期1 完全一致（**缓存键例外**：TtsCacheKeys+KEY_VERSION 首升为有意失配，存量音频一次性重合成，见 §3.7.1-2 权威声明）。

### R9 场景

- **S9-1 正常预合成流**：WHEN 用户在阅读菜单选择起止章节发起批量预合成（门控三条件满足） THEN 前台服务通知逐章进度（含参数摘要），队列串行合成落盘 httpTTS 目录并登记保留名单，完成后通知明示，通知点击进入缓存管理页。
- **S9-2 幂等跳过+账目基准**：WHEN 队列中某单元已存在同键缓存文件 THEN has() 命中直接跳过不重复合成；账目=入队时 has() 预扫描后的剩余待合成/总单元，重发同区间百分比无二义。
- **S9-3 能力门控禁用态**：WHEN 门控三条件任一不满足（当前生效引擎为系统 TTS / 流式模式开启 / 多角色模板激活） THEN 预合成入口禁用并明示具体原因，不产生任何半成品文件。
- **S9-4 取消与 .part 清理**：WHEN 用户取消任务（页面或通知栏）或服务 onDestroy/onTaskRemoved THEN 队列 cancelFlag 中止，.part 在途文件按 preserveInProgress 约定清理，已完成产物保留可用（保留名单同步移除未完成项）。
- **S9-5 播放命中零等待（单声路径）**：WHEN 预合成完成的章节开始朗读（单声路径+同键版本） THEN 播放侧同键缓存命中直接播本地文件无网络请求，且产物未被播放侧 10 分钟清理驱逐（保留名单生效）；WHEN 存量期1 版本升级后首次播放旧内容 THEN 键升版失配触发一次性重合成（不报错）。
- **S9-6 播放与预合成双向并发防护**：WHEN 批量预合成推进到正在实时朗读（或预下载）的章节 THEN 队列跳过延后（租约命中，延后 3 轮上限后强制跳过计账）；WHEN 反向朗读推进到批量队列正在写 .part 的章节 THEN 原子提交保证读到完整文件或未命中回源，无半成品损坏。
- **S9-7 缓存管理一致性**：WHEN 在缓存管理页查看/清理 TTS 维度 THEN 预合成产物纳入体积统计；播放中清理被拒绝并明示（对齐视频维度保护）。
- **S9-8 清理/删书与任务竞争**：WHEN 预合成进行中在缓存管理页清理 httpTTS 维度（或删除该书） THEN 进行中任务按 bookKey 取消+保留名单清空，无幽灵文件与 has() 误判；重发任务可重建。
- **S9-9 跨书排队与参数快照**：WHEN 任务 A 进行中为另一本书发起批量预合成 THEN 请求 FIFO 排队追加（State 携带各自 bookKey）；WHEN 任务进行中用户切换引擎/修改模板 THEN 进行中任务按入队参数快照继续不受影响，完成通知带回参数摘要。

## 6. 需求-场景覆盖矩阵

| 需求 | 场景 | 覆盖类型 |
|------|------|----------|
| R1 | S1-1 / S1-2 / S1-3 / S1-4 | 正常 + 空态 + 循环兼容路径 + 朗读中切换 |
| R2 | S2-1 / S2-2 / S2-3 / S2-4 / S2-5 | 只读复制 + 删除被引用 + 启停激活 + 副本独立 + 停用被覆盖引用回退 |
| R3 | S3-1 / S3-2 / S3-3 / S3-4 / S3-5 / S3-6 / S3-7 | 坏正则 + 跨通道定位 + 坏 JSON + 幂等 + 保留字 + 空 pattern + 超前版本拒绝 |
| R4 | S4-1 / S4-2 / S4-3 | 音色正常流 + voices 降级 + 哨兵替换 |
| R5 | S5-1 / S5-2 / S5-3 / S5-4 | 防抖 token + 资源释放 + 暂停恢复 + 独立通道 |
| R6 | S6-1 / S6-2 / S6-3 / S6-4 | 书级优先 + 清除回退 + 无书上下文 + 覆盖被删 |
| R7 | S7-1 / S7-2 / S7-3 / S7-4 | 命中消费 + 未命中兜底 + type==1 过滤 + 零回归 |
| R8 | S8-1 / S8-2 / S8-3 | 回归清单 + 降级链语义 + 存量流不动 |
| R9 | S9-1 / S9-2 / S9-3 / S9-4 / S9-5 / S9-6 / S9-7 / S9-8 / S9-9 | 正常流 + 幂等账目 + 门控三条件 + 取消清理 + 命中复用与键升版 + 双向并发 + 播放中保护 + 清理删书竞争 + 跨书排队与参数快照 |

> 编号规则：`S{R 序号}-{场景序号}`；强制覆盖 12 项见 §5 引言。实施期场景即验收脚本大纲：正常流走 L2 真机快速脚本，异常/边界流（正则校验/同通道/JSON 容错/哨兵替换/租约并发）优先 JVM 单测覆盖。
