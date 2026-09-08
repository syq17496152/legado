# optimize-tts-engine 任务清单（TTS 朗读引擎统一优化）

> **状态**：🔄 待开发（设计审核中）
> **关联文档**：
> - Spec：`docs/specs/optimize-tts-engine/spec.md`
> - Design：`docs/specs/optimize-tts-engine/design.md`
> - 设计简报（输入源，验收后删除）：`temp/tts-design-brief.md`
> **测试包口径**：真机/模拟器统一使用 `io.legado.miss.app.debug`（`build-legado.bat` 产物）
> **验证标准图例**：L1=编译（BUILD SUCCESSFUL / 单测全绿）｜L2=真机/模拟器功能验证｜L3=场景回归
> **任务统计**：共 31 项，核心任务（★）15 项

---

## 1. 准备工作

- [ ] 1.1 加载子规范（开发前置，逐项确认已读）
  - `docs/project-rules/coding-philosophy.md`（极简哲学 / 精准修改 / 简化标注三段式）
  - `docs/project-rules/logging-during-refactoring.md`（AppLog 惯例，禁 android.util.Log 残留）
  - `docs/project-rules/version-delivery-sync.md`（updateLog 编译前更新门禁）
  - `docs/project-rules/database-migration-safety.md`（ALTER TABLE 增列安全迁移）
  - `docs/project-rules/global-thinking-checklist.md`（前端入口/后端接口/数据库/覆盖安装/使用场景/回填点 6 维盘点，见简报 §5）
  - 验证标准：五项子规范全部加载，实施全程遵守（无门禁违规）

- [ ] 1.2 ★ 精读核心源码并核实简报行号证据（12 文件 = 8 核心 + 4 二轮补充）
  - Read 8 个核心文件：`model/ReadAloud.kt`、`service/TTSReadAloudService.kt`、`service/HttpReadAloudService.kt`、`help/readaloud/speech/SpeechModels.kt`、`help/readaloud/speech/SpeechVoiceCatalogRepository.kt`、`ui/book/read/config/SpeakEngineDialog.kt`、`data/entities/HttpTTS.kt`、`data/AppDatabase.kt`
  - Read 4 个二轮审查补充文件：`help/readaloud/speech/SpeechVoiceGroupRepository.kt`（第三处 SelectItem 生产点+分组 key）、`ui/main/ai/AiChatSpeechPlayer.kt`（候选消费点）、`ui/book/read/config/HttpTtsEditDialog.kt`（script 编辑域落点）、`MediaButtonReceiver.kt`（aloudClass 同步消费调用方）
  - 逐条核实简报 §2.1 行号证据仍准确：写读分裂（SpeakEngineDialog selectRoute 写 SpeechRoute JSON / initTts 按 SelectItem 解析恒 null）、`getReadAloudClass` isNumeric 恒 false、`refreshReadAloudClass` 只重算不停服务、ReadAloud `runBlocking(IO)` 查库、httpTTS 可变单例、SpeechVoiceCatalogRepository SelectItem 嵌套补丁、`fromTtsEngineValue` legacy 地雷、`sysEngines` 死代码、initTts 无超时
  - 若行号漂移：以当前代码事实为准修订实施细节，并在第 5 节 AOAdapt 日志区记录
  - 验证标准：L1 前置——`git status` 干净或现存改动与朗读模块无关（确认无并行会话冲突）

- [ ] 1.3 备份受影响文件到 bak/ 目录
  - 按 `{文件名}.bak` 规则备份简报 §4 列出的全部修改文件（含 AppDatabase.kt 与 migration 文件）
  - 验证标准：bak/ 备份数 = 计划修改文件数，可回滚

- [ ] 1.4 数据库前置核实
  - 全仓检索确认 `httpTTS` 表无 `@DatabaseView` 引用（搜技术字段，禁止搜业务数据字段）
  - Read `AppDatabase.kt` 确认当前 version 号与 migration 链尾位置，确定本次递增目标版本（N→N+1）
  - 验证标准：L1 前置——核实结论（无 @DatabaseView 引用 + 目标版本号）确认后才开始 2.4

## 2. 核心实现（按依赖顺序）

