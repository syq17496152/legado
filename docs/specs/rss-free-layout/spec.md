# 订阅源「自由」布局 — 规格（spec.md / Delta Spec）

> 存量迭代：订阅源文章列表布局枚举 `RssSource.articleStyle` 已有 0–4 五种样式，本 spec 只描述「相对当前规范新增/修改了什么」。

## Intent

为订阅源文章列表新增 `articleStyle = 5`「自由」样式，实现等行高流式自由网格（Justified Photo Grid）：
一行内所有图片高度相同、宽度按各自原始宽高比分配、整行精确撑满 RecyclerView 可用宽度，图片不变形。

## Scope

### 做什么

1. 新增布局枚举值 `articleStyle = 5`，文案「自由」，接入编辑页 Spinner 与列表页「切换布局」循环。
2. 新增自定义 `RssFreeGridLayoutManager`（含 Rect 预计算器 `FreeGridSizeCalculator`）。
3. 新增尺寸供给层 `RssImageRatioStore`：`origin@link → ratio` 三级缓存 + 异步批量预取。
4. 新增 `RssArticlesAdapter5` + `item_rss_article_free.xml`（纯图 item，已读遮罩 + 视频/多图角标）。
5. `RssArticlesFragment` 接入：LayoutManager 装配、首屏 gating、滚动预取、预加载触发分支。

### 不做什么

1. **不改 Room schema**：不给 `RssArticle` 加 `width/height` 字段，不写 DB 迁移（复用 `CacheManager` KV）。
2. **不改动其余 5 种布局的任何行为**（0–4 分支代码零修改，仅在 `when` 中新增 5 分支）。
3. **不引入第三方布局库**（greedo / flexbox / AspectRatioRecycler 全部否决，见 Alternatives）。
4. **不迁移 RSS 列表到 Compose**（现为 View 栈 Fragment + RecyclerView，整页迁移成本远超需求）。
5. **不实现 predictive item animation**（自由布局 `itemAnimator = null`，与瀑布流现状一致）。
6. **不采用「行内文字显隐不一致」方案（方案 C）**：同一行内有的格子带文字、有的不带，行高将无法统一。文字承载已裁决定稿为**方案 B（图下固定文字块）**，见 R6 与 AD-08。
7. 不改动书源/书架的任何布局。

### 现状盘点（决策依据：五种样式的 item 结构，2026-09-11 逐文件读取）

| style | 名称 | item 布局 | 图片尺寸 | 图文关系 | 圆角实现 | 文字规格 |
|-------|------|-----------|---------|---------|---------|---------|
| 0 | 列表 | `item_rss_article.xml` | 固定 110×68dp | **左右**（文左图右） | `FilletImageView` 12dp | 标题 16sp bold ×2 行；时间 12sp italic ×1 行 |
| 1 | 单列 | `item_rss_article_1.xml` | 宽 match（12dp 边距）× 固定高 220dp | 上下 + 8dp 分隔条 | `FilletImageView` 12dp | 标题 15sp bold ×2；时间 11sp ×1 |
| 2 | 双列 | `item_rss_article_2.xml` | 宽 match（4dp padding）× 固定高 272dp | 上下 | `FilletImageView` 12dp | 标题 13sp ×2；时间 11sp ×1 |
| 3 | 瀑布 | `item_rss_article_3.xml` | 宽 = 列宽（固定）× **高 = 列宽 ÷ 原图比例** | 上下 | `CardView` 12dp `clipToOutline` | 标题 13sp bold `maxLines=9`；时间 11sp `maxLines=39` ⚠️ 疑为抄漏，不在本次范围 |
| 4 | 三列 | `item_rss_article_4.xml` | 宽 match（2dp padding）× 固定高 182dp | 上下 | `FilletImageView` 12dp | 标题 13sp ×2；时间 11sp ×1 |

**由盘点得出的三条硬约束**：

1. **五种样式无一种为纯图**，全部带标题 + 时间 → 自由布局若做成纯图，将与列表页整体视觉语言断裂。
2. 1/2/4 为**固定高度**，图片必然被 `centerCrop` 裁切 → 这正是「竖图被压缩/留白」问题的根源。
3. 只有 3（瀑布）按原比例算图高，且因格子高度动态而改用 `CardView` + `clipToOutline` 兜圆角 → 自由布局同样需要动态高度容器，**沿用 CardView 方案**。

