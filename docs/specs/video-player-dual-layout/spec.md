# Spec：视频播放器双布局模式

> 红队状态：R1-R3 对抗审查完成（4P0+12P1+7P2），R4 整改落盘本版，R5 终审待执行

## Intent

用户对内置视频播放器（含前置嗅探能力）整体满意，但当前**仅有抖音沉浸式竖滑布局**，缺少传统视频应用"上部播放器 + 下部视频介绍信息"的布局形态。本设计目标：

1. 内置**两种布局结构**（抖音沉浸式 / 传统布局），用户可在全局设置中配置，**默认抖音沉浸式**
2. **设置中心化**：抽取当前内置播放器中的全局性设置到「视频播放器设置」页，播放页仅保留对当前播放即时生效的操作，避免用户在每个播放页重复设置
3. 传统布局的介绍信息**零新增字段**，全部复用当前书源/订阅源视频列表既有内容（名称、时间、图片、简介等）
4. **核心功能零变动**：组件化嗅探能力、播放内核、采集链不受此改动影响

## Scope

### In Scope

- `VideoPlay` 新增 `layoutMode` 持久化配置（0=沉浸式默认，1=传统布局，含异常值容错）
- `VideoPlayerActivity` 布局分发的**四个分发点全覆盖**：onCreate 初始化、`initFromIntent` 新会话分支、悬浮窗/通知恢复分支、`onNewIntent` 单实例复用分支（含 legacy 拆卸与 GSY 全屏窗口复位）
  - ⚠️ **实况校准（2026-09-11 源码核实）**：本条为**设计要求**。当前实际走统一入口 `dispatchLayoutMode()` 的仅 **2 处**（initFromIntent / 悬浮窗恢复），`onNewIntent` 仍走 `if(useViewPagerMode)` 直判、未接入统一入口。差距已同步修正至 `VideoPlay.kt` 注释与 `docs/project-flow/modules/video.md` §12.1，**勿据本条认定四分发点已全部落地**。
- 传统布局骨架**复活与补全**（红队 R1/R2 确认当前为死代码路径）：
  - `setupPlayerView()`（当前零调用点）复活接线：16:9 宽高比、全屏按钮、返回监听、起播触发
  - `composeTopBar` 从 `viewPagerContainer` 内**提升到根布局**（结构级改动），保证传统布局下返回/菜单/设置入口可达
  - 传统布局起播接线（`VideoPlay.startPlay(playerView)` 显式触发，不依赖 Fragment.activatePlayer）
  - 跨影片队列接线：末集播完自动切下一部（复用现有链）+ 信息区新增「下一部」入口
  - 手势提供方 = GSY 原生 + `VideoPlayer` 类内建（单击显隐/双击暂停/长按倍速/左右 seek）
- 传统布局下部信息区接线：书源分支复用现有渲染链（`showCover`/`showBook`/`showBookIntro`），订阅源分支**新写**（`rssArticle.description/image`、`rssEpisode.cover/duration` 字段绑定），缺失区块优雅隐藏
- 设置中心化（红队 R3 确认路线）：
  - 新增全局设置页：**普通 Fragment + ComposeView** 挂 `ConfigActivity`（新 tag `VIDEO_PLAYER`），渲染 `VideoSettingsPanelContent(PanelHost.GLOBAL)`；**不继承** `ComposeSettingFragment`（其 prefs 监听硬绑默认 SharedPreferences，与 `video_config` 独立文件不兼容）
  - `VideoSettingsPanelContent` 新增 `PanelHost` 参数（默认值 `PLAYER_PAGE` 保证现有调用点零破坏），按宿主裁剪分区
  - 「我的 → 视频播放器设置」入口（"工具"分组）+ 设置搜索索引收录 + `OtherConfigFragment` 既有弹框入口改为跳转全局页 + `pref_config_other.xml` 同步
- 布局切换即时生效：播放页内切换 = 进度记录（直读播放器当前位置）→ 容器重建 → 续播；全局页修改 = 下次进入生效

### Out of Scope