- [ ] 2.1 ★ SpeechModels.resolveSpeechRoute 四态解析（路由单源化，修 Bug 核心）
  - 新增 `resolveSpeechRoute(raw)` **纯字符串同步解析（不查库）**，四态：① SpeechRoute JSON（新格式）→ 直读 engineType/engineValue ② SpeechRoute JSON 且 engineType=system 且 engineValue 本身为 JSON（双嵌套，存量用户）→ 解包内层 SelectItem 的 value 作包名 ③ legacy SelectItem JSON（含 title/value）→ 取 `optString("value")` 作包名（禁止照抄 fromTtsEngineValue 的 engineValue=raw 整串行为）④ 纯数字 → legacy HttpTTS id 映射为 http 路由（存量用户数据不丢）；空白 → default
  - `fromTtsEngineValue`（SpeechModels.kt:126-131）保留 legacy 兼容，但新数据不再产生该格式
  - 单元测试依赖：`testImplementation("org.json:json")`（当前测试 classpath 无 org.json，returnDefaultValues 桩对 JSONObject 无断言意义）
  - 新增单元测试：四态正常路径（含双嵌套用例）+ 异常输入（空串 / 坏 JSON / 非法数字）
  - 验证标准：L1——resolveSpeechRoute 单元测试全绿（四态 + 异常输入用例）

- [ ] 2.2 ★ ReadAloud 路由改造
  - `getReadAloudClass`（ReadAloud.kt:29-41）改为经 `resolveSpeechRoute` 按 route.engineType 分派 system/http（script 引擎同走 http 服务，见 2.6），修复 isNumeric 对 JSON 恒 false 的 HTTP 路由 Bug；**resolveSpeechRoute 纯同步解析（不查库），14 处 aloudClass 同步消费点（ReadAloud.kt:54/95/111/128/137/150/160/168/176/189/197/205/213/223）与 4 个调用方（MediaButtonReceiver.kt:109、ReadBookActivity.kt:4176、ReadAloudPlayerPanel.kt:1052、SourceLoginJsExtensions.kt:96）零改动**
  - `httpTTS` 记录改由 `HttpReadAloudService` 服务内按 `route.engineValue` 查库装配（HttpReadAloudService.kt:155/239/447 三点改服务内装配；记录缺失明示报错，不静默回退）——去 `runBlocking(IO)`（ReadAloud.kt:35）与 `httpTTS` 可变单例残留收敛同步完成
  - `upReadAloudClass()` 统一切换语义：服务运行中先捕获 `PendingSwitch(wasPlaying/pageIndex/startPos)` 存 ReadAloud 静态记录（续播意图上收数据层，面板 STOP 分支改从 `ReadAloud.consumePendingSwitch()` 取，替换面板本地 switchingTtsEngine/pendingTtsEngineSwitch 字段）；新旧路由同为 TTSReadAloudService → 不下发 STOP，改发新增 `reInitTts` IntentAction 引擎内重建；跨类型 → **先重算（同步）后 stop** → 重启；`refreshReadAloudClass` 仅用于未运行时场景（AD-02 切换即时生效语义）
  - 验证标准：L1——编译通过；`runBlocking` 在 ReadAloud.kt 中无残留；14 处 aloudClass 同步消费点与 4 个调用方签名零改动（diff 审计）

- [ ] 2.3 ★ TTSReadAloudService：init 路由 + 超时看门狗 + 降级明示 + 引擎参数
  - 按 route 包名 init：`TextToSpeech(ctx, listener, enginePackage)`（修复 SelectItem 解析 `.value` 恒 null 导致永远默认引擎）
  - init 超时看门狗 8s（AD-03）：超时 clearTTS → 回退默认引擎 → toast 明示"引擎 X 初始化失败已回退"；onInit 失败同处理
  - 新增 `reInitTts` IntentAction 处理：收到后 `@Synchronized` clearTTS+initTts 引擎内重建（同服务类型切换不下发 STOP，AD-02 分支）
  - 看门狗防护：`onDestroy`/`stopSelf` 时 `removeCallbacks` + 实例代际判定（迟到回调/新实例防护）；终止条件——回退目标已是默认引擎时不再重建（防死循环），直接暂停+通知终态；回退后的默认引擎初始化单次不挂看门狗（失败仅 toast+停止朗读）
  - 回调竞态用主线程 Handler 收敛
  - 每引擎独立 speed/pitch/volume 配置（读 SpeechRoute 扩展字段 / 新 PreferKey，与 2.10 联动）
  - 验证标准：L1——编译通过

