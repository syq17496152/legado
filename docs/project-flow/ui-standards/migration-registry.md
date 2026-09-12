# §9.6 迁移登记表（Archive 对齐迁移）

> 登记 Archive 对齐迁移任务（`docs/specs/archive-ui-migration-202608/tasks.md`）各子项的完成状态。
> E 类为「子页面内部实现再审查」补充（E1 弹框 / E2 列表 / E3 半迁移壳 / E4 特色统一外观 / E5 骨架补缺，子任务 `7.11aa~7.11an2`），此外保留 §7.11 其它类（A 管理页缺失 / B 旧实现未对齐 / C 作用域复核 / D 我的页）及 8.x 主任务关键落点。
> **2026-08-25 回填**：本表此前全量 `[ ] 待迁移` 落后于 tasks 勾选；本次按源码实测逐项回填现状（下表"现状"列为代码核验结果），并标注与 tasks.md 勾选的偏差。

## 一、§7.11 E 类（弹框 / 列表 / 壳 / 外观 / 骨架）

### E1 弹框类

| 任务编号 | 文件（现状） | 现状 | 所属组件 | 备注 |
|---------|------------|------|---------|------|
| 7.11aa | `book/group/GroupEditDialog`+`GroupSelectDialog`+`GroupManageDialog`、`replace/GroupManageDialog` | [x] 已完成（源码核验 2026-08-25） | `ui/widget/compose/GroupManageComposeDialog` + ComposeDialogFragment | 分组弹框全部 ComposeDialogFragment；GroupManageComposeDialog 为公共 Compose 组件 |
| 7.11ab | `association` ImportBookSource/ImportDictRule/ImportHttpTts/ImportReplaceRule/ImportRssSource/ImportTheme/ImportTxtTocRule（7）+`OnLineImportActivity` | [x] 已完成（源码核验 2026-08-25） | Compose：导入对话框族 | 7 导入弹框均 ComposeDialogFragment + setContent |
| 7.11ac | `manga/config/MangaColorFilterDialog`+`MangaEpaperDialog`+`MangaFooterSettingDialog`、`import/remote/ServerConfigDialog`、`bookmark/BookmarkDialog`、`audio/config/AudioSkipCredits` | [x] 已完成（源码核验 2026-08-25） | Compose 对话框族 | 全部 ComposeDialogFragment + setContent |
| 7.11ad | `config/CheckSourceConfig`+`CoverRuleConfigDialog`+`DirectLinkUploadConfig`、`dict/rule/DictRuleEditDialog` | [x] 已完成（源码核验 2026-08-25） | Compose 对话框族 | 全部 ComposeDialogFragment + setContent（DirectLinkUploadConfig 即原 ComposeDirectLinkUploadDialog） |
| 7.11ae | `about/UpdateDialog` | [x] 已完成（源码核验 2026-08-25） | Compose 对话框族 | UpdateDialog 已 ComposeDialogFragment；UpdateAcceleratorDialog 见 7.11l |
| 7.11af | `toc/rule/TxtTocRuleEditComposeDialog` | [x] 已完成（源码核验 2026-08-25） | Compose 对话框族 | 已建 ComposeDialogFragment，替代旧 TxtTocRuleEditDialog |
| 7.11am | `highlight/` 旧弹框（GroupManage/Edit/PresetRule） | [x] 已完成（源码核验 2026-08-25） | Compose：`GroupManageComposeDialog`/`AppEditDialog` | 三弹框均已迁移 ComposeDialogFragment（HighlightRuleEditDialog 全屏 + HighlightRuleGroupManageDialog 薄壳受控复用 ComposeGroupManageDialogContent + HighlightPresetRuleDialog 底部档），删除 dialog_highlight_* 5 布局 + 1 menu |
| 7.11an | `autoTask/`（AutoTaskLogDialog/ImportAutoTaskDialog）+`widget/dialog/TextListDialog`+`config/CheckRssSourceConfig` | [x] 已完成（源码核验 2026-08-25）：AutoTaskLogDialog/ImportAutoTaskDialog 已迁移 ComposeDialogFragment（AppDialogFrame/dialog_recycler_view 不再引用）；TextListDialog、CheckRssSourceConfig 源码中未检索到（已删/并入检查体系） | `ui/widget/components` | 统一外观；规格 [dialog-leftovers-compose](../../specs/dialog-leftovers-compose/README.md)（✅ 实施完成） |
| 7.11an2 | `video/`（播放器内部旧弹框）+`image/`（图片浏览旧弹框）+`urlrecord/`（访问记录旧弹框） | [x] 已完成（源码核验 2026-08-25）：video `SettingsDialog` 已 ComposeView 承载；image 顶栏 Compose 化；urlrecord `UrlRecordFilterSheet`（过滤底部弹框）+ `ComposeConfirmDialog`（详情弹框）已迁移 | `ui/widget/components`（8.1/8.4 标注落点） | 统一到组件库（E4） |
| 7.11ao | `bookshelf/BookshelfConfigDialog`（`BaseBookshelfFragment` 旧弹框） | [x] 已完成（源码核验 2026-08-25） | Compose | 已建 ComposeDialogFragment 替换书架旧弹框 |

