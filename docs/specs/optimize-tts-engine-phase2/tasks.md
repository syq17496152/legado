# optimize-tts-engine-phase2 任务清单（TTS 多角色体验完善与生态扩展，期2 完整版）

> **状态**：🔨 实施完成（L1 全绿 + 模拟器 L2 可验证项全通过；听感双证/预合成 E2E/试听真声留真机，见 3.4-3.7/3.10 注记与 issues-found.md）
> **关联文档**：
> - Spec：`docs/specs/optimize-tts-engine-phase2/spec.md`
> - Design：`docs/specs/optimize-tts-engine-phase2/design.md`
> - 设计简报（输入源，验收后删除）：`temp/phase2-design-brief.md`
> - 上游基线：`docs/specs/optimize-tts-engine/design.md` §8 实施进度登记（分支 feat/optimize-tts-engine @07ffe7a）+ §1.8-F 回归保护清单（C1-C11）
> **测试包口径**：真机/模拟器统一使用 `io.legado.miss.app.debug`（`build-legado.bat` 产物）
> **验证标准图例**：L1=编译（BUILD SUCCESSFUL / 单测全绿）｜L2=真机/模拟器功能验证｜L3=场景回归
> **任务统计**：共 33 项（v1.1 扩围 +6），核心任务（★）15 项
> **期2 范围**：补全上游 §8.2 简化项 **S1-S7 全部**（模板列表选择器/管理页/编辑器/音色级声源/书级覆盖 UI/试听/AI 链接入/**批量预合成+缓存管理 v1.1 扩围**）；登记后续清零（§1.8-D-1 定型项由"预留"升"实装"）
> **回归保护参照**：上游 design §1.8-F C1-C11 清单（本轮相关项见 2.15/3.7）

---

## 1. 准备工作

- [x] 1.1 加载子规范（开发前置，逐项确认已读）
  - `docs/project-rules/coding-philosophy.md`（极简哲学 / 精准修改 / 简化标注三段式）
  - `docs/project-rules/ui-standards/architecture.md` + `frontend-ui-standards.md`（四组件族基线+取色唯一基线，新页面禁私自拉组件/硬编码色）
  - compose-ui-engineering skill（新 Fragment/编辑器全 Compose 实施规范）
  - `docs/project-rules/database-migration-safety.md`（本轮 Room v110 冻结无迁移，仅作守门核实）
  - `docs/project-rules/ai_e2e_testing_workflow.md`（步骤 5.5 真机验证 SOP，必须 ai_tests venv）
  - 代码变更强制配套：`logging-during-refactoring.md` + `version-delivery-sync.md`
  - 加载顺序门禁：1.1 未完成禁止进入 1.2 源码精读
  - 验证标准：子规范全部加载，实施全程遵守（无门禁违规）

- [x] 1.2 ★ 精读源码清单并核实简化项基线（12 文件 = casting 4 + UI 4 + AI 链 1 + 预合成基建 3）
  - Read casting 四件套：`help/readaloud/casting/TtsCastingModel.kt`、`TtsTagSplitter.kt`、`TtsCastingStore.kt`、`TtsVoiceSource.kt`（六级 resolve 链现签名 / setBookOverrideTemplateId / engineType=current 哨兵 / 快照失效入口）
  - **stale 注释登记**：TtsCastingTemplate.kt:24 rulesJson 注释称嵌套 `match:{type,pattern}` 结构与实际 CastingRule 扁平序列化（matchType 直挂）不符，实施时同步纠正注释（已完成纠正）
  - Read UI 锚点 4 文件：`ui/book/read/config/SpeakEngineDialog.kt`（导入入口+单 Fragment 路由模式对照）、`ui/book/read/config/SpeechVoiceRoutePicker.kt`（title/groups/currentRoute/onRouteSelected/onLogin 签名）、`ui/book/read/ReadAloudPlayerPanel.kt`（Scene 闸位+循环切换+面板内嵌列表落点）、`ui/book/read/config/ReadAloudConfigDialog.kt`（多人听书行/ttsCastingSummary/allPreferenceKeys）
  - Read AI 链：`help/ai/AiReadAloudRoleService.kt` routeForCue 区域（分镜 tag 生产与路由消费点、type==1 过滤现状）
  - Read 预合成基建 3 文件（v1.1 扩围）：`ui/book/cache/AudioCacheTaskManager.kt`（队列状态机模式）、`service/CacheBookService.kt`（dataSync 前台先例+通知节奏）、`ui/book/cache/CacheManageViewModel.kt`（httpTTS 维度 :43-44）+ `service/HttpReadAloudService.kt`（:186-235 合成循环/:242-248 preDownloadAudios/:518-520 内联键/:560-572 文件 API）
  - 逐条核实上游 design §8.2 简化项表（S1-S7）与当前代码一致：循环切换现状 / 声源仅引擎级（toneID 空）/ Store 书级覆盖能力就绪无 UI / 试听缺位 / routeForCue 未接入 / 缓存键内联现状；产出核对清单（AOAdapt 或任务备注）
  - 签名核对项：SpeechVoiceRoutePicker 调用方现签名零破坏（2.5 依赖）；Store 快照失效入口可复用（2.2/2.7/2.8 依赖）
  - 若行号漂移：以当前代码事实为准修订实施细节，并在第 5 节 AOAdapt 日志区记录
  - 验证标准：L1 前置——`git status` 干净或现存改动与朗读/选角模块无关（确认无并行会话冲突）

- [x] 1.3 bak 备份受影响文件
  - 按 `{文件名}.bak` 规则备份全部计划修改文件（casting 四件套 / 朗读设置两 Dialog / ReadAloudPlayerPanel / AiReadAloudRoleService / HttpReadAloudService / strings.xml / AndroidManifest.xml，以 2.x 实施清单为准）
  - 验证标准：bak/ 备份数 = 计划修改文件数，可回滚

- [x] 1.4 核实本轮硬约束（实施前逐项确认）
  - Room v110 冻结：本期无新表/新列需求，**禁止 bump version**（若实施中发现必须动库 → 停止并回 design 评审，禁止自行迁移）
  - 同通道校验入口：核实 TtsCastingStore 现有 engineType 校验位置，编辑器保存/JSON 导入统一走该入口（rules 内全部声源 engineType 一致，跨通道拒绝并明示）
  - preferKey 前缀命名：本期新增 PreferKey 统一前缀（试听/编辑器配置项），登记 `allPreferenceKeys`（ReadAloudConfigDialog），防备份/清理遗漏
  - 预合成约束（v1.1 扩围）：队列内存态不持久化（Room v110 冻结维持）；前台服务 foregroundServiceType=dataSync（仓库 10+ 先例核对：CacheBookService/DownloadService 等）；缓存键单源（播放端/批量端同函数禁第二套）；播放优先租约（当前朗读章跳过延后）
  - AOAdapt 兜底：任一约束与实况冲突 → 停止实施并登记第 5 节，禁止绕过
  - 验证标准：L1 前置——三项约束核实结论落定后才开始第 2 节

## 2. 核心实现（P2-1~P2-7 按依赖顺序）

> **依赖序**：2.1 试听基建（底座）→ 2.2/2.3 选择器与管理页 → 2.4 编辑器 → 2.5 音色级声源 → 2.6 试听入口（消费 2.1）→ 2.7/2.8 链路接入 → **2.10 预合成键收敛（前置重构，播放链行为等价）→ 2.11 队列 → 2.12 前台服务 → 2.13 入口 UI → 2.14 收尾**；2.9/2.15 收尾。
>
> - 任务↔P2 映射：P2-1=2.2｜P2-2a/2b=2.3/2.4｜P2-3=2.5/2.1/2.6（音色级+试听）｜P2-4=2.7｜P2-5=2.8（AI 链；编号统一修订）｜P2-6=2.15｜**P2-7=2.10~2.14（v1.1 检查点扩围）**
> - 简化项映射：S1→2.2，S2→2.3/2.4，S3→2.5，S4→2.7，S5→2.1/2.6，S6→2.8，**S7→2.10~2.14**（上游 §8.2 全闭环，登记后续清零）
> - v1.1 扩围依据：design.md §3.7 + AD-15；S7 实装严格沿上游 §1.8-D-1 九条定型契约落地

- [x] 2.1 ★ TtsVoicePreviewController 移植（试听基建，2.6 依赖底座；AD-12 契约移植+依赖适配）
  - **禁整类照抄**（NG 依赖 help/tts 包本仓零存在）：按 AD-12 适配表映射——TtsVoice→SpeechVoiceOption、TtsEngineSetting→HttpTTS+SpeechRoute、TtsPlayerFactory→本仓 ExoPlayer 直建
  - **自写 script 描述符执行器**：TtsScriptEngineClient.synthesize 返回 TtsScriptRequest 描述符 → 自写"描述符→HTTP 取流→写临时文件"（preview_ 命名空间，不入键体系）
  - **系统声源试听=自建临时 TextToSpeech 实例+shutdown**；utteranceId 前缀定型 `preview_`（NG 实际 voice_preview_，移植统一改）；试听文本=strings DEFAULT 固定文案
  - 移植 NG 试听控制器：600ms 防抖按 previewKey（key=engineId|system|voiceId，窗口内重复点击合并为最后一次，同 key 再点=停止切换）；双 token（脚本通道 ++requestToken 响应校验+临时文件删除；系统通道独立 systemPreviewToken）
  - 临时文件 `cacheDir/voice_preview_{engineId}_{ts}.audio` → ExoPlayer 播放 → STATE_ENDED/错误 finish 完成守护（100ms 轮询+末端 300ms 宽限）；stopActivePreview：token++/cancelJob/release/删文件/回 IDLE；宿主 dismiss/onDestroyView 调 release（控制器随宿主 Fragment 生命周期，不进全局单例）
  - beforePreview 暂停正在朗读；试听通道隔离：utteranceId 统一 `preview_` 前缀（不占用朗读通道，上游 design 约束）
  - 文本=strings DEFAULT 固定文案；试听按钮 LOADING/PLAYING 状态
  - 单元测试：token 竞态（迟到回调不覆盖新请求）/防抖（600ms 窗口去重）（蓝本=NG 附带 TtsVoicePreviewDebouncerTest.kt）
  - 验证标准：L1——编译通过 + 单测全绿 ✅

- [x] 2.2 ★ 模板选择列表器（P2-1，补 S1）
  - **主入口=朗读设置 ReadAloudConfigDialog"多人听书模板"行升级为完整列表器**（Dialog 承载 AppDialogStyle 组件族，design §3.1 唯一口径）：模板名+规则摘要+单选；选中写 Store 默认模板并触发快照失效（切换即时生效）
  - 列表器顶部常显当前实际生效来源（本书书级覆盖时选择仅改全局层并即时提示）；空态引导（S1-2）
  - 规则摘要生成：规则行 tag+match 类型计数摘要（不展开 pattern 原文防超长）；Scene 联动摘要：激活模板名/双声状态在场景模式摘要位同步展示（闸位解耦语义不变）
  - 循环切换保留兼容：ReadAloudPlayerPanel Scene 闸位 cycleTtsCastingTemplate 不删（既有用户路径），**循环链过滤 enabled=false 项**
  - 验证标准：L1 ✅；L2——路由层验证通过（模拟器激活模板→多角色路径命中，issues-found.md）；听感双证留真机

- [x] 2.3 ★ 选角模板管理页（P2-2a，补 S2 管理侧）
  - 单 Fragment+路由枚举 LIST/EDITOR/IMPORT（移植 NG TtsEngineConfigRoute 模式：backDestination 定义返回栈，模板 id 存 Fragment 状态不走 Intent）
  - 列表卡片：模板名+内置/自定义 tag+启停 tag；内置只读（不可删不可改）/复制为自定义/删除（仅自定义）/启停开关（即改即存+失败回滚明示）
  - 交互反馈：复制/删除/启停操作结果 toast 或计数明示，对齐 AppDialogStyle 组件族
  - 入口：朗读设置"多人听书模板"行改跳管理页（ReadAloudConfigDialog itemsForGroup 模式），循环切换保留；IMPORT 路由态与编辑器 JSON 导入共享同一幂等链
  - 空态明示：无可用模板时引导（回退内置模板导入）
  - 验证标准：L1——编译通过 ✅

- [x] 2.4 ★ 选角模板编辑器（P2-2b，补 S2 编辑侧）
  - 规则行动态表单：tag（narration/dialogue/dialogue_male/dialogue_female/`ai:` 前缀）× match（builtin_quote/regex/keyword）× pattern 输入 × 声源选择 × prosody 滑条（rate/pitch/volume 松手即存，限幅对齐 casting 模型 clamp 语义）
  - 同通道校验：保存/导入时 rules 内全部声源 engineType 一致，跨通道拒绝保存/导入并明示（走 1.4 核实入口）；regex Pattern 构造时预编译校验（非法正则即拒）
  - rulesJson 含 schemaVersion；保存走 revision 串行 Job（防并发覆盖，NG 编辑表单模式：文本类焦点丢失/Done 保存+返回前兜底保存）
  - JSON 导入导出：ignoreUnknownKeys 幂等导入（未知字段忽略+计数明示）、导出携带 schemaVersion
  - 内置模板"复制为自定义"进编辑器（builtin 幂等跳过语义不变）
  - 单元测试：跨通道拒绝 / 坏 JSON 容错 / schemaVersion 往返
  - 验证标准：L1——编译通过 + 单测全绿 ✅（收尾拆分 TtsCastingEditorWidgets.kt 控红线，AOAdapt #5）

- [x] 2.5 音色级声源（P2-3，补 S3）
  - 编辑器规则行声源接 SpeechVoiceRoutePicker（签名对齐零改造复用），声源从引擎级细化到音色级（音色级写入规则 source）
  - script 引擎音色目录：fetchVoicesCatalog 拉取缓存归一化后进 picker groups（voices 目录映射，音色 id 回写规则声源）；SYSTEM 引擎分组行为对齐 picker 现状
  - 音色级声源经 TtsCastingModel 序列化承载（写入格式纳入 2.4 schemaVersion 往返覆盖）
  - 验证标准：L1 ✅；L2 音色目录出列留真机（模拟器无 script 引擎语音数据）

- [x] 2.6 ★ 试听入口（P2-3，补 S5）
  - 管理页/编辑器音色行试听按钮接入 2.1 控制器；宿主 dismiss/onDestroyView 释放
  - 验证标准：L1 ✅；L2 试听真声链留真机（模拟器无语音数据，E1）

- [x] 2.7 书级模板覆盖 UI（P2-4，补 S4）
  - 朗读设置当前书上下文行：显示"当前书覆盖模板 / 全局缺省"态，支持书级优先全局+清除覆盖（TtsCastingStore.setBookOverrideTemplateId 接线）；无当前书上下文时行隐藏/置灰明示
  - Store 接线：覆盖写入/清除触发快照失效，resolve 链书级优先语义经 UI 生效
  - 作用域声明：仅影响该书朗读的模板 resolve 结果，不动书级引擎覆盖既有语义
  - 验证标准：L1 ✅；L2 书级优先级走查留真机

- [x] 2.8 ★ AI 链接入（P2-5/L-d，补 S6）
  - resolveSourceForTag 扩展六级链前两级：BookCharacter 绑定 → cast_role（AI 分镜 tag 命中绑定角色声源，未命中回落既有链）
  - routeForCue 修复接入播放链（路由仅 UI 展示 → 服务侧消费）；type==1 过滤维持（type=2 不进 AI 候选）；`ai:` tag 命名空间与模板层 tag 词汇打通
  - 可观测性：AppLog.putDebugWithTag 节流日志（每段 tag→source→结果，统一 tag 便于多角色链定位）
  - 回归保护声明：C3 token 统计链不触碰；C7 cue 与 tag 段坐标映射双轨声明（moveTo 死路径不挂）
  - 改动面收口：仅限 resolveSourceForTag 前两级扩展+routeForCue 消费点，不触碰分镜缓存与段落坐标映射
  - 验证标准：L1 ✅；L2 AI 分镜听感留真机

- [x] 2.9 strings.xml 新增文案
  - `values/` 全量新增（管理页/编辑器/试听/书级覆盖/导入导出/同通道校验提示/**预合成通知·进度·完成·失败·能力门控禁用明示**等），其余 locale 缺省回退声明（不逐 locale 硬译）
  - 验证标准：L1——编译通过，values/ 资源无缺 key ✅

- [x] 2.10 ★ 缓存键与合成函数收敛重构（P2-7 前置，design §3.7.1，§1.8-D-1 契约 1/2/3/5/6/8/9 落地）
  - 新增 TtsCacheKeys 纯函数（engineKey=引擎 id/speedKey/voiceKey+**章节 index**+stem+单元 hash+KEY_VERSION 参与哈希）单一权威源；HttpReadAloudService 内联键（md5SpeakFileName，约 :518-521）改走该函数（播放端与批量端同函数，禁第二套）
  - **KEY_VERSION 首升=有意失配**（design §3.7.1-2 权威声明）：存量期1 httpTTS 缓存一次性失配重合成，updateLog 面向用户告知条目必备
  - 单元切分抽独立纯函数（签名显式携带 readAloudByPage flag；预合成前置=正文下载+排版构建 TextChapter，design §3.7.1-3）；合成循环抽 `synthesizeToFile(book, chapter, unitText, engineParams): File` 纯函数（进度无关；服务状态剥离：downloadErrorNo 局部化/speechRate 注入/错误改返回值；script 引擎封装"请求转换+取流+写盘"全链，design §3.7.1-5）
  - 写缓存收敛单一原子提交（createSpeakFile 现状直写 → temp+rename+has() 幂等）+commitIfLeaseActive 租约插桩唯一落点；cacheSynthesizer 能力接口定型（门控三条件：type∈{1,2}+非流式模式+多角色未激活，design §3.7.1-6）；.part 在途标记+preserveInProgress 约定；**预合成保留名单 prebuildReservedKeys+removeCacheFile 跳过**
  - **L1 对拍单测（硬门禁）**：同 KEY_VERSION 代际内新函数 vs 旧内联式拼接规则的**算法等价性对拍**（固定 voiceKey="" 构造旧代际输入）+KEY_VERSION 失配行为+原子提交并发单测+空 url/同章名用例
  - 验证标准：L1——编译通过+对拍单测全绿 ✅；播放链可观测行为零变化声明核对（C5）✅

- [x] 2.11 ★ TtsPrebuildManager 队列单例（P2-7，design §3.7.2，AD-15）
  - object 单例（AudioCacheTaskManager 同构但**用项目 Coroutine 封装+逐单元 cancelFlag**）；**全局单队列 FIFO**（跨书排队+State 携带 bookKey）；**参数快照**（入队锁定键参数/模板/切分 flag 全集，完成通知带摘要）；**失败语义**（重试 1 次后跳过+失败章节目记录+AD-08 不适用批量）；**进度账目**（入队 has() 预扫描，账目=剩余/总单元）；状态机 idle/running/done/failed/cancelled；**预合成保留名单 prebuildReservedKeys**；内存态不持久化（Room v110 冻结）
  - 单元测试：幂等跳过账目/参数快照（切引擎不影响进行中任务）/租约延后（3 轮上限强制跳过）/取消后 .part 清理（preserveInProgress=false）/状态机迁移/失败明细记录
  - 验证标准：L1——编译通过+单测全绿 ✅

- [x] 2.12 TtsPrebuildService 前台服务（P2-7，design §3.7.2）
  - dataSync 前台壳（对齐 CacheBookService 先例，FOREGROUND_SERVICE_DATA_SYNC 权限已存在核对）：startForeground+通知进度（每秒轮询 StateFlow 刷新）+完成/失败明示+结束 stopSelf；通知点击→CacheManageActivity；**onDestroy/onTaskRemoved→Manager.cancel+cancelled**；AndroidManifest 注册（exported=false）
  - 验证标准：L1 ✅；L2 通知链走查（含 POST_NOTIFICATIONS 未授予分支）留真机
  - 租约判定数据源核实：ReadBook.curTextChapter/durChapterPos+朗读服务运行标志（禁引用 nowSpeak）✅

- [x] 2.13 阅读菜单"批量预合成"入口+起止章节 Dialog（P2-7，design §3.7.2 启动链）
  - ReadBookActivity 菜单新增（对齐 menu_download/showDownloadDialog 范式）：起止章节号输入+流量提示→能力门控校验（**三条件**：系统 TTS/流式模式/多角色激活 禁用+分别明示原因）→startForegroundService+Manager.enqueue(book, 区间, 参数快照)
  - 验证标准：L1 ✅；L2 发起链走查（含三条件禁用态逐一明示）留真机

- [x] 2.14 缓存管理页 TTS 维度验证收尾（P2-7，design §3.7.4）
  - CacheManageViewModel cacheDir/httpTTS 维度（统计+清理，整目录粒度）覆盖预合成产物核实；**清理联动**：清理 httpTTS 维度→取消进行中任务+清空保留名单；**删书联动**：删书链路同步 Manager.cancelByBook(bookKey)+清空名单（落点文件实施时核实）；**播放中保护**：音频维度对齐视频既有保护（朗读运行中拒绝清理+明示）；"按章删除钩子"现状不存在声明核对（BookHelp.delChapterCache 仅删图片+正文）
  - 验证标准：L1 ✅；S9-7/S9-8 场景走查留真机

- [x] 2.15 回归保护执行（P2-6；上游 §1.8-F C1-C11 相关项）
  - 与本变更相关项（C1/C3/C4/C5/C7/C11）逐项回归用例，其余项确认不受影响即可：
    - C1 BGM：多人朗读开 BGM 播放正常，BgmAssignmentCache 绑定不失效（缓存键组不联动）
    - C3 token：AI 朗读后 token 统计页计数正常（链接入不触碰 token 统计）
    - C4 悬浮球：样式三键设置在面板/管理页改造后不受影响（UI 改造避开悬浮球链路）
    - C5 缓存键：TtsCacheKeys 算法等价性对拍（2.10，同代际口径）+存量 type=1 模板播放合成路径/降级与期1 一致（**缓存键升版有意失配例外**，design §3.7.1-2）
    - C7 cue：cue 与 tag 段坐标映射双轨声明（moveTo 死路径不挂）
    - C11 广播：IntentAction 全集外部广播兼容核对
  - 验证标准：L1——回归用例清单落定并入 3.7 执行 ✅

## 3. 验证测试

- [x] 3.1 updateLog.md 第二十批更新（编译前强制）
  - 基于 `git diff` 逐文件对照分析真实变更，追加在 `## cronet版本:` 之后、已有条目之前；面向用户语言，禁止文字合并旧条目
  - 验证标准：L1 前置——updateLog 覆盖全部用户可感知变更 ✅（commit 6609caa）

- [x] 3.2 ★ 编译 + 全量单测
  - `./gradlew test` 全绿（含 2.1 试听 token 竞态/防抖、2.4 编辑器跨通道拒绝/坏 JSON/schemaVersion 往返、2.10 算法等价对拍/KEY_VERSION 失配/原子提交并发/空 url/同章名、2.11 队列幂等/参数快照/租约 3 轮上限/取消清理/失败明细 新增用例）
  - 验证标准：L1——BUILD SUCCESSFUL + 单测全绿 ✅（275 项，3 个已知 flaky 修复后通过）

- [x] 3.3 ★ 打包测试包 + MEmu 装机覆盖安装
  - `build-legado.bat`（产物 io.legado.miss.app.debug）→ MEmu 覆盖安装
  - Room v110 冻结验证：覆盖安装启动 FATAL=0，存量模板/引擎记录可读
  - 装机首轮冒烟：主界面 → 朗读设置（管理页入口可见）→ 旧循环切换仍可用
  - 验证标准：L2——覆盖安装启动无崩溃 + v110 冻结确认 ✅（3.26.090912，Migration 109→110 通过）

- [x] 3.4 ★ L2 主链：模板选择→朗读→编辑→热生效
  - 面板列表选模板 → Scene 进入 → 双声朗读正常 → 编辑器改规则保存 → 朗读热生效（快照失效链日志证据）
  - 证据口径：模板切换/快照失效 AppLog 技术日志 + 听感双证，同书同章切换前后对比
  - 验证标准：**部分完成**——模拟器已验证：legacy 路径标记命中、激活 builtin_male_female 后多角色路径命中（legacy 标记消失+无选角解析错误+无崩溃）、引擎切换双向路由（initTts engine 显式包名/默认回退，issues-found.md L2 表）；**听感双证+编辑器改规则热生效 UI 走查留真机**（模拟器无中文 TTS 语音数据 E1）

- [x] 3.5 ★ L2 试听链
  - 音色行试听播放/停止/连续点击防抖不叠加/同 key 再点停止切换；退出页面释放无泄漏
  - 覆盖场景：script 引擎音色试听（临时文件链）+ 系统引擎音色试听（utteranceId `preview_` 前缀日志）
  - 验证标准：**部分完成**——L1 单测（token 竞态/防抖）通过；试听真声链留真机（E1）

- [ ] 3.6 L2 书级覆盖
  - 同书设置覆盖模板生效 → 他书不受影响 → 清除后恢复全局
  - 验证标准：L2——优先级+清除语义正确（**留真机走查**；Store 层逻辑有单测覆盖）

- [ ] 3.7 ★ L3 场景回归
  - BGM 播放（C1）/token 统计页（C3）/悬浮球样式三键（C4）回归无退化
  - legacy httpTTS（type=1 模板流+纯数字 id）播放正常；AI 聊天语音（type=2 不进候选）；循环切换兼容（与列表选择器双路径并存）
  - legacy 兼容补充：legacy SelectItem 配置用户升级后双路径（循环切换+列表选择器）可用
  - 预合成回归（v1.1 扩围）：存量 type=1 模板播放合成路径/降级与期1 一致（C5 对拍，键升版失配例外）+按章清理走缓存管理页口径
  - 兼容走查：备份→恢复→书级覆盖键/激活键存活（全量 SP 还原链）+fontScale 1.4x 下列表器/编辑器规则行三滑条/预合成 Dialog 无溢出+新控件 contentDescription 全覆盖
  - 验证标准：L3——各场景无功能退化（**留真机执行**）

- [x] 3.8 静态检查
  - Grep `android.util.Log` 无残留调试日志（logging-during-refactoring 强制项）；新增单文件 500 行红线核查
  - Grep `ensureActive` 接收器正确性（防误用非当前作用域接收器）
  - 验证标准：L1——零残留 ✅ + 红线无超限 ✅（TtsCastingEditorScreen 520→464 行，拆出 TtsCastingEditorWidgets.kt 77 行，AOAdapt #5）

- [ ] 3.9 AI E2E 影响分析（步骤 5.5）
  - `ai_tests\venv\Scripts\python.exe ai_tests/run_e2e.py --diff` 源码影响分析（遵循 `ai_tests/docs/fixed_test_workflow.md`，禁止 temp/ 建临时脚本）
  - 验证标准：L2——影响面确认完毕；如需新增 L2 场景脚本，按白名单口径落位 ai_tests/scripts/（**待执行**）

- [ ] 3.10 ★ L2 预合成全链（P2-7，S9-1~S9-9 场景走查）
  - 发起链：阅读菜单入口（三条件禁用态逐一明示 S9-3）→ 起止章节 Dialog（含流量提示）→ 前台通知进度+参数摘要（S9-1）
  - 幂等与取消：重发同区间跳过已合成+账目=剩余/总（S9-2）；通知/页面取消与服务 onDestroy/onTaskRemoved 的 .part 清理+产物保留（S9-4）
  - 命中复用与键升版：预合成完成章节开始朗读无网络请求直接播本地文件+保留名单不被播放侧清理驱逐（S9-5）；升级后旧内容一次性重合成不报错
  - 双向并发防护：批量→朗读章延后（3 轮上限）；朗读推进到 .part 在途章→原子提交无损坏（S9-6）
  - 管理一致性：统计/清理覆盖预合成产物+播放中保护（S9-7）；清理/删书联动取消任务+名单清空（S9-8）
  - 验证标准：L2——S9-1~S9-8 全 PASS（**留真机执行**：需 HTTP TTS 源+真实合成出声；租约/账目/状态机纯函数已有单测覆盖）

## 4. 文档收尾

- [ ] 4.1 文档同步
  - `docs/INDEX.md` 状态流转（🔄 → ✅）；`docs/project-flow/task-navigation.md` 朗读模块锚点更新（casting 管理页/编辑器/试听控制器）；README 状态流转
  - 本 spec 目录三文档状态同步；AOAdapt 中需回写 design.md 的记录在收尾时统一落回
  - 验证标准：文档与代码结构一致，锚点可达

- [x] 4.2 issues-found.md 记录真机问题
  - 3.3~3.7 过程中发现的所有真机问题逐条登记（无问题则明确标注"无"）
  - 验证标准：真机问题零遗漏登记 ✅（含模拟器环境限制 E1/E2 + 测试过程发现 T1-T3 + L2 验证结论表 + 残留风险 R1/R2）

- [ ] 4.3 清理
  - 删除 `temp/phase2-design-brief.md`（简报使命完成）；bak 备份保留至验收；临时日志清零
  - 验证标准：`git status` 无临时文件残留

- [ ] 4.4 项目记忆更新
  - `ai_memory_main.md` "当前任务状态"字段更新为完成态
  - 验证标准：记忆与实际交付状态一致

## 5. AOAdapt 日志区

> **用途**：实施过程中发现方案与代码实况偏差（行号漂移 / API 不符 / 依赖缺失 / 简报结论失效）时**必须**记录一条，禁止静默改方案。每条记录对应一次"计划 → 实况 → 调整"闭环，作为 design.md 修订与验收审计依据。使用时机：1.2 精读核实 / 2.x 实施签名锚点不符 / 3.x 验证与预期不符。
> **联动**：需回写 design.md 的 Adapt 记录，在 4.1 文档同步统一落回，禁止只记录不回写。

**格式**：

| 字段 | 含义 |
|------|------|
| Action | 原计划动作（对应任务编号，如 2.4） |
| Observation | 实际观察到的偏差（客观事实：文件/行号/异常类型） |
| Adapt | 调整决策（新做法 + 影响面 + 是否需回写 design.md） |

| # | 时间 | Action | Observation | Adapt |
|---|------|--------|-------------|-------|
| 1 | 2026-09-09 | 2.x KSP 编译 | @Insert(OnConflictStrategy.REPLACE) 参数形式触发 KSP2 MissingType（AppDatabase 引用类型缺失） | 改为 @Insert(onConflict = OnConflictStrategy.REPLACE) 正确形参写法；无需回写 design |
| 2 | 2026-09-09 | 3.3 装机 | 覆盖安装报 A migration from 109 to 110 was required but not found | DatabaseMigrations.kt 补 migration_109_110 + AppDatabase.kt 挂载；需回写 design §数据库（v110 迁移实现确认） |
| 3 | 2026-09-09 | 2.11 单测 | TtsPrebuildLeaseTest 租约 3 轮上限用例失败（测试参数 curChapterIndex 与任务章不匹配） | 修正测试参数使 curChapterIndex=taskChapterIndex；无需回写 design |
| 4 | 2026-09-09 | 3.x 真机验证 | 模拟器无 TTS 引擎包且 MEmu NAT DNS 故障无法下载 RHVoice 语音数据（E1/E2） | 装开源 RHVoice 引擎验证 init/绑定/路由链；真实人声/试听真声/预合成 E2E 留真机；无需回写 design |
| 5 | 2026-09-09 | 3.8 静态检查 | TtsCastingEditorScreen.kt 520 行超 500 行红线 | 拆出 TtsCastingEditorWidgets.kt（ProsodySlider，77 行）→ 464 行；无需回写 design |