### 怎么实现

见 design.md。核心 = 「贪心装填 + 目标行高逼近」分行算法 + 「预计算 Rect 表 + 轻量 LayoutManager」架构 + 「尺寸供给层 + 延迟回填」抗抖动。

### 影响范围

| 文件 | 影响 |
|------|------|
| `app/src/main/res/values/arrays.xml` | `layout_type` 数组新增第 6 项 |
| `app/src/main/java/io/legado/app/ui/rss/article/RssSortViewModel.kt` | `switchLayout()` 上限 `4` → `5` |
| `app/src/main/java/io/legado/app/ui/rss/article/RssArticlesFragment.kt` | adapter/layoutManager `when` 新增 5 分支；`isGridLayout`；首屏 gating；滚动预取；预加载分支 |
| 新增 4 个文件 | LayoutManager / SizeCalculator / RatioStore / Adapter5 |
| 新增 1 个布局 | `item_rss_article_free.xml` |
| `app/src/main/assets/updateLog.md` | 版本交付同步 |
| `docs/INDEX.md` | spec 登记 |

**不受影响（已核实）**：`RssSourceEditActivity`（用 `binding.lyType.count` 动态校验范围，自动适配）、`ReadRss`（路由只看 `type` 不看 `articleStyle`）、`FastScroller`（RSS 列表未使用）、`RecyclerViewAtPager2`（只重写 `dispatchTouchEvent`，不干预布局）、`RecyclerAdapter`（`onAttachedToRecyclerView` 仅对 `GridLayoutManager` 设 spanSizeLookup，自定义 LM 不受影响）。

### 边界条件

| 编号 | 边界 | 要求 |
|------|------|------|
| B1 | 列表为空 / 仅有 footer | 不崩，footer 独占整行正常显示 |
| B2 | 文章无图（`image` 为空/加载失败） | 用占位图按估算 ratio 参与布局，不塌陷、不留空洞 |
| B3 | ratio 未知（首次加载） | 用源级中位数（无样本则 1.33）估算 |
| B4 | ratio 极端（全景 / 长条） | **策略 X（已定稿）**：钳制到 `[0.4, 3.0]`，`CENTER_CROP` 裁边（AD-09）。策略 Y（不钳制 + 独占行留白）已评估未采用，回退路径见 R8 |
| B5 | 最后一行装不满 | 按目标行高左对齐，右侧留白，禁止拉伸 |
| B6 | 屏幕宽度变化（旋转 / 分屏 / 折叠屏） | `availableWidth` 变化 → Rect 表全量重算，滚动位置按 position 锚点恢复 |
| B7 | 分页加载追加数据 | 仅从「最后一个完整行的起始 position」增量重算，不全量 |
| B8 | ratio 真值到达且在可见区内 | 不立即重排（脏标记），滚出视口后重算 |
| B9 | 万级数据量 | Rect 表 `IntArray` 存储（4 int/item ≈ 16B），5000 条 ≈ 80KB；预计算 O(n) |
| B10 | 主线程阻塞 | 布局路径**只允许**读内存 LruCache；严禁 `CacheManager.getFloat`（内部 `runBlocking` 查库）与任何解码 |
| B11 | 预取协程与 Fragment 生命周期 | 绑定 `viewLifecycleOwner.lifecycleScope`，`onPause` 取消 |
| B12 | 快速反复切换标签页 | 预取任务可取消 + 幂等，切换不产生错位（沿用现有 `holder.itemView.tag = link` 防复用错位机制） |
| B13 | 滚动过程中反复触发预取 | 按缓存键精确去重（已在内存 / 已在途的条目逐条跳过），不重复查库/下载；不得使用「单调递增已预取上界」这类会在批次取消后永久跳过区间的方案 |
| B14 | 源图需防盗链请求头 | 尺寸探测必须注入与列表展示相同的 `sourceOriginOption` 头，否则永久落回估算值 |
| B15 | `image` 为 base64 `data:` URL | 走解码路径取尺寸，不尝试落盘（既有 `LegadoDataUrlLoader` 同源能力） |

## ADDED Requirements