- 播放内核（GSY/Exo）、嗅探引擎（`SniffEngine`）、采集链（`VideoPlaybackPipeline`）的任何改动
- 书源/订阅源规则引擎、字段体系、数据库 schema 的改动（零迁移）
- 传统布局下的垂直滑动手势切视频（切集走选集列表；跨影片仅"末集自动切+下一部按钮"两条显式链路）
- 沉浸式布局的任何行为改动（保持现状为默认模式）
- `video_config` 配置向 AppConfig/PreferKey 体系的迁移（登记升级路径，本期不做）
- `VideoBookDetailSheet` 详情抽屉与沉浸式悬浮控件层的改动（传统布局天然不加载 Fragment，无双入口问题）

### 并行协调约束（P0，红队 R1）

本设计与 `video-booksource-align-rss`（另一 AI 活跃实施中）在 `VideoPlayerActivity.kt`/`VideoPlay.kt` 等 4 文件上重叠：

1. **基准快照**：本设计实施必须以 align-rss 任务收口（startPlay 收口为 Pipeline 委托、提交推送远端）之后的 commit 为基准
2. **实施前检查点**：开工前 `git log` 确认 align-rss 已收口；`startPlay/playRssEpisode/startPlayBookChapter` 已为 Pipeline 委托形态；UP_VIDEO_INFO legacy 分支存活
3. **串行约束**：`layoutMode` 写入 `VideoPlay.kt` 等重叠文件排在 align-rss 队列之后，禁止并行 Edit

## Approach

### Selected Approach

**双容器复用 + 四分发点路由 + 传统布局骨架复活 + 设置三宿主裁剪（路线 B 全局页）**：

1. `activity_video_player.xml` 中 `legacyContainer`（传统布局）与 `viewPagerContainer`（沉浸式）已并存于同一布局。将 `useViewPagerMode` 硬编码（onCreate 与 `initFromIntent` 两分支）改为按 `VideoPlay.layoutMode` 分发，**四个分发点**全覆盖（onCreate / initFromIntent 新会话 / 悬浮窗恢复 / onNewIntent），传统模式补齐 legacy 拆卸分支与 GSY 全屏窗口复位。
2. 传统布局为**被弱化的死代码路径，需复活**：`setupPlayerView()`（16:9 校准/全屏按钮/返回监听，当前零调用）纳入 `setupLegacyMode()`；`composeTopBar` 提升到根布局使传统布局菜单/返回可达；起播由 `setupLegacyMode` 显式调用，不走 Fragment 链。
3. 信息区数据绑定双源分发：书源分支复用现有渲染方法；订阅源分支新写绑定（`description`/`image`/`duration` 字段）；封面经 `CoverImageView.load` 既有采样降码链（大图铁律天然满足）。
4. 设置中心化：`VideoSettingsPanelContent` 新增 `PanelHost` 枚举参数（默认 `PLAYER_PAGE`），`GLOBAL` 宿主由新建普通 Fragment（非 ComposeSettingFragment）承载；两宿主读写同一 `VideoPlay` 配置源，无事件总线需求。
5. 布局切换：播放页内切换采用重建续播，**时序契约**=直读 `videoManager.currentPosition` → 持久化 → 主线程串行（旧实例释放→新容器 setUp）→ 复用 `switchingInProgress` 类互斥标记防进度串写 → 续播。

### Alternatives Considered

