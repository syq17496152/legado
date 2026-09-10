# TTS 期1+期2 全量代码对抗性审查报告（2026-09-10）

> 审查范围：feat/optimize-tts-engine 分支 25 笔提交（c99db8e→bd46e53），66 文件 +13160 行
> 审查方式：4 个并行子代理分域全文审查（路由服务链/casting+脚本引擎/预合成缓存链/UI 层）→ 主代理对 P0/P1 逐条源码实证
> 实证方式：Grep 调用点核验 + 源码 Read 逐行 + jshell 格式串复现
> 结论：**P0×5 + P1×26 + P2×32（合并去重后 63 条）**。核心功能性主链（预合成命中、AI 朗读缓存链、脚本引擎音色目录）存在成建制失效，须修复后回归。
> ⚠️ 2026-09-10 三轮红队复审后修正：P0-4/P0-5/P1-6/P1-19/P1-24 结论有偏差（详见 §六），新增 F-1/F-2 两条 P1，修复方案以 §七 批次实施序为准。

## 一、P0（崩溃 / 主链功能不可用，已实证）

| # | 位置 | 问题 | 证据 |
|---|------|------|------|
| P0-1 | strings.xml:3279 + TtsPrebuildDialog.kt:181 | `tts_casting_prebuild_chapter_count`="本书共 %1 章"非法格式符，`String.format` 抛 UnknownFormatConversionException，任何有章节的书打开预合成对话框**必崩**（jshell 实测复现） | 应为 `%1$d` |
| P0-2 | SpeakEngineDialog.kt:482-497 + 542 | ImportChoiceDialog 唯一调用点未传 `onBuiltinScriptTemplates`（默认 `{}`），"内置脚本引擎模板"导入是**死按钮**，`importBuiltinScriptTemplates()`(302) 不可达 | Grep 实证 |
| P0-3 | TtsPrebuildManager.kt:240-244 | `getTextChapterAsync` 后**立即** `getNeedReadAloud`，但 LAZY 排版仅 `job.start()` 即返回且无 `isCompleted` 等待（播放端有守卫 BaseReadAloudService.kt:272-274，批量端没有）→ 每章几乎必然读到空/不完整分页 → 单元为空报"无可合成内容"或随机漏章，**预合成主线不可用** | 源码实证 |
| P0-4 | TtsPrebuildManager.kt:123,251 vs HttpReadAloudService.kt:95,689 | speedKey 键因子错配：批量端=`AppConfig.ttsSpeechRate`，播放端=`speechRatePlay + 5`（AppConfig.kt:2109）→ **键永不匹配，预合成产物播放端 100% miss**；且 TtsSynthesizer.kt:59/68 合成 speakSpeed 也是不同域，即使键对上音频内容也不一致 | Grep 实证 |
| P0-5 | AiReadAloudRoleService.kt:377,411 | `ensurePlayableCache`/`ensureCache` 全工程**零调用方**（期1 曾在 BaseReadAloudService.newReadAloud 调用，期2 重构丢失）；`routeForCue/routeForSegment` 零播放侧消费；TTSReadAloudService.kt:365 调 `resolveSourceForTag` 未传 characterId → 角色绑定/cast_role 两级永远跳过 → **AI 分镜选角链整体断链**（期1→期2 回归） | Grep 实证 |

## 二、P1（特定场景必现 BUG）