### Requirement: R1 布局枚举扩展

系统 SHALL 支持 `RssSource.articleStyle = 5` 表示「自由」布局，并在订阅源编辑页样式下拉框与文章列表页「切换布局」循环中可选中。

#### Scenario: 编辑页选择自由布局
- **GIVEN** 用户打开订阅源编辑页
- **WHEN** 样式下拉框展开
- **THEN** 出现第 6 项「自由」，选中并保存后 `articleStyle == 5` 落库

#### Scenario: 切换布局循环覆盖自由布局
- **GIVEN** 当前 `articleStyle == 4`（三列）
- **WHEN** 点击列表页顶栏「切换布局」
- **THEN** `articleStyle` 变为 5 并刷新为自由布局；再点一次回到 0（列表）

#### Scenario: 非法值兜底
- **GIVEN** 导入的订阅源 JSON 中 `articleStyle = 99`
- **WHEN** 打开编辑页
- **THEN** 沿用现有逻辑（`!in 0..<lyType.count` → 置 0），不崩溃

### Requirement: R2 等行高精确填充布局

系统 SHALL 保证同一行内所有 item 的**图片区高度相等**，各 item 宽度与其图片原始宽高比成正比，且行内 item 宽度总和加间距**精确等于**可用宽度（误差 0px）。

> **符号约定（与 design.md 一致，避免歧义）**：
> - `H_img` = 图片区高度（参与宽度分配的唯一高度量）
> - `TEXT_BLOCK_HEIGHT`（简写 `T`）= 文字块固定高 = **46sp**（AD-08 定稿方案 B；权威值定义在 `res/values/dimens.xml` 的 `rss_free_text_block_height`，XML 与算法共用同一资源。回退方案 A 时置 0）
> - `rowHeight = H_img + T` = 格子总高
> - `TARGET_ROW_HEIGHT` = 目标图片区高度（**不含** `T`），即 `TARGET_ROW_HEIGHT_RATIO × 屏幕宽`
>
> 「行高统一」指 `H_img` 统一；因 `T` 为常量，故 `rowHeight` 亦统一。

#### Scenario: 三张混合比例图同行
- **GIVEN** 一行装入 ratio 分别为 1.5 / 0.75 / 1.0 的三张图，可用宽度 `W`，间距 `S`
- **WHEN** 布局完成
- **THEN** 图片区高度相同为 `H_img = (W - 2S) / (1.5+0.75+1.0)`，宽度分别约 `1.5·H_img / 0.75·H_img / 1.0·H_img`，且 `w1+w2+w3+2S == W` 严格成立（末位 item 吸收舍入误差）；格子总高 = `H_img + T`

#### Scenario: 单张超宽图独占一行
- **GIVEN** 某图 ratio = 5.0
- **WHEN** 装填该图（采用 AD-09 裁决的策略 X）
- **THEN** ratio 钳制为 3.0，该图独占一行，`H_img = availableWidth / 3.0`，并被 `CENTER_CROP` 裁掉左右多余部分。**策略 Y 变体（不钳制 + 留白）已评估未采用，说明见 R8**

#### Scenario: 最后一行不足
- **GIVEN** 剩余 1 张 ratio = 1.0 的图
- **WHEN** 布局最后一行
- **THEN** `H_img` 取 `TARGET_ROW_HEIGHT`（不拉伸至撑满），图宽 `= H_img × 1.0`，右侧留白；格子总高 = `H_img + T`

### Requirement: R3 换行阈值与行内张数约束

系统 SHALL 通过「目标行高逼近」决定换行时机，并限制单行张数上限，避免一行图片过密或行高过矮。

#### Scenario: 加图后图片区高度偏离目标更远则收行
- **GIVEN** 已装 2 张，当前 `H_img = 220px`；`TARGET_ROW_HEIGHT = 200px`；加入第 3 张后 `H_img` 变为 150px
- **WHEN** 判定是否收行
- **THEN** `|220-200| = 20 < |150-200| = 50` → 不加第 3 张，当前行收于 2 张（判定只用 `H_img`，与 `T` 无关）

#### Scenario: 单行张数封顶
- **GIVEN** 连续多张窄长图（ratio ≈ 0.6），按行高逼近可装 8 张
- **WHEN** 装填
- **THEN** 竖屏封顶 4 张 / 横屏封顶 6 张，超出转下一行