### E2 列表类

| 任务编号 | 文件（现状） | 现状 | 所属组件 | 备注 |
|---------|------------|------|---------|------|
| 7.11ag | `replace/ReplaceRuleActivity` 列表 | [x] 已完成（源码核验 2026-08-25） | `AppManagementScaffold` 全 Compose | setContent 已接管，recyclerView 已 removeView；ReplaceRuleAdapter 死代码已删 |
| 7.11ah | `book/search/SearchActivity` 结果列表 | [x] 已完成（源码核验 2026-08-25） | Compose 列表组件 | composeResults/composeInputHelp 已 setContent（SearchResultScreen/SearchInputHelpScreen）；残留 initRecyclerView 仅剩空壳调用 compose 初始化 |
| 7.11ai | `book/cache/CacheActivity` | [x] 已完成（compileAppDebugKotlin 通过 2026-08-25） | `CacheScreen` 纯 Compose `LazyColumn` | 列表 RecyclerView 全量退役：CacheAdapter/item_download.xml 已删，事件局部刷新（UP_DOWNLOAD/EXPORT_BOOK）改按 bookUrl tick 重组；顶栏 GlassTopAppBar 保留；待真机回归（tasks 3.1） |

### E3 半迁移壳（配置页升 ComposeSettingFragment）

| 任务编号 | 文件（现状） | 现状 | 所属组件 | 备注 |
|---------|------------|------|---------|------|
| 7.11ak | config `CoverConfigFragment`/`ThemeConfigFragment`/`WelcomeConfigFragment` | [x] 已完成（源码核验 2026-08-25） | `ComposeSettingFragment` | 三个 fragment 均继承 ComposeSettingFragment（另 Ai/Discovery/SubscriptionConfigFragment 也已完成） |
| 7.11al | config `BackupConfigFragment`/`OtherConfigFragment` | [x] **实际已完成但 tasks 未勾（tasks 标 [ ] 滞后）**：BackupConfigFragment:64 + OtherConfigFragment:55 均继承 ComposeSettingFragment | `ComposeSettingFragment` | ⚠️ 纠正 tasks 7.11al「BackupConfigFragment 仍为 PreferenceFragment」表述已过期：源码已 Compose 化 |

### E4 特色统一外观（接入组件库）

| 任务编号 | 文件（现状） | 现状 | 所属组件 | 备注 |
|---------|------------|------|---------|------|
| 7.11aj | `explore/ExploreFragment` 主列表接入现代/套件 | [ ] 未完成：主列表仍 RecyclerView（rvDiscoverBooks），但已新增 composeDiscoverBooks/composeDiscoverySuite 区块 | 现代 discovery 套件 | 并入 7.11h（唯一搬入 owner）；部分区块已 Compose，主列表待接入；已出规格 [list-residue-compose](../../specs/list-residue-compose/README.md)（🔄 设计中） |
| 7.11an | （并入 E1 行） | — | — | 特色弹框统一外观归 E4 |
| 7.11an2 | video/image/urlrecord 旧弹框 | [x] 已完成（见 E1 行） | `ui/widget/components` | 统一到组件库 |
| 7.11am | highlight 旧弹框外观 | [x] 已完成（见 E1 行） | `GroupManageComposeDialog`/`AppEditDialog` | 列入 E4 |

### E5 骨架补缺 / 综合

| 任务编号 | 说明 | 现状 | 所属组件 | 备注 |
|---------|------|------|---------|------|
| 7.11（E 类门禁） | 每项编译 `assembleAppDebug` + 运行可达性核对 | [ ] 未完：aa-ab-ac-ad-ae-af-ag-ah-ak-al-ao-am-an-an2 已 Compose 化（am/an/an2 随规格 dialog-leftovers-compose + highlight-dialog-compose 实施完成，待全量编译）；**ai/aj 仍待实施** | — | 待办：7.11ai CacheActivity 列表、7.11aj Explore 主列表（规格 list-residue-compose 实施中） |
| 7.11ap | 作用域复核：`widget/SourceSelectDialog`（并入 7.11t）、`widget/WaterfallCardMetrics`（并入 7.11h 瀑布流批） | [x] 已完成（tasks 已勾；SourceSelectDialog 已 Dialog+ComposeView setContent） | — | 作用域判定项 |

## 二、§7.11 其它类（A / B / C / D）

> 仅列任务骨架，明细见 tasks.md §7.11 主条目。

| 类 | 范围 | 现状 |
|----|------|------|
| A 管理页缺失 | 7.11a~q：云存储/封面图库/书籍信息/导航/顶栏/中转/在线导入/现代发现/书架标签/我的设置/在线导入协议/关于页/规则订阅/ExploreShow/目录规则/换源/RSS 文章搜索等 | [ ] 待实施（见 tasks.md） |
| B 旧实现未对齐 | 7.11r~z：书籍导入识别、音频缓存、SourceSelect 组件、头部布局对齐、关于页回退、配置顶栏回退、主题配置改 Archive、书架死适配器清理 | [ ] 待实施（关于页 7.11w/顶栏 7.11x/主题 7.11y 为回退 Archive 决策项） |
| C 作用域复核判定 | 7.11r/s/t：ImportBook、AudioCache、SourceSelect | [ ] 需判定位 |
| D 我的页 | — | 现状见 tasks.md（D 类在我页相关项） |