- [ ] 2.4 ★ HttpTTS 实体扩展 + 安全迁移
  - `HttpTTS` 增列 `type`（Int，1=http 模板默认，2=script）、`script`（String，JS 源码），字段全默认值（项目 Room 实体惯例）
  - `equal()` 比对补 type/script 字段；`fromJsonDoc` 补新列解析 + 缺字段逐条容错；legacy httpTTS JSON（无 type/script 字段）反序列化 type 默认 1 往返单测
  - AppDatabase version 递增 + ALTER TABLE 增列 migration（**禁止 DROP TABLE，规避 NG 丢数据缺点**；依赖 1.4 核实结论）；migration 语句以 `kotlin.runCatching` + `AppLog` 包裹（database-migration-safety R2 容错要求）
  - 确认 Dao 无需改动（实体增列带默认值，现有查询/插入兼容）
  - 验证标准：L1——编译通过 + connectedAndroidTest migrateAll 通过（migration version 以实施时 AppDatabase.kt 实际 version 为基准 +1，写作时点 109→110；androidTest，需模拟器）+ 3.3 覆盖安装 L2

- [ ] 2.5 ★ TtsScriptEngineClient 新增（脚本引擎执行器，AD-04）
  - 新增 `help/readaloud/script/TtsScriptEngineClient.kt`：Rhino 执行三函数 `options()/voices()/synthesize(text,voice,params,options,ctx)`，**三函数执行统一包 `withTimeout(10s)`**（Rhino 指令观察器仅协作式取消，RhinoScriptEngine.kt:327,340-344，防不住 while(true)）
  - 头注契约解析：`@name/@schema/@capabilities/@defaultSpeed`——**注明为本项目自定义契约（借鉴 NG 概念，NG 无 @capabilities 字面量）**；voices() 兼容多参调用
  - Rhino 作用域装配与暴露面清单：不向脚本暴露 HttpTTS 对象（复核 App.kt:465 RhinoWrapFactory.register(HttpTTS) 包装面），脚本仅获脱敏 sourceLabel 与白名单 JsExtensions 面，实施时输出可访问对象/函数/常量的暴露面清单
  - synthesize 返回值映射：URL 或请求对象 → AnalyzeUrl 请求链（本期支持 HTTP 轮询型；SSE/WS 流式登记后续，不阻塞）；**请求对象仅承接 url/method/headers/body 四字段（AnalyzeUrl.kt:244-298 原生支持），NG 多出的 transport/audioExtract/responseType 等越界字段拒绝导入并明示**
  - 体积限额：synthesized URL≤8KB、请求体≤256KB、脚本源码≤512KB、voices 目录超限截断并明示
  - **强制 P0 沙箱接入**：`RhinoClassShutter` 类白名单 + `SourceSandboxExtensions` 文件沙箱，文件访问走 `BookSourceStorageScope`（规避 C 版无沙箱缺点）
  - **沙箱启用条件（已核实，必须落实）**：HttpTTS 非 BookSource，`BaseSource.withBookSourceClassPolicy` 包装（enabled = this is BookSource）对 HttpTTS 不生效——须显式调用 `RhinoClassShutter.withBookSourceClassPolicy(enabled = true, sourceLabel = <脱敏短码>)` 或扩展 BaseSourceExtensions 启用分支（见 design §3.3）
  - 验证标准：L1——编译通过 + 沙箱拦截单测全绿（脚本越权访问/反射逃逸用例被拦截，含 HttpTTS 上下文类策略确实启用断言）

- [ ] 2.6 ★ HttpReadAloudService 接入脚本引擎（AD-07）
  - type=2 分支：脚本引擎合成路由走现有 HttpReadAloudService 合成链（并发预下载 / 缓存 / ExoPlayer 全复用）；**type=2 接入点统一收口 `getSpeakStream`**（流式 downloadAndPlayAudiosStream 与非流式 downloadAndPlayAudios 两路径共用，HttpReadAloudService.kt:233-332，流式 loader 线程 runBlocking 内脚本执行受 10s 超时保护）
  - **候选过滤 type==1**：AiChatSpeechPlayer.kt:346-347 / AiReadAloudRoleService.kt:2507/2876 / SpeechVoiceAssigner（SpeechModels.kt:272-273）等以 httpTTSDao.all 为候选的消费点统一过滤 type==1（type=2 不进 AI 语音/多角色候选，本期过滤、登记后续支持）
  - voices() 结果缓存进既有 speakersJson 字段（复用现音色目录机制）；并发写回 in-flight 去重（按引擎 id 锁）；写回同步更新 lastUpdateTime
  - 缓存键扩展：引擎类型 + voice + speed + volume + pitch + capabilities 声明维度（未声明的参数维度不进缓存键，防能力协商污染）
  - 缓存写 .part 临时文件 + rename 原子发布
  - 引擎级并发上限（复用 concurrentRate 基建，脚本引擎默认 2）
  - **缓存定型项（对照 design §3.6，防未来补预合成/缓存管理返工）**：缓存 key 纯函数化+KEY_VERSION 参与哈希并收敛单一入口；key 维度（engineKey/speedKey/voiceKey）与引擎实际生效参数同源解析（单点函数）；目录结构定型（书目录/章节 stem 同正文缓存主名/单元 hash 文件名+按章删除钩子）；写缓存提交收敛单一原子函数（未来租约门控唯一插桩点）；合成函数签名纯化进度无关；key 全输入可枚举可持久化
  - 验证标准：L1——编译通过