### Requirement: R4 图片尺寸供给层

系统 SHALL 提供 `origin@link → ratio` 的三级缓存（内存 LruCache → `CacheManager` 磁盘 KV → 异步解码回填），布局路径 MUST NOT 执行任何阻塞 I/O 或图片解码。

#### Scenario: 缓存命中
- **GIVEN** 该文章 ratio 已在内存 LruCache
- **WHEN** LayoutManager 预计算 Rect
- **THEN** 同步取值，无 I/O

#### Scenario: 冷启动跨会话命中
- **GIVEN** 上次会话已把 ratio 写入 `CacheManager`（TTL 20 天）
- **WHEN** 本次进入列表，预取协程在 IO 线程运行
- **THEN** 从磁盘 KV 读出并回填内存，无需重新下载解码

#### Scenario: 解码取尺寸
- **GIVEN** ratio 三级全部未命中
- **WHEN** 预取协程执行
- **THEN** 经 `RssArticleDao.getImage` 取 URL → Glide `loadFile` 落盘 → `BitmapFactory.inJustDecodeBounds` 只读文件头得宽高 → 钳制后写内存 + 磁盘

#### Scenario: 防盗链源取尺寸
- **GIVEN** 某源的图片需带 Referer/Origin 头才可访问（既有 Adapter 均通过 `OkHttpModelLoader.sourceOriginOption` 注入）
- **WHEN** 预取协程发起尺寸探测
- **THEN** 请求携带与列表展示完全相同的防盗链头，取到真实尺寸；不得因缺少请求头导致 403 而永久落回估算值

#### Scenario: base64 内联图取尺寸
- **GIVEN** 该文章 `image` 字段是 `data:` 开头的 base64 数据图（部分源存在此形态）
- **WHEN** 预取协程处理该条
- **THEN** 走 base64 解码路径（不落盘），以 `inJustDecodeBounds` 取宽高，与网络图同等待遇

#### Scenario: 解码失败
- **GIVEN** URL 为空 / 下载失败 / 非图片格式 / 解码返回宽高为 0
- **WHEN** 预取协程处理该条
- **THEN** 记 `AppLog.put` 一条（不含域名/URL 原文，只记 origin 前 2 字符 + `***`）；该条落回估算 ratio 且 **不写入缓存**（避免污染后永久无法重试）；其余条目不受影响

#### Scenario: 滚动预取窗口与去重
- **GIVEN** 用户连续滚动，`lastVisiblePosition + 预取前瞻` 反复越过已预取范围
- **WHEN** 触发新一批预取
- **THEN** 按缓存键**精确去重**：已在内存或已在途的条目逐条跳过，不重复查库/下载；同一图不会被并发解析两次
- **AND** 去重不得依赖「单调递增上界」——该方案在批次被取消时会把未完成区间标记为已完成，导致永久跳过

### Requirement: R5 抗抖动策略

系统 SHALL 在 ratio 真值到达后，仅在「不影响用户当前视觉」的前提下重排，禁止可见区域内容跳动。

#### Scenario: 首屏一次成型
- **GIVEN** 首次进入自由布局列表
- **WHEN** 数据流首帧到达
- **THEN** 先对前 24 条执行预取（`withTimeoutOrNull(800ms)`），再 `setItems`；超时则以估算值一次成型，不做二次重排

#### Scenario: 屏幕外修正无感重排
- **GIVEN** position 60 的 ratio 真值到达，当前 lastVisiblePosition = 20
- **WHEN** 收到回填通知
- **THEN** 从 60 起增量重算 Rect 表并 `requestLayout()`，用户无感

#### Scenario: 可见区内修正延迟到滚出后
- **GIVEN** position 8 的 ratio 真值到达且当前可见
- **WHEN** 收到回填通知
- **THEN** 仅标记 `pendingDirtyFrom = 8`，不重排；待该行滚出视口上方后再重算

### Requirement: R6 item 呈现规范

自由布局 item SHALL 由「图片区 + 图下固定文字区」构成（AD-08 已裁决方案 B）：图片区 `scaleType = CENTER_CROP`，下方 1 行标题 + 1 行时间，文字块高度 MUST 为编译期常量。已读态与内容类型标识保留。

