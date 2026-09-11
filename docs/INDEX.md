# 文档索引

> 项目所有文档的统一入口，覆盖项目规范、项目流程、功能设计、核心 Skill 五类文档。最后更新：2026-08-30（文档规整重建，历史 spec 已归档至 docs/specs/archive/）。

---

## 🔴 进行中的工作 → 设计中

- [主题字号日夜生效与预置主题体系](./specs/theme-fontscale-daynight/README.md) - 夜间字号 fontScaleN 死键修复（AppContextWrapper 日夜感知+顶栏5消费点同源）+内置主题字号统一9（2A）+历史17主题资产移除+暗夜紫配置代码内置（日夜变体）+磨砂玻璃晨昏套件压缩入库幂等seeding（AD-01~04，红队2轮闭环） 🔄 设计中
- [TTS朗读引擎统一优化](./specs/optimize-tts-engine/README.md) - 朗读引擎切换不生效修复（UI写SpeechRoute/服务层读SelectItem分裂）+引擎路由单源化+脚本引擎协议（Rhino沙箱）+MultiTTS/CloneTTS深度适配+在线TTS内置模板库（默认停用） ✅ 期1 实施+期2 扩展实施（模板列表器/管理页编辑器/音色级声源/书级覆盖/试听/AI链/批量预合成，L1+模拟器L2通过，听感留真机，见 specs/optimize-tts-engine-phase2/）
- [TTS 批次E 加固](./specs/tts-batch-e-hardening/README.md) - 全量审查批次E 六项收尾：AI 预热接线（起播点 fire-and-forget）/保留名单落盘（进程重启防驱逐）/选角模板备份恢复/内置模板首装链+升级刷新/键因子三件套收编（KEY_VERSION v3 冻结）/Rhino 沙箱 TTS 脚本档收紧（app 前缀实拦，书源档零变化） ✅ 已实施（2026-09-10，包 3.26.091014，单测 42 绿）
- [批次F 八项真机问题全量修复](./specs/real-device-bugfix-0911/README.md) - 用户真机反馈八项：视频下载后缀（命名补全+probe MIME 纠正+PTS 钳制防 MPEG4Writer abort）/管理列表底部全选条收口顶栏（范围收窄两页）/高亮对话 120→400+演进覆盖+isRegex 提示/HostAccessStrategy 网络稳定性阶段1（host 健康表+坏 IP 黑名单+fail-open）/日志治理（AppLog 频控双机制+级别语义+噪音源处置）/缓存清理整合（WebView 迁缓存管理第 4 分项）/规则扩充（高亮 24+净化 12+TXT 30，insertIfAbsent 只追加）/TTS 多角色可用性（系统引擎音色枚举 getVoices+对话切分诊断+入口正名+AD-09 克隆）+F10 TTS 联调脚本 🔄 实施中（批次A+B 代码落地，单测回归中，2026-09-11）

- [UI 设置体验修复包](./specs/archive/2026-09-06-ui-settings-fix-pack/README.md) - 三项 UI 体验修复：主界面底栏搜索框显隐入口补齐（设置页快捷开关+两层配置防回滚）+ fontScale 放大组件文字截断逐点修复（12 处 height→heightIn(min)）+ 恢复被误删调用链的优化版取色器 ColorPickerSheet（扩展跟随默认）✅ 已完成并归档（2026-09-06 验收通过，commit 13574ae41）
- [主界面头部透明对齐Archive](./specs/main-topbar-transparent-align/README.md) - 四Tab头部透明失效根因修复：恢复 MaterialValueHelper backgroundColor 透明原语（背景图→TRANSPARENT 分支被删）+BaseActivity decorView 着色策略对齐（P0/P1 diff 实证，overlay/blur 排除）🔄 设计中
- [书源视频对标订阅源](./specs/video-booksource-align-rss/README.md) - 书源视频单页化+列表驱动上滑+公共采集链组件（根治直链地址不正确/播放信息不匹配/上滑卡死三类反复问题）✅ 已实施（2026-09-03，S1-S4 真机验证通过）
- [视频播放器双布局模式](./specs/video-player-dual-layout/README.md) - 内置抖音沉浸式(默认)+传统(上播放器下信息区)双布局可配置，设置中心化到「我的→视频播放器设置」，播放页仅保留即时生效项，嗅探/采集链零改动（五轮红队闭环，/goal 实施中）🔄 开发中
- [UI 主题纳管与弹框交互优化](./specs/ui-theme-governance-polish/README.md) - 7 项 UI 问题修复：订阅布局弹框开关主题化+登录弹框按钮收纳+主题编辑器保存感知+字号滑条偏左+沉浸顶栏开关修复+管理页透明度设置+本地密码弹框托管（八轮红队+N1-N3 已闭环）🔄 开发中（真机反馈转入 followup）
- [管理页样式统一与交互回归修复](./specs/ui-theme-governance-followup/README.md) - 真机反馈 5 类问题：发现页视频上滑误报（队列注入被 revert 移除）+书架手势三修+发现页标签闪烁+透明度 v2 预混模型全域生效+管理族子页面顶栏列表统一（24 页分型矩阵）🔄 设计中
- [子页面顶栏取色统一](./specs/subpage-topbar-unify/README.md) - 顶栏取色统一+沉浸透明语义（全局壁纸下顶栏透明透壁纸）：三级决策链单源 resolvePageBarColorWithAlpha，ConfigTopBar 消灭（4→3），二期组件归一 Delta 已追加（共享内核+管理族委托 3→2+MainTopBarView 消亡路线）✅ 已验收通过
- [我的全域Compose化](./specs/my-compose-full/README.md) - "我的"入口 45 页 Compose 化盘点（C-full 36/C-mixed 8/C-none 2）+ 六波迁移（W0 闸门→W1 基线→W2 地基→W3 并存页→W4 纯 View 重写→W5/W6 穷举清扫→W7 顶栏 3→1 归一收官） ✅ 实施完成（L2/L3 真机回归待补验）
- [真机反馈五项修复 0908](./specs/real-device-bugfix-0908/spec.md) - 书源管理万级域名分组卡死（host 表 IO 线程化+dataVersion 重组信号+扁平 RowModel）+替换净化双搜索框（removeView 静默 no-op 改 GONE）+弹框透明（SourcePicker 背景补齐+ComposeDialogFragment dim 补偿）+书架媒体入口删除+订阅栏目文件夹残留（applyModernRssMode 补隐藏） ✅ 已实施（L2 五场景自测通过，2026-09-08）
- [真机反馈三项修复 0908-followup](./specs/real-device-bugfix-0908-followup/spec.md) - 经典发现头部纯黑（TitleBar managed 接入 resolvePageBarColorWithAlpha 唯一取色+半透明清阴影）+顶栏图标颜色/尺寸混乱（GlassTopAppBar 自绘分支补 LocalContentColor+MenuActionIcon 恢复 R5 继承决策+一级图标 20dp 统一）+摘录分享模板启动崩溃（addView 索引 coerceAtMost） ✅ 已实施（L2 t6/t7/t8 自测通过，2026-09-08）
- [Compose化进度盘点 0908](./specs/compose-progress-audit-0908/README.md) - 汇报类：我的域 Compose 化收官确认+全项目 14 个真 View 页清单+死布局 5 个/孤儿字符串 554 个核查+规范文档 4 处滞后修订建议（architecture.md 3 基线口径滞后/frontend-ui-standards 缺弹框与归一条款）🔄 下次迭代输入
- [本地打包提速](./specs/local-build-speedup/README.md) - daemon 复用+debug 降堆+configuration cache+版本号 ValueSource 化，增量打包 7m33s→≤4min，内存峰值 93.5%→≤91%（基线实测支撑，红队 2 轮闭环）🔄 实施完成，R1 计时补测待并行会话合并
- [批量 UI 修复 0905](./specs/ui-batch-fix-0905/README.md) - 4 项用户反馈：崩溃弹框误弹回归+视频书源沉浸式左下角线路/集数+发现页分组弹窗 Bug 与全前端死菜单清理+经典订阅头部收口（搜索留外/六项收三点/删分组信息列举）✅ 开发完成（T1-T8 L2 真机验证，待用户验收）
- [真机回归修复 0906](./specs/video-regression-fix-0906/README.md) - 4 项真机反馈：嗅探播放下滑（ExoPlayer 4003 解码竞态重建重试+DoH 死节点熔断+token 竞态观测）+书源切布局死窗（短路重采集）+书源上滑失效（队列兜底注入+集内降级，AD-01 边界增补）+分类列表页三点死按钮接线（45 文件日志脱敏分析+3 路源码探索）✅ 开发完成（S1-S3+T1-T8 L2 真机回归，待用户验收）
- [日志系统升级改造](./specs/log-system-upgrade/README.md) - 测试包默认详细日志（recordLog 按包类型默认值+DEBUG 级完整落盘+内存 100→500）+ 全屏日志管理中心（应用/崩溃/文件/堆转储 4 Tab：搜索、多选删除、详情、分享、一键清除、导出；右上角三点收口+精准管理三件套菜单收口至统一入口）✅ 开发完成（L1 全勾+编译通过+S3 L2 抽样四 Tab 可达，待用户打包验收）