- [ ] 2.7 内置模板 4 个 JS（AD-06）
  - 新增 `app/src/main/assets/defaultData/tts/`：
    - `multitts_forwarder.js`（localhost:8774 /forward + /voices；speed 换算 `{{speakSpeed*5}}`——本仓 `speakSpeed = AppConfig.speechRatePlay + 5`（HttpReadAloudService.kt:93,357），默认 10 → MultiTTS speed 参数 50（假定 50=常态速度）；volume/pitch 按 MultiTTS 参数域换算，真机校准判据：默认语速下 MultiTTS speed 参数=50）
    - `clonetts.js`（localhost:8080 /api/tts，speed 换算 `{{speakSpeed/10.0}}` 并 clamp 0.5~2.0，默认 10 → 1.0 倍速；真机校准判据：默认语速下 CloneTTS=1.0x；支持 /api/legado/all 批量导入入口）
    - `openai_compat.js`（OpenAI /v1/audio/speech 兼容，覆盖主流在线服务自定义端点）
    - `edge_proxy_template.js`（**@enabled false，端点留空由用户填，不硬编码任何第三方 IP**）
  - 模板加载机制：参照 DefaultData + `assets/defaultData/httpTTS.json` 先例（help/DefaultData.kt:50-56,118-121），asset 目录枚举 + 头注解析呈现，导入时写入 httpTTS 表（type=2）
  - 全部默认停用，启用需用户确认
  - 验证标准：L1——4 个资源文件存在 + 头注 JSON 可解析 + Rhino 语法试执行通过

- [ ] 2.8 SpeakEngineDialog / SpeakEngineViewModel 改造
  - "内置模板"导入入口：逐个导入，启用需用户确认，冲突策略 OVERWRITE / KEEP_BOTH（借鉴 NG）；导入逐条 `kotlin.runCatching`，结果明示成功/跳过/冲突计数
  - CloneTTS 一键导入：拉取 /api/legado/all 返回的 JSON 数组 → 批量导入为 httpTTS 记录（逐条 runCatching + 计数明示）
  - `HttpTtsEditDialog` 增补 script 编辑域（脚本内 CONFIG 区常量由用户编辑端点/密钥；本期不做 options() 参数化 UI，登记后续）
  - 死代码清理：`SpeakEngineViewModel.sysEngines`（lazy 构造临时 TextToSpeech）
  - 验证标准：L1——编译通过

- [ ] 2.9 UI 一致性收口
  - ReadAloudPlayerPanel `selectTtsEngine` 统一走 `upReadAloudClass`（与设置路径同语义）
  - SpeechVoiceCatalogRepository：删除"SelectItem JSON 嵌套进 engineValue"补丁（:131），改生成结构化 SpeechRoute；系统引擎枚举（PackageManager 查 INTENT_ACTION_TTS_SERVICE）生成结构化 SpeechRoute（engineType=system、engineValue=包名，AD-05，借鉴 C 版）
  - 三文件 legacy 波及适配：`SpeechVoiceGroupRepository`（:177 第三处 SelectItem 生产点结构化 + 分组 key 含 engineValue（:44/187/191）存量条目等价性）、`ReadAloudConfigDialog`（fromTtsEngineValue 消费适配 :229）、`SpeechRouteSanitizer`（legacy 语义变更波及盘点 :60/71/118/121/130/134/140/161 + type=2 清理行为核实）
  - `HttpTtsEditViewModel.kt:50` 保存后 refreshReadAloudClass 改为服务运行中走 upReadAloudClass 语义（与 AD-02 对齐）
  - SpeechVoiceRoutePicker 适配新结构化路由格式
  - SpeechRouteSanitizer 失效清理核实（旧补丁格式的清理逻辑是否需同步失效；type=2 脚本记录与 speakersJson 缓存的失效清理行为是否覆盖）
  - （可选，spec R 级）书级覆盖 Book.setTtsEngine 被两处 UI 强制写 null 失效修复
  - 验证标准：L1——编译通过