### 预合成/缓存链
| # | 位置 | 问题 |
|---|------|------|
| P1-1 | TtsPrebuildManager.kt:246 vs BaseReadAloudService.kt:277 | 切分口径不一致：批量端 trim+filter(isNotBlank)，播放端不 trim；排版层注入段首缩进（默认"　　"）参与键 → 默认配置下几乎每段键失配 |
| P1-2 | TtsPrebuildManager.kt:254 vs HttpReadAloudService.kt:527 | chapterTitle 因子：批量端用 bookChapter.title 原始值，播放端用 getDisplayTitle（去换行/简繁转换/替换规则）→ 标题含换行或开转换时键失配 |
| P1-3 | TtsPrebuildManager.kt:63 + HttpReadAloudService.kt:609-632 | 保留名单 reservedKeys 纯进程内存态，进程重启即空 → 下次 HTTP 朗读服务销毁时非当前章 >10min 产物**全部被驱逐**，设计承诺"仅缓存管理页清理/删书联动可移除"被打破 |
| P1-4 | TtsSynthesizer.kt:39-87 | 批量端缺 Content-Type 校验与 loginCheckJs（播放端 436-475 有）→ 错误页 JSON/text 被当音频写盘并计入 Success，且登记 reservedKeys 受保护 → **缓存毒化** |
| P1-5 | HttpReadAloudService.kt:583-605 + TtsPrebuildManager.kt:295 | createSpeakFile 先建空目标文件，写流失败遗留 0 字节 → 播放端误判命中播放空文件；批量端幂等判断仅 File.exists() 把 0 字节当"已完成"计 done |
| P1-6 | TtsPrebuildManager.kt:323-331 | 设计"失败重试 1 次"未实装，Result.retryable 是死字段 → 网络抖动单次失败即永久漏章 |
| P1-7 | TtsPrebuildManager.kt:271-293 | 租约语义错位：deferredRounds 按章累计而非轮次，正在朗读章节前 3 个单元被永久跳过且计 done → 终态 DONE 谎报整本完成 |
| P1-8 | TtsPrebuildService.kt:66-73 | observeState 首轮无延迟，enqueue→ensureWorker(SCANNING) 落地前窗口判 IDLE 即 stopSelf → 前台壳自杀，任务裸奔 |

### 朗读主链
| # | 位置 | 问题 |
|---|------|------|
| P1-9 | TtsVoiceSource.kt:116 + TTSReadAloudService.kt:249-312 | SystemEngineSource 每句 `setOnUtteranceProgressListener` **永久覆盖**服务级监听器且无恢复点 → 多角色会话后切回 legacy 路径（关模板/换书）onDone 落空转 listener → **整章读完静默卡死不推进** |
| P1-10 | TTSReadAloudService.kt:146-166 | onInit 无代际校验：reInit 后旧引擎迟到 SUCCESS 把新引擎 ttsInitFinish 置 true → 新引擎 init 挂死时看门狗条件恒假，永不降级 |
| P1-11 | TTSReadAloudService.kt:107,126-137,174 | postDelayed 挂的是捕获 generation 的包装 lambda，removeCallbacks 移除的是裸 initWatchdog → 永远移不掉 → **onDestroy 后 8s 在已销毁服务上重建 TextToSpeech**（泄漏+过期 toast） |
| P1-12 | BaseReadAloudService.kt:234-258 + ReadAloud.kt:132-147 | IntentAction.moveTo 无任何处理分支 → 播放面板拖进度/点 cue 发出后**静默丢弃，播放中 seek 完全无效**（服务未运行时才正常） |
| P1-13 | HttpReadAloudService.kt:269-281 | 流式路径 `if (currentHttpTts?.type == 2)` 依赖缓存字段，首播时未 resolve（resetCurrentHttpTts 在检查之后）→ **type=2 脚本引擎+流式开启首播必现失败**走错通道 |
| P1-14 | TTSReadAloudService.kt:386 + HttpReadAloudService.kt:395-400 | 韵律恒传默认 `CastingProsody()`，规则 pitch/rate 全无效；朗读链 synthesize 传 `null,null,null` → 全局语速对脚本引擎完全无效（仅试听链传了） |
| P1-15 | TtsVoiceSource.kt:12,63-68 | 头注宣称三类声源，实际只有 SystemEngineSource；speakMultiRole 对任意路由硬编码 CHANNEL_SYSTEM → 规则指向 HTTP/脚本声源时静默降级引擎默认音；play() 无 engineType 门禁，当前 HTTP 引擎时多人模式整体绕过 |

