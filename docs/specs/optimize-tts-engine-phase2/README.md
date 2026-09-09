# optimize-tts-engine-phase2（TTS 多角色体验完善与生态扩展·期2 完整版）

> 状态：🔨 实施完成（2.x 全量编码 + 单测 275 项 + 3.26.090912 测试包覆盖安装 + 模拟器 L2 可验证项全通过；听感双证/试听真声/预合成 E2E/书级覆盖走查留真机，环境限制与证据见 `issues-found.md`） ｜ 日期：2026-09-09 ｜ 上游基线：`docs/specs/optimize-tts-engine/`（v1.3 定稿 + §8 实施进度登记，分支 `feat/optimize-tts-engine @07ffe7a`）

## 1. 背景与定位

- **上游基线**：optimize-tts-engine 期1+期2 部分已交付（编译/JVM 单测/装机验证通过，见上游 §8 实施进度登记），解决"朗读引擎切换完全不生效"Bug 并落地多角色分层。
- **期次拆分**：期1 为最快交付"小白零配置双声"最小价值闭环，对 7 项能力做了裁剪（S1-S7，上游 design.md §8.2 登记）。
- **本期定位**：补全 **S1-S7 完整产品形态**——模板层从"能用"升级为"可发现、可管理、可编辑、可试听、可书级覆盖、可被 AI 消费"（L-c 范式模板层完整化，四层模型不动）；**S7 批量预合成/缓存管理经检查点扩围裁决纳入**（v1.1，登记后续清零）。
- **验收口径**：以 spec.md R1-R9 + 43 场景矩阵为准（含 S2-5/S3-7/S9-8/S9-9）；R8 锚定上游 §1.8-F C1-C11 回归清单（含期1"零 AI 零 httpTTS 双声可听感"口径不回退）；R9 锚定 S9-1~S9-9 预合成场景（键升版/保留名单/双向并发/清理删书竞争/跨书排队与参数快照）。
- **边界**：预合成任务持久化/音频跨设备迁移不做（队列内存态，AD-15）；其余期后项见 design.md §3.6（角色级绑定 dock/按段换源主体/SSE 流式等）。

## 2. 上游已交付基线（期1+期2 部分 @07ffe7a）

| 模块 | 已交付内容 |
|------|-----------|
| 引擎路由 | SpeechRoute.resolveSpeechRoute 四态解析（新 JSON/双嵌套/SelectItem/数字 id；坏 JSON→default、裸包名→system），切引擎立即生效 |
| 系统 TTS | 8s init 看门狗（代际判定）+降级默认引擎明示+reInitTts 引擎内重建+PendingSwitch 数据层续播+每引擎独立参数（TtsEngineParamsStore） |
| 脚本引擎协议 | TtsScriptEngineClient（Rhino 沙箱 withBookSourceClassPolicy 显式启用+10s 超时+四字段边界+体积限额）+options/voices/synthesize 三函数契约 |
| 数据库 v110 | httpTTS 增列 type(1=http/2=script)+script；ttsCastingTemplates 建表（覆盖安装 migration 真机验证 ✓） |
| 选角模板层 | TtsCastingModel（CastingRuleSet/CastingRule/tag 词汇表/ai: 前缀/CastingProsody）+TtsTagSplitter（引号规则中英集/区间互斥/并集覆盖）+TtsCastingStore（Room 单一权威源/engineType=current 哨兵/书级覆盖能力就绪无 UI/六级 resolve 链/内置 4 模板幂等导入）+TtsVoiceSource |
| 服务层 | speakMultiRole 逐段驱动（五元组分段契约/精确字符账/onDone-onStop-onError 推进闸）+speakLegacyLoop 双路径+type=2 合成分支+voices 目录拉取缓存 |
| UI（期1 简化版） | 朗读设置"多人听书模板"循环切换（ttsCastingSummary/cycleTtsCastingTemplate）+Scene 闸位解耦（multiRoleAvailable=AI 多角色开启‖选角模板激活） |
| 内置模板与导入 | 选角 4（双声/男女对读/MultiTTS 透传/单声）+引擎 4（multitts_forwarder/clonetts/openai_compat/edge_proxy_template）；SpeakEngineDialog 导入第 4 行内置脚本引擎模板（幂等，默认未启用） |

## 3. 期2 简化项缺口（S1-S7）