| 替代方案 | 否决理由 |
|---------|---------|
| A1：全新开发独立"传统布局播放 Activity" | `legacyContainer` 与既有适配器已覆盖传统布局素材；新建页面导致双倍维护成本，且播放状态/续播/预加载需跨 Activity 同步，复杂度显著更高 |
| A2：将 `video_config` 的 20+ 配置项迁移进 AppConfig/PreferKey 统一体系 | 迁移涉及全量 key 兼容与旧值搬移，风险大且无本期能力收益；列入升级路径 |
| A3：全局设置页继承 `ComposeSettingFragment`（SettingSpecs 声明式体系） | **红队 R3 证据否定**：基类 `prefs` 硬绑默认 SharedPreferences 且非 open 无法重定向，`video_config` 为独立文件——监听永远不触发、helper 误用将造成配置双文件分裂；且 `VideoSettingsPanelContent` 为自绘分区结构，塞入 `SettingItemSpec` 模型等于重写组件 |
| A4：传统布局信息区用 Compose 重建（内嵌 `VideoBookDetailSheet` 内容） | 书源分支 View 渲染链（`showCover`/`showBookIntro` 含三态简介机器）已存在，接线工作量与回归风险远小于重建；Compose 抽屉继续服务沉浸式 |
| A5：布局模式按书源/订阅源粒度记忆 | 用户需求是全局统一设置；按源记忆增加配置维度，且无法自动推断 |
| A6：播放页内切换布局采用播放器实例跨容器迁移（不重建） | 重建续播在共享 `ExoVideoManager` + `saveState/cloneState` 底层支撑下已足够可靠（红队 R2 确认规避件成熟），实例迁移复杂度无必要 |
| A7：传统布局菜单入口走 GSY 控件区加按钮 / legacy 内新增入口行 | `composeTopBar` 提升根布局是复用现成 9 项菜单体系的最小改动，且两布局菜单行为天然一致；另两者需重复实现菜单逻辑 |
| A8：传统布局不提供跨影片语义（VideoPlaylistHolder 仅沉浸式消费） | 书源视频传统布局将成"信息孤岛"（红队 R2 定性 P0），末集自动切+下一部按钮复用现有链成本低，产品完整性必须保住 |

### Drawbacks

- **传统布局是"回归"死代码路径**（红队 R1/R2 铁证：`setupPlayerView` 零调用、顶栏被困、onNewIntent 链 ViewPager 专用），复活涉及 6+ 处接线，工作量为全设计最大块。接受理由：仍远小于新建 Activity；tasks 已按复活清单逐项拆分并配 L2 验证。
- **手势体验与沉浸式不完全对齐**：传统布局走 GSY 原生手势，无 `seekSensitivity` 灵敏度配置、提示样式不同、无双指缩放全屏。接受理由：传统布局用户预期即"标准播放器手势"；灵敏度下沉通用层登记为升级路径。
- **XML 结构级改动**（composeTopBar 提升）超出最初"仅属性级"声明。接受理由：红队 R2 证明不提升则传统布局菜单/返回全部不可达，属必要结构改动；沉浸式路径行为经 L3 场景验证零回归。
- **设置双处存在可能产生认知重叠**（如画质增强两处同名入口）。接受理由：单源双入口（两处写同一持久化 key，无会话级覆盖），语义如实声明；分工原则=全局承载持久偏好，页内承载即时操作+布局即时切换。
- **文章模式 HTML 预缓冲触发器随 Fragment 缺失而失效**（红队 R2 [5]）。接受理由：预缓冲为体验优化非核心功能，传统布局文章模式首帧稍慢；触发点迁移登记为后续优化项，不阻塞本期。

### Prior Art

- 抖音沉浸式布局：`douyin-style-video-player` spec（已实施）
- 传统布局参照：主流影视 App 通用形态（用户提供的参照截图：上部播放器 + 封面/名称/评分/简介/线路/选集）
- 设置多宿主先例：`SettingsDialog` 双调用方（`VideoSettingsPanel.kt` 全回调 / `SettingsDialog.kt` 空回调）——本期参数化收编为 GLOBAL/PLAYER_PAGE 双宿主 + 顶栏第三入口
- 布局分发先例：`onUserLeaveHint`/`startFloatingWindow` 已按 `useViewPagerMode` 双分支（PiP 与悬浮窗），四分发点路由与该先例同构

## Requirements

- **R1 布局模式配置**：`layoutMode` Int 持久化于 `video_config`；0=抖音沉浸式（默认），1=传统布局；getter 异常值容错（非 0/1 值一律回落 0，参照 `playerType` 废弃值迁移先例）；未配置时行为与现状完全一致
- **R2 沉浸式布局保持现状**：`layoutMode=0` 时所有现有行为（竖滑切换、悬浮控件、手势、预加载、PiP、悬浮窗、历史续播）零回归
- **R3 传统布局结构**：上部播放器（16:9 基准 + onPrepared 按视频比例校准，支持横屏全屏进出且退出后信息区可见性/滚动位置恢复），下部信息区：封面图、影片名称、辅助信息行、简介（书源分支支持展开）、线路（横向）、选集（横向滚动列表，进入时自动定位并高亮当前集）；信息全部来自现有数据流，不新增字段；订阅源简介字段=`rssArticle.description`、封面=`rssArticle.image`/`rssEpisode.cover`；某项缺失时对应区块隐藏
- **R4 传统布局交互与骨架可达性**：
  - 手势由 GSY 原生 + `VideoPlayer` 内建提供：单击显隐控件、双击暂停、长按倍速、左右滑动 seek（与沉浸式的灵敏度/样式差异如实接受，见 Drawbacks）
  - **顶栏可达（P0 修正）**：`composeTopBar` 提升到根布局，传统布局下返回、9 项菜单（悬浮窗/设置/登录/复制地址/浏览器打开/其他播放器/换源/编辑源/日志）、设置面板入口全部可用
  - 禁用垂直翻页；选集/线路切换即时生效且标题同步