### 脚本引擎/切分
| # | 位置 | 问题 |
|---|------|------|
| P1-16 | TtsScriptEngineClient.kt:116 | eval 显式传 null 协程上下文 → Rhino 指令观察器（threshold 10000）永不触发打断，withTimeout(10s) 对无挂起点阻塞调用失效 → 脚本 `while(true){}` 永久挂死 IO 线程 |
| P1-17 | TtsScriptEngineClient.kt:140 vs multitts_forwarder.js:24/clonetts.js:24 | fetchVoicesCatalog 只认 `voicesUrl` 键，内置模板返回 `{type:"url", url:...}` → 动态目录恒不执行，原始 JSON 整体写入 speakersJson 解析 0 音色 → **MultiTTS/CloneTTS 音色目录永远拉不到** |
| P1-18 | TtsTagSplitter.kt:96-108 | collectQuotes 只查端点 !used[i] 不查区间内部 → regex 命中引号内部字符时引号段仍整体入 bounds → **分段重叠**（违反自声明不变量），重复合成+进度重复计数 |

### UI 层
| # | 位置 | 问题 |
|---|------|------|
| P1-19 | TtsCastingManageFragment.kt:100,129,177 + TtsVoicePreviewController.kt:98 | 试听状态三重断链（普通 var 非 state + getter 非 snapshot + onStateChanged 传空）→ 编辑器"合成中/停止"按钮**永远不出现**，试听无法从 UI 停止 |
| P1-20 | TtsCastingEditorScreen.kt:98 | 保存按钮文案用 `tts_casting_saved`="已保存"（应"保存"） |
| P1-21 | strings.xml:3262 + TtsCastingEditorScreen.kt:183 | `tts_casting_pick_source`="选择声源（规则 {0}）"MessageFormat 语法走 String.format → 标题恒显"{0}"字面量，违反 %1$d 规范 |
| P1-22 | TtsCastingManageFragment.kt:134 | 编辑器 onBack 无 dirty 检查 → 返回即静默丢失全部编辑 |
| P1-23 | TtsPrebuildDialog.kt:120 + ReadAloudPlayerPanel.kt:1089 | 门禁只查 `activeTemplateId()`（全局），实际生效走 `resolveActiveTemplateId(bookKey)`（含书级覆盖）→ 书级覆盖激活时预合成门禁误放行、PlayerPanel Scene 闸误判强制回 Immersive |
| P1-24 | ReadAloudConfigDialog.kt:417-432 + TtsCastingPickerDialog.kt:114-118 | `runBlocking { TtsCastingStore.all() }` 在组合期/点击回调同步等 DB → 主线程阻塞（已有 observeAll Flow 可用） |

## 三、P2（隐患/规范/优化，25 条摘要）