- [ ] 2.10 strings.xml 文案 + AppConfig 配置
  - strings 策略：`values/` 全量新增 + 其余 7 locale 缺省回退声明（不逐 locale 硬译）：模板导入 / 冲突策略 / 降级回退 toast / 引擎初始化失败 / 引擎配置无效已回退等
  - AppConfig 新 PreferKey（每引擎 speed/pitch/volume 存储，与 2.3/AD-05 联动）；**新键组必须登记 `allPreferenceKeys`（ReadAloudConfigDialog.kt:149-166），防备份/清理遗漏**
  - 验证标准：L1——编译通过，values/ 资源无缺 key（其余 locale 走缺省回退）

- [ ] 2.11 ★ 范式模板层：模型+内置模板+分段规则（AD-09 v1.2，2026-09-08 增）
  - **实施约束：逐条对照 design §3.7/§3.8 子系统定型项清单**（§3.7 roleType 枚举一套词汇+StoryboardSegment 最小核分段产物+分镜缓存 key 源标识 `template:<版本>` 占位；§3.8 引擎 JSON 双命名兼容+冲突 Resolver 复用+voiceParamsJson 键结构+@capabilities 透传）
  - 新增 `help/readaloud/casting/`：`TtsCastingModel.kt`（声明式模板模型：tag narration/dialogue/角色名 × match{builtin_quote|regex|keyword} × source SpeechRoute × prosody；JSON 序列化/幂等导入导出）、`TtsCastingStore.kt`（内置 4 模板默认启用+用户模板 CRUD+备份链路登记）、`TtsTagSplitter.kt`（内置引号规则：引号内=对白/引号外=旁白；regex/keyword 规则执行）
  - **模型类命名避让**：casting 包模型类**不用 `TtsCastingTemplate` 命名**（与 Room 实体 data/entities/TtsCastingTemplate 同名异包易混淆）——模板模型类=`TtsCastingModel`、规则集类=`CastingRuleSet`
  - **存储落地**：Room 实体 `TtsCastingTemplate`（id/name/builtin/enabled/order/rulesJson/fallbackSourceJson/lastUpdateTime）+ `TtsCastingTemplateDao`；**v110 同版建表**（与 httpTTS 增列同一 migration，CREATE TABLE ttsCastingTemplates，runCatching+AppLog 包裹）；内置模板 `assets/defaultData/tts/castingTemplates.json` 经 DefaultData 链导入（幂等键 templateId，builtin 冲突跳过）；BackupController 增模板导出/恢复
  - 内置模板：①旁白/对白双声（引号规则，系统声源）②男女对读（性别启发式）③MultiTTS 对话透传（系统引擎透传整段）④单声（关闭）
  - regex 规则 Pattern **构造时预编译校验**（非法正则导入即拒，防用户正则卡 IO 主链）；rulesJson 含 `schemaVersion` 字段
  - 验证标准 L1——JVM 单测全绿：引号分段（中英文引号集显式枚举/嵌套=外层优先/跨段未闭合=段内闭合）、规则匹配（数组顺序首命中/区间互斥先到先得）、模板 JSON 往返、schemaVersion、幂等导入、Pattern 预编译校验
  - 验证标准 L1——androidTest `migrateAll`（与 2.4 合并执行，MigrationTest.kt:32-76 先例，需模拟器）：含 ttsCastingTemplates 同版建表断言