---

## 一、项目规范（docs/project-rules/）

> AI Agent 编码时必须遵循的项目特有规则，按需加载。

| 规范 | 文件 | 核心内容 |
|------|------|----------|
| 命名规范 | [naming_rules.md](./project-rules/naming_rules.md) | 类后缀约定+up/dur/Await 缩写+常量混合风格+扩展函数组织+包结构 |
| 代码风格 | [checkstyle_rules.md](./project-rules/checkstyle_rules.md) | Coroutine 链式封装+双版本模式+kotlin.runCatching+object 单例+@IntDef+顶层 lazy |
| 异常处理 | [exception_rules.md](./project-rules/exception_rules.md) | NoStackTraceException 体系+四种捕获模式+网络错误+协程异常 |
| 日志规范 | [logging_rules.md](./project-rules/logging_rules.md) | AppLog+LogUtils+DebugLog 三层体系+标签约定+使用规则 |
| 架构模式 | [architecture_rules.md](./project-rules/architecture_rules.md) | 手动 DI+ViewModel 模式+Room 配置+Web 服务+事件系统+模块依赖+构建配置 |
| 测试规范 | [testing_rules.md](./project-rules/testing_rules.md) | JUnit4+书源自测三阶段+测试运行命令 |
| 工作流程 | [openspec-workflow.md](./project-rules/openspec-workflow.md) | OpenSpec 四文档+强制检查点+文档同步映射表+子代理使用指引+上下文预算检查 |
| 延伸版本对比方法论 | [forks_comparison_methodology.md](./project-rules/forks_comparison_methodology.md) | 对比方法论：五阶段流程+决策矩阵+踩坑（延伸版本数据以 forks-reference.md 为唯一权威源） |
| E2E 测试流程 | [ai_e2e_testing_workflow.md](./project-rules/ai_e2e_testing_workflow.md) | 5.5.1-5.5.8 八步强制流程+固化层保护+V3.1 快速验证脚本 |
| 测试用例设计 | [test-case-design-guide.md](./project-rules/test-case-design-guide.md) | 双轨制+源码溯源字段+步骤语义化 |
| 改造过程日志 | [logging-during-refactoring.md](./project-rules/logging-during-refactoring.md) | 10 类必加日志场景+永久/临时双轨+Tag 规范+验证检查清单 |
| 版本交付同步 | [version-delivery-sync.md](./project-rules/version-delivery-sync.md) | 同步清单+updateLog.md 格式+编译前更新时机 |
| 复杂任务流水线 | [complex-task-pipeline.md](./project-rules/complex-task-pipeline.md) | 五阶段流水线+硬性约束（单子代理≤12 文件）+反模式 |
| 子代理质量管理 | [sub-agent-quality-management.md](./project-rules/sub-agent-quality-management.md) | 分级子代理策略（低风险强制/高风险禁止）+prompt 四要素+主代理监督+二次验证+兜底机制 |
| 全局思考检查清单 | [global-thinking-checklist.md](./project-rules/global-thinking-checklist.md) | 改动功能前强制门禁：前端入口+后端接口+数据库+覆盖安装+使用场景+回填点 6 维盘点 |
| 错误沉淀机制 | [spec-sedimentation-mechanism.md](./project-rules/spec-sedimentation-mechanism.md) | 错误→沉淀→子规范→主规范引用闭环+5 条沉淀规则+3 次验证流程 |
| 数据库升级安全规范 | [database-migration-safety.md](./project-rules/database-migration-safety.md) | DatabaseView 修改 DROP+CREATE+migration runCatching+version 递增+覆盖安装兼容性 |
| 真机测试流程复用 | [real-device-test-reuse.md](./project-rules/real-device-test-reuse.md) | 可用脚本清单+测试流程模板+问题闭环+数据库验证（WAL 模式校验必须触发真实路径） |
| 包名规范 | [package-naming.md](./project-rules/package-naming.md) | 构建 APK 包名配置+与原版共存 |
| 延伸版本参考 | [forks-reference.md](./project-rules/forks-reference.md) | 延伸版本清单+活跃度快照+优先级矩阵（唯一权威数据源） |
| 前端 UI 规范 | [frontend-ui-standards.md](./project-rules/frontend-ui-standards.md) | archive 迁移后前端统一基线：设计 Token（AppShapes/UiCorner）+页面骨架分型+组件六族选用+View/Compose 混用红线+改造检查清单+已知坑 |
| Git 提交工作流 | [git-commit-workflow.md](./project-rules/git-commit-workflow.md) | Git 提交工作流 |
| APK 发布工作流 | [apk-publish-workflow.md](./project-rules/apk-publish-workflow.md) | 一键发布编排器（publish.bat 五阶段：构建→校验→gh release→tag） |
| 工作方法论 | [work-methodology.md](./project-rules/work-methodology.md) | 大型任务（10+ 文件/多 Issue）工作方法论 |