- **R5 跨影片队列语义（P0 新增）**：传统布局下末集播完自动切换列表下一影片（复用沉浸式现有切换链，API 以 align-rss 收口后为准）；信息区提供「下一部」显式入口；无上一部已耗尽/无队列时入口隐藏
- **R6 设置中心化分工（按红队归属表修正）**：
  - 全局设置页（GLOBAL 宿主）：布局模式默认值、播放器类型、自动播放、直接全屏、**全屏底部进度条**、起始静音、长按倍速、滑动灵敏度、快进快退秒数、边播边缓、缓存大小、首帧预加载、缓冲策略、播放历史、错误提示、自动重连、画质增强默认参数
  - 播放页面板（PLAYER_PAGE 宿主）：**布局模式即时切换**、画面比例、音轨选择、快进快退操作、播放信息复制、调试、画质增强即时调节、功能菜单（悬浮窗/其他播放器/编辑源/登录/日志）
  - 播放页顶栏：保持现有 9 项不变（换源/浏览器打开为本就仅在顶栏，不误列为面板项）
  - 画质增强为**单源双入口**（两处读写同一持久化 key，无会话级覆盖），文档如实声明
- **R7 布局切换生效机制**：全局页修改 → 下次进入生效，正在播放页面不受干扰；播放页内切换 → 直读当前位置 → 容器重建 → 自动续播（时序契约见 design AD-05）；Activity 在四个分发点（会话边界）与面板切换时读取 layoutMode
- **R8 嗅探与采集链零改动**：`SniffEngine`、`VideoPlaybackPipeline`、`VideoPlay` 播放状态字段不变；两布局共用同一采集链
- **R9 双源兼容与单页化协调**：书源/订阅源信息区均正确渲染且优雅降级；书源模式传统布局同样"1 影片页"语义；跨影片切换 API 对齐 align-rss 收口后形态
- **R10 布局分发全场景覆盖（P1 修正）**：onCreate、initFromIntent（新会话）、悬浮窗/通知恢复（clonePlayState 链按 layoutMode 分发）、onNewIntent（legacy 拆卸分支 + GSY 全屏窗口复位）四场景布局一致，无"被硬拽回沉浸式"漂移
- **R11 设置可达性与搜索（P1 新增）**：`OtherConfigFragment` 既有"视频播放器设置"弹框入口改为跳转全局页；`pref_config_other.xml` 同步防死条目；设置搜索页收录全局页关键条目（layoutMode/playerType/autoPlay/videoCache 等，ownerConfigTag=VIDEO_PLAYER）

## W2 增补（2026-09-04 用户真机验收反馈，Goal 模式实施）

用户装机体验后提出 5 项修正（原文字段见项目记忆 2026-09-04 条目），全部折入本期：

