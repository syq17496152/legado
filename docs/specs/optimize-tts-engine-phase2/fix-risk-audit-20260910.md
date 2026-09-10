# TTS 审查报告修复方案引入新 BUG 风险审计（2026-09-10）

> 审计对象：code-review-20260910.md 的 P0×5、P1×24 修复建议 + 深度优化方向 6 条
> 审计方式：逐条源码实证（Grep 全调用方 + Read 逐行）+ 项目铁律对照（Coroutine 封装/NoStackTraceException/取消传播/Room v110 冻结/UI 线程）
> 结论速览：29 条修复中 **高风险 6 条**（照报告原样做会引入新缺陷或做不闭环）、中风险 12 条、低风险 11 条。最大系统性风险集中在：①键口径修复牵动 KEY_VERSION 与播放进度账；②"恢复 ensurePlayableCache + 单测锁调用点"是架构级改造叠加伪需求；③监听器分发表改造缺少生命周期清理设计会引入新挂死点。

## 一、修复风险总表（P0）

| 编号 | 风险级 | 风险描述（照报告原样修的后果） | 修正后建议 |
|------|--------|-------------------------------|-----------|
| P0-1 | 低 | 无（格式符修正无副作用） | `%1$d`；顺带 grep 全部 tts_casting_* 字符串排查同类非法格式符（P2-25 章界夹紧已存在：`end.coerceAtMost(start+199)`，103 行） |
| P0-2 | 低 | 无。唯一调用点（SpeakEngineDialog.kt:482-497）未传 lambda；但 `importBuiltinScriptTemplates()` 是 private（302 行）且不在 `SpeakEngineDialogActions` 接口（390-400）内，Composable 调用点无法直接触达 | 接口 `SpeakEngineDialogActions` 增加 `importBuiltinScriptTemplates()`，dialog 实现委托；调用点传 `{ importDialogVisible = false; actions.importBuiltinScriptTemplates() }` |
| P0-3 | 中 | 报告只说"等待排版完成"。三个隐藏雷区：①排版失败路径 `onError→onFinally isCompleted=true`（TextChapterLayout.kt:173-180），只等 isCompleted 会把异常章当"空章"静默吞成"无可合成内容"；②无超时防线→排版卡死=任务卡 SCANNING 永挂；③取消后 layout job 是 Manager scope 的孤儿（P2-19 放大）。无死锁风险（等待方与排版均在 IO 调度器，无互斥锁，无 ANR 风险） | 等待实现：`setProgressListener`（TextChapter.kt:321 已支持，isCompleted 时立即回调）+ `suspendCancellableCoroutine` + withTimeout(60s)；恢复后检查 `pages.isEmpty()`→按失败跳章计 failed；任务级登记 layout job，cancel 时一并 cancel（顺带修 P2-19） |
| P0-4 | 中 | 键域错配实锤（批量 `AppConfig.ttsSpeechRate`=5，播放 `AppConfig.speechRatePlay+5`=10，AppConfig.kt:2109 `speechRatePlay=if(ttsFlowSys) 5 else ttsSpeechRate`）。**禁止反向修（把播放端改成 ttsSpeechRate）**：播放端 speechRate 同时是 AnalyzeUrl speakSpeed 的请求参数（HttpReadAloudService.kt:412/431），+5 是历史引擎参数域约定，改它=改变所有在线 TTS 引擎实际收到的语速值=破坏既有书源兼容 | 批量端单向对齐播放端：`task.speechRate = AppConfig.speechRatePlay + 5`（入队快照处，TtsPrebuildManager.kt:123），且 TtsSynthesizer 的 speakSpeed 注入同值（音频内容才一致）。必须同步 KEY_VERSION v2→v3（见数据兼容结论） |
| P0-5 | 高 | 报告建议"恢复 ensurePlayableCache 调用"但不足以闭环：①消费链断裂——speakMultiRole 消费的是 CastingSegment（TtsTagSplitter 产物，无 characterId 字段），而 routeForSegment 需要 AI 链 Segment（AiReadAloudRoleService.kt:937）；两套切分体系（AI 分镜 vs 规则切分）段落坐标系是否同源未验证；②ensurePlayableCache 内含 AI 网络分配（repeat+waitForRunningCache），插进 newReadAloud 启动链会阻塞 play() 启动（无超时包裹时）；③"补回归单测锁调用点"在 Kotlin/JVM 无调用图断言手段（反射查不到调用关系，加字节码分析依赖违反极简） | 分期：本期=在 newReadAloud 的 execute(IO) 内、`launch(Main){play()}` **之后** fire-and-forget 调 ensurePlayableCache（恢复缓存预热与角色分配副作用，不阻塞启动）+ 失败静默 TtsTrace 记录；消费链打通（CastingSegment.ai 前缀段经 assignedSegmentsForCue 映射 routeForSegment）单独立项验证段落坐标系后实施。回归保障用真机 E2E（ai_tests L2 + TAG_TTS_TRACE 日志断言），放弃"单测锁调用点"伪需求 |