---

## 二、项目流程（docs/project-flow/）

> 项目架构和模块的详细技术文档，按需加载。

### 快速入口

| 文档 | 核心内容 |
|------|----------|
| [project-flow/quick-reference.md](./project-flow/quick-reference.md) | 命令/文件/版本锁定速查 |
| [project-flow/task-navigation.md](./project-flow/task-navigation.md) | 14 个任务导航表（按任务类型索引代码锚点） |
| [project-flow/build-apk-guide.md](./project-flow/build-apk-guide.md) | APK 打包全流程：环境搭建+签名+构建+包名修改 |
| [project-flow/INDEX.md](./project-flow/INDEX.md) | 关键词索引（A-Z） |
| [project-flow/git-repo-management.md](./project-flow/git-repo-management.md) | Git 仓库管理规范（master 分支+Conventional Commits） |
| [project-flow/README.md](./project-flow/README.md) | 项目文档导航 |

### 架构文档（project-flow/architecture/）

| 文档 | 核心内容 |
|------|----------|
| [architecture/rule-engine.md](./project-flow/architecture/rule-engine.md) | SourceRule 状态机+五种解析+JS 环境+WebJs 模式+变量系统+ruleType 常量 |
| [architecture/rule-engine-algorithms.md](./project-flow/architecture/rule-engine-algorithms.md) | SourceRule 完整规范+RuleAnalyzer 完整算法+五种解析器算法细节+Mode 枚举 |
| [architecture/rule-engine-js-env.md](./project-flow/architecture/rule-engine-js-env.md) | AnalyzeRule/AnalyzeUrl 环境绑定+ajax 跨域请求+Rhino 编译缓存+共享作用域+@put/@get 变量机制 |
| [architecture/skill-architecture.md](./project-flow/architecture/skill-architecture.md) | Skill 架构：金字塔架构+5 阶段工作流+JVM 仿真器+basic-memory 经验引擎+固化脚本+审计器 |
| [architecture/multi-agent-analysis-spec.md](./project-flow/architecture/multi-agent-analysis-spec.md) | 大规模并行子代理分析验证修复方法论：五阶段流水线+单代理≤12 文件+并行+交叉验证+导航同步 |
| [architecture/overview.md](./project-flow/architecture/overview.md) | 架构全景图 |
| [architecture/android-ui-core.md](./project-flow/architecture/android-ui-core.md) | Android UI 核心框架册：MainActivity 主框架+Activity/Fragment 体系+Base 基类+导航链路+启动引导+N1 顶栏体系+N2 Compose 现状 |
| [architecture/android-ui-pages.md](./project-flow/architecture/android-ui-pages.md) | Android UI 页面详解册：页面布局与交互流程+书源调试+搜索范围+发现页+关联导入+辅助工具+N3 订阅双模式+N4 发现页缓存加固 |
| [architecture/android-ui-media-theme.md](./project-flow/architecture/android-ui-media-theme.md) | Android UI 阅读媒体与主题册：阅读界面+排版引擎+漫画+音频+Widget+主题+布局资源+横屏+N5 EPUB 与高亮+N6 画质增强 |
| [architecture/android-ui-changelog.md](./project-flow/architecture/android-ui-changelog.md) | Android UI 统计与变更记录册：UI 层源码统计+时敏优化记录 |
| [architecture/api-dataflow.md](./project-flow/architecture/api-dataflow.md) | 接口数据流—前后端交互全链路：HTTP/WebSocket/Beacon 完整链路+API 对照表 |
| [architecture/app-init.md](./project-flow/architecture/app-init.md) | App 入口与初始化流程：50 步启动流程+常量系统+EventBus+异常体系+监控 |
| [architecture/base-layer.md](./project-flow/architecture/base-layer.md) | Base 类与 MVVM 体系：BaseActivity/VMBaseActivity/BaseViewModel/BaseService/RecyclerAdapter/Diff+动画 |
| [architecture/frontend.md](./project-flow/architecture/frontend.md) | 前端架构—Vue3 Web 管理界面：MPA 架构+config/types 工具+模块+路由+组件+技术栈 |
| [architecture/frontend-refactor-plan.md](./project-flow/architecture/frontend-refactor-plan.md) | Vue3 Web 重构方案（⚠️未实施存档）：路由+组件树+阅读器核心+移动端适配+TypeScript 类型+Pinia Store（落地 3/5）+API 调用层 |
| [architecture/network-layer.md](./project-flow/architecture/network-layer.md) | 网络层架构：OkHttp 拦截器链+SSL 全信任+Cookie 双层+Cronet 加速+代理 |
| [architecture/build-configuration.md](./project-flow/architecture/build-configuration.md) | Legado 构建配置与依赖体系 |
| [architecture/ci-cd-pipeline.md](./project-flow/architecture/ci-cd-pipeline.md) | CI/CD 流程文档 |
| [architecture/intent-deep-links.md](./project-flow/architecture/intent-deep-links.md) | Intent 与深度链接体系 |
| [architecture/multi-module-architecture.md](./project-flow/architecture/multi-module-architecture.md) | 多模块架构 |
| [architecture/security-model.md](./project-flow/architecture/security-model.md) | Legado 安全模型 |