- [ ] 2.12 ★ 适配层统一声源接口 + 服务侧路由消费（AD-09 v1.2；拆期边界按 design §3.5.6：期1=单实例逐段 setVoice 主链，期2=HTTP/script 按段换源+书级覆盖+AI 链接入）
  - `TtsVoiceSource` 三类统一抽象（引擎级：getVoices 异步+缓存、国产枚举不全降级引擎级；provider/音色级：MultiTTS /voices；HTTP 参数级：CloneTTS voice UUID）；UtteranceResult sealed 三态（SpeakSubmitted/AudioFile/AudioStream，design §3.5.3）
  - **逐段 onDone 驱动（期1）**：多人模式取消整章预入队，speak 循环逐段提交；**单实例逐段 setVoice**（speak 调用时刻生效；setVoice 支持判定=复用 getVoices 异步缓存快照预判（play 链不直连 getVoices，design §3.5.2③；快照空集或不含目标→直接降级）+运行期 onError 兜底）；**双实例（跨引擎）登记后续，本期不做**
  - **分段契约五元组**：`(text, tag, paragraphIndex, offsetInParagraph, length)`——服务侧保持原 contentList 段落单元不变，tag 段为段内 sub-utterance，进度算术按段元数据折算（精确字符账，禁按比例折算）；pause 静音项仅 paragraphIndex 变更时插入
  - **moveTo 死路径声明**：cue 定位存量链不消费 moveTo，tag 段映射不挂 moveTo（死路径不做适配，防实施时误挂）
  - setVoice 不支持/零第二声源：降级引擎默认音+首次 toast 明示（dialogue=旁白同音时明示单声终态）；声源失败段级回退 fallbackSource（AD-08 链）
  - HTTP 声源按段换源（期2，HttpReadAloudService 段级 HttpTTS/voice 装配+段级 try/catch 回退+预下载段元数据——**如实评级：中改约 3-4 天**，design §3.5.2③）
  - L2 AI 链接入（期2）：AI 角色名 tag（`ai:` 前缀）走同一模板层（未命中→角色绑定→性别兜底→旁白→默认，NG/C 式兜底链）；routeForCue 接入播放链修复"路由仅 UI 展示"
  - 书级模板覆盖（期2）：书级 config 存模板 id（与书级引擎覆盖 R9 同构），resolve 时书级优先全局
  - 路由命中可观测性：每段 tag→source→结果 AppLog.putDebugWithTag 节流日志（统一 tag，多角色链问题定位）
  - 对白段剥离引号字符（不读出，正文显示保留）+ 纯静默段跳过（对标 NG TtsSynthesisText）；prosody clamp（rate/pitch/volume coerceIn 限幅）；tag 段生产令牌 isCurrent 校验（防换书/跳转竞态，对标 NG generation）
  - **基座硬护城河同批移植（§5.6，与逐段驱动重构同文件同批）**：playbackStateOwner 单实例仲裁（服务重建后旧实例回调不覆盖新实例）+actualPlaybackConfirmed 真实播放确认+tryReusePreparedPlayback/旧请求终止（NG BaseReadAloudService.kt:95/98-112/264-345 三项，一次改完防二次返工）
  - **回归保护清单落实（§5.7）**：分段契约补 readAloudByPage 分页特判分支（C6）；ttsParagraphPauseMs 段间停顿+pause 静音项在逐段驱动循环的插入位置定义（C10）；响度学习 loudness key 输入不变性评估（C5）；tag 段与 Cue/Planner 坐标映射声明（C7）；IntentAction 全集外部广播兼容核对（C11）——C1 BGM/C3 token/C4 悬浮球归 3.8 回归
  - 验证标准：L1——编译+单测（分段路由/句级轮换/失败回退/模板热切换/书级覆盖优先级/引号剥离/限幅） **进度算术回归（翻页 readAloudNumber/onRangeStart/续播 substring：按段元数据折算与段落级进度链零改动）+分页朗读分支回归**
- [ ] 2.13 多人听书入口与模板管理 UI（AD-09 v1.2；拆期边界按 design §3.5.6：期1=播放面板内嵌模板选择列表（builtin_dual_voice/mono 先上）+入口，期2=管理页/编辑器/书级覆盖 UI）
  - **UI 锚点钉死**：模板选择=播放面板内嵌列表（与高亮规则选择同构交互）；模板管理页=新 Activity（对齐 HighlightRuleActivity 先例 ReadBookActivity.kt:902，内置只读+自定义编辑+JSON 导入导出分享）；编辑器声源选择=跨仓聚合 system+httpTTS(type=1) groups（SpeakEngineViewModel 装配逻辑复用）；strings.xml 中英双语
  - **Scene 闸位解耦（期1）**：播放面板场景模式闸位 DisplayMode.Scene 现被 `if (multiRoleEnabled)` 门控（ReadAloudPlayerPanel.kt:3598）——期1 改由激活 casting 模板驱动显示（解耦 aiReadAloudRoleEnabled，零 AI 配置也显示场景模式）
  - 导入声源存在性校验：rules 内声源可达性检查，缺失项标记"待绑定"不静默生效（§5.3）；同通道校验（rules 内全部 source.engineType 一致，跨通道拒绝保存/导入并明示，design §3.5.6①）
  - 音色试听（期2）：引擎/音色选择处对单段文本试听（design §3.8 定型项 6 签名：key(engine,voice,style)+debounce 防抖+token 防竞态+临时文件落盘）；L2 用例：试听可播放可取消，连续点击防抖不叠加播放
  - 真机 L2 归 3.8 场景组（补用例：【期1】零配置内置模板双声听感+日志双证/MultiTTS 透传/声源不足降级/模板切换即时生效；【期2】书级覆盖/JSON 导入导出幂等）
  - 验证标准：L1——编译