## 二、修复风险总表（P1，24 条）

| 编号 | 风险级 | 风险描述 | 修正后建议 |
|------|--------|----------|-----------|
| P1-1 | 中 | 与 P0-4 同批。缩进本身两端一致（同走排版 getNeedReadAloud），真失配是批量端 `trim()+filter(isNotBlank)` vs 播放端 `filter(isNotEmpty)` 不 trim。**禁选"播放端改 trim"方向**：播放端 contentList 行数变化会使 `getParagraphNum→nowSpeak` 错位（BaseReadAloudService.kt:277-289 进度账强耦合排版行号），全角空格行被过滤即错位 | 批量端对齐播放端：`split("\n").filter { it.isNotEmpty() }` 不 trim；合成文本对齐播放端 `text.replace(notReadAloudRegex, "")` 并判空落静音（当前批量端会把纯标点/空白直接发引擎）。KEY_VERSION 升 v3 |
| P1-2 | 中 | 修复=批量端 displayTitle 改走 `bookChapter.getDisplayTitle(...)`（BookChapter.kt:140，去换行+简繁+替换规则）。坑：替换规则/简繁是用户可变配置，批量任务期内变更→键漂移 miss（与语速快照同语义，可接受但需在键注释声明）；getDisplayTitle 的 replaceRules 参数必须与播放端排版链同源（ContentProcessor 有效规则集），拿错规则集=仍然失配 | 批量端 `getTextChapterAsync` 的 displayTitle 参数改传 `bookChapter.getDisplayTitle(processor 有效替换规则)`（与阅读页 ContentProcessor 同源），KEY_VERSION 升 v3。播放端零改动 |
| P1-3 | 中 | 报告未给方案。若持久化进 Room→**违反 v110 冻结铁律**（TtsPrebuildManager.kt:51 设计注释明示）；reservedKeys 键量可达上万（200 章×N 单元），SP 全量重写性能差 | cacheDir/httpTTS/ 下 JSON 增量落盘（按 rename 成功事实 append）+ 启动异步加载 + `cancelAllAndClearReserved`/删书联动同步删除；与"内存态不持久化"设计不冲突（保留的是资产登记非任务状态）。低优先级：进程重启丢名单的后果=下次服务销毁时 >10min 产物被驱逐（可重合成），非数据损坏 |
| P1-4 | 中 | 修复若照抄播放端 `getSpeakStream` 的 while(true)+downloadErrorNo 结构→违反 TtsSynthesizer 设计注释"服务状态剥离：无 downloadErrorNo 累计"（TtsSynthesizer.kt:12）；且重试计数落 object 单例=跨任务串味。loginCheckJs 依赖 AnalyzeUrl 实例 evalJS(Response)，批量端已有该实例可复用 | 校验失败/异常一律返回 `Result.Failure(retryable=true)`（单次重试交给 P1-6 的调用方重试），禁止把播放端重试状态机搬进批量端；TtsSynthesizer 已正确放行 CancellationException（88-89 行），改造时保持此铁律 |
| P1-5 | 低 | 无方向性风险，但注意：`isSilentSound=length==2160L` 判定（HttpReadAloudService.kt:625）与静音文件共存，修 0 字节勿误伤；播放端 createSpeakFile(name) 无参重载（createSilentSound 用）不能被改动波及 | 播放端 createSpeakFile(name, inputStream) onFailure 时补 `target.delete()`；批量端幂等判断 `hasTargetFile` 改 `exists() && length()>0`（TtsPrebuildManager.kt:389-391）。两端同批 |
| P1-6 | 中 | 与 P1-7 同一循环结构（forEachIndexed 扁平单遍），加重试若用外层 while 嵌套会与租约 defer 相互作用：defer 单元被重复计数/重复合成 | 重试内联在单元处理处（Failure&&retryable 时立即重试 1 次），不改循环结构；与 P1-7 修复同批设计再动手 |
| P1-7 | 高 | 报告说"defer 计数改轮次"但未给结构。当前 `deferredRounds` per-chapter 在单遍扫描里连续自增（一章前 3 单元连续 defer 后强制执行，285-292 行）——修复"重排队列尾"会触发隐藏雷区：`unitFileNames[unitIdx]` 按索引暂存（294 行），重排后索引与 fileName 错位；且 done++ 计数与进度 current/total 口径要重设计 | 改 while 队列：defer 单元 append 队尾+`deferredRounds` 按全局轮次计数；fileName 生成内联到合成时（按 chapterIndex+unitText 重算，勿按索引暂存）；deferred 单元不计 done，进度 total=unitList.size 恒定。JVM 单测覆盖 leaseDecision 新语义（已有 TtsPrebuildLeaseTest 可扩） |
| P1-8 | 低 | 报告建议"首轮延迟"仍有竞态窗口（worker launch 调度延迟不确定） | 双保险：①`enqueue` 在 ensureWorker 前同步置 `_state.value=SCANNING` 占位（StateFlow 线程安全，主线程调用无碍）；②observeState 首轮 IDLE 不立即 stopSelf，连续 2 次采样（2s）仍 IDLE 才退。同文件顺带修 P2-18（ensureWorker 加 synchronized 防双 worker） |
| P1-9 | 高 | 见方向 4 专项审计。直接"由服务级监听器统一分发"若漏掉：legacy `AppConst.APP_TAG+i` 与 multiRole `${AppConst.APP_TAG}mr_p_off` 前缀区分、`pause` 静音项过滤（TTSReadAloudService.kt:498）、reInit/onDestroy 时 pending 挂起协程的唤醒——会引入比原缺陷更隐蔽的挂死 | 见"深度优化方向审计结论"④的完整改造清单 |
| P1-10 | 中 | onInit(status) 回调不携带 sender 实例，"加代际校验"若只在 onInit 里比对 `initGeneration` 无效（旧引擎迟到时 initGeneration 已被新 init ++，比对恒真）。必须让每次 TextToSpeech 创建携带独立监听 | initTts 中 `val gen = initGeneration; TextToSpeech(this, { onInitFor(gen, status) }, engine)`——per-instance OnInitListener 闭包捕获 gen，onInitFor 校验 `gen == initGeneration` 才置 ttsInitFinish/play；服务不再 override onInit。@Synchronized 与 handleInitFailure 链保持 |
| P1-11 | 低 | 无方向风险（实锤：包装 lambda 永远移不掉，onDestroy 后 8s 重建引擎） | 代际检查合并进 initWatchdog 本体（自持 scheduledGeneration），postDelayed(initWatchdog)/removeCallbacks(initWatchdog) 生效；行为等价小重构。与 P1-10 同文件同批，回归 init 降级链（AD-03） |
| P1-12 | 高 | 报告只说"无处理分支"。moveToCue 携带 cueIndex/chapterPosition/expectedChapterIndex（ReadAloud.kt:132-147），实现=跨章跳转+paragraphStartPos/nowSpeak 重算+双服务各自实现——是**新功能实现**而非补一行分支；进度账实现错误会破坏朗读状态机（错位/跳段/重复） | 立项实施：BaseReadAloudService 增 open fun onMoveTo(...)，HttpReadAloudService 按 contentList 累计长度定位、TTSReadAloudService 按 multiRole/legacy 双路径定位；先真机验证 cue→position 映射精度。本期最低成本兜底：收到 moveTo 时若章不符先跳章再 play（段落级 seek 留 TODO 明示），杜绝静默丢弃 |
| P1-13 | 中 | 修复"先 resolve 再判断"的障碍：downloadAndPlayAudiosStream 非 suspend（play() 直调，HttpReadAloudService.kt:126-130/269-274），函数头不能直接 resolve；且 upSpeechRate（686-695）同样按 streamReadAloudAudio 分流，只修 play 不修 upSpeechRate=切语速后再犯 | 把 type 分流移入 execute 协程内：resolveCurrentHttpTts() 后 `if (httpTts.type==2) downloadAndPlayAudios() else 流式`；upSpeechRate 同构处理（downloadAndPlayAudios 内部 resetCurrentHttpTts+resolve，无递归）。resolve 含 speakersJson 拉取副作用，与既有非流式路径语义一致可接受 |
| P1-14 | 中 | 修复需把 rule.prosody 从 splitter 传到 speakMultiRole，但 CastingSegment 无 prosody/rule 字段（TtsTagSplitter.kt:10-13）；"脚本引擎全局语速无效"另一根因=getEngineSpeakStream 传 null,null,null（HttpReadAloudService.kt:395-400），修此处的 prosodyRate 需要一个与 speechRate 域换算明确的值，拍脑袋传 speechRate 会让脚本引擎语速突变 | ①CastingSegment 加 `val prosody: CastingProsody = CastingProsody()`（带默认值，现有构造方/单测零改动），splitter 按 rule 填充，speakMultiRole 改传 `segment.prosody`；②getEngineSpeakStream 的 prosodyRate 传 `(AppConfig.speechRatePlay + 5) / 50f` 口径需先与脚本模板（defaultSpeed=50）实测对齐，不确定就本期只修①+登记②待实测，禁拍脑袋 |
| P1-15 | 高 | "三类声源补齐"（HTTP/Script 声源进 multiRole）=utterance 语义重写：AudioFile 路径需 ExoPlayer 播放完成回调驱动逐段推进（现 speakMultiRole 是逐句挂起循环），与 P1-9 监听器改造、ExoPlayer 生命周期（HttpReadAloudService 私有）强耦合，本期照做必烂尾 | 本期只做门禁：play() 路径判定处，`ruleSet 非空 && engineType != ENGINE_SYSTEM` 时降级 legacy+toastOnUi 明示"多人模式需系统引擎"；HttpForwarderSource/ScriptSource 声源分发立项下一期（依赖方向 4 的分发表落地） |
| P1-16 | 低 | 无方向风险：三参 eval(js, scope, coroutineContext) 是工程标准用法（AnalyzeUrl.kt:437、ParagraphRuleProcessor 等多处先例） | evalFunction 加 CoroutineContext 参数（或变 suspend 取 currentCoroutineContext()），3 个内部调用方均在 withContext(IO)+withTimeout 内可取；保持 withTimeout 外层语义 |
| P1-17 | 低 | 无。注意向后兼容：既有引擎脚本返回 voicesUrl 键的形态要继续支持 | `obj.optString("type")=="url"` 取 url，否则回落 voicesUrl 键；顺带 P2-9（responseCode/体积上限/disconnect） |
| P1-18 | 低 | 无。纯函数+已有 TtsTagSplitterTest 可扩 | 成段前检查 `(openIndex..i).any { used[it] }`，被占则放弃该引号段回落旁白；补重叠场景单测 |
| P1-19 | 低 | 构造调用方唯一（TtsCastingManageFragment:172），影响面小 | controller 的 `currentState` 改 `by mutableStateOf`（state getter 不变，Compose 自动订阅）；`onStateChanged={}` 空实现保留参数兼容。符合 ui-standards 桥接模式（方向 6） |
| P1-20 | 低 | 无 | 改文案，零风险 |
| P1-21 | 低 | 无。但注意该字符串若被 MessageFormat 语义消费过（当前恒显 {0} 字面量=从未生效），改回 %1$s 需同步改调用点传参方式 | strings.xml 改 `规则 %1$s` + 调用点 stringResource(R.string.x, tag) |
| P1-22 | 低 | 无 | onBack 前比对 editor 与打开时快照，有差异弹确认；注意 IMPORT 路由返回同样处理 |
| P1-23 | 中 | 障碍：`resolveActiveTemplateId(bookKey)` 是 suspend DB 查询（TtsCastingStore.kt:73），而 gateCheck（TtsPrebuildDialog.kt:107，LaunchedEffect 内可改协程）与 setMode（ReadAloudPlayerPanel.kt:1087，同步 UI 回调）不能直接 await。报告未提示此签名障碍，照做会编译失败或诱导 runBlocking 回潮（重犯 P1-24） | gateCheck：LaunchedEffect 内 withContext(IO) 调用；setMode：进入面板时 LaunchedEffect 预载 `bookEffectiveTemplateId` 存 state（伴随 bookKey 变化重载），setMode 读缓存值。两处同批+口径统一 |
| P1-24 | 中 | 修复陷阱：①组合期改 Flow collect 首帧 emptyList→摘要闪"未启用 共 0 个"一帧；②cycleTtsCastingTemplate 点击回调需要同步列表值，改 Flow 后若读异步结果会引入新的时序 bug | summary 用 `produceState(emptyList()→null 占位区分加载中)`+observeAll；cycle 回调读 state 缓存列表（Flow 已维护），点击时无 DB 等待；TtsCastingPickerDialog.refreshActiveState 的 runBlocking 改 scope.launch 异步刷 state。禁用 runBlocking/Flow 首值闪烁两者都要处理 |