1. **沙箱逃逸面**：RhinoClassShutter 书源模式对 `io.legado.app.*` 观察放行，TTS 脚本可访问 AppConfig/ReadBook 等宿主类（RhinoClassShutter.kt:202-206）
2. **吞 CancellationException**：AiReadAloudRoleService.kt:817,1483、TtsCastingStore.kt:151-170、HttpReadAloudService 多处 runCatching 不放行取消（项目已知坑）
3. **内置模板升级不刷新**：importBuiltinTemplates 对已存在 id skip（TtsCastingStore.kt:242-246）
4. **castingTemplates.json schema 三方不一致**：JSON 用 `order` 键，实体是 sortOrder → 内置模板排序全 0
5. **删书联动不彻底**：deleteById 不清书级覆盖 pref 残留；BookInfoActivity 删书孤儿音频持续受保护
6. **sameChannel 不含 fallbackSourceJson**：跨通道 fallback 模板可通过校验
7. **KEY_PARAM_VOLUME 域越界**：coerceIn(0,2.0) 超官方 0~1 → >1 放大静默无效
8. **utterance 无句级超时**：引擎丢回调时永久挂起
9. **fetchVoicesCatalog 健壮性**：不查 responseCode/无体积上限/不 disconnect
10. **脚本编译产物无缓存**：头注宣称有缓存实际每次全量重编译
11. **返回值双重包装**：JSON.stringify 包裹致纯 URL 返回值带字面引号
12. **url 含逗号解析错位**：`url+","+optionJson` 依赖逗号拆分约定
13. **preDownloadAudios type=2 走错通道**（三代理一致命中）：脚本引擎下一章预下载静默失效
14. **edge_proxy_template.js 能力虚标**：声明 speed/voice 实际丢弃
15. **.part 残留**：失败/取消路径不删 temp；removeCacheFile 对当前章 .part 永不清理
16. **终态 failedCount 恒 0**：finishTask 硬编码 0；runTask FAILED 时 current=totalUnit 恒 0
17. **onTaskRemoved 只取消当前任务**，排队任务继续裸奔；onDestroy 注释与行为不符
18. **双 worker/共享可变状态**：ensureWorker 非同步可产生双 worker；unitFileNames object 级未同步可内容写错键
19. **取消后排版孤儿协程**：最多 200 个 LAZY 布局 job 空耗 CPU
20. **HttpReadAloudService 未覆写 onReInitTts**：HTTP↔HTTP 同类切换不即时生效，过渡期键混用新旧引擎
21. **ReadAloud.pendingSwitch/consumePendingSwitch 死代码**，注释与实际消费方矛盾
22. **speechRoute 双入口漂移**：fromTtsEngineValue 空串→ENGINE_SYSTEM vs resolveSpeechRoute→ENGINE_DEFAULT，结论相反
23. **speakMultiRole 跳过静默段不计进度账**，与空白段路径口径不一
24. **UI 规范族**：四屏手写顶栏偏离 AppDialogFrame 单源、字符串未资源化（SPEAKER_MANAGE_SUMMARY 等）、列表 index 做 key、editor 行 previewing 未比对 key 全行显示停止、requireContext() 无 attached 防护、主线程同步 HttpTTSDao.get、picker 可静默激活未启用模板、试听未挂 onStop
25. **submitting 防抖失效**（从未置 true）+ 章界未夹紧 chapterCount + 200 章上限无预提示

## 四、深度优化方向（根因层）

1. **键单源须"参数同源"而非"函数同源"**：P0-4/P1-1/P1-2 三处失配的根因相同——TtsCacheKeys 只是函数复用，入参各算各的。终态方案：批量端与播放端共用同一"切分+键因子解析"纯函数入口（输入 book/chapter/speechRate，输出 unitList+fileName），彻底消灭两端各算。
2. **预合成管线缺"排版就绪"与"响应校验"两个闸**：对齐播放端已有守卫（isCompleted/ContentType/loginCheckJs），应抽公共合成前校验组件，批量端不得另起炉灶。
3. **AI 链与 casting 链需统一消费入口**：P0-5 期2 重构丢失调用点是"重构即回归"的典型，建议在 BaseReadAloudService.newReadAloud 恢复 ensurePlayableCache 调用并补回归单测锁定调用点存在性。
4. **监听器所有权归一**：TtsVoiceSource 不应抢 setOnUtteranceProgressListener，改为 utteranceId 分发表由服务级监听器统一路由（A/B 双代理命中同一缺陷）。
5. **Rhino 沙箱策略分档**：书源模式观察档不应适用于 TTS 第三方脚本，需独立白名单档。
6. **UI 状态桥接标准化**：Fragment 持有控制器 + Compose 消费的场景统一 `mutableStateOf` 桥接模式，杜绝 P1-19 类三重断链复发。

## 五、验证通过项（攻击未实锤）

resolveSpeechRoute 四态边界（坏 JSON/裸包名/双嵌套 legacy/engineType 兜底）、legacy 单声整章路径与原版行为一致、降级熔断单次终止条件、KEY_VERSION/chapterIndex 键维度两端一致、temp+rename 原子提交、Manifest 前台声明与 dataSync 权限、跳转链（菜单→预合成/配置→picker→管理→编辑器）与 Manifest 注册闭环、服务包无 android.util.Log 残留。

## 六、三轮红队复审结果（2026-09-10）

### R1 遗漏面审计（新增 2 P1 + 7 P2）