## 三、关键衔接（tasks.md「重叠去重/依赖」）

- **重叠去重**：8.9「RuleSub 用 Archive 版」与 7.11m（规则订阅）重复，RuleSub 主体归 7.11m，8.9 仅保留分组封面适配。
- **依赖衔接**：8.5 全局搜索入口依赖 7.11v 订阅顶栏对齐；8.6 分组入口依赖 7.11a-p 管理页 + 7.11v；8.1/8.4 特色弹框外观统一归 E4。
- **门禁**：A/B/D/E 每项补完 = 编译 + 运行可达性；C 类不单独设编译门禁。

## 四、ui-theme-gap-audit 审计产出登记（2026-08-26）

> 来源：`docs/specs/ui-theme-gap-audit/`（问题清单 v0 → v2）。本任务为「主题管理缺口」专项审计 + 修复，与 §7.11 迁移表互补：§7.11 管 Archive 对齐迁移，本表管主题联动/风格统一缺口。

| 编号 | 问题 | 处置 | 落点 |
|------|------|------|------|
| G1 | 字号硬编码 678 处未随字号缩放 | typography token 化（保留 3 处刻意豁免：8sp 迷你角标×2/49sp 品牌标题×1） | `ui/theme/LegadoTheme.kt`（labelXSmall..titleLargeX 扩展 token） |
| G2 | 圆角 token 未覆盖 | AppShapes 增补 Circle/Capsule/CornerZero，倍率动态缩放全局收口 | `ui/widget/components/AppShapes.kt` |
| G3 | 视频 UI 硬编码色 | 并 video-player-theme-unify（控制条/进度条/弹框接入 ThemeStore+UiCorner；深色悬浮层白字=AD-01 例外保留） | `help/gsyVideo/VideoPlayer.kt` 等 |
| G4 | 调试工具 7 页主题联动盲区 | 抽 DebugBaseActivity 公共基类（ThemeSync RECREATE 订阅 + Compose 系统栏），7 页全继承 | `ui/debug/DebugBaseActivity.kt`（新增） |
| G5 | View 型弹窗未包 LegadoTheme | ComposeView 型 5 个（SpeakerGroupManage/SpeakEngine/BgTextConfig/PageKey/HttpTtsEdit）包 LegadoTheme；纯 View 型 5 个=合理存量 | `ui/book/read/config/*Dialog.kt` |
| G6 | BaseDialogFragment 残留 ~20 个 | ⚠️ **判定撤销（2026-08-27）**：实测 36 子类(+pref2) 仅 `setBackgroundColor(ThemeStore)` 联动背景、控件硬编码，**不再视为合理存量**；全部入 ui-style-unify-deep-fix D1 迁移队列（P0 高可见优先，特殊复用型登记过渡期保留但入队） | `base/BaseDialogFragment.kt`（待迁移） |
| G7 | PopupMenu 双风格 | 9 处迁移 ModernActionPopup；SelectActionBar 改走 showSelMenu | `ui/widget/ModernActionPopup.kt` / `SelectActionBar.kt` |
| G8 | 书源编辑/调试头旧 TitleBar | 两页均 MainTopBarView(Mode.SUB) + ModernActionPopup.showFromMenu | `ui/book/source/edit/BookSourceEditActivity.kt` / `debug/BookSourceDebugActivity.kt` |
| G9 | Kotlin 硬编码色 | 残余 27 处核验=全豁免类（语义 Danger/封面打底/高亮/ThemeSpec 定义源/阅读配置色） | 登记仅观察 |
| G10 | XML 布局硬编码色 | 残余核验=全豁免类（scrim 遮罩/媒体画布/视频控制层/小组件固定色） | 登记仅观察 |
| G11 | 图标品牌色/几何色 | 矢量固有属性 + TintHelper 例外 | 登记仅观察 |

> 复测证据：R2 全量 report_20260826_204811（52 条 fail=0/warning=0/VL 无新候选）、logcat 针对性计数=0；R3 修复面专项 TC-071/041/081 步骤全过。

## 五、填写说明（维护者）

- 「现状」列：勾选 `[x]` 表示已完成/已覆盖决策；`[ ]` 待做。以 authority tasks.md 勾选为准。
- 「所属组件」列：最终 Compose/组件化落点（`ui/widget/compose/` 或 `ui/widget/components/`）。
- 每完成一项，同步更新本表 + tasks.md 勾选 + 实施回执。

## 六、ui-style-unify-deep-fix 组件体系统一登记（2026-08-27）

> 来源：`docs/specs/ui-style-unify-deep-fix/`（issue-list H1-H14 / D1-D4 / S1-S6 + tasks.md + design AD-07/08）。本批次管「组件单一来源治理」，与 §7.11（Archive 对齐）、ui-theme-gap-audit（G 系列微观 token）互补——本批管宏观组件/取色来源统一。**迁移状态权威源 = issue-list/tasks.md，本表为登记镜像。**

