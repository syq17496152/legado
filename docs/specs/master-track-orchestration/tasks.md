# tasks.md — 三轨总线任务编排

> 格式：主任务 `- [ ] X.Y` + 子任务 `- [ ] X.Y.Z` ｜ 每波次收束必须回写本清单 + README 状态（R6）
>
> **子任务账本规则（防双账本，X13）**：各分期实施细节的子任务账本 = 该轨原 spec tasks/分册（权威，总线不复制）；总线独有的编排动作在本文档展开 X.Y.Z 子任务，逐项核销。**各分期开工首个动作=另立实施 spec**（依 docs/specs/TEMPLATE.md 建三件套，落 docs/specs/{分期名}/；2.1.1/3.2.1 为示范，其余分期同此执行）。**任务编号≠执行序，窗口内执行以 design §2 波次表推荐序为准。**
>
> **分册对照（compose spec）**：分册①=design-b1-b2-baseline-freeze.md / ②=design-b3-d4-flagship.md / ③=design-b3-pages.md / ④=design-b4-b5-pages.md。引用其余 spec 章节号以该分册实际章节为准（行号可能随并行会话漂移，以函数/小节名定位）。

## 0. 编排落盘

- [x] 0.1 检查点 1：用户审查波次计划 ✅（七轮审核闭环；用户跳过第七次提问并指令 /goal continue，视为通过记录，2026-08-31）
- [x] 0.2 legadoc-benchmark-analysis/README.md 状态转正 ✅（README 状态字段已替换为 ✅ 设计完成；design.md AD-03~06 Status Proposed→Accepted 联动更新）
- [x] 0.3 docs/INDEX.md 增加"三轨总线编排"条目 ✅（INDEX L204）
- [x] 0.4 V 轨活跃任务盘点登记：新建 v-track-registry.md ✅
  - [x] 0.4.1 全量誊录 design §4 表（33 项登记：实质协调面 18 + 顶栏集群 4 + 低冲突 4 + 不占波次 5+7）✅
  - [x] 0.4.2 登记 video-sniff 4.8e 条目（v109 实占，与 db-version-registry 互见）✅
  - [x] 0.4.3 登记低冲突/不占波次清单（含 ng P4 暂缓）✅
  - [x] 0.4.4 登记 light-theme/video-sniff 真机项（并入 B0 合并窗口）✅

## 1. W0 公共闸门

- [x] 1.1 deep-fix 剩余收口 ✅（弹框迁移收官 2026-09-01；子任务账本=ui-style-unify-deep-fix tasks §2.3 收官记录，R3 终测合并至 2.6 执行）**批次汇总**：实况盘点（D1 P0 原清单 11 项已全迁，tasks 描述陈旧）→ 批 A（3 处 D2 标准件，489d3aed7）→ 批 B（ReadRecord 两弹框换壳，098da583d）→ 批 C+D 复核（4 处登记保留，00c729212）→ 批 E（HighlightStyle 换基类+AdvancedTitleConfig 重写，c6414fe36）→ 批 F 收官（LibraryCloudChapterDialog 新建+PageKeyDialog 换壳，66ffba414；ExploreFragment Kind/SelectionWebSearchDialog 登记保留+专项建议入册 migration-registry 六.4）| 每批编译门禁 BUILD SUCCESSFUL+分步提交 | **deep-fix 剩余**：仅 §5 收尾登记项+R3（合并 2.6）——弹框迁移实质完成；✅ 批 C+D（2026-09-01）：ThemeManageActivity 双表单/WaitDialog/PhotoDialog 4 处逐一 Read 复核**全登记保留**（复合编辑器非文本表单非换壳范畴/20 调用文件+取消语义耦合/全屏图片查看器特殊承载），零代码修改，updateLog 不追加，基线编译门禁复核 BUILD SUCCESSFUL
- [x] 1.2 新建 docs/project-flow/database/db-version-registry.md ✅（五列表+占号规则五铁律+7 期条目：v109 已实占/P1 顺延 v110/P3 顺延 v111/C2/C3 预占/无需占号 2 项）
  - [x] 1.2.1 建表+占号状态枚举（预占/已实占/已顺延/已销号）✅
  - [x] 1.2.2 预占号登记（当前源码 version=109）✅
  - [x] 1.2.3 video-sniff 4.8e 已实占条目 ✅
  - [x] 1.2.4 门禁条款+单人自批留痕 ✅
- [x] 1.3 C0 红测试先行 ⚠️（Level 2；Q1 用户跳过提问→自主裁决采纳推荐项"纯 JVM 单测+L3 兜底零新依赖"）
  - [x] 1.3.1 Q1 裁决 ✅（纯 JVM 降级：returnDefaultValues=true 已配置，LruCache stub 退化永 miss 不影响断言语义；缓存路径 L2 S2/L3 兜底）
  - [x] 1.3.2 复现/守护用例 ✅（AnalyzeRuleCachePollutionTest 3 用例：rule 字段不可变/快照跨调用独立幂等/五元组携带断言；三向量复现依据=C0 分册 §3.6 推演+源码逐点核实）
  - [x] 1.3.3 红测试运行 ⚠️（**AOAdapt 偏差**：实施顺序偏差——修复先于红测试落地，未在修复前运行展示红；以 §3.6 静态推演为复现依据，3 用例转为回归守护（修复后全绿）；后续实施严格红测试先行）
- [x] 1.4 C0-F1 缓存污染 bug 修复 ✅（Level 1 代码完成；开工前 R7 已核：git status AnalyzeRule.kt 无并行占用）
  - [x] 1.4.1 ResolvedSourceRule 不可变快照化 ✅（新增 internal data class 五字段；SourceRule rule→val+删 3 var 字段（init 双赋值重构为局部变量单次赋值）；makeUpRule 返回快照；5 处调用方+replaceRegex 签名适配；全库 Grep 门禁确认消费面零外溢）compileAppDebugKotlin BUILD SUCCESSFUL
  - [x] 1.4.2 回归守护用例全绿 ✅（testAppDebugUnitTest 3 用例 PASS；全量单测 BUILD SUCCESSFUL 零回归）+ daemon 清场 ✅
  - [ ] 1.4.3 L3 书源基线回归（待与 2.13.2 ng P0×C0 合并跑；L2 S2 真机同步延后）
- [x] 1.5 补登轨 C P3-tts-multirole.md ✅（落点 :6，`## 0.1` 正交声明）
- [x] 1.6 补登轨 C P4-visual-patterns.md ✅（落点 :436，`### 5.1` 时序协调）
- [x] 1.7 ui-standards 双栈豁免 + 轨前缀约定落盘 ✅
  - [x] 1.7.1 architecture.md :113 双栈豁免条款 ✅
  - [x] 1.7.2 TEMPLATE.md :42 轨前缀约定 ✅