| # | 简化项 | 期1 简化形态 | 完整形态缺口 | 本期处置 |
|---|--------|--------------|--------------|----------|
| S1 | 模板选择入口 | 循环切换 | 列表选择器（模板名+摘要+单选+Scene 联动） | ✅ P2-1 |
| S2 | 管理页/编辑器 | 无（仅内置 4 模板不可编辑） | 管理页（内置只读+复制为自定义+删除/启停）+编辑器（规则行+声源选择+韵律）+JSON 导入导出 | ✅ P2-2 |
| S3 | 音色选择 | 声源仅引擎级（toneID 空缺省） | SpeechVoiceRoutePicker 接入（音色级）+script voices 目录映射 | ✅ P2-3 |
| S4 | 书级模板覆盖 UI | Store 能力就绪无 UI | 书级覆盖行+当前书上下文 | ✅ P2-4 |
| S5 | 试听 | 无 | 防抖 600ms+双 token+临时文件+播放 | ✅ P2-3 |
| S6 | AI 链 routeForCue 服务侧消费 | 未接入 | AI 分镜 tag → 模板层消费（L-d 完整） | ✅ P2-5 |
| S7 | 批量预合成/缓存管理 | 未实装（§1.8-D-1 定型项已就绪） | 任务队列/前台服务/选章 UI/缓存管理页 | ✅ P2-7（v1.1 扩围纳入） |

## 4. 五角度缺口分析总览

| 角度 | 现状 | 缺口 | 本期对策 |
|------|------|------|----------|
| 产品 | 内置 4 模板开箱即用，小白路径（0 配置双声）可用；折腾路径仅循环换模板 | 模板自定义能力为零；循环切换发现性差、误触率高，看不到"有哪些/当前是哪个" | 列表选择器（P2-1）+管理页/编辑器（P2-2）补齐折腾路径 |
| 整体体验 | 切模板需连点循环；配错声源要整段朗读才能发现 | 试听缺位使选型漏斗断裂；空模板列表无引导；Scene 联动摘要弱 | 列表单选+Scene 联动摘要（P2-1）；试听（P2-3）；空态引导 |
| 功能完备 | S1-S7 均为简化态（上游 §8.2） | 音色级声源（toneID 空缺省）、书级覆盖 UI（Store 能力就绪无 UI）、AI 链消费（routeForCue 零接入）、批量预合成缺位（实时合成等待+无离线加速）缺位 | P2-3/P2-4/P2-5/P2-7 逐项闭环，对照 NG/C 收敛剩余差距，登记后续清零 |
| 架构稳定性 | Store 单源 + invalidateSnapshot 快照失效链稳定 | 新 UI 并发保存可能引入旧响应覆盖；AI 接入触碰播放链回归面；预合成与播放并发写读竞争 | 编辑器显式保存+全量校验；AI 接入收敛到 AiReadAloudRoleService 侧；预合成四件套（租约+原子提交+键单源+保留名单，AD-15）；R8 回归清单兜底 |
| 整体架构不缺 | 四层模型/同通道校验/Room v110/预合成预留（§1.8-D-1）已定型 | 风险=新 UI 破坏定型项（voiceParamsJson 键/同通道入口/Room 单源/缓存键单源） | 约束锁定：Room v110 冻结、同通道校验强制、§1.8-D 定型项不破坏；S7 沿 §1.8-D-1 九条契约实装（预留升实装，零返工） |

## 5. 期2 交付范围

| # | 交付项 | 内容 | 需求 |
|---|--------|------|------|
| P2-1 | 模板选择列表器 | 列表单选（模板名+摘要+Scene 联动）替代循环切换；保留循环为兼容入口 | R1 |
| P2-2 | 管理页+编辑器 | 内置只读/复制为自定义/删除/启停（排序可延后）；规则行 tag×match×source×prosody；同通道校验；JSON 导入导出 | R2/R3 |
| P2-3 | 音色级声源+试听 | SpeechVoiceRoutePicker 复用 + script voices 目录映射（MultiTTS /voices 归一化）；试听防抖 600ms+双 token+临时文件+beforePreview 暂停朗读 | R4/R5 |
| P2-4 | 书级模板覆盖 UI | 消费 setBookOverrideTemplateId 既有能力；书级优先全局；当前书上下文 | R6 |
| P2-5 | AI 链接入 | ai: 分镜 tag → 模板层消费；routeForCue 修复接入播放链；type==1 过滤维持 | R7 |
| P2-6 | 回归保护 | 上游 §1.8-F C1-C11 清单执行与登记（含 C5 缓存键对拍护栏） | R8（回归保护伴随） |
| P2-7 | 批量预合成+缓存管理（v1.1 扩围） | TtsCacheKeys 键单源纯函数（KEY_VERSION）+synthesizeToFile/单元切分纯函数+cacheSynthesizer 能力门控+TtsPrebuildManager 内存队列（播放优先租约+.part 约定）+TtsPrebuildService 前台（dataSync）+阅读菜单起止章节入口+缓存管理页维度验证 | R9 |