### 模块文档（project-flow/modules/）

| 文档 | 核心内容 |
|------|----------|
| [modules/webbook-search.md](./project-flow/modules/webbook-search.md) | WebBook 搜索与网络书模块：双版本+并发搜索调度+四分类聚合去重+发现/详情/目录/正文全链路 |
| [modules/content-pipeline.md](./project-flow/modules/content-pipeline.md) | 内容处理管线：ContentProcessor 七步管线+替换规则引擎+分段/简繁+样式适配 |
| [modules/reading-engine.md](./project-flow/modules/reading-engine.md) | 阅读引擎模块：ReadBook 状态机+三章缓存+预下载+翻页跳章+漫画+音频 |
| [modules/reading-engine-pagination.md](./project-flow/modules/reading-engine-pagination.md) | 阅读引擎分页算法详解：durChapterPos 字符偏移分页机制+TextChapter 数据结构+页面计算算法+6 种翻页动画 |
| [modules/reading-engine-media.md](./project-flow/modules/reading-engine-media.md) | 多媒体阅读（漫画+音频）：ReadManga 漫画阅读+AudioPlay 音频播放+BookType 位标记 |
| [modules/data-layer.md](./project-flow/modules/data-layer.md) | 数据层模块：56 实体+43 DAO+1 视图 BookSourcePart+BookChapter 复合主键+AutoMigration+位标记+TypeConverter（v90-v108 新增 35 实体见 entities-extensions.md） |
| [modules/web-service.md](./project-flow/modules/web-service.md) | Web 服务索引页（详情分见 web-service-api.md REST/端点/WebSocket 与 web-service-lifecycle.md 生命周期/传书） |
| [modules/web-service-api.md](./project-flow/modules/web-service-api.md) | Web 服务 REST API 规范：HttpServer 路由+端点详解+ReturnData+WebSocket+Beacon+静态服务+Vue3 前端对照 |
| [modules/web-service-lifecycle.md](./project-flow/modules/web-service-lifecycle.md) | Web 服务生命周期：WebService 服务+ReaderProvider+快捷方式+WiFi 传书+帮助系统+安全模型 |
| [modules/local-book.md](./project-flow/modules/local-book.md) | 本地书籍解析模块：TXT 编码检测+目录规则自动匹配+EPUB 懒加载+PDF/MOBI |
| [modules/service-layer.md](./project-flow/modules/service-layer.md) | 服务层与辅助模块：WebDAV 同步+下载缓存+TTS 朗读+RSS 子系统+JS 扩展函数 |
| [modules/config-system.md](./project-flow/modules/config-system.md) | 配置系统：AppConfig/ReadBookConfig/ThemeConfig/SourceConfig/LocalConfig |
| [modules/android-services.md](./project-flow/modules/android-services.md) | Android Service 层：11 个 Service+WebSocketServer+ExoPlayer+朗读状态机+音频焦点+WakeLock+通知 |
| [modules/backup-restore.md](./project-flow/modules/backup-restore.md) | 备份恢复系统：21 数据源 JSON 导出+AES 加密+WebDAV 同步+Mutex 并发 |
| [modules/remote-third-party.md](./project-flow/modules/remote-third-party.md) | 远程书籍与第三方集成：RemoteBook/WebDAV 浏览+Glide/GSYVideo/ExoPlayer+更新系统 |
| [modules/js-extensions.md](./project-flow/modules/js-extensions.md) | JS 扩展函数体系：30+ JS 可调用方法 ajax/connect/webView/cache/file/encode/python |
| [modules/source-management.md](./project-flow/modules/source-management.md) | 书源管理全链路：导入/导出/校验/调试/登录/18+过滤/排序 |
| [modules/model-layer.md](./project-flow/modules/model-layer.md) | Model 层全局单例：ReadAloud/VideoPlay/BookCover/CheckSource/Debug/RuleUpdate/SharedJsScope |
| [modules/rss-subsystem.md](./project-flow/modules/rss-subsystem.md) | RSS 子系统：Rss 调度+RssParserByRule 规则解析+RssParserDefault 标准解析+文章流 UI |
| [modules/tools-infrastructure.md](./project-flow/modules/tools-infrastructure.md) | 工具与辅助层：utils 工具类+协程封装+加密+广播接收器 |
| [modules/custom-libraries.md](./project-flow/modules/custom-libraries.md) | 自定义库层：MOBI 解析引擎+WebDAV 客户端+主题引擎+阿里云 TTS |
| [modules/association-import.md](./project-flow/modules/association-import.md) | 关联导入体系 |
| [modules/constant-system.md](./project-flow/modules/constant-system.md) | 常量系统 |
| [modules/exception-system.md](./project-flow/modules/exception-system.md) | 异常体系 |
| [modules/glide-video-webview.md](./project-flow/modules/glide-video-webview.md) | Glide·视频·WebView 索引页（详情分见 glide.md/video.md/webview-pool.md） |
| [modules/glide.md](./project-flow/modules/glide.md) | Glide 图片加载模块：ModelLoader+Fetcher 体系+OkHttpStreamFetcher+注册中心+模糊变换+异步回收 |
| [modules/video.md](./project-flow/modules/video.md) | 视频播放模块：四层架构+VideoPlayer/FloatingPlayer+弹幕+ExoPlayer 引擎层+画质增强+手势体系 |
| [modules/webview-pool.md](./project-flow/modules/webview-pool.md) | WebView 池化模块：WebViewPool 对象池+PooledWebView 动态 Context+WebJsExtensions JS 桥接 |
| [modules/help-layer.md](./project-flow/modules/help-layer.md) | Help 辅助层 |
| [modules/http-helper-layer.md](./project-flow/modules/http-helper-layer.md) | HTTP 辅助层 |
| [modules/rhino-module.md](./project-flow/modules/rhino-module.md) | Rhino 模块深度分析 |
| [modules/ui-core-pages.md](./project-flow/modules/ui-core-pages.md) | 核心 UI 页面深度分析 |
| [modules/ui-secondary-pages.md](./project-flow/modules/ui-secondary-pages.md) | 次要 UI 页面架构文档 |
| [modules/update-system.md](./project-flow/modules/update-system.md) | 应用更新系统 |
| [modules/widget-system.md](./project-flow/modules/widget-system.md) | 自定义控件体系 |