| 编号 | 问题 | 处置 | 状态 |
|------|------|------|------|
| H6 | ConfigActivity 头部黑色 + 系统菜单（用户实锤） | ConfigTopBar 增背景 + MenuProvider → AppDropdownMenu（渲染层对齐基线）；**补记（topbar-icon-semantics-fix 2026-08-28）：本项改造曾静默收拢原 always 一级图标（备份页问号等），已由 MenuAction.alwaysShow 分级渲染修复** | [x] ✅ 已完成（2026-08-27） |
| H7 | 系统菜单可见点 + 管理页残存 | 漫画登记豁免（沉浸红线）+ 发现经典「分组」→ ModernActionPopup（main_explore 移除 `<menu/>`）+ 死 menu XML 38 个删除 | [x] ✅ 已完成（2026-08-27，H17 补清 2026-08-28） |
| H8 | AppDropdownMenu M3 体系（38 文件，实测 44）与 ModernActionPopup 不符（用户实锤） | 渲染层对齐基线（调用点零改动）；死代码已删；剩余头部接入 TopBarConfig 评估完成（替换净化=AppManagementTopBar 已接 / 字典=GlassTopAppBar 已接，无需再动） | [x] ✅ 全链收口（2026-08-28） |
| H9 | 精准管理页背景 M3 surface + SettingsCard/ClickRow M3 派生色（用户实锤） | 根背景 → palette.page；组件取色归位直色（5 文件 13 处） | [x] ✅ 已完成（2026-08-27） |
| H10 | 列表项 M3 派生色（Dict/Highlight/Download + ListCard） | → palette.settings.row / 调色板入参 | [x] ✅ 已完成（2026-08-27） |
| H11 | 6 页**列表项卡片** M3 surface（AutoTask/TxtTocRule/AllBookmark/Highlight/DictRule/RecycleBin） | → `palette.settings.row`（与 H10 同源；根背景 M3 surface 仅 PreciseManage 1 页归 page）；TxtTocRuleScreen 底部操作栏 L268 palette.row 已归位（源码亲核 2026-08-28） | [x] ✅ **6/6 完成（2026-08-28 收口）** |
| H12 | Debug 8 页 M3 TopAppBar secondary 色 | 7 页中 6 页早已 GlassTopAppBar，补 PingTestScreen 归位；MyFeatureBooks 并入 H3 | [x] ✅ 已完成（2026-08-27） |
| H13 | GlassTopAppBar 不消费 TopBarConfig | **已实施（2026-08-27 用户裁决后）：接入 TopBarConfig**，STYLE_REGULAR 消费壁纸/圆角/背景色 | [x] ✅ 已完成 |
| H14 | 角色系列 4 页底色硬编码（page 黑白 + card/cardAlt/stroke 十六进制） | → palette 直读（page→palette.page、card→UiCorner.surfaceColor(themeUiPalette.cardColor)、stroke→palette.divider） | [x] ✅ 已完成（2026-08-27） |
| H15 | 自绘头部未接入 TopBarConfig 族（AppManagementTopBar + 5 同类，11 页） | AppManagementTopBar 接入 TopBarConfig + AiProvider/AiImageProvider/双容器/人物志 5 处换 GlassTopAppBar；Toc/AiChat 豁免 KDoc | [x] ✅ 已完成（2026-08-28，tasks 2.2.0i） |
| H16 | AppDropdownMenu vs ModernActionPopup 实现差异 6 项（47 文件感知主因） | 6 项对齐（字体/0dp 海拔/条件描边/LegadoMiuixChoiceRow/42dp 行高/124-244dp 宽度），调用点签名零改动 | [x] ✅ 已完成（2026-08-28，tasks 2.2.0j） |
| H17 | 菜单漏网 4+1 处 + 死代码清理 | CurlTest→AppDropdownMenu/ReplaceRule inflate 删除/Explore 经典 ModernActionPopup/LibraryContainer menu.add 清理/ReadRecordFragment 死链删除+死 menu XML 38 个删除 | [x] ✅ 已完成（2026-08-28，tasks 2.2.0k） |
| D1 | BaseDialogFragment 36(+pref2) 旧弹框（撤销 G6） | **全量收口（2026-08-28）**：35 个迁移 ComposeDialogFragment（含 P0 换源/SourceLogin/ReadAloudConfig 等 8 超大弹框）+ 复杂型登记保留；`ui/` 下 BaseDialogFragment 子类 grep=0 | [x] ✅ 已完成（tasks 2.3.1/2.3.4） |
| D2 | alert{} DSL 71 文件 162 处（复核 import=76 文件） | **累计转换约 90 处**（确认/单选/多选/TextInput/selector 全收敛）+ 剩余 25 文件 import 为 customView 复杂型登记保留（实测 2026-08-28 import=25 文件） | [x] ✅ 可转型全收口（tasks 2.3.2） |
| D3 | M3 @Composable 5 组件 + Import 系 7 | Import 3 对话框 alertCustomGroup → showComposeTextFormDialogWithChecks；M3 组件经 H8/H16 渲染层对齐 | [x] ✅ 已完成（2026-08-27，tasks 2.3.3） |
| D4 | 散点弹框 13 | 纯展示型补主题取色；WebView/BottomSheet 承载类实况已随主题；复杂定制表单登记保留 | [x] ✅ 已完成（2026-08-27，tasks 2.3.5） |
| S1-S6 | 订阅页经典/新版切换 6 遗留 | 状态机修复（guard/重置/事件/ViewModel 隔离）——**已完成（2026-08-27，tasks 2.1.1-2.1.5 全勾，编译门禁通过）** | [x] ✅ 已完成 |