- [x] 1.8 AppLog Tag 序号规则统一（X6）✅（P0 :402 / P1 :282 / P2 :340 三处措辞修正；P0 原文无"第 27 个"字样，按 :401"26 个模块 Tag"实测行等义修正）
  - [x] 1.8.1 ng P0/P1/P2 分册声明改"按落地顺序顺延" ✅
  - [x] 1.8.2 C5 分册 fromTag 头注（并入 1.9）✅
- [x] 1.9 被引分册头部总线修订注记 ✅（deep-fix tasks :5 / C5 分册 :6 / ng P1 :8）

## 2. W1 安全与基线

- [x] 2.1 ng P0 全期实施 ✅（实施 spec=ng-p0-source-security-impl；S1/S3 提交 1643f1c03+S2/S4 提交 289f898e0（随并行 rss-cms 批次）+T1-T22 单测 b652fb1af（21 用例全 PASS/全量 243 绿/T6 沙箱偏差修复）；四观察开关已登记（bookSourceFileSandbox/blockSourceDialogs/bookSourceCacheScoped/bookSourceClassPolicyLog，默认 false 观察档）| **唯一遗留**：L2 真机回归归 2.6 合并窗口）
  - [x] 2.1.1 另立实施 spec（ng-p0-source-security-impl，引用 P0 分册为权威）
  - [x] 2.1.2 五子项按 §10 顺序实施（S1 文件沙箱/S2 缓存命名空间/S3 弹窗拦截/S4 类导入灰度/S5 零修改回归）
  - [x] 2.1.3 四观察开关登记至回退预案表（8.4.2 联动——开关清单+默认关+AppConfig getter 实时读）
- [x] 2.2 C0-F3 BookScriptObject 注册 ✅（提交 5f4fd7a1c：新文件 31 行+App.kt initRhino 注册；与 ng P0 子项4 机制分层正交已实证）
- [x] 2.3 C0-F2 章节列表并发去重 ✅（提交 02059eb9f：去重壳+LAZY async+23 字段回填（Book.kt 23/23 核对全存在）+book.copy 隔离；编译门禁过）
- [x] 2.4 C0-F4 exploreKinds 多因素缓存键 ✅（提交 5f4fd7a1c：双层键含 lastHost（DR-C0-4）+isValidExploreKindsRule 三处加固；与 ng P0 脚本缓存命名空间不同文件正交已实证）
- [x] 2.5 C0-F5 WebViewHtmlStore 大 HTML 落盘 ✅（提交 5f4fd7a1c：新文件+BottomWebViewDialog 四点改造，V3-12 偏离设计规避 StrictMode；C0 分册五项 F1-F5 全部落地）
- [ ] 2.6 compose B0 继承收口 + 真机合并窗口 **进展（2026-09-01 真机窗口第 1 轮，包 3.26.090115）**：✅ 2.6.2 logcat 残留=0（verify_no_crash 两轮重启 FATAL/RT/CC 全零 PASS；源码 android.util.Log 零残留；⚠️ 发现并行会话 TopBarDebug 调试日志在 MainTopBarView 未清——移交并行会话）；✅ P0 L2（新脚本 l2_verify_p0_sandbox_cache 首跑：S2 缓存命名空间+S1 沙箱环境就绪双 PASS，开关生效+DB 前缀基线+FATAL=0；T11-T14/T22 手动触发清单已输出）；🟡 2.6.1 R3 继承项：视频手势回归场景 A（灵敏度）/C（P5 全屏）PASS+六项错误全零，**场景 B 亮度手势模拟器像素判定失灵（prefs_ok=True 逻辑层通）→ 真机 MI 9 手动复验**；订阅切换专项 verify_rss_mode_switch 脚本失配（modernRssPage 键读不到，疑似并行 rss-cms 改订阅形态存储）→ **移交并行会话收口后复跑**；🟡 2.6.3 light-theme S1-S9=视觉判定型，手动走查清单已盘点（S1/S2 可后续仿亮度差模板补自动化）；video-sniff 1.11 S6 复跑待订阅切换脚本修复后同跑 | **剩余**：2.6.1 B 场景真机复验/2.6.4 registry 销项（待全部场景闭合）/2.13.2 L3 书源基线
  - [ ] 2.6.1 订阅切换专项/视频手势回归/G1-G11 回归（deep-fix R3 继承项）
  - [ ] 2.6.2 logcat 残留=0 + compose spec 检查点 3 + B10 CacheActivity 真机回归
  - [ ] 2.6.3 同包合并走查：light-theme S1-S9 九场景 + video-sniff 1.11/2.9 待真机项（一次打包覆盖，R8；video-sniff 项未就绪则拆包先行，其项由 W4 走查兜底）
  - [ ] 2.6.4 registry 7.11ai 销项 + deep-fix 收口状态回写
- [ ] 2.7 compose B1 基线校准（子任务账本=compose tasks §3 + 分册①四产物成品段）
  - [x] 2.7.1 pages-inventory §0/§G 成品表粘贴 ✅ 2026-09-01（compose tasks 3.1+3.2：§0 整段替换为分册 §1.1 成品总览表+统计定稿/双口径/权威源 3 注；§G 按分册 §1.2 X-01~X-22 全量执行=18 处条目技术标注+2 处清单归属+权威源增注+v2.13 变更记录；6 项维持免改核验通过）
  - [x] 2.7.2 registry 24 项粘贴 + tasks.md 冻结标注 ✅ 2026-09-01（compose tasks 3.3+3.4：migration-registry §七 aq~bn 24 项整块追加于六.4 之后，编号顺延核验原止于 7.11ap 无冲突；ui-redesign-m3/tasks.md 头部插入分册 §1.4 冻结标注 4 条；compose tasks §3 已加基线冻结标注=校准后编号/范围不再变更、后续以 registry 增量登记）
  - [x] 2.7.3 顶栏集群 4 spec（my-topbar/subpage-topbar/tag-mode/topbar-icon）盘点吸收/注销（tag-mode 实施时点排 3.5 后，热点④）✅ 2026-09-01（结论：四 spec 代码域均基本完成、均余真机验证尾巴，无一达"吸收完毕待归档"销档线，全部保留不注销；my-topbar 验证章节未启动属实施进行中，subpage-topbar/topbar-icon/tag-mode 均剩真机回归；吸收路径=剩余真机项并入 compose B2 检查点真机窗口合并验证；tag-mode 时点关系已按本条原文注记；详录 compose tasks §3.6）
