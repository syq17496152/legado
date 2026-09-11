# 前端 UI 规范（archive 迁移后）

> 适用范围：所有涉及 UI 的新功能开发、页面迁移、样式统一、主题接入。
> 定位：在"迁移学习 archive 前端 UI"接近尾声后（2026-08-24，bugfix-ui-20260824 ⑨）沉淀的一套统一前端规范，作为新开发/改造的**强制基线**。
> 关联规范：
> - `docs/specs/ui-redesign-m3/ui-standards.md`（历史 Compose 化工程详规：设计基石/骨架六类/组件精确真值表/检查清单/KPI）——自研增量 Compose 化阶段的产物，**归档为历史参考**，仅作组件规格真值溯源；本项目 UI 改造以**本文件（frontend-ui-standards.md）为强制基线**。
> - `docs/project-flow/ui-standards/`（archive 迁移后的源核验参考文档：components/color/间距/骨架/dialog/迁移登记）
> - `docs/specs/bugfix-ui-20260824/design.md`（本批 UI 修复设计）。
> 涉及 Compose 编写/审查/迁移时，**动手前必须先读 skill：`compose-ui-engineering`**（Legado Compose 专项）。

---

## 1. 设计 Token（全局基线）

### 1.1 圆角 Token（Compose：`AppShapes`）
收敛来源 `app/src/main/java/io/legado/app/ui/widget/components/AppShapes.kt`，**新代码一律引用本 token，禁止再硬编码 `RoundedCornerShape(N.dp)`**。

| Token | 值 | 用途 |
|------|----|------|
| `AppShapes.Card` | 18dp | 卡片容器（SettingsCard/卡片化条目/弹窗大容器） |
| `AppShapes.Button` | 12dp | 按钮（M3 默认圆角） |
| `AppShapes.Search` | 18dp | 搜索框（统一 archive 订阅头部 searchEntry 口径，本批 ② 新增） |
| `AppShapes.SheetTop` | 16dp | 底部弹层顶角（ModalBottomSheet） |
| `AppShapes.IconContainer` | 10dp | 图标容器（MetricTile 图标底/小功能图标） |
| `AppShapes.Chip` | 8dp | 小标签/Chip/缩略小图 |
| `AppShapes.Tiny` | 4dp | 极小块状元素（进度标/点状装饰） |

### 1.2 View 侧圆角（`UiCorner`）
View 世界的圆角统一走 `io.legado.app.lib.theme.UiCorner`（`panelRadius`/`actionRadius`/`scaledDp`/`searchRadius` 等，受 UI 圆角倍率 `uiCornerScale` 控制）。Compose 侧与 View 侧口径对齐：**搜索框两者均 18dp**（本批 ② 已统一）。

### 1.3 字号与间距
- 全局字号刻度基线 `text_14sp`（`res/values/dimens.xml`）；Compose 正文统一 `MaterialTheme.typography.bodyMedium`（14sp，对齐 `text_14sp`），由主题统一管理，禁止页面内散落魔数字号。
- 顶部栏常用高度：`bookshelf_title_select_height` 42dp、`top_bar_regular_action_size`（现代形态动作按钮）、`bookshelf_tag_bar_height` 38dp。

#### 1.3.1 页面级 spacing token（`AppPageSpacing`，B2 冻结 2026-08-30 设计/2026-09-01 落地）

> ⚠️ **未落地警示（2026-09-11 核实）**：`AppPageSpacing` 目前在 `app/src/main/java` 下 **grep 零命中**，`AppUiTokens.kt` 中实际只存在 `AppListSpacing` 与 `AppDialogSize`。
> 本节为**设计冻结的规范**而非已实现代码：新页面**暂不得引用** `AppPageSpacing`（会编译失败）。当前可用替代 = `AppListSpacing`（Compact 6 / Normal 8 / Section 12dp）+ `AppDialogSize`。
> 落地前引用本节取值属于违规；落地后需回填本节并移除本警示。

- 定义位置：`io.legado.app.ui.widget.compose.AppUiTokens.kt#AppPageSpacing`，全部取值落在 **4dp grid 整格**。
- 存量 `AppListSpacing`（6/8/12）保留不动：6dp 为**登记豁免半格，仅限列表场景继续使用，禁止新代码扩散**。
- Token 清单（取值冻结，修改需走检查点审查，治理级别等同 `AppShapes`）：