> 组件层根因复盘（防回潮依据）：①同类页面不同组件（顶栏 8 形态/菜单 4 体系/弹框 5 家族/卡片列表 3 套）②M3 派生色 vs 直色双取色来源（C5 判据漏洞）③逐项豁免无登记（G6 教训）。四阶段路线图（AD-08）：取色源统一 → 根背景+顶栏收敛 → 弹框分批迁移 → 机制防回潮（本 ui-standards 目录 + 代码门禁即 Phase4 常驻部分）。

## 六.1 顶栏图标语义处置登记（topbar-icon-semantics-fix，2026-08-28）

> 起源：H6 改造链路（菜单下沉范式 → 搬壳不搬语义 → 数据+渲染双向降维）静默收拢原版 `showAsAction="always"` 一级图标，全量普查实锤 18 页/25 项回归。修复与裁决权威源 = `docs/specs/topbar-icon-semantics-fix/showasaction-audit.md`（本节为登记镜像）。
>
> **维护者必读**：今后任何 TopBar 迁移/重构登记，必须在登记行附「原 showAsAction 处置」说明（取值：一级恢复 / 下拉收拢 / 有意进化，需注理由）——防静默降级重犯。新增一级图标按 how-to.md §2.2.1 分级标准接入取色权威源。

| 类别 | 页面/范围 | 原 showAsAction 处置 | 状态 |
|------|----------|---------------------|------|
| ConfigTopBar 系 | 备份恢复页 Help / 主题设置页 DarkMode | always → `MenuAction.alwaysShow=true` 一级恢复 | [x] ✅ 已完成（2026-08-28） |
| GlassTopAppBar 系 | 订阅源编辑(代码/保存/调试) / 替换编辑(代码/保存) / txt_toc 新增 / dict 新增 / WebDav 刷新 / 本地导入选目录 / 收藏夹分组 | always → actions 槽分级直出一级 IconButton | [x] ✅ 已完成（2026-08-28） |
| MainTopBarView 系 | 我的 tab 帮助 / 订阅 tab 历史+分组+设置 | always → `addActionButton` 一级恢复（moreButton 无剩余项隐藏） | [x] ✅ 已完成（2026-08-28） |
| View TitleBar 系 | 书源编辑(代码/保存/调试) | always → `addActionButtons` 一级恢复 + 弹出菜单重复项隐藏 | [x] ✅ 已完成（2026-08-28） |
| 豁免（已达标） | 缓存页分组（实为一级图标+子菜单） | always 语义等价保留 | [x] ✅ 核验豁免 |
| 有意进化（维持） | 书信息页 ifRoom×6 / 视频浮窗（动态）/ 发现 tab modern 分组 / 换源筛选（页内搜索条）/ 排序平铺项收敛下拉 | ifRoom/动态无 Compose 直接映射，进化形态功能可达，登记不回退 | [x] ✅ 裁决登记 |

## 六.2 主 Tab 搜索入口形态与搜索框取色登记（topbar-search-entry-align，2026-08-28）

> 权威源 = `docs/specs/topbar-search-entry-align/`（spec/design/tasks）；规范落点 = `project-rules/frontend-ui-standards.md` §1.4（取色修订）+ §3.1（入口形态新条款）+ `architecture.md` 顶栏族。

| 部件 | 文件 | 变更 | 状态 |
|------|------|------|------|
| A 发现页 | `ExploreFragment.kt` | `setSearchEntryVisible(false)` + `updateDiscoverSearchButtonState` regular 按钮可见 + 删胶囊点击绑定（titleSelect 源选择入口回归） | [x] ✅ |
| B 订阅页（用户裁决纯按钮） | `RssFragment.kt` | 删 `selectSource` 的 `setSearchEntryVisible(hasSearch)` 覆盖调用 + `searchButton.isVisible = hasSearch`（regular 可见）+ 清胶囊 isEnabled/alpha 残留 | [x] ✅ |
| C Compose 取色 v3 | `SettingsSearchBar.kt` | 背景消费 `ThemeUiPalette.searchFieldBackgroundColor`（key→background_menu 兜底）+ alpha 0.18/0.42 + 1dp 描边；**清除 surfaceVariant**（修正 M3 派生色违规）；14 调用点自动联动 | [x] ✅ |
| D 规范 | `frontend-ui-standards.md`/`architecture.md`/本表 | §1.4 取色矛盾条款修订（B9）+ §3.1 入口形态新条款 + 顶栏族形态约束 + 本登记 | [x] ✅ |

> 验证范围（用户裁决 2026-08-28）：仅编译通过（compileAppDebugKotlin），不打包不真机；真机验证归后续会话按 tasks.md §6 执行。