### 数据库文档（project-flow/database/）

| 文档 | 核心内容 |
|------|----------|
| [database/overview.md](./project-flow/database/overview.md) | 数据库概览（架构总览） |
| [database/entities.md](./project-flow/database/entities.md) | 核心实体字段详解 |
| [database/tables.md](./project-flow/database/tables.md) | 核心 21 表 DDL+新增表速览+索引（版本以 AppDatabase.kt 为准，当前 v108） |
| [database/entities-extensions.md](./project-flow/database/entities-extensions.md) | 扩展实体清单（v90-v108 新增 35 实体）：AI 能力/朗读 BGM/阅读增强/系统管理四组 |

### Python 重构参考（project-flow/python-ref/）

> Python 重构参考外迁区：从 Kotlin 源码提取的跨语言移植参考件，权威业务文档仍在 modules/ 与 architecture/。

| 文档 | 核心内容 |
|------|----------|
| [python-ref/README.md](./project-flow/python-ref/README.md) | 目录用途+6 件清单+权威源声明 |
| [python-ref/reading-engine.md](./project-flow/python-ref/reading-engine.md) | 阅读引擎 Python 参考：ReadBook 状态+三章缓存+翻页跳章+预下载 |
| [python-ref/web-service.md](./project-flow/python-ref/web-service.md) | Web 服务 Python 参考：REST API+数据模型+响应辅助 |
| [python-ref/local-book.md](./project-flow/python-ref/local-book.md) | 本地书籍解析 Python 参考：TXT 编码+EPUB |
| [python-ref/service-layer.md](./project-flow/python-ref/service-layer.md) | 服务层 Python 参考：WebDAV+TTS+RSS |
| [python-ref/webbook-search.md](./project-flow/python-ref/webbook-search.md) | WebBook 搜索 Python 参考：并发调度+四分类聚合 |
| [python-ref/config-system.md](./project-flow/python-ref/config-system.md) | 配置系统 Python 参考：AppConfig+ReadBookConfig+SSE 推送适配 |

### UI 设计标准（project-flow/ui-standards/）

> UI 设计架构体系：四组件族基线+取色唯一基线+开发门禁，前端 UI 改造必读。

| 文档 | 核心内容 |
|------|----------|
| [ui-standards/architecture.md](./project-flow/ui-standards/architecture.md) | UI 设计架构体系（总纲）—AI 前端开发必读：四组件族基线+取色唯一基线+开发门禁 |
| [ui-standards/README.md](./project-flow/ui-standards/README.md) | ui-standards 文档索引 |
| [ui-standards/color.md](./project-flow/ui-standards/color.md) | §9.2 取色规范 |
| [ui-standards/components.md](./project-flow/ui-standards/components.md) | §9.1 组件目录 |
| [ui-standards/dialog-shell.md](./project-flow/ui-standards/dialog-shell.md) | §9.5 对话框壳（Dialog Shell） |
| [ui-standards/how-to.md](./project-flow/ui-standards/how-to.md) | UI 实操指南（How-to）—AI 新增/修改 UI 的即查手册 |
| [ui-standards/migration-registry.md](./project-flow/ui-standards/migration-registry.md) | §9.6 迁移登记表（Archive 对齐迁移） |
| [ui-standards/page-skeleton.md](./project-flow/ui-standards/page-skeleton.md) | §9.4 页面骨架（Scaffold） |
| [ui-standards/spacing-corner-typography.md](./project-flow/ui-standards/spacing-corner-typography.md) | §9.3 间距/圆角/字体规范 |
| [ui-standards/theme-architecture.md](./project-flow/ui-standards/theme-architecture.md) | §9.7 主题体系架构总纲：三大体系+红线禁令 |
| [ui-standards/theme-token-bridge.md](./project-flow/ui-standards/theme-token-bridge.md) | §9.8 主题语义 Token 与双栈桥接（View/Compose 统一取色角色表+Compose 全面化迁移守则+colorScheme 桥接演进） |

---

## 三、功能设计（docs/specs/）

> 功能设计文档，按 OpenSpec 工作流程管理。活跃 spec 64 个，完整状态表见 [specs/INDEX.md](./specs/INDEX.md)。

### 活跃 Specs（64 个）