| 编号 | 级 | 位置 | 问题 |
|---|---|------|------|
| F-1 | P1 | Backup.kt:118,472-473 + Restore.kt:163-164 | **ttsCastingTemplates 表零备份零恢复**（WebDAV 同缺）→ 用户自建模板+书级绑定换机全丢；config.xml 恢复的 activeId 悬空（有 null 兜底不崩，静默回退单声）|
| F-2 | P1 | TTSReadAloudService.kt:74-83 | **内置模板导入唯一入口=TTS 朗读服务 onCreate**（DefaultData.kt 零接入，注释宣称机制与实现不符）→ 首启用户未启动朗读前打开管理页/Picker=空列表，"开箱即用"失实 |
| F-3 | P2 | SpeechRouteResolveTest | 死变量 inner（URLEncoder 创建未使用），URL 编码形态双嵌套解析零覆盖 |
| F-4 | P2 | TtsPrebuildLeaseTest | 断言全打在纯函数上，P1-7 发生在 runTask 集成路径——测试绿但主链坏 |
| F-5 | P2 | TtsCastingTemplateDao.kt:42-43 | deleteUserTemplates 死代码（零调用点）|
| F-6 | P2 | AppDatabase.kt + AndroidManifest.xml | 意外引入 UTF-8 BOM（EF BB BF 字节实证），污染 diff 归因 |
| F-7 | P2 | TtsTagSplitterTest | 名实不符 + P1-18 重叠场景零回归网（当前应红的用例缺失）|
| F-8 | P2 | TtsCacheKeysTest | 恰缺 title/text 两个失配因子（P1-1/P1-2）的键变断言 |
| F-9 | P2 | build.gradle:274-275 | JVM 单测用 Maven org.json 与真机平台削减实现存在差异面，JSON 归一化结论不可完全外推 |

R1 验证通过：109→110 迁移 DDL 与实体与 110.json 三方一致；httpTTS 增列/equal 一致；PreferKey 三键常量读写一致；reInitTts 消费链闭合。R1 遗漏领域定性：备份/WebDAV 链、DefaultData 首装链、测试资产有效性、updateLog 交付一致性（第二十一批"收敛为同一键源函数"与 P0-4 矛盾、第十九批"开箱即用"与 F-2 矛盾）原审查均未覆盖。

### R2 误报核验（29 条 → 实锤 24 / 有偏差 5 / 误报 0）

| 编号 | 原结论 | 修正后结论 |
|---|---|---|
| P0-4 | 键"永不匹配/100% miss" | 错配属实，但 ttsFlowSys=true 且 ttsSpeechRate=10 时两端 speedKey 同为"10"且 speakSpeed 同为 10（AppConfig.kt:2108 分档差非恒差 5）——巧合配置下可命中；"永不匹配"过绝对，修复方向（参数同源）不变 |
| P0-5 | "期2 重构丢失调用点，AI 分镜链整体断链" | git -S 实证 ensurePlayableCache 自 3c8aa5c 引入即零调用（**从未接线**，非期2 丢失）；角色路由由 ReadAloudSpeechPlanner 等效实现且被 PlayerPanel:1205 消费（UI 链未断）；resolveSourceForTag 不传 characterId 是注释明示设计（TtsCastingStore.kt:139），AI 分镜正确入口 routeForSegment:948-949 有传。**降级为：AI 分镜音频预生成缓存链是从未接线的死代码** |
| P1-6 | "Result.retryable 死字段" | 重试 1 次未实装属实，但 retryable 在 TtsPrebuildManager.kt:326 被消费驱动连续 IO 中止判定——"死字段"说法撤回 |
| P1-19 | "试听无法从 UI 停止" | 状态桥接断链+专用停止按钮不出现属实；但 Controller.preview:104-107 同 key 再点可隐式停止——改为"无 UI 停止指示，仅可再点同项隐式停止" |
| P1-24 | picker 同样 runBlocking 等 DB | ReadAloudConfigDialog 两处为真 DB 阻塞属实；TtsCastingPickerDialog.kt:116 包的是 resolveActiveTemplateId（SharedPreferences 读，轻）——函数引述更正 |