- [ ] 2.8 C5 用户日志+工程纪律（子任务账本=C5 分册；fromTag 表按实际全集登记，X6）
  - [x] 2.8.1 fromTag 全集登记 + 日志纪律门禁固化 ✅ 2026-09-01（登记落点=logging_rules.md，C5 分册 §7 提升点 1 指定：①"模块 Tag 规范"表 7→30 TAG 实际全集重登记（30=SOURCE_NETWORK 14/READING 5/IMAGE 4/PERFORMANCE 3/GENERAL 3/RSS 1；死常量 3=TAG_WEB_VIEW/TAG_SHELF_PROGRESS/TAG_SOURCE_SANDBOX 0 调用点保留不删；字面量 tag 4 值登记收编清单=WebDavBackup(常量同值)/DeviceInfo/RssSourceEdit/CrashReport）；②新增"用户日志模块归属规范（C5 双层架构·预登记）"节=双层三铁律+classify 三原则（单点归类/钉定表防双命中/未匹配归 GENERAL 兜底）+X6 新增 Tag 顺延登记规则（ng P1/P2 同步两表+fromTag 代码+单测断言）；③脱敏铁律补 app.log persist 门禁（R4 预固化）。LogModule.kt 未实施（Glob 确认），零代码改动，updateLog 不追加（无用户可见行为变化））
  - [ ] 2.8.2 C5 分册 P1-P5 代码实施（独立批次不强推：LogModule.kt+AppLog 双层改造+UI 双入口+build-legado.bat 校验段+.githooks 范式+4 组单测/L2；实施时另立实施 spec，fromTag 照 logging_rules.md 预登记表执行并以 LogModuleFromTagTest 全量断言）
- [x] 2.9 docs/project-rules/forks-reference.md NG 条目核对 ✅（实测已含阅读NG条目 :31 + ng-benchmark-analysis 引用 :54-56，无需重复登记，任务销项）
- [x] 2.10 bugfix-20260822 / bugfix-ui-20260824 收尾或显式冻结 ✅（自主模式裁决=显式冻结登记：两 spec 为 20260822/0824 时效性真机问题批，其中多项已随 light-theme/video-sniff/发布批次覆盖修复；遗留项已入各自 tasks，恢复实施前需先核对与当前代码的相关性；v-track-registry 已登记）
- [ ] 2.11 cronet-global-enable + network-perf-stability + thread-pool-audit 与 video-sniff 4.8c 开关双逻辑合并裁决
  - [x] 2.11.1 三 spec 与 video-sniff 4.8c 触点清单对照 ✅（2026-09-01：三域 24 触点对照完成——域A Cronet 开关/降级 11 触点（video-sniff 4.8c/Z7/F-07 为基准；cronet-global-enable REQ-01 默认翻转未实施与代码冲突（AppConfig.isCronet 默认 false）、REQ-03 降级链描述陈旧（代码 CronetInterceptor 已含探测/迟滞/震荡抑制/HTTP2 分级）、np-s P2-2 熔断器已被实际实现覆盖、P2-3 协程拦截器仍待评估、F-P1-6 版本口径演进）、域B 连接池 3 触点（np-s C3 已实施值 50 被 video-sniff R-P0-6 演进至 128=代码现状 HttpHelper.kt:102）、域C 线程池开关 7 触点（thread-pool-audit 审查框架×video-sniff R-P0-3~5 实施事实互补无冲突；钳制终值定稿仍归 3.6）；详见 merger-ruling-network.md §三）
  - [x] 2.11.2 产出合并裁决单（保留哪套开关/文档归一），纯文档不动码 ✅（产出=merger-ruling-network.md；七条裁决 R1-R7：R1 开关双逻辑以 video-sniff 4.8c/design Z7+F-07 为唯一权威——isCronet 默认 false 仅控爬取链路 OkHttp builder 装配（其内自动熔断），视频链路 cronetDataFactory 无条件装配不受控，两逻辑独立不联动；R2 cronet-global-enable REQ-01 冻结待裁决（未实施+与已固化口径冲突，重新评审前禁止实施引用，其 tasks 勾选不动）；R3 降级机制归一为 CronetInterceptor 实测口径（阈值5+启动宽限300ms+half-open 探测5min/连续2次成功+震荡抑制15min+HTTP/2 降级1min+证书错误独立去重）；R4 连接池权威值 128（演进链 默认5→C3 50→R-P0-6 128）；R5 Cronet 版本以 gradle.properties 实时值为权威（当前 500.0.1，文档禁硬编码历史快照）；R6 线程钳制定稿边界归总线 3.6 不越界；R7 P2-3 协程拦截器保留待评估；§五=各 spec 归一注记落点清单 8 条（本裁决不改其 tasks 勾选）；零代码改动）
- [x] 2.12 ai-test-system-refinement scripts 批先行 ✅（2026-09-01 销项：子任务账本=该 spec tasks 五批次全勾且已提交 ee13b2f75；scripts 批=批次 D（删 52+引用复核）+批次 E pytest 295×2 全绿，无遗留实施项；**B2 L2 模板依赖的目录口径已明确（三层）**：①落位 `ai_tests/scripts/` 命名族 `l2_verify_*`/`verify_*`；②入库=.gitignore 默认忽略+白名单固化行（现存 3 白名单脚本与磁盘精确一致，清单已落 README）；③登记=SOP 固定脚本表+README 脚本族索引双落点（SOP 补现状注记+补登 16j 画质增强脚本）。细节登记=该 spec tasks 4.6 | **W2 进入条件之一已满足**）
- [ ] 2.13 P0 文档澄清补注 + P0×C0 合并回归
  - [x] 2.13.1 ng P0 分册 NetworkLog"零修改"补注"将由 ng P1 补敏感头，本期限于零语义变更"（X14）✅（a35ed638d）
  - [ ] 2.13.2 ng P0×C0 合并跑一轮 L3 书源基线回归（X8）**进展（2026-09-01 真机窗口）**：🟡 部分——①单测层 243 全绿（b652fb1af，含 C0-F1 快照化守护+P0 21 用例）②真机 L1 两轮重启崩溃全零（b5a0df088）③真实书详情链路核心断言 PASS（book is null 弹框未出现+阅读/删除入口 OK——C0-F1 规则解析真实链路通）④订阅源搜索 L3 受阻：090115 包含并行 rss-cms 未提交 RssFragment 半成品（订阅页搜索锚点 SearchView 失配，与 verify_rss_mode_switch 失配同源）→ **完整 L3 复跑与 2.6.1 订阅切换专项同一等待条件（并行收口后同包复跑）**；verify_book_info_no_null 更多菜单段脚本自身 TypeError（tap_center 缺参）已登记脚本修复项