## 三、深度优化方向审计结论

**①"两端共用切分+键因子纯函数"——方向正确，边界要收窄（中风险）**
- 性能/内存：预合成端**已经在排版**（getTextChapterAsync），“同源”不新增排版成本；新增成本仅 P0-3 的等待。TextChapter 是 runTask 局部变量逐章创建，GC 可回收，无内存累计问题。该方向没有"批量端必须额外排版"的新负担——报告此点疑虑不成立。
- 真正的边界问题：播放端 contentList 是进度状态机（nowSpeak/getParagraphNum/翻页判定）的数据源，**不能被 planner 的输出替换**。照报告字面"输出 unitList+fileName 两端共用"，播放端会弃用一半输出、接口名不副实。
- 修正：抽"键因子三件套"纯函数（speedKeyOf()/titleKeyOf()/unitKeyTextOf()，输入含 ttsFlowSys 分支与 getDisplayTitle 语义）+ 切分口径以单一常量函数锁两端；播放端在 md5SpeakFileName 处调用键因子函数，批量端在 runTask 调用同一组。这才是"参数同源"的可落地形态。
- 数据兼容：KEY_VERSION 必须 v2→v3（TtsCacheKeys 注释已声明升级协议）。存量缓存作废评估：当前 P0-4 下预合成产物本来就 100% miss、分支未发版→**零实际损失，可接受**；updateLog 须声明。