| Spec | 状态 | 说明 |
|------|------|------|
| [archive-theme-parity-audit](./specs/archive-theme-parity-audit/README.md) | ✅ | Archive 主题体系对齐全面审计（源码最新基线+配置项 1:1+Red zip 已兼容）+5 轮红队设计+超越实施 AD-01~07（顶栏玻璃感/.red+SEND 关联/colorScheme 桥接收敛/内置色板现代化/Compose 编辑器/Red 元数据/Material You）+语义 Token 新规范，L2 真机验证闭环 |
| [theme-topbar-default-glass](./specs/theme-topbar-default-glass/README.md) | ✅ | 默认顶栏死黑修复+玻璃感（defaultBackgroundColor 跟主题背景色+92% 微透，已并入 archive-theme-parity-audit AD-01 实施并 L2 验证） |
| [bookshelf-refresh-and-title-font](./specs/bookshelf-refresh-and-title-font/README.md) | ✅ | 书架下拉刷新转圈不消失+顶栏标题字号不统一修复 |
| [bugfix-20260822](./specs/bugfix-20260822/README.md) | 🔄 | 20260822 真机反馈 6 类问题+12 处 FATAL 崩溃+运行时异常专项修复 |
| [build-release-automation](./specs/build-release-automation/README.md) | ✅ | 打包发布体系优化：publish_release.py 一键发布编排器（L3 真实发版成功，Release 三包齐全+tag 锚点） |
| [bugfix-ui-20260824](./specs/bugfix-ui-20260824/README.md) | 🔄 | 20260824 用户反馈 11 项 UI/功能修复（图片圆角/搜索框/顶栏/分组/入口/文案等） |
| [cache-entry-relocate](./specs/cache-entry-relocate/README.md) | 🔄 | 「我的」页功能归堆重构（内容与规则/外观/同步/工具/精准管理/关于 6 组框架） |
| [enhance-switch-governance-fix](./specs/enhance-switch-governance-fix/README.md) | 🔄 | 画质增强治理修复（总开关失灵/预设脱节/无长度响应 OOM/滑条帧级开销） |
| [cache-toggle-rename-rss-all-label](./specs/cache-toggle-rename-rss-all-label/README.md) | ✅ | 文案调整：视频缓存开关改名「播放时缓存」+订阅「全部」分组标签缩短 |
| [compose-migration-status-audit](./specs/compose-migration-status-audit/README.md) | 🔄 | 前端 Compose 化进度全景审计+推进设计（页级 69 类总表+B0-B5 批次+4 实施级分册，5 轮交叉审核 ACCEPT-WITH-NOTES，设计完成待实施） |
| [compose-skill-audit-upgrade](./specs/compose-skill-audit-upgrade/README.md) | ✅ | Compose Skill 对标 APC 增强（资料优先级声明+开工声明+必读源码+选用阶梯+扫描验证+交付纪律+audit 审查矩阵+Preview 规则，红队审查 3E+7W 全修闭环） |
| [config-needs-restart-fix](./specs/config-needs-restart-fix/README.md) | ✅ | 配置修改需重启生效统一修复（订阅顶栏残留+书架布局不生效）+视效对齐 archive |
| [cookie-management-fix](./specs/cookie-management-fix/README.md) | ✅ | Cookie 管理链路修复（WebView/CookieStore/OkHttp 同步断裂 6 问题） |
| [cronet-global-enable-20260731](./specs/cronet-global-enable-20260731/README.md) | 🔄 | Cronet 默认自动启用与扩展使用方案（P0 全局启用已落地） |
| [dialog-leftovers-compose](./specs/dialog-leftovers-compose/README.md) | ✅ | 弹框遗留项 Compose 化（autoTask 两弹框+urlrecord 详情/过滤弹框迁移） |
| [douyin-style-video-player](./specs/douyin-style-video-player/README.md) | ✅ | 抖音风格沉浸式竖屏视频播放器重设计（垂直滑动+悬浮控件+三态切换） |
| [download-hls-complete-fix](./specs/download-hls-complete-fix/README.md) | 🔄 | 下载 HLS 完成链路修复（m3u8 下载成功但产物异常三连问题） |
| [download-manager-maturity](./specs/download-manager-maturity/README.md) | 🔄 | 下载器成熟化改造（Room 持久化+断点续传+暂停恢复+并发上限+重试） |
| [download-manager-optimize](./specs/download-manager-optimize/README.md) | ✅ | 下载管理深度优化（P1 正确性+引擎健壮性+IDM 动态分段引擎） |
| [exoplayer-resilience](./specs/exoplayer-resilience/README.md) | ✅ | ExoPlayer 韧性优化（MimeSniffer 识别链+WebView 降级两层防护） |
| [fix-highlight-rule-toggle-refresh](./specs/fix-highlight-rule-toggle-refresh/README.md) | ✅ | 高亮规则管理页复选框切换不即时刷新修复（Compose 跳过引用比较） |
| [fix-rss-search-scope](./specs/fix-rss-search-scope/README.md) | 🔄 | 订阅搜索范围上下文修复（分组/标签/类型内搜索按上下文收窄；2026-09-02 真机验证 3.3 五场景+3.4 三回归全 PASS+文档同步完成，仅剩 4.3 用户验收门） |
| [folder-cover-ratio-archive-align](./specs/folder-cover-ratio-archive-align/README.md) | 🔄 | 文件夹封面比例对齐 Archive（0.7→0.75 消除拉长失真） |
| [folder-cover-replace-bugfix](./specs/folder-cover-replace-bugfix/README.md) | ✅ | 书架/订阅文件夹自定义封面替换失效回归修复 |
| [folder-view-welcome-refactor](./specs/folder-view-welcome-refactor/README.md) | ✅ | 书源/订阅源文件夹视图重构+欢迎页增强+前端样式审计 |
| [forks-ecosystem-analysis](./specs/forks-ecosystem-analysis/README.md) | 🔄 | 阅读 M 功能借鉴与整体实施（17 fork 生态分析完成+分阶段借鉴落地） |
| [global-spec-restructure](./specs/global-spec-restructure/README.md) | 🔄 | 全局规范重组（AGENTS.md 跨项目通用内容迁移至全局规范） |
| [header-search-unify](./specs/header-search-unify/README.md) | ✅ | 主 Tab 头部搜索入口统一（以订阅页为标准，书架/我的对齐） |
| [highlight-dialog-compose](./specs/highlight-dialog-compose/README.md) | ✅ | 高亮三弹框 Compose 化迁移（编辑/分组管理/预设规则） |
| [image-canvas-3fix-20260728](./specs/image-canvas-3fix-20260728/README.md) | — | 图片画布模块 3 问题根因修复（基于日志包证据链） |
| [image-player-vertical-canvas-optimization](./specs/image-player-vertical-canvas-optimization/README.md) | 🔄 | 内置图片播放器垂直画布优化（垂直长画布+点击查看大图） |
| [image-thread-coordination-fix-20260731](./specs/image-thread-coordination-fix-20260731/README.md) | 🔄 | 图片加载与视频切换线程协调修复（正式包反馈两类问题） |
| [legados-forks-comparison](./specs/legados-forks-comparison/README.md) | ✅ | legados Fork 对比与集成方案（逐文件源码对比识别可集成特性） |
| [legado-skill-v2-rebuild](./specs/legado-skill-v2-rebuild/README.md) | 🔄 | Legado Skill V2 重建方案（基于第十轮深度审计的统一重建） |
| [light-theme-contrast-fix](./specs/light-theme-contrast-fix/README.md) | 🔄 | 亮色主题文字对比度系统性修复（对齐 Archive 取色派生+textColorSecondary 补写+根因3 全量 21 处+Compose 槽位治理；已实施测试包 083116 已交付，真机 L2 延后） |
| [list-residue-compose](./specs/list-residue-compose/README.md) | ✅ | 遗留列表 Compose 化收尾（CacheActivity 缓存列表+Explore 瀑布列表） |
| [master-track-orchestration](./specs/master-track-orchestration/README.md) | 🔄 | 三轨总线编排（Compose 化 B0-B5 × legadoc C0-C5 × ng P0-P5）：W0-W5 波次调度+6 大共性问题整合+V 轨挂靠（18 实质协调面）；七轮审核闭环，检查点 1 通过，W0 执行中（registry/v-track/补登已落盘，剩 C0-F1 真 bug 修复+deep-fix 收口） |
| [memory-mechanism-redesign](./specs/memory-mechanism-redesign/README.md) | ✅ | 项目记忆机制改造（项目记忆独立至 .trae/memory+废弃 conv_id） |
| [multiline-on-demand-extraction](./specs/multiline-on-demand-extraction/README.md) | 🔄 | 多线路多集按需采集架构优化（ruleContent 返回播放页 URL+按需采集 m3u8） |
| [my-topbar-unify](./specs/my-topbar-unify/README.md) | 🔄 | 「我的」页头部迁移 MainTopBarView（与书架/订阅/发现观感统一） |
| [legadoc-benchmark-analysis](./specs/legadoc-benchmark-analysis/README.md) | ✅ | 阅读C（legadoC）深度对标调研+迁移设计前置（朗读原语化/多媒体插入/合集书架/AI 净化四分期+快速修复包 C0，七轮验证含红队，待分期实施） |
| [ng-benchmark-analysis](./specs/ng-benchmark-analysis/README.md) | ✅ | 阅读NG（legado_NG）深度对标调研+迁移设计前置（8 维全景+决策表 15 项+五份函数级分期设计 P0-P5，设计验收通过，P0 先行，实施另立 spec） |
| [network-perf-stability](./specs/network-perf-stability/README.md) | 🔄 | 网络性能与稳定性深度优化（OkHttp/Cronet/协程/缓存/图片解密） |
| [p0-bugfix-round1](./specs/p0-bugfix-round1/README.md) | ✅ | P0 核心 Bug 修复第一轮（2026-07-08 改动发现的 4 项） |
| [player-mature-solutions-alignment](./specs/player-mature-solutions-alignment/README.md) | 🔄 | 播放器成熟方案对齐（可观测性+视频/图片核心能力+网络韧性，5 Phase） |
| [player-review-and-optimization](./specs/player-review-and-optimization/README.md) | ✅ | 视频/图片播放器审查与优化整合（8 份审查报告 108 项+12 个 ADR） |
| [read-menu-highlight-entry-restore](./specs/read-menu-highlight-entry-restore/README.md) | ✅ | 阅读页三个点菜单补回漏挂动作项 6 项+修正段落规则 EPUB 误显示（Compose 迁移漏译修复） |
| [reader-overlay-compose](./specs/reader-overlay-compose/README.md) | 🔄 | 阅读器浮层 Compose 化（S5 骨架：菜单层+浮层壳核分离，正文零改动） |
| [rss-classic-layout-align](./specs/rss-classic-layout-align/README.md) | ✅ | 经典订阅布局管理与书架对齐修复（margin/排序/书名/弹框等 7 项实锤） |
| [rss-cms-multiroute-nojs](./specs/rss-cms-multiroute-nojs/README.md) | ✅ | 视频订阅源多线路多集零JS解析增强（CMS分隔格式解析层原生支持+列表范式对齐书源目录+大括号模板上下文修复）+MacCMS聚合采集书源转化 |
| [video-booksource-multiroute](./specs/video-booksource-multiroute/README.md) | 🔄 | 视频书源多线路多集兼容：ruleToc/ruleContent 映射+规范化双结构+解析分级L0-L3+详情抽屉（不动现有字段解析） |
| [video-playlist-continuity](./specs/video-playlist-continuity/README.md) | 🔄 | 视频播放行为统一：多集下滑下一集/末集下滑接播列表下一个视频，全入口列表注入盘点 |
| [rss-folder-cover-dialog-align](./specs/rss-folder-cover-dialog-align/README.md) | ✅ | 订阅文件夹封面弹框对齐书架（标准弹框+预览+恢复默认） |
| [rss-folder-subtag-fix](./specs/rss-folder-subtag-fix/README.md) | ✅ | 订阅文件夹样式点进文件夹头部误显标签/箭头修复（isTagMode 守卫；2026-09-02 真机走查 3.2/3.3/3.4 三项全 PASS 收口） |
| [rss-image-load-optimization](./specs/rss-image-load-optimization/README.md) | 🔄 | 图片订阅源加载优化（参考书源：URL 缓存+采样解码+并发预下载） |
| [rss-video-player-enhancement](./specs/rss-video-player-enhancement/README.md) | 🔄 | 订阅源视频播放器增强（多集选择/调试日志/自动抓取 R1-R5） |
| [sniff-migration-booksource](./specs/sniff-migration-booksource/README.md) | ✅ | 嗅探与滑动切换能力迁移至书源（图片/视频嗅探+上下滑动切换） |
| [sniff-regression-rss-image-crash](./specs/sniff-regression-rss-image-crash/README.md) | ✅ | 嗅探回归与图片订阅源崩溃取证修复（① WebView 池全局互斥修复嗅探回归 ② 图片订阅源崩溃根因模拟器复现实锤：appendItems 后台线程更新 vs 主线程 notify 竞态 → RecyclerView Inconsistency FATAL，修复后 3 轮全绿；Phase B 定向防御 H4/H6/H1/H3；真实崩溃栈回灌闭环） |
| [source-arch-mutual-borrow](./specs/source-arch-mutual-borrow/README.md) | 🔄 | 书源/订阅源架构差异分析与机制层互补优化（6 个共享机制组件，V2） |
| [source-layout-redesign](./specs/source-layout-redesign/README.md) | ✅ | 书源/订阅源布局设置重做（视图模式/排序/类型筛选/统一配置对话框） |
| [subpage-topbar-unify](./specs/subpage-topbar-unify/README.md) | 🔄 | 子页面顶栏取色统一（TopBarConfig 三级决策链单源+组件缩减 4→3：ConfigTopBar 消灭/MainTopBarView SUB 过渡态，40+ 页跟随主题）✅ 开发完成待验收 |
| [tag-mode-unify](./specs/tag-mode-unify/README.md) | 🔄 | 书架订阅标签样式统一（对齐 Archive MainTopBarView 顶栏标签体系） |
| [theme-rss-header-layout-sync](./specs/theme-rss-header-layout-sync/README.md) | ✅ | 主题设置与订阅/发现页头部布局联动修复（即时刷新+废弃 key 清理） |
| [ai-test-system-refinement](./specs/ai-test-system-refinement/README.md) | 🔄 | ai_test 体系沉淀反思优化（五批次：SOP 文档沉淀+经验回流 / 编排层 feedback+五件套接入 / 用例解析修复 30 条 seg 残留 / scripts 52 个删除候选治理 / pytest 全量验证） |
| [thread-pool-audit](./specs/thread-pool-audit/README.md) | 🔄 | 线程池配置全面审查（13 项配置点静态审查） |
| [topbar-icon-semantics-fix](./specs/topbar-icon-semantics-fix/README.md) | 🔄 | 顶栏图标语义与功能修复（Archive 迁移三级丢失链全量普查） |
| [topbar-search-entry-align](./specs/topbar-search-entry-align/README.md) | ✅ | 主 Tab 头部搜索入口形态统一与主题取色对齐（消费主题槽位） |
| [ui-redesign-m3](./specs/ui-redesign-m3/README.md) | 🔄 | UI 重构设计（对标 M3 设计语言，全量页面 Compose 化，ADR 01-22） |
| [ui-style-unify-deep-fix](./specs/ui-style-unify-deep-fix/README.md) | 🔄 | UI 风格统一深度修复（头部 5 类+弹框 4 套体系双基线收敛） |
| [ui-theme-gap-audit](./specs/ui-theme-gap-audit/README.md) | 🔄 | UI 主题管理缺口审计与全量样式测试（F/L/P 三维清单+测试用例集+修复轮） |
| [video-back-fullscreen-fix](./specs/video-back-fullscreen-fix/README.md) | 🔄 | 视频播放器返回按钮修复+全屏按钮迁移+真全屏优化 |
| [video-download-manager](./specs/video-download-manager/README.md) | ✅ | 视频下载与下载管理整合（自研 IDM 式分片引擎+m3u8 重封装） |
| [video-extractor-enhancement](./specs/video-extractor-enhancement/README.md) | 🔄 | 内置视频抓取能力增强（自动抓取视频链接补齐规则短板） |
| [video-player-image-enhance](./specs/video-player-image-enhance/README.md) | ✅ | 视频播放器画质增强三级档位（色彩参数/CAS 锐化降噪/Anime4K 超分） |
| [video-player-theme-unify](./specs/video-player-theme-unify/README.md) | ✅ | 视频播放器主题统一（控制条/弹框动态设色+硬编码色清理） |
| [video-play-7001-videograph-fix](./specs/video-play-7001-videograph-fix/README.md) | ✅ | 视频切集 7001 渲染管线崩溃修复（media3 VideoGraph 回归：增强无条件注入空 effects 激活 GL 管线） |
| [video-player-ux-fixes](./specs/video-player-ux-fixes/README.md) | ✅ | 视频播放器体验五项修复（下载按钮/快进灵敏度/弹框透明/标题/图标） |
| [video-sniff-403-and-rss-classic-fix](./specs/video-sniff-403-and-rss-classic-fix/README.md) | ✅ | 视频嗅探引擎架构级重构（SniffEngine 统一播放/下载+删 WebView 播放器+拦截面扩展+经典布局修复+线程数 256，Phase 0-4 全闭环） |