## 3. 验证测试

- [ ] 3.1 updateLog.md 更新（编译前强制）
  - 基于 `git diff` 逐文件对照分析真实变更，追加在 `## cronet版本:` 之后、已有条目之前
  - 面向用户语言，逐文件审计不漏项，禁止文字合并旧条目
  - 验证标准：L1 前置——updateLog 覆盖全部用户可感知变更

- [ ] 3.2 ★ 编译打包测试包
  - `build-legado.bat`（产物 `io.legado.miss.app.debug`）
  - 验证标准：L1——BUILD SUCCESSFUL + libcronet/APK 产物在位

- [ ] 3.3 ★ 模拟器覆盖安装 + 启动验证
  - 覆盖安装测试包（验证 httpTTS 增列 migration 不崩、存量数据不丢，database-migration-safety 强制项）
  - 启动 FATAL=0（logcat 崩溃模式分析，口径参考 `ai_tests/scripts/verify_no_crash.py`）
  - 验证标准：L2——覆盖安装后启动无崩溃 + 旧 httpTTS 记录可读

- [ ] 3.4 ★ L2 引擎切换主链真机（Bug 修复主验证）
  - 设置界面选系统引擎 A → 朗读生效（听感 + 日志双证：initTts 使用的引擎包名变化）
  - 切换引擎 B 生效；播放面板朗读中切换 → 续播语义正常（PendingSwitch 上收数据层 + consumePendingSwitch；同服务类型 reInitTts 引擎内重建）
  - **朗读中切换期间按蓝牙耳机键（媒体键 MediaButtonReceiver 路径）回归**：播放/暂停/停止响应正常，消费的 aloudClass 为切换后新路由
  - 验证标准：L2——两次切换均即时生效（日志 + 听感双证据），朗读中切换无缝续播，媒体键操作无异常

- [ ] 3.5 ★ L2 在线 TTS + 脚本引擎链路
  - 选择/新建 HttpTTS 引擎（真实在线模板）→ 路由到 HttpReadAloudService（日志证据）→ 合成播放正常（验证 HTTP 路由 Bug 修复）
  - 脚本引擎导入 multitts_forwarder 模板 + 设备装 MultiTTS 开转发服务 → /voices 音色目录拉取 → 合成播放正常
  - MultiTTS speed 换算真机校准：本仓 `speakSpeed = speechRatePlay + 5`（默认 10），模板 `{{speakSpeed*5}}` → **校准判据：默认语速下 MultiTTS speed 参数=50 听感为常态速度**，边界值（clamp 后）无倍速异常
  - 验证标准：L2——http 路由播放正常 + 脚本引擎音色目录拉取与合成链路全通

- [ ] 3.6 ★ L2 CloneTTS 适配验证
  - /api/tts 单音色播放 + speed 换算正确（模板 `{{speakSpeed/10.0}}` 并 clamp 0.5~2.0：**校准判据——默认语速下 CloneTTS=1.0x 听感为常态倍速**；边界值经 clamp 无倍速异常）
  - /api/legado/all 批量导入 N 条记录（导入计数与源返回计数一致）
  - 验证标准：L2——单音色播放正常 + 批量导入记录数核对一致

- [ ] 3.7 ★ L3 降级链回测（AD-08）
  - 停用 MultiTTS 转发服务 → 合成失败重试 1 次 → 回退默认系统引擎 + toast 明示原因
  - init 超时场景（构造不可用引擎）→ 8s 看门狗触发降级（onInit-FAILURE 路径 L2 实证；看门狗计时以 logcat 技术日志为准，不依赖秒表）
  - 路由解析失败场景 → "引擎配置无效已回退"明示，禁止静默失效
  - 验证标准：L3——三条降级路径均触发（onInit-FAILURE 路径 L2 实证 + logcat 技术日志证据），无静默失效、无僵死