| Token | 取值 | 用途 | 样板落点 |
|---|---|---|---|
| `PageHorizontal` | 16dp | 页面左右安全边距（对齐 M3 常规） | S1~S6 全部页面根容器 |
| `PageTop` | 8dp | 顶栏下内容起始间距 | S2/S3/S4 |
| `SectionGap` | 16dp | 区块与区块之间（表单分组间） | S3 分组、S4 信息区 |
| `CardGap` | 12dp | 卡片与卡片之间 | S2 网格、S4 卡片列 |
| `ItemGapInline` | 8dp | 行内元素间（图标-文字） | 全部 |
| `ListBottom` | 24dp | 滚动列表尾部留白（无底栏页） | S2/S3 |
| `NavBridgeBottom` | 88dp | 列表尾部 FAB+导航桥接避让 | S1 书架、S2 |

- 落地规则：①B2 起新迁移页面 spacing 一律引用 `AppPageSpacing`，禁止页面内 `16.dp` 等魔数（`Modifier.padding` 字面量 grep 纳入 B5 巡检项）；②豁免登记：阅读器正文内核（红线）、WebView 页内样式、第三方 LyricViewX 不适用本 token。

### 1.4 颜色与主题
- **三套主题概念分工（勿混）**：`LegadoTheme`（Compose 主题）/ `ThemeSpec`（可配置主题规格）/ `TopBarConfig`（顶栏管理专属配置）。
- 满足用户的"顶栏管理设色→主界面所有头部生效"，遵循本批 ③ 的 **局部读配色** 范式：`MainTopBarView` 天然读 `TopBarConfig`；传统 `TitleBar` 如需读顶栏配色，置 `app:topBarColorManaged="true"` 并实现 `refreshTopBarAppearance()`（影响面收窄到主界面头部，保留内联搜索/动态菜单）。
- 搜索框底色统一走 **`ThemeUiPalette.searchFieldBackgroundColor`**（`themeSearchFieldBackgroundColor` 自定义 key → `background_menu` 兜底）+ alpha（日 0.18/夜 0.42）+ 1dp 描边，Compose 侧与 View 侧 `TopBarSearchStyle.surfaceColor()` 同源对齐（topbar-search-entry-align v3，2026-08-28 修订；**旧条款"Compose 用 surfaceVariant"已废止**——M3 派生色违反 `ui-standards/color.md` §五禁令，禁止回引）。

---

## 2. 页面骨架（自 archive 迁移后的统一分型）

| 分型 | 骨架 | 说明 |
|------|------|------|
| S1 主框架 | `PillNavigationBar` 底部导航 | 书签/发现/订阅/我的 等 Tab 主框架 |
| S2 列表管理页 | `GlassTopAppBar` + `SettingsSearchBar` | 书源管理/订阅管理/自动任务等（见 `AppManagementScaffold`） |
| S3 表单编辑页 | 分区卡片表单 | 编辑类页面 |
| S4 详情页 | 现代 Compose 详情 | 书籍详情等 |
| S5 阅读器 | **View 内核，红线** | 阅读器保留 View 实现，禁止无评估迁移 Compose |
| S6 弹层 | `ComposeDialog` 家族 | 帮助/日志/编辑/单选/确认 等全部 Compose 化 |

---

## 3. 组件六族与选用规则