## 六.3 书架取色归位与遮罩豁免登记（config-needs-restart-fix，2026-08-29）

> 权威源 = `docs/specs/config-needs-restart-fix/`；规范落点 = `how-to.md` 严禁清单 13/14 条（配置快照禁令 + collector 泄漏禁令）。

| 项 | 文件 | 处置 | 状态 |
|----|------|------|------|
| M3 派生色归位 | `BookshelfScreen.kt` | surfaceContainerHigh→UiCorner.surfaceColor(cardColor)；outline/onSurfaceVariant→palette.secondaryText；primary→palette.accent；surfaceVariant track→cardColor；RoundedCard→palette.settings.row（基线 B） | [x] ✅ |
| **遮罩白字豁免（登记）** | `BookshelfScreen.kt` 书名遮罩（showBookname==2）+ ShelfStatusBadge 角标白字 | 对齐 archive 纯白字叠印/白字角标，属 color.md「中性灰浮层/遮罩」语义豁免（Color.White 仅用于深底遮罩/角标上前景，非页面级取色） | [x] ✅ 已登记 |
| 范围外遗留（登记不处置） | `BookshelfItems.kt` GeneratedCover（MaterialTheme.primary/onPrimary） | 非本次改动文件，后续同型归位批次处理 | [ ] 登记 |
| 真机 L2 场景 A-F | 订阅 modern→classic 顶栏 / 书架配置即时生效 / K7 迁移 | 用户裁决延后，归 R2 复测 | [ ] 待测 |

## 六.4 弹框迁移收官批 F 登记（dialog-migration-final-batch-f，2026-09-01）

> deep-fix 弹框迁移收官批 4 处；样板 = `SourcePickerDialog`（ComposeDialogFragment + AppDialogFrame + LegadoTheme + DisposeOnViewTreeLifecycleDestroyed），标准件 = `ui/widget/compose/ComposeDialogAdapters.kt`。守门原则：等价迁移 > 登记保留。

| 处 | 文件 | 处置 | 状态 |
|----|------|------|------|
| F1 | `ui/book/read/ReadBookActivity.kt` 书库云端章节选择（原 showLibraryCloudChapterDialog/createLibraryCloudChapterRow/libraryCloudActionText 约 130 行 View 代码） | AlertDialog+LinearLayout 动态章节行 → 新 `LibraryCloudChapterDialog`（ComposeDialogFragment + AppDialogFrame scrollContent=false + LazyColumn heightIn(max=420.dp)，分组头+行卡片：标题/时间/整行与「读取」=读取回调、「删除」=删除回调）；删除确认仍走 showComposeTextInputDialog，调用点改 showDialogFragment 标准 API，回调行为等价 | [x] ✅ 已完成（compileAppDebugKotlin 通过 2026-09-01） |
| F2 | `ui/book/read/config/PageKeyDialog.kt` | ComponentDialog+ComposeView 换壳 ComposeDialogFragment + AppDialogFrame（Confirm 档）；硬件翻页键拦截由 Compose onPreviewKeyEvent 承担（字段聚焦 ⟺ 原 dialog onKeyDown focusedField≠None，语义等价，BACK/DEL 仍走系统路径）；调用点 BaseReadBookActivity/MoreConfigDialog 改 showDialogFragment 标准 API | [x] ✅ 已完成（compileAppDebugKotlin 通过 2026-09-01） |
| F3 | `ui/main/explore/ExploreFragment.kt` showDiscoverKindsDialog（发现 Kind 选择） | **登记保留**：非「标题+动态 Kind 列表+点击回调」简单结构——5 种 Kind 控件（url/button/toggle/select/text）均经 SourceLoginJsExtensions 执行源 JS 并以 reUiView 回调直接引用 ItemFindBookBinding 重渲染 flexbox，叠加 SwipeRefreshLayout/RotateLoading/自定义窗口尺寸与 View 强耦合；等价迁移需整体重写源 JS 交互协议，风险大于收益 | [ ] 保留（专项重写需连 SourceLogin JS 扩展交互契约一并设计，建议单开专项） |
| F4 | `ui/book/read/SelectionWebSearchDialog.kt`（626 行，划词搜索） | **登记保留**：BottomSheet 主体为 WebView 承载——WebViewPool 池化复用 + hideCss 注入（JS/HTML 双通道）+ shouldInterceptRequest 重写主文档 + BottomSheetBehavior.isDraggable 与 WebView canScrollVertically/触摸事件联动 + 返回键 WebView 历史栈；引擎 chip 列表仅一排小件，部分迁移收益极低且 AndroidView 桥接引入新风险 | [ ] 保留（专项重写建议：整体重设计为 Compose 壳 + AndroidView 包 WebView，先固化「sheet 拖拽 ↔ WebView 滚动」联动契约再动壳；编辑入口 SelectionSearchEngineManageDialog 已是 ComposeDialogFragment 无需再动） |

## 六.5 遗留弹窗底色源切换登记（dialog-transparent-bg-fix，2026-09-11）