- [x] 2.14 subpage-topbar-unify × compose B4 待迁页互斥声明（X2）✅ 2026-09-01
  - [x] 2.14.1 对照 B4 待迁页名单（B5/B14/B15/D2/D3/D5/D7）与顶栏 spec 页名单求交集 —— 交集={B14 ExploreShowActivity}（唯一命中：顶栏批次 C 4.2 `activity_explore_show`；易混淆项已核验排除——顶栏 4.6 `activity_source_debug`=BookSourceDebugActivity ≠ D3 RssSourceDebug，顶栏 2.2 `activity_rss_source`=管理列表页 ≠ D2 编辑页）
  - [x] 2.14.2 交集中的页面登记"禁止先换 View 顶栏再整页 Compose"，写入两 spec 门禁 —— B14 门禁声明已写入两 spec.md §X2 + 两 tasks.md 对应行加注（含实况注记：顶栏 4.2 替换已先期完成，收敛口径=B14 整页迁移时顶栏一次性收敛为 Compose，禁回退双栈）

## 3. W2 地基与样板

- [x] 3.1 compose B2 样板冻结验收 ✅ 2026-09-01 冻结（三轮真机实况：4.1 token+4.2 模板 7 脚本（64211df09）→第 2 轮环境准备+数据就绪（cfee889e8）→第 3 轮锚点校准（0c2b0f9b9）→三修复收口（419decf31 s5 自动隐藏补齐/58c461bff s2-8 BACK+4.3 KeyboardToolPop，包 090204）｜**终态计分**：S1 3/4（s1-2 注入通道限制挂 L3 手动清单）+S2 7/8（s2-4 三视图 N/A 载体页无此功能登记）+S3 6/6+S4 4/4+S5 5/5（两轮）+S6 4/4+cache 3/3=**35 检查点 32 过/2 登记/N-A 1**，全程 FATAL=0｜发现并修复 3 真 bug：阅读菜单自动隐藏 fork 源头缺失+动画期点击竞态+多选态 BACK 直退；系统性发现=Compose 三点按钮 desc 无障碍树暴露待核查（独立项）｜**B2 样板冻结生效**：S1-S4 四页样板+AppPageSpacing token+compose_assert 库为后续 B3/B4 迁移基线，registry 登记随 3.1.2 回执归档）
  - [x] 3.1.1 4.1 spacing token 编译门禁 → 4.2 L2 模板+7 脚本 → 4.3 C2 S3 接线 ✅（4.1 ✅（AppPageSpacing token 落地+frontend-ui-standards §1.3.1+编译门禁过，见 compose tasks §4.1 回执）；4.2 ✅（compose tasks §4.2 回执——`ai_tests/lib/compose_assert.py` 复用层+7 脚本 `l2_verify_compose_*`，py_compile 全过，白名单/SOP/README 三登记，真机执行归 3.1.2 冻结验收窗口）；4.3 未勾（S3 脚本已标注依赖其接线）
  - [x] 3.1.2 S1-S6/D9 35 检查点回执 ✅（32 过/2 登记（s1-2 注入限制 L3 手动+s2-4 N/A）/1 N-A 计入 33 有效项全过口径；逐项证据=compose tasks §4.4-4.9 行内回执+ai_tests/reports/ 档案）
  - [x] 3.1.3 L2 脚本模板登记为三轨共用测试基建（AD-05）✅（compose_assert.py+7 脚本白名单/SOP 16l~16r/README 族索引三登记；供 video-ng/compose/C 轨共用）
- [ ] 3.2 ng P1 AI 地基实施（子任务账本=P1 分册 §4.2 J1-J9 注入点 + §6 DDL（标题 v109 口径以 registry 顺延为准）；实施 spec=docs/specs/ng-p1-ai-foundation/README.md）
  - [x] 3.2.1 另立实施 spec + registry 占号（v109 规划号→**顺延 v110 实施时实占**）✅ 2026-09-01（spec 另立+v109 基线复核+预占留痕，实占留待 T7）
  - [x] 3.2.2 密钥防线四层先落地（P2 前置）✅ 2026-09-01（①NetworkLog 补 x-goog-api-key+单测 ②备份 AES 三处对称+Web 端 keyIsNotIgnore 过滤补齐 ③AppLog 规范约束 ④MCP Sanitizer 留 P2）
  - [x] 3.2.3 分册补注"AiChatService 冻结=不修改既有方法，新增方法不受限"（C4 衔接澄清）✅ 2026-09-01（已写入 P1 分册 §3.4）
- [x] 3.3 C1 朗读架构原语化（子任务账本=C1 分册三步+diff 改造点）✅ 2026-09-01（代码三步全落地，compileAppDebugKotlin BUILD SUCCESSFUL + stop-daemons 清场；L2 十步真机/l2_verify_aloud_primitives.py/L3 回归挂后续批次）
  - [x] 3.3.1 OQ-2 旧键裁决（READ_ALOUD_PROGRESS 保留给 P3 接入或删除）+ 声明禁止 P3 复活旧键 ✅ 2026-09-01（**裁决=删除**：全库 Grep 实证 READ_ALOUD_PROGRESS 零发布者（E2 观察者孤儿化）+ TTS_PROGRESS 零观察者死事件——两常量+help/readaloud/ReadAloudProgressState.kt 整体删除，ReadBookActivity E2 观察者改接 READ_ALOUD_POSITION；理由=C1 原语化目标为发布层唯一通道，保留旧键即双通道风险；分册 §10.2 已写"禁止 P3 复活旧键"强制声明，多角色一律经 publishAloudPosition 接线）
  - [x] 3.3.2 引擎发布层/显示跟随/绘制期投影三步实施（ReadAloudPositionUpdate 五字段不扩）✅ 2026-09-01（**改动 18 文件**：新建 model/ReadAloudPosition.kt（2 data class 五字段不扩）+service/ReadAloudProgress.kt；ReadAloud.kt 位置原语 5 函数+seek 命令层；EventBus/AppLog/IntentAction/PreferKey 常量；BaseReadAloudService 契约注释+prepareReadAloudChapter/resolveParagraphStartPos 拆分+upTtsProgress 发布制（启动代数守卫）+seek 两套+advanceToPrev/NextChapter 派生跟随+switchReadAloudChapterKeepingView+onDestroy clearAloudPosition；TTS EMA+页界预测+speakGeneration（D7/D8 拆除）；HTTP lastCharDurationMs 流式兜底（D6 拆除）；ReadBook skipReadAloudSyncOnce+loadTextChapterForReadAloud+readAloud(pageIndex) 参数+CallBack.onManualPageChanged（D1-D5 拆除+H6）；ReadBookActivity 观察者重写（observeEventSticky+isCurrentPosition 闸门+唯一跟随写点）+原语 A/B+shouldFollowAloudAdvance/isViewBehindAloud 纯函数+restartFromPage/resolveTrueParagraphStart+M6 归一+forcePageFollow 开关（ReadAloudConfigDialog+strings 双语言）；TextLine.isReadAloud var→val 绘制投影+TextPage/TextPageFactory 删存储态 H1-H5+ReadView.invalidateReadAloudHighlight；OQ-5 裁决=位置事件喂面板/OQ-7 裁决=挂 markReadAloudUserNavigation 汇合点；单测 ReadAloudProgressValidateTest 6 用例全绿，分册 §8.1 其余用例依赖 Android 运行时归 L2）
  - [x] 3.3.3 OQ-11 off-by-one 对照表产出（P3 rebase 依赖，X4）✅ 2026-09-01（落 C1 分册新增 §13：13.1 十个发布调用点旧值→新值对照（统一章节绝对字符位，段推进/起点=readAloudNumber 不再 +1）+13.2 引擎页界判定口径六点（保持不变）+13.3 段号↔字符位换算规则六条（getParagraphNum(x+1) 的 +1 补偿必带）+13.4 实施期行为修正三项（M3 起点偏移统一/AD-C1-3 跨章续播/M2 流式兜底）；P3 rebase（总线 5.3.2）按 §13.3 强制执行）