- [ ] 3.8 L3 场景回归（Scope 兼容性强制项）
  - RSS 朗读（ReadRssActivity）
  - AI 聊天语音（AiChatSpeechPlayer）——**用例前置：库中已存在 type=2 记录（验证候选过滤后 type=2 不泄漏进 AI 语音候选）**
  - 多角色朗读（AiReadAloudRoleService）——**AI 链经模板层真机用例（期2）+ JSON 跨设备幂等导入用例**（原"仅回归不改链"表述废止，L-d 服务侧路由消费修复已触及该链边界）；同样验证 type=2 不进候选
  - 书级引擎覆盖回归（若 2.9 可选项实施：设置书级引擎后该书朗读优先使用）
  - 备份→恢复往返：httpTTS 导出含 type/script 新字段，恢复后记录完整（Backup.kt httpTTS.json 自动覆盖）
  - legacy 数据兼容：纯数字 id 的存量 httpTTS 记录播放 + legacy SelectItem 配置用户升级兼容（含双嵌套 legacy）
  - **BGM 播放回归（C1，§5.7）**：多人朗读开启 BGM 正常播放，BgmAssignmentCache 绑定不失效（缓存键改造不联动 BGM 键组）
  - **token 统计页回归（C3）**：AI 朗读后 token 用量统计页计数正常（L-d 链接入不触碰 token 统计链）
  - **悬浮球样式回归（C4）**：悬浮球样式三键设置在面板/Scene 闸位改造后不受影响（2.13 UI 改造避开悬浮球链路）
  - 验证标准：L3——各类场景回归无功能退化

- [ ] 3.9 静态检查
  - Grep `android.util.Log` 无残留调试日志（logging-during-refactoring 强制项）
  - 沙箱拦截单测全绿；全量单测 `./gradlew test` 通过
  - 验证标准：L1——零残留 + 测试全绿

- [ ] 3.10 AI E2E 影响分析（步骤 5.5）
  - 按需执行 `ai_tests\venv\Scripts\python.exe ai_tests/run_e2e.py --diff` 源码影响分析
  - 遵循 `ai_tests/docs/fixed_test_workflow.md`（必须用 ai_tests venv，禁止 temp/ 建临时脚本）
  - 验证标准：L2——影响面确认完毕；如需新增 L2 场景脚本，按白名单口径落位 ai_tests/scripts/

## 4. 文档收尾

- [ ] 4.1 文档同步
  - `docs/INDEX.md` 状态流转（🔄 → ✅）
  - `docs/project-flow/task-navigation.md` 朗读模块锚点更新（新增 TtsScriptEngineClient / 内置模板等）
  - 若新增模块结构，更新 `docs/project-flow/quick-reference.md`
  - 验证标准：文档与代码结构一致，锚点可达

- [ ] 4.2 issues-found.md 记录真机问题
  - 3.3~3.8 过程中发现的所有真机问题逐条登记（无问题则明确标注"无"）
  - 验证标准：真机问题零遗漏登记

- [ ] 4.3 清理
  - 临时日志清零；删除 `temp/tts-design-brief.md`（简报使命完成）；bak 备份保留至验收后
  - 验证标准：`git status` 无临时文件残留

- [ ] 4.4 项目记忆更新
  - `ai_memory_main.md`"当前任务状态"字段更新为完成态
  - 验证标准：记忆与实际交付状态一致

## 5. AOAdapt 日志区

> **用途**：实施过程中发现方案与代码实况偏差（行号漂移 / API 不符 / 依赖缺失 / 简报结论失效）时**必须**记录一条，禁止静默改方案。每条记录对应一次"计划 → 实况 → 调整"闭环，作为 design.md 修订与验收审计依据。

**格式**：

| 字段 | 含义 |
|------|------|
| Action | 原计划动作（对应任务编号，如 2.2） |
| Observation | 实际观察到的偏差（客观事实：文件/行号/异常类型） |
| Adapt | 调整决策（新做法 + 影响面 + 是否需回写 design.md） |

| # | 时间 | Action | Observation | Adapt |
|---|------|--------|-------------|-------|
| （空，实施中追加） | | | | |