> 背景：背景图模式下 `Context.backgroundColor` 返回 TRANSPARENT（主界面沉浸原语），遗留 View 弹窗经 `filletBackground` 误消费导致整窗透明穿帮（典型：订阅源页码 NumberPickerDialog）。修复 = 弹窗窗口底色统一切 `Context.dialogSurfaceBackground`（themeCardColor → `R.color.dialog_surface` 日 #FFF/夜 #1C1C1E 兜底，`UiCorner.opaqueRounded`+`panelRadius` 不透明），与 ComposeDialogFragment 弹框族取色口径对齐。**豁免口径**：遗留弹窗（alert{} DSL 25 豁免文件 / NumberPickerDialog 9 调用方 / ColorPreference / prefs 三兄弟）暂不迁移 Compose 基线，其底色视同已按基线来源管理；新增弹窗仍一律走 9 大工厂。

| 处 | 文件 | 处置 | 状态 |
|----|------|------|------|
| D1 | `utils/DialogExtensions.kt` `AlertDialog.applyTint()` | filletBackground→dialogSurfaceBackground（公共入口，覆盖 alert{} DSL、NumberPickerDialog 全部调用方、ColorPreference） | [x] ✅ compileAppDebugKotlin 通过（2026-09-11） |
| D2 | `utils/DialogExtensions.kt` `Dialog.applyModernWindowStyle()`（死代码，零调用） | 同步换源，防启用时复活 bug | [x] ✅ 同上 |
| D3 | `lib/prefs/ListPreferenceDialog.kt` / `MultiSelectListPreferenceDialog.kt` / `EditTextPreferenceDialog.kt` | 手动 setBackgroundDrawable 同步换源 | [x] ✅ 同上 |

## 七、compose-migration-status-audit 基线校准登记（B1/B2 批次，2026-08-30）

> 权威源 = `docs/specs/compose-migration-status-audit/design.md` 页级总表（AD-01：进度以本表为唯一权威，本块为 B1 校准+B2 冻结的登记落点）。
> 证据字段规范：每项含【源码路径 | 核验方式 | 核验日期】；核验方式取值：源码核验 / Grep setContent+ComposeView / 真机 L2（脚本+截图+logcat）。

### B1 登记核对（🔁，16 项）

| 任务编号 | 文件（现状） | 现状 | 核验方式 | 日期 | 备注 |
|---------|------------|------|---------|------|------|
| 7.11aq | `ui/replace/ReplaceRuleActivity.kt`+`ReplaceEditActivity.kt` | [x] 已核对（AppManagementScaffold 全 Compose + Edit 桥接） | Grep setContent + composeView | 2026-08-30 | design C4；registry 7.11ag 镜像 |
| 7.11ar | `ui/highlight/HighlightRuleActivity.kt` | [x] 已核对（HighlightRuleScreen 全 Compose；强跳过修复 ✅） | 源码核验 | 2026-08-30 | design C5 |
| 7.11as | `ui/dict/rule/DictRuleScreen.kt` | [x] 已核对（Activity setContent→Screen） | 源码核验 | 2026-08-30 | design C6；inventory 标 View 滞后已校正 |
| 7.11at | `ui/file/FileManageScreen.kt` | [x] 已核对 | 源码核验 | 2026-08-30 | design C9 |
| 7.11au | `ui/download/DownloadManageScreen.kt` | [x] 已核对 | 源码核验 | 2026-08-30 | design C10 |
| 7.11av | `ui/urlrecord/UrlRecordScreen.kt`（+FilterSheet） | [x] 已核对 | 源码核验 | 2026-08-30 | design C11 |
| 7.11aw | `ui/source/recycle/RecycleBinScreen.kt` | [x] 已核对 | 源码核验 | 2026-08-30 | design C12 |
| 7.11ax | `ui/image/`（Gallery/Detail 双页） | [x] 已核对 | 源码核验+二次 Grep 复核 | 2026-08-30 | design C15 |
| 7.11ay | `ui/autoTask/AutoTaskScreen.kt` | [x] 已核对 | 源码核验 | 2026-08-30 | design C16 |
| 7.11az | `ui/about/ReadRecordScreen.kt` | [x] 已核对 | 源码核验 | 2026-08-30 | design F3 |
| 7.11ba | `ui/config/BackupConfigFragment.kt:64` | [x] 已核对（继承 ComposeSettingFragment） | 源码核验 | 2026-08-30 | design E1；registry 7.11al 镜像 |
| 7.11bb | `ui/config/CoverConfigFragment.kt` | [x] 已核对 | 源码核验 | 2026-08-30 | design E3；7.11ak 镜像 |
| 7.11bc | `ui/config/WelcomeConfigFragment.kt` | [x] 已核对 | 源码核验 | 2026-08-30 | design E6；7.11ak 镜像 |
| 7.11bd | `ui/config/OtherConfigFragment.kt:55` | [x] 已核对 | 源码核验 | 2026-08-30 | design E4；7.11al 镜像 |
| 7.11be | `ui/book/cache/CacheScreen.kt` | [ ] 待真机回归（纯 Compose 已核；缺真机回归报告） | 真机 L2（l2_verify_compose_cache.py） | 2026-08-30 登记 | design B10；registry 7.11ai 销项=B0 |
| 7.11bf | `ui/rss/subscription/RuleSubScreen.kt` | [ ] 待收尾核对（桥接已有，回执归 B4） | 源码核验+真机 | 2026-08-30 登记 | design D8 |