## 6. 文档索引

| 文档 | 内容 |
|------|------|
| [spec.md](./spec.md) | Intent/Scope/Approach（Selected+4 Alternatives+Drawbacks+复盘+数据契约+蓝本对照）/R1-R9/43 场景+覆盖矩阵 |
| 上游 `docs/specs/optimize-tts-engine/design.md` | §0 阅读指南与术语表+§8 实施进度登记（本期基线权威源） |
| 上游 `docs/specs/optimize-tts-engine/spec.md` / `tasks.md` | 上游 R 条款验收锚点与任务拆分（R8 回归清单出处） |
| `temp/phase2-design-brief.md` | 期2 设计简报（简化项 S1-S7/NG·C 交互流深挖/五角度框架/架构约束） |

## 7. 关键约束

- **四层模型不动**（L-a 基础服务/L-b 适配层/L-c 范式模板层/L-d AI 复用）：新 UI 只是 L-c 的展示层；
- **同通道校验强制**：`CastingRuleSet.sameChannel()` 在编辑器保存/导入时强制，跨通道组合拒绝并明示；
- **Room v110 冻结**：无新表/列需求则不再 bump（预合成队列内存态，AD-15）；Dao 新增查询优先 suspend（KSP2 解析限制）；`@Insert(onConflict=)` 禁位置参数；
- **脚本沙箱不破**：RhinoClassShutter/BookSourceStorageScope 保存链沿用，不弱化暴露面清单；
- **上游 AD-01~09 全继承**：降级链（AD-08）与熔断语义不变；存量 type=1 httpTTS 模板流不动；
- **预合成定型契约锁定**（§1.8-D-1 九条）：缓存键纯函数化+KEY_VERSION 单源（播放端/批量端同函数）、单元切分纯函数、temp/rename 原子提交、.part/preserveInProgress 约定、cacheSynthesizer 门控三条件（系统 TTS/流式模式/多角色激活→禁用明示）、播放优先租约（ReadBook.curTextChapter 数据源+3 轮上限）；
- **红队专项契约**（design §8 记录）：预合成保留名单（prebuildReservedKeys 抵御播放侧 10 分钟清理驱逐，P0）+KEY_VERSION 首升=有意失配（存量缓存一次性重合成，updateLog 告知）+参数快照（入队锁定）+清理/删书联动取消任务；
- **试听不占用朗读通道**：独立 utteranceId 前缀 `preview_`；**循环切换保留为兼容入口**；
- **项目惯例**：Compose+LegadoTheme/AppDialogStyle/AppShapes 取色、协程 Coroutine 链、AppLog、无 Timber、strings.xml 中文优先（新页面必须用 strings）。

## 8. 变更日志