- [x] 3.4 cache-entry-relocate 收口（B2 样板冻结前；cache-entry 先行→B4-c 瘦身 About，反序重复劳动）✅ 2026-09-01（代码实施核实已随 e706bae53 全量入库：6 文件与 design 表逐项一致+strings 5 key 双语言到位+Grep 复核路由/诊断方法零残留；spec tasks 2.1-2.8+3.1-3.3+3.7 勾选，3.4-3.6 模拟器 L2 挂总线 2.6.2 真机窗口；updateLog L193-195 已登记；收口零代码改动未触发编译门禁；B4-c 瘦身 About 前置解除）
- [x] 3.5 fix-rss-search-scope + rss-folder-subtag-fix 收口（B3 Rss 域动工前置）✅ 2026-09-02（MEmu 127.0.0.1:21503 包 3.26.090204 含全部改动；B2 数据在库。**spec1 fix-rss-search-scope**：3.3 五场景全 PASS（分组/类型/@no_group/全部/友好文案+手动切换，合成源 6 个本地 RSS feed+appLog"源数量=N"权威判定，真实源测试期禁用后已恢复 31 个全启用）+3.4 三回归全 PASS（modern 源内搜索=组范围 2 源/我的页入口全局 6 源/书源搜索合成书锚点命中）+3.5 Grep 零残留；tasks 1-4.2 全勾、4.3 用户验收门待过（INDEX 标 🔄 注记）；**spec2 rss-folder-subtag-fix**：3.2/3.3/3.4 三项真机走查全 PASS（文件夹样式目录+子列表 top_bar=131 无标签条=守卫生效/返回键回目录仍隐藏/标签样式胶囊条+向下箭头+点击展开 tagsBar 源标签，top_bar 高度 131 vs 217 对比判定；无需手动清单，环境完全具备）→ **spec2 全收口 INDEX 标 ✅**；观察项（非阻塞）=搜索页菜单切范围双触发重搜已登记 fix-rss-search-scope/issues-found.md IF-01 随 B3；新增 6 验证脚本双登记 SOP 脚本表+README 族索引；零源码改动（纯验证收口），updateLog 不追加）
- [x] 3.6 thread-pool-audit 与 video-sniff 线程钳制定稿（W2 内首项，防回退 Phase0 钳制；非波次进入条件）✅（2026-09-01 纯文档定稿，零代码改动：**①钳制终值确认**（代码实测六点全就位，与 merger-ruling F4/F5/F6 一致）——R-P0-3 AppConfig updateCacheThreadCount coerceIn(1,256)（:2884）+UI max=256（OtherConfigFragment:460），searchThreadCount coerceIn(1,128)（:2877）+UI max=128（:448）；R-P0-4 WebViewPool coerceAtMost(15)（:61）；R-P0-5 CacheBookService minOf(…,128)（:46）+ **ImageCanvasViewModel minOf 双处（:87/:126）已实施**（merger-ruling C4 留核项就此闭合）；R-P0-6 ConnectionPool(128,5,MINUTES)（HttpHelper:102）；**②文档归一**=thread-pool-audit spec.md 三处落盘（头部"线程钳制定稿"注记块=权威值清单+防回退声明+偏差登记；R1.3/Scenario 3 基线 50→128 注记，merger-ruling §五注记清单 thread-pool-audit 两行就此执行完毕）；**③防回退声明**=Phase0 钳制（R-P0-3~6）禁止回退至 64/50/无钳制旧值，重审以权威值为唯一输入基线；**④附带偏差登记**=thread-pool-audit 清单 #5/#6 MainViewModel upTocPool 线程数来源实为 AppConfig.threadCount（默认 16，setter 无 coerceIn）+min(…,MAX_THREAD=9) 兜底（MainViewModel.kt:56-58），非 updateCacheThreadCount，归 thread-pool-split-config 校正；updateLog 不追加（无用户可见行为变化），编译门禁不触发（零代码））
- [x] 3.7 ng P2 实施 spec 前置登记（X3/X14）✅ 2026-09-01（三登记落 P2-mcp-service.md 头部总线节：①3.7.1 MCP 工具按 C0-F4 新缓存语义登记禁按 NG 快照断言；②3.7.2 源 JS 安全盲区自主裁决=登记暂缓至 ng P4 二期清单（P2 期以 write 门+McpAuth 运行）；③3.7.3 replace 族可删 C4 净化规则在工具描述显式披露）

## 4. W3 旗舰攻坚