> 历史 spec 已归档至 docs/specs/archive/（100 个 spec 目录+2 个散件分析文档，2026-08-30 文档规整），其中 42 个为停滞设计归档待定（README 顶部有标注，可随时恢复），完整清单见 [specs/INDEX.md](./specs/INDEX.md)。

---

## 四、核心 Skill（.trae/skills/legado-source-creator/）

> 项目核心工具：Legado 书源/订阅源智能创建器（79 条陷阱检查清单+5 阶段闭环工作流+10 大参考目录+验证脚本）。

### 核心文档

| 文档 | 核心内容 |
|------|----------|
| [SKILL.md](../.trae/skills/legado-source-creator/SKILL.md) | 主文档：规则引擎完整知识+79 条陷阱检查清单+5 阶段闭环工作流 |

### 参考文档索引（10 大目录）

| 目录 | 子文档数 | 核心内容 |
|------|----------|----------|
| [references/](../.trae/skills/legado-source-creator/references/_INDEX.md) | 4 核心文档 | 规则语法+URL 模板+实体字段+示例库 |
| [references/troubleshooting/](../.trae/skills/legado-source-creator/references/troubleshooting/_index.md) | 6 子文档 | 常见陷阱与故障排除 |
| [references/js-extensions/](../.trae/skills/legado-source-creator/references/js-extensions/_index.md) | 11 子文档 | JS 扩展函数完整参考 |
| [references/js-patterns/](../.trae/skills/legado-source-creator/references/js-patterns/_index.md) | 11 子文档 | JS 模式参考手册 |
| [references/special-scenarios/](../.trae/skills/legado-source-creator/references/special-scenarios/_index.md) | 13 子文档 | 登录/验证码/加密/视频等特殊场景 |
| [references/source-analysis/](../.trae/skills/legado-source-creator/references/source-analysis/_index.md) | 6 子文档 | 源码分析验证结果 |
| [references/site-features/](../.trae/skills/legado-source-creator/references/site-features/_INDEX.md) | 5 子文档 | 站点特征与规则类型映射 |
| [references/rule-construction-guide/](../.trae/skills/legado-source-creator/references/rule-construction-guide/_index.md) | 3 子文档 | 规则构建指南 |
| [references/known-fix-patterns/](../.trae/skills/legado-source-creator/references/known-fix-patterns/_index.md) | 8 子文档 | 已知修复模式 |
| [references/cms-samples/](../.trae/skills/legado-source-creator/references/cms-samples/_INDEX.md) | 2 子文档 | CMS 模板样本 |

### 工具与脚本（已清理）

> ⚠️ 历史工具（JVM 规则引擎仿真器、验证/固化/辅助 Python 脚本）已在 skill-optimization v6 中删除（确立 Playwright MCP 唯一验证地位，废弃 JVM 仿真器与 Python 客户端），本表不再罗列。当前验证方式以 SKILL.md 内工作流为准。

### 模板文件

| 文件 | 用途 |
|------|------|
| `templates/auto-video-player.html` | 自动视频播放器模板 |
| `templates/hls-video-player.html` | HLS 视频播放器模板 |
| `templates/inject-video-player.js` | 注入式视频播放器 JS |

---

## 五、工作流审计 Skill（.trae/skills/legado-workflow-auditor/）

> 书源/订阅源创建或优化任务完成后的强制审计工具，确保 Phase 完成标志、basic-memory 执行证据、自测交付流程的合规性。

| 文档 | 核心内容 |
|------|----------|
| [SKILL.md](../.trae/skills/legado-workflow-auditor/SKILL.md) | 审计规则+检查清单+审计报告模板 |