**②"公共合成前校验组件"——可行（低-中风险）**
- 抽 `validateTtsResponse(analyzeUrl, response, httpTts): Throwable?`（ContentType json/text/不匹配 + loginCheckJs evalJS 链），两端共用。红线：播放端 while(true) 重试与 downloadErrorNo 累计是播放专属状态（错误恢复三分类），不得进公共组件；批量端校验失败只转 Result.Failure。

**③"恢复 ensurePlayableCache + 回归单测锁调用点"——前半可行、后半伪需求（高→拆解后中）**
- "单测锁调用点"在 Kotlin/JVM 工程架构上不可行：单测无法断言方法 A 调用了方法 B（调用图需字节码分析，项目无此依赖，引入 ArchUnit 违反极简与零新依赖惯例）；源码文本扫描单测依赖 src 路径假设，脆弱且无先例（现有单测全是纯函数：TtsCacheKeysTest/TtsPrebuildLeaseTest/TtsTagSplitterTest）。
- 替代：真机 E2E 用例锁行为（ai_tests 体系 L2+TAG_TTS_TRACE 日志断言"预合成入队后 AI 链触发"）+ 消费链编译期收敛（CastingSegment 走 routeForSegment 的路径由类型系统表达）。

**④"监听器所有权归一（utteranceId 分发表）"——可行，但报告漏了三处必改点（高风险，须带清单实施）**
1. 前缀路由表：服务级 TTSUtteranceListener 按前缀分发——`mr_`→查 pending map 唤醒挂起协程；`pause`→过滤（现 onDone 498 行语义）；其余（legacy `AppConst.APP_TAG+i`）→nextParagraph 保持不变。
2. pending 注册表生命周期：reInit（clearTTS+initTts）/onDestroy/onError 全路径必须 resume(false)+clear 所有 pending，否则叠加 P2-8（句无超时）出现比原缺陷更隐蔽的永久挂起。建议与 P2-8（句级超时）同批。
3. 服务持 `activeSource` 引用：SystemEngineSource 每 play() new 实例（TTSReadAloudService.kt:321-326），pending map 放实例则服务监听器无法触达——需服务持有当前 source 弱引用并在 play/legacy 切换时置空清表。
- 兼容性验证过：试听链走 TtsVoicePreviewController 自建独立 TextToSpeech（sysTts 字段），不经服务实例，不受分发表影响；`preview()` 方法若无人调用应一并清理而非适配。
- 收益确认：A/B 双代理命中的覆盖缺陷真实（TtsVoiceSource.kt:116 每句覆盖且无恢复点），改造后 legacy 路径行为不变（前缀路由直通 nextParagraph），风险可控。