专项攻击核验（8 项高风险裁决全部经三源码层穿透，未被推翻）：P0-3（TextChapter.getNeedReadAloud:186-210 纯读 pages 无阻塞）、P1-9（speakLegacyLoop:249-312 全文无重挂监听器）、P1-10（onInit:146-166 无 initGeneration 读取）、P1-11（包装 lambda 与裸 initWatchdog 是不同实例，5 处 removeCallbacks 全无效）、P1-12（IntentAction.moveTo 全工程唯一引用=发送方）、P1-16（modules/rhino 三层实证 null 上下文→观察器空转）、P1-17（两模板 JS 返回键为 url 非 voicesUrl）。

### R3 修复方案安全性审计（防引入新 BUG）

**高风险 6 条——照原报告方案修会引入新缺陷**：

| 编号 | 风险 | 修正后方案 |
|---|---|---|
| P0-3 | 只等 isCompleted 会把排版异常章当空章吞（onError→isCompleted=true）；无超时=任务永挂 SCANNING | listener 挂起等待 + 60s 超时 + `pages.isEmpty()` 判失败 + layout job 随任务取消（顺带修 P2-19）|
| P0-4 | **禁止反向修**：播放端 `speechRatePlay+5` 同时是发引擎的 speakSpeed 请求参数（+5 是历史参数域），改播放端=破坏全部在线引擎语速兼容 | 批量端单向对齐 `AppConfig.speechRatePlay + 5`（含 ttsFlowSys 分支），KEY_VERSION 升 v3（当前产物本就 miss、分支未发版，零损失）|
| P0-5 | "恢复一行调用"不闭环：AI Segment 与 CastingSegment 是两套切分体系，段落坐标系同源性未验证；ensurePlayableCache 含网络分配会阻塞 play() 启动 | 本期 fire-and-forget 预热（不阻塞启动），消费链单独立项 |
| P1-7 | "失败单元重排队列尾"会触发 unitFileNames 按索引暂存的隐藏错位雷（TtsPrebuildManager.kt:294）| 改 while 队列 + fileName 内联重算；与 P1-6 同批设计（互相定义循环结构）|
| P1-9 | 分发表方案漏三处：①前缀路由（mr_→pending 唤醒/pause→过滤/其余→nextParagraph）②reInit/onDestroy 全路径 resume+clear pending ③须先修 P2-8 句级超时否则换一种方式挂死；服务需持当前 source 引用 | 按①②③补全后实施；试听链走独立 TTS 实例不受影响（已实证）|
| P1-12 | moveTo 不是"补一行分支"：跨章跳转+进度账重算=新功能 | 本期最低成本兜底（章级跳转），段落级 seek 立项 |

中风险典型：P1-23（~~resolveActiveTemplateId 是 suspend DB 查询~~ ⚠️实施核实：该函数仅读 SharedPreferences 无挂起点，R3"DB 查询"前提有误；实际修复=直接去 suspend 供同步调用，效果等同且无需预载缓存，setMode/门禁零阻塞）；P1-24（Flow 首帧 emptyList 会闪"未启用 共 0 个"一帧）；P1-13（upSpeechRate 同样分流，只修 play 必复发）；P1-1（**禁选播放端改 trim 方向**——contentList 行数变化会使 getParagraphNum→nowSpeak 进度账错位，必须批量端对齐播放端）；P1-3（持久化进 Room 违反 v110 冻结铁律，须走 cacheDir JSON 落盘）。

深度优化方向修正：方向①批量端已在排版，同源不新增成本，正确形态=抽"键因子三件套"纯函数（speedKey 含 ttsFlowSys 分支/title 走 getDisplayTitle/unitText 统一口径），非"unitList 两端共用"；方向③单测锁调用点=伪需求（JVM 无法断言调用关系），替代=真机 E2E+TAG_TTS_TRACE 日志断言；方向⑤白名单过紧=四个内置模板直接不可用，实施前须先枚举模板能力面。

## 七、修复实施批次序（R3 结论）与实施记录

### 实施记录（2026-09-10，批次A→D 全部落地）