#### Scenario: 文字块与算法兼容
- **GIVEN** 图下固定文字块，块高 `TEXT_BLOCK_HEIGHT = 46sp`（常量，取自 `dimens.xml`）
- **WHEN** 预计算行高
- **THEN** 算法把 `TARGET_ROW_HEIGHT` 语义理解为**目标图片区高度**，图片区行高统一，最终 `rowHeight = H_img + T`；行内宽度分配只用 `H_img`，因此**行宽仍严格撑满 `availableWidth`**，行高统一性不被文字块破坏

#### Scenario: 文字块高度必须恒定
- **GIVEN** 标题长度差异极大的两条文章
- **WHEN** 两条 item 被布局
- **THEN** 两者文字块高度完全一致（标题 `maxLines=1` + `ellipsize=end`，内边距固定）；**禁止**出现随内容变化的高度，否则行高统一性失效

#### Scenario: 窄格子标题截断
- **GIVEN** 某行格子宽度仅约 80dp（一行 4 张时）
- **WHEN** 标题超出可用宽度
- **THEN** 标题按 `ellipsize=end` 截断，**不改变格子尺寸**，不影响该行其他格子

#### Scenario: 已读态
- **GIVEN** 文章 `read == true`
- **WHEN** item 绑定
- **THEN** 图上叠加半透明遮罩（区分已读/未读），点击后遮罩即时生效（payload `"read"` 局部刷新）

#### Scenario: 视频类文章角标
- **GIVEN** `article.type == 2`
- **WHEN** item 绑定
- **THEN** 右下角显示播放角标

#### Scenario: 点击路由与其余布局完全一致
- **GIVEN** 自由布局下的任意 item
- **WHEN** 用户点击
- **THEN** 走与 0–4 布局**完全相同**的 `callBack.readRss(item)` 路由（图片源 → `ImageGalleryActivity`，视频源 → `VideoPlayerActivity`，网页源 → `ReadRssActivity`），不引入自由布局专属分支

#### Scenario: 无障碍描述
- **GIVEN** 图片 item
- **WHEN** 开启无障碍服务
- **THEN** ImageView 有可读的 `contentDescription`（与既有 item 布局口径一致，取文章标题）

### Requirement: R7 footer 独占整行

系统 SHALL 让 `LoadMoreView`（footer）在自由布局中独占一整行宽度，且不参与图片分行计算。

#### Scenario: 加载更多
- **GIVEN** 列表尾部存在 1 个 footer
- **WHEN** 预计算 Rect 表
- **THEN** footer 单独成行、宽度 = 可用宽度、高度 = 自身测量高度；上滑到底触发 `scrollToBottom()` 正常加载下一页

### Requirement: R8 极端比例图片处理（钳制裁边）

系统 SHALL 将超出常规范围的图片宽高比（全景图 / 长条图）钳制到 `[MIN_RATIO, MAX_RATIO] = [0.4, 3.0]`，超出部分由 `CENTER_CROP` 裁掉，以保证行高恒定与整行撑满。

> **已知代价（AD-09）**：瀑布布局（style 3）不裁图，故自由布局是本列表唯一会裁图的样式。已由用户于 2026-09-11 裁决定稿（策略 X）；策略 Y（保真 + 独占行留白）记录为已评估未采用方案，回退路径保留。

#### Scenario: 全景图（ratio 过大）
- **GIVEN** 某图 ratio = 5.0
- **WHEN** 装填
- **THEN** ratio 钳制为 3.0，该图独占一行，`H_img = availableWidth / 3.0`，左右各裁掉约 40% 宽度

#### Scenario: 长条图（ratio 过小）
- **GIVEN** 某图 ratio = 0.2（高度为宽度的 5 倍）
- **WHEN** 装填
- **THEN** ratio 钳制为 0.4，该图独占一行，`H_img = availableWidth / 0.4`，上下裁掉超出部分
- **AND** 若该 `H_img` 超过一屏高度，仍按此值布局（用户需滚动查看），不做额外限制