**⑤"Rhino 沙箱策略分档"——方向对，但白名单定档有功能回归风险（中风险）**
- 风险：TTS 脚本模板（multitts_forwarder/clonetts/openai_compat/edge_proxy）依赖的宿主能力面未枚举，白名单过紧=内置模板直接不可用（重犯 P0-2 式"导入即死"）。须先枚举四模板+既有用户脚本用到的 JsExtensions 能力再定档，分档开关留逃生口（日志明示被拦截能力）。

**⑥"UI 状态桥接标准化"——低风险，规范沉淀即可**
- P1-19 是唯一实例，修复后把 `mutableStateOf` 桥接模式写入 ui-standards/architecture.md，不必造新组件。

## 四、修复实施顺序建议（依赖图）

```
批次A（独立小修，可并行，1 天内）:
  P0-1 + P1-20 + P1-21（字符串）
  P0-2（导入按钮接口接线）
  P1-16（eval 上下文）/ P1-17（音色目录）/ P1-18（切分重叠）
  P1-11（看门狗 runnable）/ P1-22（dirty）/ P1-5（0字节两端）
  P1-8 + P2-18（服务首轮+双 worker，同文件同批）

批次B（键单源强耦合批，必须一起设计、串行实施）:
  前置决策: KEY_VERSION v3 + updateLog
  P0-4（speedKey 域）→ P1-1（trim 口径）→ P1-2（title 因子）→ P0-3（等排版+异常检查+超时）
  → P1-6（重试内联）→ P1-7（租约 while 队列+fileName 内联，P1-6 与它互相定义结构）
  → 批量端 P1-5 幂等口径（length>0）
  全批 JVM 单测: TtsCacheKeysTest/TtsPrebuildLeaseTest 扩展

批次C（服务链，C1 必须先于 C2）:
  C1: P1-13（type=2 提前 resolve，消除键生成窗口）
  C2: P1-10 + P1-11（若 P1-11 未在批次A）+ P1-9/方向④（分发表，先补 P2-8 句级超时）
  C3（可与 C2 并行）: P1-14①（CastingSegment.prosody）/ P1-15 门禁版 / P1-12（moveTo 立项，最低成本兜底先行）

批次D（UI 状态，可并行）:
  P1-19 / P1-23+P1-24（同批：ReadAloudConfigDialog+PickerDialog+PrebuildDialog 门禁与去 runBlocking）

批次E（独立立项，不阻塞发版）:
  P0-5 消费链（AI Segment↔CastingSegment 坐标系验证先行）/ P1-3（reservedKeys 落盘）/ 方向①纯函数收编 / 方向⑤沙箱分档
```

依赖要点：
- P1-6/P1-7 互相定义循环结构，**必须同批设计**，否则二次返工；
- P0-4/P1-1/P1-2 改的是同一函数区域（runTask 预扫描段），与 P0-3 同章代码，串行做防冲突；
- P1-9（方向④）依赖 P2-8 先行，否则修完覆盖缺陷换来挂死缺陷；
- P1-23 依赖 P1-24 的"去 runBlocking"模式先定型（预载缓存模式），同批实施；
- P1-15 声源补齐版依赖方向④完成，本期只出门禁版；
- 批次 B 键口径落地后立即真机验证"预合成→播放命中"全链（这是本分支核心交付），命中失败优先查 ContentProcessor 规则集同源性而非键算法。