| 族 | 组件 | 选用要点 |
|----|------|---------|
| 顶部栏 | `GlassTopAppBar`（**子页单源**）/ `MainTopBarView`（仅主 Tab）/ `TitleBar`（仅"我的/发现经典"managed 遗留） | **2026-09-08 顶栏 3→1 归一收官（my-compose-full W6.5/6.6/W7.2）**：子页/设置族/管理族全部 `GlassTopAppBar` 单源（`AppManagementTopBar` 定义已删除，管理族经 AppManagementScaffold 委托）；`MainTopBarView` 仅主界面 Tab；`TitleBar` managed 分支取色已收编 `resolvePageBarColorWithAlpha`（bugfix-0908f），新页面禁止使用。**自绘顶栏分支必须包 `CompositionLocalProvider(LocalContentColor provides contentColor)`，图标默认 tint 继承 `LocalContentColor.current` 禁止钉主题色，同栏图标统一 20dp**（bugfix-0908f 铁律，违者图标黑色不随日夜主题）。详见 `docs/project-flow/ui-standards/architecture.md` §三.1 |
| 搜索框 | `SettingsSearchBar` / `TopBarSearchStyle` | **统一 18dp 圆角 + palette 槽位取色**（§1.4，禁止 surfaceVariant）。Compose 用 `SettingsSearchBar`（40dp + `ThemeUiPalette.searchFieldBackgroundColor` + alpha/描边 + `AppShapes.Search`）；View 搜索框背景对齐 `TopBarSearchStyle`/`bg_searchview`(18dp)，不要再出现 35dp 全胶囊等发散圆角 |
| 卡片 | `LegadoMiuixCard` / `SettingsCard` | 卡片化条目/面板统一 18dp 圆角 |
| 列表项 | 列表/单列/双列/三列/瀑布 | 封面图**四角圆弧统一 `FilletImageView`(12dp)**；瀑布流用 `CardView` + `android:clipToOutline="true"`（引用 `compose-ui-engineering` 相关段落） |
| 菜单 | `ModernActionPopup` | 右上角三点等弹出菜单 |
| 弹窗 | `ComposeDialog` 家族（`AppDialogFrame`/`ConfirmDialog`/`AppEditDialog`/`SingleChoiceDialog`/`ComposeChoiceListDialog`/`GroupManageComposeDialog` 等） | 帮助/日志/编辑/单选/确认等一律 Compose 化。**已收官（2026-09-08）**：BaseDialogFragment 残留=0、alert{} DSL 已收敛；弹框根节点必须显式背景（`rememberAppDialogStyle().surface`），dialogAlpha<100 时 `ComposeDialogFragment` 自动 dim 补偿（bugfix-0908 T4） |

### 3.1 主 Tab 头部搜索入口形态（topbar-search-entry-align，2026-08-28 新增）

书架 / 发现 / 订阅 / 我的 四主 Tab 头部搜索入口**统一形态 = 标题区（titleSelect）+ 搜索按钮（searchButton，点击打开新搜索页）**：

1. **禁止** searchEntry 胶囊式"伪输入框"（视觉像输入框实际点击跳页，误导用户）——宿主一律 `setSearchEntryVisible(false)`。
2. **禁止** 就地展开搜索框过滤（对齐"点搜索按钮 → 新页面"交互）。
3. **互斥关系**：regular 顶栏风格下 `searchEntry` 与 `titleSelect` 互斥（`MainTopBarView.applyRegularStyle`）——关胶囊后 titleSelect（标题+下拉箭头）自动回归，点击弹源选择菜单/弹窗（各宿主既有绑定）。
4. 各页搜索行为：书架/发现 → `SearchActivity`（发现带当前源 searchScope）；订阅 → `RssSearchActivity`（按分组/类型 buildSearchScope）；我的 → `SettingsSearchActivity`。
5. `MainTopBarView` 组件本体保留 searchEntry 能力（不删除），仅宿主侧关闭。

---

## 4. View 与 Compose 混用红线

1. **阅读器内核（S5）= View，禁止迁移**。
2. **WebView 操作必须 UI 线程**：`destroy/setLayoutParams` 等一系列 WebView 调用必须在主线程（`shouldInterceptRequest` 在工作线程调 `destroy` 会抛异常）。
3. `SettingsSearchBar`（Compose）与 `view_search.xml`（View）并存场景：改样式须两端同步口径（本批 ② 已统一 18dp），不能用一边改一边漏。
4. Compose 页面内嵌 `AndroidView`/`View` 时注意 z 序与触摸事件拦截。
5. **Compose 列表状态禁止原地修改后回流**（2026-08-30 新增，铁证：高亮规则开关不刷新）：列表数据对象必须以 `copy(...)` 创建**新实例**再交给 ViewModel/State 更新，**禁止**先改对象字段（`item.enabled = x`）再把同一实例传回列表渲染链路。原因：项目 Kotlin 2.0.20+ 强跳过模式默认开启，unstable 类型参数按**引用相等**比较，同实例回流 → 行 Composable 被跳过重组 → UI 显示过期状态。先例：`DictRuleActivity`/`TxtTocRuleActivity`/`ReplaceRuleActivity` 等全部 `copy` 模式。