| 批次 | 内容 | 状态 |
|---|---|---|
| A | P0-1（%1$d）/P0-2（actions.importBuiltinScriptTemplates 接线）/P1-20（保存文案）/P1-21（%1$d）/P1-16（eval 传 currentCoroutineContext）/P1-17（url 键兼容+响应码/2MB 限额/disconnect）/P1-18（引号区间内部互斥+装配层截断双保险）/P1-11（watchdog 包装 Runnable 持引用）/P1-22（未保存拦截 dirty 快照比对）/P1-5（目标文件延后 rename 才存在+length>0 命中）/P1-8（IDLE 判定 5s 宽限+连续 2 次确认）| ✅ 已落地 |
| B | KEY_VERSION v2→v3 / P0-4（批量端 speedKey=AppConfig.speechRatePlay+5 单向对齐）/P1-1（split("\n")+isNotEmpty 逐字同源）/P1-2（getDisplayTitle 同源解析）/P0-3（isCompleted 轮询等待+60s 超时+空页判失败跳过+排版 job 随 worker 取消）/P1-6↔P1-7（while 队列+fileName 内联 PendingUnit+retryable 重试 1 次+延后不计 done）/P1-4（Content-Type 校验拒错误页+失败路径 .part 清理）/P2-18（ensureWorker synchronized）/P2-16（终态 failedCount 透传）/P2-4（castingTemplates.json sortOrder 键）| ✅ 已落地 |
| C | P1-13（type=2 分流移到 resolve 后+playDownloadQueue 抽取复用）/P1-10（onInit 携代际）/方向④（UtteranceProgressSink 分发表，SystemEngineSource 不再覆盖监听器；sink 含 contains/dispatch/clearAll；legacy/mr_ 前缀分流；试听链独立 TTS 不受影响）/P2-8（句级 120s 超时兜底）/P1-14（规则韵律送达+speakMultiRole 传 ruleProsody+脚本引擎 speechRate/10f 倍率）/P1-15 门禁版（非系统通道声源明示降级日志）/P1-12（moveTo 章级+cue 章内位置精确装配）+selectChapter/playFromPosition 两个同类孤儿 action 一并兜底/P2-17（onTaskRemoved/onDestroy cancelAll）/P2-15（播放端陈旧 .part 清理）/P2-23（预合成 Dialog submitting 防抖+空输入明示+章界上界校验+200 章预提示）| ✅ 已落地 |
| D | P1-23（resolveActiveTemplateId 去 suspend（仅 prefs 读）→门禁/Scene 闸同步走书级口径）/P1-24（PickerDialog 去 runBlocking；ReadAloudConfigDialog observeAll Flow 快照+首帧未加载不显误导文案）/P1-19（previewController+currentState 改 snapshot state）/P2 行级 key 比对（previewKeyFor）+稳定列表 key+requireContext 防护（appContext 捕获）| ✅ 已落地 |

验证：compileAppDebugKotlin BUILD SUCCESSFUL；TTS 单测 29 项全绿（TtsTagSplitter 8/TtsCacheKeys 7/TtsPrebuildLease 6/SpeechRouteResolve 8）；android.util.Log 残留 0。

### 后续批次序

- **批次A**（并行小修）：P0-1/P0-2/P1-20/P1-21（字符串三条）+ P1-16/P1-17/P1-18/P1-11/P1-22/P1-5 + P1-8+P2-18
- **批次B**（键单源强耦合，必须同批设计否则返工）：KEY_VERSION v3 决策 → P0-4 → P1-1 → P1-2 → P0-3 → P1-6↔P1-7 → 幂等口径；落地后立即真机验证"预合成→播放命中"全链
- **批次C**（服务链）：C1: P1-13（含 upSpeechRate）；C2: P1-10+P1-11+方向④（先修 P2-8 句级超时）；C3 并行：P1-14/P1-15 门禁版/P1-12 章级兜底
- **批次D**（UI，可并行）：P1-19；P1-23 依赖 P1-24 预载缓存模式先定型，同批
- **批次E**（独立立项，不阻塞发版）：P0-5 消费链/P1-3 落盘/F-1 备份/F-2 DefaultData/方向①收编/方向⑤分档