### B2 样板冻结回执（✅回，8 项；冻结=验收回执而非重写，AD-06）

| 任务编号 | 文件（现状） | 现状 | 所属组件 | 核验方式 | 备注 |
|---------|------------|------|---------|---------|------|
| 7.11bg | `ui/main/MainActivity.kt` | [ ] 待冻结回执（PillNav 已接线；S1 验收检查点见 design-b1-b2 §2.1） | S1 主框架 | 真机 L2 | design A1 |
| 7.11bh | `ui/main/my/MyFragment.kt` | [ ] 待冻结回执（XML 壳+ProfileScreen3Level） | S1 我的页 | 真机 L2 | design A2 |
| 7.11bi | `ui/main/bookshelf/BookshelfScreen.kt` | [ ] 待冻结回执（Phase3 已过；12 菜单 View 红线登记说明） | S1 书架 | 真机 L2+红线登记 | design A3/A4 |
| 7.11bj | `ui/book/read/ReadBookActivity.kt` | [ ] 待冻结回执（MenuLayer 等浮层 Compose，Phase4；S5 验收 §2.5） | S5 全屏沉浸 | 真机 L2 | design B1 |
| 7.11bk | `ui/book/info/BookInfoActivity.kt`+`BookInfoComposeActivity.kt` | [ ] 待冻结回执（双栈运行时分派，X4 禁回退；分支各过） | S4 详情 | 真机 L2 | design B6 |
| 7.11bl | `ui/book/source/manage/BookSourceScreen.kt` | [ ] 待冻结回执（S2 双轨已接线；验收 §2.2 全表） | S2 管理列表 | 真机 L2 | design C1 |
| 7.11bm | `ui/book/source/edit/BookSourceEditActivity.kt` | [ ] 待接线+冻结回执（5 处 Compose 接线，View 内核保留） | S3 表单编辑 | 真机 L2 | design C2；🔨+✅回 复合 |
| 7.11bn | `ui/video/VideoPlayerActivity.kt`+`VideoFragment.kt`（F6 同族） | [ ] 待冻结回执（顶栏+设置面板 Compose；手势四件套复用 R3 4.2） | S5 模式 | 真机 L2 | design D9/F6 |

> 回执完成判定：对应 S 样板 §2 检查点全过 + L2 脚本落盘（ai_tests/scripts/l2_verify_compose_{page}.py）+ 截图/logcat 证据归档 + 本表 [ ]→[x]。完成后同步刷新 pages-inventory §0 快照。
## MC-13 my-compose-full 全域登记（2026-09-08，W0~W7 收官）

> 规格：docs/specs/my-compose-full/（spec/tasks/page-tree）。编译 L1 23 轮全过；真机 L2 回归见 7.1（GPU 故障遗留，待补验）。

| 登记项 | 范围 | 现状 | 备注 |
|-------|------|------|------|
| W0 公共闸门 | SubBarDebug 定稿/manageBgAlpha 裁决/Tier×波次 | [x] | 22ac62dd4 |
| W1 安全与基线 | 三页顶栏归一+installGlassTopBar 迁移模式 | [x] | 97daa7a23；共用布局 14 页 XML 保留 |
| W2 地基与样板 | RssSearch/About/MyFragment+顶栏 3→1 Delta | [x] | 406beb1c0 |
| W3 并存页收编 | TopBarManage/ShareNoteTemplate/AdvancedTitle/NavigationBarManage+孤儿治理 | [x] | 624e773c2→8446de161 |
| W4 纯 View 重写 | CacheManage+CoverCollectionDetail（composeHost+Screen） | [x] | aa840faad/20e9d0a6f |
| W5 管理列表/编辑型穷举 | 17 页（10 零残留登记+7 实施）+批D Book/Rss 清理 | [x] | d01e7d9f8/4bf20fcea |
| W6 浏览型+对话框 | RssArticleInfo 重写+AiImageGallery 迁移+PackageSyncTaskDialog+5 页豁免 | [x] | 41fe61a9d/8a06ef855 |
| W6.5/6.6 顶栏归一收官 | GlassTopAppBar 插槽扩展（barHeight/secondRow/titleFontFamily）+AppManagementTopBar 定义删除 | [x] | 6011f0654 |
| W7.2 Mode.SUB 消亡 | 10 页收官迁移+枚举删除+MainTopBarView 仅剩主 Tab | [x] | 846d9e399 |
| 豁免登记 | WebView/QrCode（相机/WebView 主体不可迁移，顶栏已达标）；VerificationCode/OpenUrlConfirm（透明壳）；DebugTools（纯 Compose）；WaitDialog（全 App 共用加载框） | [x] | 豁免理由见 tasks.md 6.2/6.3 |
| 待真机补验 | 全域 L3 回归+视觉验收（W2/W3 两波一并） | [ ] | 7.1；MEmu GPU 故障待修复 |