| 日期 | 状态 | 说明 |
|------|------|------|
| 2026-09-09 | ✅ 设计完成 | 四件套齐备：README（缺口+五角度分析）/spec（R1-R8+32 场景+蓝本对照）/design（架构+子系统契约）/tasks（任务清单）；待评审后进入实施 |
| 2026-09-09 | ✅ v1.1 检查点扩围 | 用户裁决 S7 纳入本期：新增 R9+7 场景（S9-1~S9-7）、design §3.7 预合成子系统+AD-15、tasks 2.10-2.14+3.10；**登记后续清零（S1-S7 全闭环）**；Room v110 冻结维持（队列内存态） |
| 2026-09-09 | 🔴✅ v1.2 红队专项 | 双子代理（A 需求覆盖/B 源码穿透）42 条发现全量处置：3 P0（保留名单防清理驱逐/KEY_VERSION 有意失配裁决/参数快照）+14 P1（租约数据源/流式门控/切分排版依赖/6h 时限/失败语义/双向并发等）落盘 design §3.7+§8 红队记录；场景 39→40（S9-8 清理竞争）；对拍口径改同代际算法等价性 |
| 2026-09-09 | ✅ v2.0 通篇重构 | 主代理亲自通读四文档后整体重构：清除正文全部补丁式标注（审查溯源集中 design §8）、3.7.2 巨型 bullet 重构为契约表、编号/术语/版本叙事统一为 v2.0、修 mermaid 编号残留与 narrator 术语漂移 |
| 2026-09-09 | 🔴✅ v1.3 红队五轮 R1（需求完备性） | 14 条发现全量处置：P1×5（S1-1 与 S6-1 覆盖冲突→列表器常显生效来源/2.2 主入口矛盾以 design 为准/停用模板被覆盖引用→S2-5/导入通道 SAF+剪贴板/超前 schemaVersion 拒绝→S3-7）+P2×9（循环过滤禁用/无网预检/voices loading 态/2.6 编号残留/次入口裁掉/试听入口收窄/S3-2 定位冲突行/头部状态同步/S9-9 跨书排队场景）；场景 40→42 |
| 2026-09-09 | 🔴✅ v1.4 红队五轮 R2（可实施性） | 15 条发现全量处置：**P0×2**（toneID 承载 voiceId 纠错——speakerName 仅显示名，禁照写 voiceId 进 speakerName/AD-12 整类移植改契约移植+依赖适配表——NG help/tts 依赖本仓零存在，自写 script 描述符执行器）+P1×6（系统试听定型自建临时 TTS 实例/preview_ 前缀统一/2.1 顺序依赖缺口/File Changes 补 CacheManageViewModel+menu book_read.xml+细线 drawable/通知渠道定型复用 channelIdDownload/租约账目抽纯函数可测性接缝）+P2×7（preDownloadAudios type==2 缺陷豁免/试听文本 DEFAULT 定型/RuleRow 独立文件/TtsCastingTemplate stale 注释登记/归一化复用文件指名/DebouncerTest 蓝本/正向确认字段零缺口） |
| 2026-09-09 | 🔴✅ v1.5 红队五轮 R3（可靠性并发） | 12 条发现全量处置：P1×8（**快照不按 bookKey 键控跨书串模板**——期1 缺陷本期扩大→(bookKey,templateId) 二元组校验/S1-4 改章粒度口径防进度账漂移/Manager scope SupervisorJob+runCatching 异常屏障/状态机单写者+任务代际 id 防旧服务误报/累计计数器归属调用方保留三分类/静音占位不落保留名单/beforePreview 三路径统一恢复/失败分类+磁盘满任务级中止）+P2×4（revision 出队校验/名单按落盘事实登记/临时 TTS init 超时守护/State 终态复位+NotificationId） |
| 2026-09-09 | 🔴✅ v1.6 红队五轮 R4（兼容回归） | 10 条发现全量处置：P1×1（S7-4 AI×模板共存矛盾修正——零回归条件改"未激活任何选角模板"，AI 开启×模板激活=narration/dialogue 命中属预期换声+updateLog 告知）+P2×9（AD-13 备份表述修正=全量 SP 还原+备份恢复走查/2.3 入口措辞对齐/API<29 FGS 行为声明/临时 TTS init 超时 8s/C1 扩共存走查/fontScale+contentDescription 走查/宿主容器声明/渠道禁用兜底/导出注明期2+版本） |
| 2026-09-09 | 🔴🏁 v1.7 红队五轮 R5（对抗破坏终审）=**GO-WITH-NOTES** | 10 条发现全量处置：P1×5（场景计数四处打架统一 43+矩阵补 S2-5/S3-7/tasks 2.4 narrator→narration 术语纠错/**删书联动取消任务落点补齐**/**预扫描异步+单批 ≤200 章上限**/**ReDoS 防护**——pattern 限长 256+切分超时熔断落旁白兜底/S9-9 补入 3.10）+P2×4（术语表补 4 行/通知清除语义=划掉不中止/重复发起去重/C1 补试听焦点+备份排除 .part 裁决）| 五维评分：需求 9.0/可实施 9.0/可靠 8.5/兼容 9.5/一致性 7.5→修订后可冻结 |