| # | 反馈 | 设计处置 |
|---|------|---------|
| B1 | 传统布局全屏按钮要在播放器右下角、倍速前面，支持全屏切换 | `setupPlayerView()` 内将 GSY 底部栏 `fullscreen` 按钮设为常显并移到 `playback_speed` 之前（仅传统布局实例生效，不影响沉浸式）；点击走 Activity `toggleFullScreen` legacy 分支（startWindowFullscreen/backFromFull），全屏时同步隐藏 Compose 顶栏与信息区 |
| B2 | 沉浸式核心功能（下载/收藏等）传统布局缺失 → 下部专门按钮区 | 新增 `legacy_actions` 按钮行：下载（`Download.start` 同链）/ 收藏（`onFragmentStarClicked` 同链 + 图标随 `rssStar` 切换）/ 悬浮窗 / 设置（`VideoSettingsPanel` BottomSheet 同组件）；置于信息区与选集区之间 |
| B3 | 右下角列表丑陋 + 不要二级子页面，页面平铺；样式模仿参考截图，基于 ui-standards 规范化 | 移除 `iv_chapter` 二级目录页入口；信息区重排（标题加粗18sp/副信息行12sp/简介滚动区）；新增「线路」「选集」区块标题（14sp bold）；列表横向平铺、clipToPadding=false、主题色取色（primaryText/tv_text_summary），无硬编码色 |
| B4 | "抖音沉浸式"改名"沉浸式" | `video_layout_mode_immersive` 文案改为"沉浸式" |
| B5 | 订阅源可能没有目录/正文图片等信息，要自动对接列表信息 | `showRssLegacyInfo` 增强对接链：名称=videoTitle→rssArticle.title；副信息行=列表计数（第N篇·共M篇/第N集·共M集）；简介=rssArticle.description→content 去HTML标签纯文本兜底；封面=rssArticle.image→rssEpisode.cover→默认占位图；单URL模式优雅降级仅标题 |

**命名口径（全局生效）**：两种布局正式名称为 **沉浸式**（默认）/ **传统布局**。

对 R3/R4/R9 的修订以本节为准。

## Scenarios

```gherkin
场景: 默认布局为抖音沉浸式
  假如 用户未修改过布局模式配置
  当 用户从书架/搜索/订阅页进入视频播放
  那么 播放页呈现抖音沉浸式布局（全屏竖滑）
  并且 上滑切换、预加载、画中画行为与现状一致

场景: 切换为传统布局后进入播放页
  假如 用户在"视频播放器设置"中选择传统布局
  当 用户进入视频播放页
  那么 页面上部为播放器（16:9 基准，按视频比例校准）
  并且 下部显示封面、名称、简介、线路、选集（当前集定位高亮）
  并且 顶栏与 9 项菜单全部可达
  并且 不响应垂直滑动切换影片手势

场景: 传统布局下切换选集与线路
  假如 用户处于传统布局且当前影片有多集多线路
  当 用户切换线路或点击另一集
  那么 播放器切换到所选内容并开始播放
  并且 信息区标题与当前集高亮同步更新

场景: 传统布局看完当前影片进入下一部
  假如 用户处于传统布局且播放列表存在下一影片
  当 当前影片末集播完（或用户点击"下一部"入口）
  那么 自动加载并播放列表中的下一影片
  并且 信息区刷新为下一影片数据
  假如 已是最后一个影片
  那么 不触发切换且"下一部"入口隐藏

场景: 播放页内即时切换布局
  假如 用户正在传统布局观看某影片第 3 集且已播放 10 分钟
  当 用户在播放页设置面板中切换布局模式
  那么 容器重建为对应布局
  并且 自动续播该影片第 3 集且进度接近切换前位置（误差≤5 秒）
  并且 切换过程中无进度串写/双 seek 竞态

场景: 悬浮窗与通知恢复布局不漂移
  假如 用户处于传统布局并进入悬浮窗播放
  当 用户从悬浮窗/通知点击返回播放页
  那么 仍为传统布局且播放状态正确恢复（clonePlayState 链）
  并且 不被硬拽回沉浸式容器

场景: onNewIntent 单实例复用布局一致
  假如 用户处于传统布局且播放页为 singleTask 复用
  当 新的播放 Intent 到达
  那么 按 layoutMode 呈现传统布局（legacy 拆卸分支生效）
  并且 若此时处于 GSY 全屏窗口则先复位

场景: 全局设置修改布局模式
  假如 用户在"我的 → 视频播放器设置"中修改布局模式
  当 下次进入播放页
  那么 按新布局呈现
  并且 设置搜索页可搜到"布局模式"等全局页条目

场景: 简介与封面缺失时优雅降级
  假如 当前订阅源视频无简介内容
  当 用户以传统布局进入播放页
  那么 隐藏简介区块，其余区块正常渲染

场景: 沉浸式模式零回归
  假如 用户始终使用默认布局模式
  当 用户执行日常播放、上滑切换、换线路、选集、悬浮窗、画中画、历史续播操作
  那么 所有行为与本次改动前完全一致
```