- [ ] 4.1 compose B3-D4 Rss 列表旗舰（子任务账本=分册② 12 场景+五代 Adapter 收敛设计）
  - [x] 4.1.1 RssFragment 四波排序列队登记 ✅ 2026-09-01（S批✅→scope fix ✅ 3.5 收口 722e95583→tag-mode ✅（tag-mode-unify 代码域完成）→A8（D4 本体，前置全解除）；Rss 域工作区已清空（并行会话收口 4c9b331af）；**D4 分批计划**：批1 组件层新包 ui/rss/article/compose（§2 state holder+三形态+Cover+边界态，纯新增）→批2 宿主接线（§3.4 双模式分派+§5 RssSortActivity）→批3 五代 Adapter 删除（§6）+12 场景 L2（§8））
  - [ ] 4.1.2 D4 实施 + 12 场景 L2 全过（**批 1 组件层 ✅ 2026-09-02**：新包 ui/rss/article/compose 7 文件纯新增（勘误 2026-09-07 红队 R3-E：原记"6 文件"漏计 Style.kt，git f4f1e3078 实证 7 文件；⚠️ master 276120ab9 已 revert 全部 8 文件（打包对齐），8 文件现仅存 feat/master-track-waves 分支）（§2 枚举/state holder/Screen/三形态 ArticleItem/Cover+宽高比缓存/footer 空态骨架），compileAppDebugKotlin BUILD SUCCESSFUL+daemon 清场；⚠️ glide-compose beta08 两处失实实证（GlideImage 无 onResourceReady+传递 glide 5.0.5 与项目 4.16.0 版本对抗）→ CustomTarget 桥替代零依赖变更，L1 复用 EmptyStatePlaceholder/Shelf 骨架；扫描 Color(/.sp/RoundedCornerShape(=0、.dp=6 全登记；回执全文=compose tasks §5.1.a；批 2 宿主接线+批 3 Adapter 删除+12 场景 L2 未动；**批 2 宿主接线 ✅ 2026-09-02**：§3.4 双模式分派+§5 RssSortActivity 全 Compose 落地（改动 11 文件：新 RssArticleListBridge/RssSortScreen+壳瘦身 RssArticlesFragment+RssSortActivity ViewPager 家族退役+VM StateFlow（§3.1 uiState/articlesFlow.debounce+§5.2 articleStyleFlow）+fragment 布局缩壳 ComposeView；RssFragment 零改动除 gotoTop 走 scrollToTop 新 API；compileAppDebugKotlin BUILD SUCCESSFUL+stop-daemons 清场；勘误=RssFavoritesFragment 复用 fragment_rss_articles 布局为分册漏项→独立 fragment_rss_favorites.xml 零行为变化；灰度=五代 Adapter 保留未删但分派已切新组件、无 feature flag；扫描 Color(/.sp/RoundedCornerShape(=0、.dp=4 全登记；Level 1 代码完成，真机/L2 归批 3；回执=compose tasks §5.1.b（物理行序在 5.1.a 之前，内容互不影响）；批 3 五代 Adapter 删除+12 场景 L2 未动；**批 3 开工前置（AD-25，2026-09-07 红队 P0 修复落任务）**：①批次盘点 diff（pages-inventory 登记页集 vs 批次任务集，差集非空阻断开工）+追溯项（红队 R3-A）：批1/批2 已迁 3 页补录操作保留/降级映射表、批2"ViewPager 家族退役"补充删除理由并补检查点裁决；②D4 页操作保留/降级映射表（降级=删除同等待遇，须检查点裁决）③§8 并入 S13/S14/S15 入口场景 + outbound 每跳到达断言（断言方式：ai_tests 现框架 Compose 语义未暴露 testTag，先以坐标/像素/日志锚点半自动+真机人工走查兜底，testTagAsResourceId 接入列为前置）④活代码缺陷修复（2026-09-07 红队 R5 实锤，feat/master-track-waves）：MASONRY/GRID 形态 ScrollRestoreEffect/scrollToTopRequest 写死 lazyListState 全部失效→按形态分发对应 LazyState；VM 一次性事件 loadErrorLiveData/loadFinallyLiveData LiveData 承载 View 重建 Toast 重放→Channel(BUFFERED).receiveAsFlow()；Bridge 两处 P2（SideEffect 每次重组回调 onHostStateReady 非幂等重复订阅风险→DisposableEffect；snapshotFlow 块内写状态+articles 未回填时 pending 定位竞态）；修复遵循 design AD-28 条款）
  - [ ] 4.1.3 A8 回归清单含 video-sniff Phase0 padding 改动项
- [ ] 4.2 compose B3 其余 9 页（A7→A8→B2→B8→B11→C3→C13→D1→E2，子任务账本=分册③；A7=ExploreFragment classic 收敛（compose tasks §5.2），产出为 B-C3 前置基线，X9；**E2 裁决**：保持 W3 实施不破轨 A 禁跳批，其 ThemeSpecPresets 产物纳入 6.1.3 P5 截图回归面兜底，X1；**AD-25 前置（2026-09-07 红队修复，全批次生效）**：每页开工前 inbound/outbound 盘点 diff+操作保留/降级映射表+L2 场景补入口/出口断言；84 页存量 inbound/outbound 字段补齐随本批次盘点动作落 pages-inventory）
- [ ] 4.3 ng P2 MCP 服务端实施（子任务账本=P2 分册 70 工具规格表（V6 增补后）+四模块拆分）
- [ ] 4.4 C2 多媒体插入 Phase A（子任务账本=C2 分册 Phase A）
  - [ ] 4.4.1 OQ-1 焦点矩阵裁决（C2 开工前分期级闸门）且矩阵补 P3 多角色参与者行（X7）
  - [ ] 4.4.2 与 C1 侧共同声明"插图占位行跳过朗读单元构建"（X5，单侧落地即可）
  - [ ] 4.4.3 DB 占号 + 锚点数据层实施

## 5. W4 长尾与听书

- [ ] 5.1 compose B4-a 登记 6 项（B7/B16/C17/E5/D8/B13，子任务账本=分册④；**AD-25 前置（2026-09-07 红队修复）**：每页 inbound/outbound 盘点 diff+操作保留/降级映射表（降级=删除同等待遇，检查点裁决）+L2 场景补入口/出口断言，B4 全 6 项逐页执行；**AD-26/27/29 前置（2026-09-07 R4-R6）**：ia-map.md 建档+84 页 iaLevel 补齐+入口选位四级判据；交互模式矩阵全站生效+fontScale=1.3 冒烟；红线清单纳入回执核验；migration-registry 增"回退开关"列（旗舰页保留 View 分支至下一里程碑验收））
- [ ] 5.2 compose B4-b 收口 5 项（B9 裁决→D3→D5→D7→D2 压轴；D3/D5/D7 须 D4 回执后）
- [ ] 5.3 ng P3 多角色听书一期实施（子任务账本=P3 分册 diff 式改造（§4.4，实施前校准段数）+6 新表 DDL）
  - [ ] 5.3.1 DD3 Segmenter 评审通过（P3 开工前分期级闸门）+ ng P0 合入确认 + HttpReadAloudService 释放确认（C1 已收口）
  - [ ] 5.3.2 rebase 后补发布制接线条款：多角色逐段推进经发布层，禁止直写 durChapterPos/moveToNextPage（X4）+ OQ-11 覆盖新调用点
  - [ ] 5.3.3 补 AudioFocusRequest 声明（X7）+ v111 占号实施（v110 归 P1）
  - [ ] 5.3.4 D6 UI 三件（RoleBindDialog/TtsEngineManageActivity/入口行）+ ReadAloudDialog 入口挂载点若晚于 S6 冻结须补记回执
- [ ] 5.4 C2 多媒体 Phase B-C 收尾（排版回归独占真机窗口 R8；C1 步骤③→C2 Phase B 串行；AudioBlockPlayer×C1 高亮重绘共享 CanvasRecorder 一次联测）
- [ ] 5.5 V 轨衔接建议单（事件驱动，R9：总线只出建议单不接管）
  - [ ] 5.5.1 video-sniff Phase3 收口事件确认（向并行会话拉取进度快照）
  - [ ] 5.5.2 出衔接建议单：enhance-switch-governance-fix → video-back-fullscreen-fix / rss-video-player-enhancement → video-extractor-enhancement → multiline-on-demand-extraction；经并行会话确认后登记 v-track-registry（确认前不排程）
  - [ ] 5.5.3 下载域：download-hls-complete-fix / download-manager-maturity 排 video-sniff 4.6 headersJson 落地之后的建议
- [ ] 5.6 播放器纪律登记（X10）
  - [ ] 5.6.1 ExoPlayer 实例/release 纪律归口文档（C2 AudioBlockPlayer+PhotoDialog+V 轨预加载器并存）
  - [ ] 5.6.2 C2 sniffMediaExt 与 V 轨 MimeSniffer 命名文档区分 + C2 OQ-3 二期必须走 SniffEngine 登记

## 6. W5 收官与视觉

- [ ] 6.1 ng P5 视觉三模式实施（子任务账本=P4 分册四 data class+三分支+18 处直读清单）
  - [ ] 6.1.1 前置确认：deep-fix Phase2 已收尾（已满足）、light-theme 已交付版本为基线（禁止回退 guard/Archive 派生，实施时逐行 diff MaterialValueHelper，X1）
  - [ ] 6.1.2 18 处直读中 #15/#18 落 B 批文件域的协调实施
  - [ ] 6.1.3 D4+E2 已实施页面纳入截图对比回归面（X1 E2 裁决兜底）
- [ ] 6.2 B-C3 合集书架 + RowUi（子任务账本=C3 分册 B1-B14）
  - [ ] 6.2.1 前置：B2 样板冻结回执 + B3-A7 ExploreFragment 回执（X9）
  - [ ] 6.2.2 数据层实施（4 表占号+DAO+Help，可与 A 轨并行）→ UI 层实施（排 A3/A4 冻结回执后，实施后补一次回执复验，X12）
  - [ ] 6.2.3 BookGroup 并存裁决 AD-C3-2 + 新弹框 MaterialRole.OVERLAY 声明（若 P4 分册 §P5-1 规范条款已合入）
- [ ] 6.3 compose B4-c + B12（B15→B14→B5 列表三连 + C20 About 基于 cache-entry 瘦身内容；B12 断言文件域严格限定 manga 内核路径，X11）
- [ ] 6.4 C4 一期 AI 净化（子任务账本=C4 分册一期；B 路线零等待；§12 开放问题 1/2 裁决；P1 验收后 0.5d 切 AiManager 门面 OQ-9）
- [ ] 6.5 compose B5 收官（子任务账本=compose tasks §7：A6 销号+巡检+五维评分+KPI 终值落 registry+pages-inventory；kpi-final.md 为总线新增产物名，与轨 A 口径对齐后产出）
- [ ] 6.6 KPI 终值复盘 + NG/legadoC 代差分析 + deep-fix Phase4 门禁固化（吸收 P5 MaterialRole 条款：取色+材质双检查；=W5 收尾动作 Z）

## 7. 总线收束

- [ ] 7.1 db-version-registry 终态核对（全部占号销号）+ v-track-registry 终态核对（挂靠项全部闭环）
- [ ] 7.2 文档同步：docs/INDEX.md 三轨状态更新 + 各 spec README 状态对齐（R6）
- [ ] 7.3 检查点 2：用户最终验收（验收材料=W5 全量 E2E 报告+kpi-final.md+registry 终态+热点审计结论，§8 全过为前置）
- [ ] 7.4 经验沉淀（跨轨编排经验 → ai_memory_main.md）
- [ ] 7.5 L4 交付级发布（用户触发：publish.bat 五阶段，--dry-run 预览先行；design §6.1-L4）

## 8. 波次收束验证框架（每波次收束必跑，design §6，R10）

- [ ] 8.1 L2 波次级验证
  - [ ] 8.1.1 整包编译（build-legado.bat 测试包，0 error）
  - [ ] 8.1.2 `./gradlew test` 全量单测通过（0 failed）
  - [ ] 8.1.3 E2E 冒烟（ai_tests\venv\Scripts\python.exe ai_tests/run_e2e.py 核心用例集全过）
  - [ ] 8.1.4 热点文件 git diff 审计：git status/diff 对照 14 对热点表逐文件核对，产出审计清单（变更文件→所属分期→预期内/外）
  - [ ] 8.1.5 波次验收单核销：验收单=本清单 §8.1 逐项勾选记录；进入条件逐项核销（完成级别按 G1-G3 判定，design §6.2）+ 验证结果记录 + registry/v-track 同步（每波次收束向并行会话拉取一次进度快照）+ 本清单状态回写
- [ ] 8.2 L3 里程碑验证
  - [ ] 8.2.1 W2 基线包：真机安装+样板/AI/朗读三合入域走查+logcat 0 FATAL
  - [ ] 8.2.2 W4 跨域走查：视频/朗读/Rss 三大热点域同包集中走查清单（含 W3 末 D4 首次真机）
  - [ ] 8.2.3 W5 全量：run_e2e.py --tc all 全过 + kpi-final.md 产出
- [ ] 8.3 分期提交门禁（每期收束，R11）
  - [ ] 8.3.1 updateLog 基于 git diff 真实变更更新（编译前，version-delivery-sync 三步流程）
  - [ ] 8.3.2 文档同步（INDEX/README/registry 回执/热点表状态/issues-found）
  - [ ] 8.3.3 daemon 清场（stop-daemons.bat）
  - [ ] 8.3.4 Grep `android.util.Log.d|e` 残留=0 + 临时日志 Tag（如 SwipeTest 类）清理确认
  - [ ] 8.3.5 Conventional Commits 一期一提交（大型分期 B3/B4/C2 按页/Phase 子提交）
- [ ] 8.4 回退预案就位（每期开工前）
  - [ ] 8.4.1 bak 目录备份
  - [ ] 8.4.2 灰度/观察开关登记（P0 四开关状态表 + C2/C3 新功能门控）
  - [ ] 8.4.3 热点文件实施前后 diff 存档
  - [ ] 8.4.4 回退路径确认：一期一提交单元完整性检查（保证可按分期 revert；回退后重算依赖分期占号并同步 registry）

## 9. 可控性门禁（2026-09-01 用户裁决"保守暂停 AI 轨"新增，最高优先级约束）

> **背景**：用户质疑"实施完成后完全不可控"，可控性审计确认缺口（开关无 UI 入口/大改未真机验证/高侵入功能缺默认关门禁）。裁决：**ng 轨 AI 功能全部挂起，逐项经用户解锁后才实施**。

- [x] 9.1 挂起清单 ✅（已挂起，逐项解锁制）：**3.2 剩余**（T7 DB v110 实占+J1-J9 全量注入——已落地的密钥防线三层保留，纯防御无侵入）、**4.3 ng P2 MCP 服务端**（外部 AI 暴露书架/设置+常驻前台服务+HTTP 端口）、**5.3 ng P3 多角色听书**（DB+6 表）、**6.4 C4 一期 AI 净化**（触碰书箱内容）｜解锁方式=用户逐项明示（如"解锁 P2"）；未解锁前禁止实施含其前置 DB 占号
- [x] 9.4 用户主线校准（2026-09-01）✅：**"除阅读器之外全面 Compose 化"= 总线唯一主线**——compose 轨全部任务升级为最高优先级；联动挂起（阅读器域，与 AI 轨同规则逐项解锁）：**4.4/5.4 C2 多媒体插入**（阅读页内功能）、**6.1 ng P5 视觉三模式**（阅读渲染）；S5 阅读器正文 View 内核红线不变（frontend-ui-standards §2）
- [ ] 9.3 可控性四件套门禁（对解锁后实施生效）：每个高侵入功能实施时强制交付 ①默认关开关 ②设置 UI 入口（人可达，非 adb prefs）③回退路径 ④updateLog 人话说明——实施 spec 审查时核对，缺一不开工

## AOAdapt 日志

- [2026-08-31] 初版后用户质询"就这么随意么？不需要再全面审核一下么？"
  - Action: 3 审核子代理并行（规范性/事实核对/遗漏排查）
  - Observation: 3 P1+6 P2 + 事实 10/10 一致 + 14 个视频域活跃 spec 遗漏 + deep-fix 实况过时
  - Adapt: 全修 + AD-07 V 轨挂靠 + R8/R9 + deep-fix 1.1 改写 + 新增 0.4/2.10-2.12/3.4-3.6/5.5-5.6

- [2026-08-31] 二轮用户质询"共性问题怎么整合？方案有没有打架？"
  - Action: 8 组点对点推演
  - Observation: 真打架 3 + 正交 4 + 编号碰撞
  - Adapt: design §5 推演矩阵 + 1.7 双栈豁免 + registry 自适应规则 + 4.8e 方案 B 提示

- [2026-08-31] 三轮用户质疑"这么凑巧？没有重合冲突点么？"
  - Action: 承认非全覆盖，3 域分组子代理矩阵式扫描（轨C×轨B 15 对/阅读朗读 DB 15 对/UI 域约 20 对）
  - Observation: X1-X14 新发现（真冲突 4/语义交叉 4/热点新增 5 对/正交实证 2），0 对不兼容级真冲突
  - Adapt: 热点表 9→14 对 + design §5.1 + tasks 新增 1.8/2.13/2.14/3.7 及多任务修订

- [2026-08-31] 四轮用户质询"如何测试验证如何提交确保万无一失？设计文档有说明么？"
  - Action: 诚实盘点缺验证交付专章 → 补 design §6 + spec R10/R11
  - Observation: 四层验证体系 + 回退预案 + 提交策略成型
  - Adapt: tasks §8 验证框架 + 7.3 验收材料前置

- [2026-08-31] 五轮用户批评"设计内容要回填文档，至少要有子任务"
  - Action: tasks.md 全量子任务化（60+ 子任务）+ 分期实施标注子任务账本引用防双账本
  - Observation: 子任务粒度达成
  - Adapt: 第五次提请检查点 1

- [2026-08-31] 六轮用户要求"最后一次全面审查，全方位无死角"
  - Action: 3 终审子代理并行（内部一致性/外部引用/逻辑闭环）+ 主代理源码实测 version=109
  - Observation: 内部 6 P0（"9 对"残留/v111/README Phase1/22≠21/AD 索引断链/整合点漏项）+ 外部 4 偏差（22 单测/70 工具/kpi-final/forks 防重复）+ 逻辑 1 P0（热点⑧被波次违反）+ 8 P1（进入条件自引用/ng P4 漏网/↔无方向/5.5 接管等）
  - Adapt: 四文档全量修复（热点单向化/进入条件可判定化/ng P4 暂缓显式/E2 裁决/5.5 事件驱动/S5 场景/7.5 L4/v111 顺延/G1-G3 改名）

- [2026-08-31] 七轮用户要求"再次全面审核"
  - Action: 2 终审子代理（重写后回归检测+可执行性演练）
  - Observation: 回归检测：六轮 10 项修复 9 项完全落盘，热点⑧链序文字反写残留 + design §6 结构错位 + §4 算术偏差（32→31）+ P2 处置确认（P2-19/32 已修）；演练：F1 P0（"14 spec 清单"无法照单誊录，§4 表计数口径脱节→实为 18 实质协调面+33 全表）+ F2-F15（账本指位偏差 §3.6→§4.1/占号状态枚举缺/Q1 首日裁决/豁免条款落点/1.5-1.6 锚点/3.6 首项/TEMPLATE.md 模板锚点/分册对照/旧口径头注/验收单载体等）
  - Adapt: design §4 导语重写（18 实质协调面+33 全表+计数以表为准）、热点⑧链序正写、§6 移位 File Changes 前、AD-07 18 个、裸 ng 前缀统一、W1/W2 行补 2.13/2.14/3.7、W2 推荐序 3.6 首位、W5 进入条件可判定化；tasks 补 0.4.1 誊录口径/1.2.1 枚举/1.2.4 自批/1.3 Q1 裁决/1.4 §3.6+§4.1/1.5-1.6 锚点/1.7 拆分/1.9 分册头注/头部 TEMPLATE+分册对照/2.12 W1 收束前/8.1.5 验收单载体；第七次提请检查点 1