#### Scenario: 回退到策略 Y 的触发条件
- **GIVEN** 真机实测发现钳制裁切过于激进（用户反馈内容丢失明显）
- **WHEN** 评估回退
- **THEN** 两种手段按成本排序：① 放宽 `MAX_RATIO` 至 `4.0`（改 1 个常量）；② 启用策略 Y（`FreeGridSizeCalculator` 两处分支 + `MIN/MAX_ROW_HEIGHT`）；**不删除策略 Y 的分支说明**

## MODIFIED Requirements

### Requirement: 布局切换循环上限

#### Scenario: switchLayout 边界
- **WHEN** `RssSortViewModel.switchLayout()` 被调用
- **THEN** 判定条件由 `articleStyle < 4` 改为 `articleStyle < 5`（5 之后回 0）

### Requirement: isGridLayout 语义

#### Scenario: 图缺失时的占位策略
- **WHEN** `articleStyle == 5`
- **THEN** `RssArticlesFragment.isGridLayout` 返回 `true`（原仅 `== 2`），使 `BaseRssArticlesAdapter.loadArticleImage` 走「显示占位图」而非「隐藏 ImageView」——自由布局隐藏 ImageView 会撕裂行

## REMOVED Requirements

无。

## Approach

### Selected Approach

**自定义 `RecyclerView.LayoutManager` + 预计算 Rect 表 + 独立尺寸供给层。**

落地理由：

1. **position 语义不变**是决定性优势。Adapter 仍持有 `List<RssArticle>`，因此以下现有逻辑**零改动**：`ReadRss` 传 `adapter.getItems()` 给播放器、`VideoPlay/ImagePlay.lastPlayedArticleLink` 的 `indexOfFirst → scrollToPosition` 位置记忆、`DiffUtil.ItemCallback<RssArticle>` 差异更新、`getActualItemCount()` 分页判断。
2. **LayoutManager 复杂度被预计算器压平**：`FreeGridSizeCalculator` 一次 O(n) 算出全部 Rect，LayoutManager 只做「按 Rect 填充 + 回收 + 偏移」，无需在布局过程中做增量装填决策。这是 greedo 的核心架构智慧。
3. **零新依赖、零 DB 迁移**，符合项目 Landmines 版本锁定文化与 `database-migration-safety` 规范的风险偏好。
4. 尺寸供给层与布局层解耦，未来若要迁 Compose 或复用到书源图片列表，`RssImageRatioStore` 可直接复用。

### Alternatives Considered

| 方案 | 描述 | 否决理由 |
|------|------|---------|
| **A. 行合并（Row-Packing）**：Adapter item = 一整行，行内自定义 ViewGroup 横排 N 图，外层仍 `LinearLayoutManager` | LM 零风险、滚动/回收/footer 全部免费 | **position 语义被破坏**：`adapter.getItems()` 变成 `List<Row>`，侵入 5 处现有逻辑（播放器列表传参、两处 `indexOfFirst→scrollToPosition` 位置记忆、`DiffUtil.ItemCallback<RssArticle>`、`getActualItemCount` 分页判断）；分页追加时行重排需自行维护；回收粒度退化为整行。**综合改动面反而更大且更易回归** |
| **B. 引入 500px/greedo-layout（MIT，1.6k★）** | 现成 `GreedoLayoutManager` + `SizeCalculatorDelegate` | Maven 托管在 GitHub raw（项目 `GRADLE_USER_HOME=F:\gh`，离线/墙内构建脆弱）；2022 年后停更、Java 实现；**不支持 footer 独占行**（`LoadMoreView` 会被当图片摆进行内）；不支持 ratio 动态回填与脏标记重排；已知 issue「last row not filled」。**改造它的成本 ≈ 自研**。→ 仅移植算法思路并在代码注释标注来源（MIT 兼容） |
| **C. `StaggeredGridLayoutManager` 瀑布流** | 现有 `articleStyle=3` | 列宽固定、高度自由，与需求（行高固定、宽度自由）**方向完全相反**，行内无法撑满 |
| **D. `GridLayoutManager` + `spanSizeLookup` 模拟** | 把宽度量化为 N 等分 span | 只能量化到 1/N 精度，无法精确撑满；且行高统一 → 竖图仍被压缩 |
| **E. Compose 自定义 `Layout` + `LazyColumn`** | 代码更简洁 | RSS 列表现为 View 栈（`VMBaseFragment` + `RecyclerView` + ViewBinding），整页迁移成本远超需求；UI 规范对 View/Compose 混用有红线约束。**保留为未来迁移方案** |
| **F. 一个 `ViewGroup` 装全部图 + 分页** | `onMeasure/onLayout` 一次摆完 | 无视图回收，千级图片必卡顿/OOM |
| **G. Google flexbox-layout（`FlexboxLayoutManager`）** | `flexWrap` + `flexGrow` | flexGrow 按剩余空间线性分配，**不保持宽高比**；且无 per-row 等高保证，仍需自行算宽度 → 等于自研 |
| **H. 文字承载方案 A（图上底部渐变遮罩，T=0）** | 图片占满整格，标题+时间以浮层叠在底部 | 一屏图片数最多，但一行 4 张时格子仅约 80dp 宽，标题只能显示 3–4 字、形同虚设；浅色图上文字可读性差。**已由用户裁决否决**（AD-08） |
| **I. 文字承载方案 C（行内自适应显隐）** | 宽格子显示文字、窄格子隐藏 | 同一行内文字块高度不一致 → 「行高统一」性质失效：强行统一则带文字的格子图片被压扁、比例失衡。**设计死路，明确排除** |
| **J. 极端比例策略 Y（保真 + 独占行留白）** | 不钳制 ratio，极端比例图独占一行并保留完整原图，两侧留白 | 图片零损失，但破坏「整行撑满」这一特性核心不变式，该行观感突兀。**已由用户裁决否决**（AD-09）；回退路径保留在 R8 |