---

## 5. 新页面/改造检查清单（门禁）

动手前逐项确认，防止改 A 破 B（对照本批 ⑨ 沉淀）：

- [ ] 圆角统一走 `AppShapes`/`UiCorner`，无散落 `RoundedCornerShape(N.dp)` 魔数
- [ ] 搜索框按 §3 搜索框规范（18dp/浅底/高度对齐），View/Compose 两端一致
- [ ] 主界面头部是否需读顶栏配置 → `MainTopBarView` 或 `TitleBar`+`topBarColorManaged`
- [ ] 列表项封面图圆角：`FilletImageView`(12dp) 或 `CardView`+`clipToOutline`
- [ ] 弹层用 `ComposeDialog` 家族，不新建 View 弹框
- [ ] 样式中是否用了 `TopBarConfig`/`ThemeSpec` 而非硬编码 `MaterialTheme` 取色
- [ ] Compose 代码：状态管理/重组/Modifier 符合 `compose-ui-engineering` skill
- [ ] **源码核验已做**（skill 必读源码段）：设计 Token 源码 + 选中组件封装函数体 + 相邻实现已核对，未凭函数名/文档猜测行为
- [ ] **扫描验证已做**（skill 交付纪律）：新增 `Color(/.dp/.sp/RoundedCornerShape(` 与基础布局 import 已逐项对照 Token 表与组件目录，命中数与处置已记入实施回执
- [ ] Compose 列表开关/勾选：数据对象用 `copy(...)` 新实例更新，**无原地修改字段后回流**（§4 红线 5，强跳过引用比较会吞重组）
- [ ] 改动后按 AGENTS.md「AI 自动端到端测试」真机/模拟器验证，禁止只改不测

---

## 6. 已知坑速查

- `AppShapes.Button`(按钮 12dp) ≠ `AppShapes.Search`(搜索框 18dp)：做搜索 UI 别误用按钮 token。
- `bg_searchview.xml` 曾有 35dp 全胶囊形，已统一 18dp；新增 View 搜索框勿复制旧值。
- 顶栏"全面迁移 MainTopBarView"会破坏 `view_search` 内联过滤 / `menu_group` 动态菜单（ADR-01 结论），遇主界面经典头优先"局部读配色"。
- Compose 搜索框默认高可能 72dp（M3 TextField+padding），缩至 40dp 输入区（对齐 `SettingsSearchBar`）。
- **强跳过吞重组陷阱**（2026-08-30 沉淀）：Kotlin 2.0.20+ Compose 编译器强跳过模式默认开启——含 unstable 参数（var 字段类）的 Composable 变为可跳过，unstable 参数按**引用相等**比较。表现为：原地修改数据对象后列表"看起来没反应"，退出重进才更新（重新 load 出新实例）。修复一律用 `copy()`，禁止 key/contentType 层 workaround。铁证案例：`HighlightRuleActivity` 复选框切换不刷新。
- **removeView 静默 no-op**（2026-09-08 bugfix-0908 T1 实锤）：Compose 化替换旧 View 时，`binding.titleBar` 等视图与内容区可能**不同父容器**（根 LinearLayout vs 中间 FrameLayout），对错误 parent 调 removeView 不报错但无效 → 旧搜索框/顶栏残留（替换净化页双搜索框）。处理：removeView 后必须 `isVisible` 断言或直接 GONE。
- **ComposeView 容器替换 addView 越界**（2026-09-08 bugfix-0908f T3 实锤：摘录分享模板启动闪退 index=3 count=1）：批量 removeView 后原 `indexOfChild` 索引失效，`addView(view, index)` 必须写 `index.coerceAtMost(container.childCount)`。同批复制模板时逐页核对（W3.3 曾漏改 1/7 页）。
- **自绘顶栏分支内容色**（2026-09-08 bugfix-0908f T2 实锤：书源管理返回/编辑书源图标黑）：自绘 Column/Row 顶栏漏包 `CompositionLocalProvider(LocalContentColor provides contentColor)` 时图标回落主题 onSurface（黑），且不随日夜主题。M3 TopAppBar 自带三键 contentColor，自绘分支必须手动等价提供。