### Drawbacks

| 缺陷 / 风险 | 影响 | 接受理由 | 兜底预案 |
|------------|------|---------|---------|
| 自定义 LayoutManager 需自行实现滚动/回收/状态保存 | 约 350–450 行新代码，是本变更主要风险点 | 有 greedo 架构参考；预计算器把复杂度隔离在纯函数里，可单测 | 分两阶段落地：先跑通静态布局（L1/L2），再补滚动边界（L3）；出现严重回归可通过隐藏 arrays.xml 第 6 项快速下线，不影响 0–4 |
| 首次进入首屏最多多等 800ms | 冷启动首屏稍慢 | 图片列表本就需等图；换来「不跳动」的体感收益更大 | 超时立即以估算值成型；`PREFETCH_FIRST_SCREEN` / 超时阈值定义为常量便于调参 |
| 最后一行留白 | 与「不留无效空白」目标有偏差 | 数学上与「不变形」互斥（见 README 不可能三角） | 已在 spec B5 显式定义为期望行为 |
| 极端比例图被裁边 | 全景/长条图丢失边缘内容（ratio 5:1 钳到 3.0 约失 40% 宽） | 不钳制则单图独占一行且行高仅几十 px，观感更差；且真实 RSS 配图绝大多数落在 0.5–2.0，超出区间属少数 | 钳制区间 `[0.4, 3.0]` 为单点常量，可放宽至 `4.0`；点击进入图库仍可看完整原图。**注意：瀑布布局（style 3）不裁图，此处是自由布局的保真度退步** —— 已由用户于 2026-09-11 裁决定稿（AD-09 策略 X），策略 Y 回退路径保留在 R8 |
| 一屏图片数因文字块减少 | 纵向信息密度下降约 25–30% | 与既有 5 种样式视觉语言一致，避免样式切换时的断裂感（AD-08 方案 B） | `TEXT_BLOCK_HEIGHT` 为单点常量；若后续实测密度不足，可改走方案 A（T=0）或压缩文字块至 36dp |
| 无 item 插入/删除动画 | 视觉过渡略生硬 | 与现有瀑布流一致（`itemAnimator = null`），且预测动画在自定义 LM 下实现成本高 | 后续可选补 `supportsPredictiveItemAnimations` |
| ratio 缓存与 Adapter3 现有 `img_ar_` 缓存键不同源 | 同一图可能被解码两次 | Adapter3 以 imageUrl 为键（布局时拿不到），自由布局必须以 `origin@link` 为键 | 预取时若命中 `img_ar_{url}` 旧缓存则直接换算复用，避免重复解码（design AD-02 已含此桥接） |
