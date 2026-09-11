# 订阅源「自由」布局 — 任务清单（tasks.md）

> 格式：`- [ ] X.Y 任务`｜完成级别标注 `(L1)` 代码完成 / `(L2)` 功能验证 / `(L3)` 场景验证
> 核心任务必须标注**验证标准**

## 实施进度（2026-09-11）

- ✅ **L1 已完成**：全部代码落地并编译通过（`compileAppDebugKotlin` / `compileAppDebugJavaWithJavac` / `compileAppDebugUnitTestKotlin` 均 Successful），`updateLog.md` 已同步，敏感词与临时调试日志检查已过。
- ✅ **单测已通过**：`./gradlew :app:testAppDebugUnitTest --tests "*FreeGridSizeCalculatorTest*"` → **13 tests / 0 failures / 0 errors**（BUILD SUCCESSFUL in 15m43s）。
- ✅ **算法额外预验证**：用 Python 等价移植跑通 11 组断言（含增量==全量、footer 独占行、万级输入、末行不拉伸），比 Kotlin 编译快一个数量级；据此提前修掉「末位宽度补偿导致末行拉伸变形」的缺陷。
- ⏸ **L2 / L3 受阻**：本机 `adb devices` 为空、无可用 AVD（MEmu 已安装于 `D:\Program Files\Microvirt\MEmu` 但未运行）。真机/模拟器验证（任务 3.2–3.7）**未执行**，需启动模拟器后补做。
- 偏差记录：见 `design.md` 的「实施校准」表（5 处）。

## 1. 准备工作

- [x] 1.1 读 `docs/project-rules/openspec-workflow.md` + `logging-during-refactoring.md` + `version-delivery-sync.md` + `real-device-test-reuse.md`
  - 验证标准：四份规范已读，能复述本次涉及的门禁项（updateLog 编译前更新、真机测试、包名规则、检查清单）
- [x] 1.2 核实既有基础设施方法签名（禁凭印象）
  - 验证标准：Read 确认 `CacheManager.put/getFloat/get`、`RssArticleDao.getImage(origin, link)`、`ImageLoader.loadFile(context, path)`、`OkHttpModelLoader.sourceOriginOption`、`isDataUrl()` 扩展、`RecyclerAdapter.TYPE_FOOTER_VIEW/getHeaderCount/getFooterCount/getItemViewType`、`BaseRssArticlesAdapter.loadArticleImage` 签名与 `itemView.tag` 机制
  - 验证标准：确认 `help/rss/` **不存在**、`help/image/` 存在（决定新文件归属）；确认 `app/src/test/java/io/legado/app/` 单测目录结构
- [ ] 1.3 记录改动前 0–4 五种布局的真机截图（回归基线）
  - 验证标准：5 张截图存至 `output/` 临时目录（不入库），供 L3 回归对比
- [x] 1.4 确认当前 `AppDatabase.version`（只读，本次不迁移）
  - 验证标准：Read `AppDatabase.kt` 记录实际值为基线（**不在文档中硬编码快照**）

## 2. 核心实现

### 2.1 尺寸供给层

- [x] 2.1.1 新增 `help/image/RssImageRatioStore`（与既有 `ImageUrlCache.kt` 同族）：内存 LruCache + 源级中位数样本 + `peek()` / `peekAll()` 纯同步接口
  - 验证标准 (L1)：文件存在且包路径为 `io.legado.app.help.image`；`peek()` 全路径无 I/O（Code Review 确认无 `CacheManager.getFloat`、无 `runBlocking`、无解码）；ratio 入缓存前钳制到 `FreeGridSizeCalculator.MIN_RATIO..MAX_RATIO`
- [x] 2.1.2 实现 `prefetch(origin, articles, from, count)`：去重上界判定 → 磁盘 KV 命中回填 → `getImage` → **注入 `OkHttpModelLoader.sourceOriginOption` 防盗链头** → Glide `loadFile` → `inJustDecodeBounds` → 写内存 + 磁盘；`isDataUrl()` 走 base64 分支不落盘；`Semaphore(4)` 限并发；`img_ar_{url}` 桥接（取倒数）
  - 验证标准 (L2)：① 真机首次加载后重启 App 再进入同一源，ratio 直接命中磁盘（无二次下载）；② 桥接分支对瀑布流已看过的图生效；③ 防盗链源能取到真实尺寸（日志确认非 403 兜底）；④ base64 图源能取到尺寸
  - 验证标准 (L1)：连续调用两次 `prefetch(0, 24)`，第二次返回 0（去重生效）
- [x] 2.1.3 失败与兜底：URL 空 / 下载失败 / 解码失败 / 宽高为 0 → 记 `AppLog.put`（**只记 origin 前 2 字符 + `***`，严禁域名/URL 原文**）→ 落回估算值且**不写缓存**（保证可重试）→ 不影响同批其他条目
  - 验证标准 (L2)：构造一个无图源 + 一个非法 URL 源，列表正常渲染不崩，日志无敏感信息；再次进入该源时仍会重试（未命中缓存）

### 2.2 预计算器

- [x] 2.2.0 新增 `FreeGridSizeCalculatorTest`（`app/src/test/java/io/legado/app/ui/rss/article/free/`，与既有 `app/src/test/java/io/legado/app/...` 结构一致）
  - 验证标准 (L1)：测试类可运行（`./gradlew testAppDebugUnitTest`），初始为空壳待 2.2.1–2.2.5 逐步补断言
- [x] 2.2.1 新增 `FreeGridSizeCalculator`：`buildRects()` 全量算法（贪心装填 + 目标行高逼近 + 回退修正 + 末位宽度补偿）
  - 验证标准 (L1)：纯 Kotlin 无 Android import；单测覆盖 —— 末位补偿后行宽总和严格等于 `availableWidth`；混合比例行高一致
- [x] 2.2.2 约束常量（**权威定义在本类 companion object**，供 `RssImageRatioStore` 与 LayoutManager 共享，避免环形依赖）与死循环保护：`MIN_RATIO` / `MAX_RATIO` / `DEFAULT_RATIO` / `TEXT_BLOCK_HEIGHT`（= 46dp，AD-08 定稿）/ `MAX_ITEMS_PORTRAIT` / `MAX_ITEMS_LANDSCAPE` / `SHRINK_TOLERANCE` / `TARGET_ROW_HEIGHT_RATIO`；`rowCount == 0` break；`usableWidth()` 对 `k <= 0` 或 `sumRatio <= 0` 返回 `Int.MAX_VALUE`。策略 Y 的 `MIN_ROW_HEIGHT` / `MAX_ROW_HEIGHT` 保留定义但标注「当前不启用」
  - 验证标准 (L1)：**每个常量均有注释说明取值依据**；`TEXT_BLOCK_HEIGHT` 与 item 布局实测高度一致；单测构造全 0 ratio 数组与超长数组（10000 项），不 hang、不 OOM，输出长度 == `4 × n`
- [x] 2.2.2b 文字块高度参与方式：`rowHeight = imageHeight + TEXT_BLOCK_HEIGHT`，宽度分配只用 `imageHeight`
  - 验证标准 (L1)：单测断言 —— 含 `T` 时行内宽度和仍严格等于 `availableWidth`；同一行内所有格子的 `imageHeight` 相等（即图片区等高）
- [x] 2.2.3 `viewTypes` 入参处理：footer/header 强制独占整行，不参与图片分行
  - 验证标准 (L1)：单测传入含 footer 的 viewType 序列，断言 footer 行矩形宽度 == `availableWidth`、左右无其他 item
- [x] 2.2.4 `recalcFrom(position)` 增量重算：先回退到该 position 所属行的起点，再重算到末尾
  - 验证标准 (L1)：单测 —— 增量重算结果与全量重算结果**逐字节一致**；传入处于行中间的 position 时结果正确
- [x] 2.2.5 最后一行处理：不足一行时行高取 `min(自然行高, 目标行高)`，左对齐留白，禁止拉伸
  - 验证标准 (L1)：单测断言末行不撑满且各行 item 高度一致

### 2.3 LayoutManager

- [x] 2.3.1 新增 `RssFreeGridLayoutManager` 骨架：`generateDefaultLayoutParams`、`canScrollVertically`、`computeVerticalScrollOffset/Extent/Range`
  - 验证标准 (L1)：编译通过；滚动条比例正常（真机肉眼）
- [x] 2.3.2 `onLayoutChildren`：首次布局前按需 `buildRects()`（`rectsValid` 判定）；按 Rect 表填充可见区间、`detachAndScrapAttachedViews` 后重新摆放；`itemCount == 0` 与仅有 footer 时安全返回（B1）；宽度变化检测（`availableWidth != lastAvailableWidth` → 记录 `anchorPosition` → 全量重算 → `scrollToPosition(anchor)`）
  - 验证标准 (L2)：真机首次渲染行宽精确撑满；旋转屏幕后布局自适应且首可见位置不丢；空列表不崩
  - 验证标准 (L1)：`onLayoutChildren` 中无 `buildRects()` 无条件调用（Code Review 确认走 `rectsValid` 短路）
- [x] 2.3.3 `scrollVerticallyBy` + `onLayoutChildren` 回收：按可见 Rect 区间填充/回收，`fill()` 上下各留 1 屏缓冲
  - 验证标准 (L2)：真机滑到第 500 项流畅无白屏；`RecyclerView.dump()` 确认 attached ChildCount 稳定在可见数附近（无泄漏增长）
- [x] 2.3.4 滚动位置接口：`findFirstVisibleItemPosition` / `findLastVisibleItemPosition` / `scrollToPosition` / `onSaveInstanceState` + `onRestoreInstanceState`
  - 验证标准 (L3)：从视频播放器返回、从图库返回，均滚动到原文章所在位置（两条链路分别验证）
- [x] 2.3.5 脏标记机制：`onRatiosUpdated(affectedFrom)` + `consumeDirtyIfSafe()`（挂在 `onScrolled` 末尾）
  - 验证标准 (L2)：真机滚动过程中观察 —— 可见区内无行高突变；滚出后重排正确

### 2.4 Adapter 与布局资源

- [x] 2.4.0 **裁决前置已完成**（2026-09-11，用户裁决）：AD-08 定为**方案 B（图下固定文字块，`TEXT_BLOCK_HEIGHT = 46sp`，权威值在 `dimens.xml`）**；AD-09 定为**策略 X（ratio 钳制 `[0.4, 3.0]` + `CENTER_CROP` 裁边）**
  - 验证标准：两项裁决已回写 design.md 的 AD Status（Proposed → Accepted）与 spec.md R6/R8
- [x] 2.4.1 新增 `item_rss_article_free.xml`：动态高度容器沿用瀑布流的 `CardView(cardCornerRadius=12dp, clipToOutline, cardElevation=0dp)`；内含图片区 `ImageView(centerCrop)` + **图下文字块（标题 `maxLines=1` + 时间 `maxLines=1`，固定内边距，高 = `@dimen/rss_free_text_block_height` = 46sp）** + 已读遮罩 View + 视频角标 ImageView
  - 验证标准 (L1)：圆角与 `item_rss_article_3.xml` 口径一致（12dp）；无硬编码颜色（遮罩色走 `getCompatColor` 或资源引用）；ImageView 有 `contentDescription`；**文字块实测高度 == `rss_free_text_block_height`（46sp）**，用长标题/短标题两条数据真机测量均一致
- [x] 2.4.2 新增 `RssArticlesAdapter5`：绑定图片（复用 `BaseRssArticlesAdapter.loadArticleImage`，`hideWhenBlank = false` 保证缺图不塌陷）、标题/时间文字、已读遮罩、视频角标、payload `"read"`/`"title"` 局部刷新
  - 验证标准 (L2)：真机已读/未读视觉区分正确；标题/时间正常显示且不撑高格子（长标题必须 `ellipsize=end` 截断而非换行）；点击路由与 0–4 布局完全一致（图片源 → 图库，视频源 → 播放器，网页源 → 阅读页）
- [x] 2.4.3 关键实现点：ImageView 尺寸由 LM 提供的矩形决定，**不写 `MATCH_PARENT`**；`holder.itemView.tag = item.link` 防复用错位
  - 验证标准 (L2)：快速滑动 20 屏后，抽检 10 个 item 的图片与标题匹配（无错位图）

### 2.5 接入 Fragment 与枚举

- [x] 2.5.1 `arrays.xml`：`layout_type` 追加「自由」（**不新增 string 资源**，沿用既有 5 项单文件中文口径）
  - 验证标准 (L1)：编辑页下拉框出现第 6 项；`RssSourceEditActivity` 的 `!in 0..<lyType.count` 校验自动覆盖 5；`strings.xml` / `values-zh` 无新增 diff
- [x] 2.5.2 `RssSortViewModel.switchLayout()`：`< 4` → `< 5`
  - 验证标准 (L2)：连点「切换布局」6 次（0→1→2→3→4→5→0）循环正确、无崩溃
- [x] 2.5.3 `RssArticlesFragment`：adapter `when` 增 `5 ->`；layoutManager `when` 增 5 分支（自由 LM + `setPadding(4,0,4,0)` + `itemAnimator = null`）；`isGridLayout` 改 `== 2 || == 5`
  - 验证标准 (L1)：0–4 分支代码**零改动**（`git diff` 确认仅新增行）；编译通过
- [x] 2.5.4 `initData()` 首屏 gating：仅 `articleStyle == 5` 且首次加载时，把预取任务挂到 `viewLifecycleOwner.lifecycleScope`（独立 Job，**不可被 timeout 包裹**），再 `withTimeoutOrNull(FIRST_SCREEN_TIMEOUT_MS) { job.join() }` 只等待不取消，随后 `setItems`
  - 验证标准 (L2)：冷启动进入自由布局首屏无可见跳动；断网场景首屏 800ms 内出现（走估算值）；**超时后剩余条目仍能被预取完成并触发脏标记补正**（真机验证：断点或日志确认 timeout 后 prefetch 继续执行）
- [x] 2.5.5 滚动预取窗口（`lastVisibleItemPosition + PREFETCH_AHEAD` 越界才触发，依赖 `prefetchedUpperBound` 去重）+ 预加载触发分支（`layoutManager is RssFreeGridLayoutManager` 时用 `findLastVisibleItemPosition` 参与 `isPreload` 判定）
  - 验证标准 (L2)：预加载源（`preload=true`）在自由布局下自动加载下一页；滚动到接近底部触发预取；连续滚动不产生重复预取（日志确认）
- [x] 2.5.6 `onDestroyView` / `onPause` 取消预取协程与脏标记任务，避免泄漏
  - 验证标准 (L2)：LeakCanary 或反复进出页面 20 次，无 `RssArticle`/`Context` 泄漏告警

## 3. 验证测试

- [x] 3.1 编译与单测：`./gradlew :app:compileAppDebugKotlin`（flavor 仅 `app`，任务带 `App` 前缀）+ `./gradlew testAppDebugUnitTest`
  - 验证标准 (L1)：编译零错误；单测全绿（行宽严格撑满、增量==全量、footer 独占行、极端值不 hang、末行左对齐）
- [x] 3.2 打包（测试包）：`./gradlew assembleAppDebug` 成功；产物 `output/apk/test/legado_miss_app_3.26.091117.apk`（68.7 MB）
  - 验证标准 (L1)：✅ 通过 —— libcronet(arm64) 已打包（`lib/arm64-v8a/libcronet.151.0.7922.47.so`）；dex 中已确认包含 `FreeGridSizeCalculator` / `RssFreeGridLayoutManager` / `RssImageRatioStore` / `RssArticlesAdapter5` 四个新类与两个新资源
  - 未完成：**安装到设备**（`adb devices` 为空，无可用设备）
- [ ] 3.3 L2 真机功能验证：自由布局渲染 / 行宽 0px 误差 / 无变形 / footer 独占行 / 滚动流畅 / 抗抖动（首屏无跳动、滚动中无行高突变）
  - 验证标准 (L2)：design.md「验证方式」表中两条 L2 行逐项截图 + 目视确认
- [ ] 3.4 L3 边界场景：旋转分屏 / 空列表 / 全无图源 / 极端比例源 / 防盗链源 / base64 图源 / 快速切标签 / 播放器与图库返回位置记忆
  - 验证标准 (L3)：spec.md 边界条件 B1–B15 逐条走查并记录结论
- [ ] 3.5 L3 回归：0–4 五种布局与 1.3 基线截图对比无差异
  - 验证标准 (L3)：5 张对比图一致（允许 ±2px 布局误差，不允许行为差异）
- [ ] 3.6 AI 端到端测试：`ai_tests\venv\Scripts\python.exe ai_tests/run_e2e.py --tc all`
  - 验证标准 (L3)：全量用例通过，无新增失败项
- [ ] 3.7 真机问题记录至 `docs/` 对应 issues-found 文件
  - 验证标准：本次发现的所有真机问题均已记录（含复现步骤与结论）

## 4. 文档收尾

- [x] 4.1 `app/src/main/assets/updateLog.md`：基于 `git diff` 逐文件审计，面向用户语言追加条目（追加在 `## cronet版本:` 之后、已有条目之前）
  - 验证标准：变更文件与日志条目一一对应，无漏项、无文字合并旧条目
- [x] 4.2 敏感词扫描 + `android.util.Log.d|e` 残留检查
  - 验证标准：输出无域名/URL/源名/成人词汇；无临时调试日志残留（`AppLog.put` 正式诊断日志保留）
- [x] 4.3 文档同步声明式映射自查
  - 验证标准：新增 4 个文件 → 若有接口文档需同步；`docs/INDEX.md` 状态更新；`docs/project-flow/task-navigation.md` 若列了 RSS 布局锚点则补充
- [ ] 4.4 沉淀：若实现中形成可复用结论（如布局算法调参经验），写入 `.trae/memory/ai_memory_main.md` 或转为 Skill
  - 验证标准：有则写入，无则在本任务标注「无可沉淀项」

## 5. 归档

- [ ] 5.1 README 状态置「已完成」；tasks.md 全部 `[x]`
- [ ] 5.2 `docs/specs/rss-free-layout/` → `docs/specs/archive/YYYY-MM-DD-rss-free-layout/`
- [ ] 5.3 Delta Spec 合并进 `rss-classic-layout-align` 主规范（布局枚举扩至 6 种）
- [ ] 5.4 `docs/INDEX.md` 条目从「进行中」移至「已完成」
- [ ] 5.5 清理临时文件与调试代码
  - 验证标准：`temp/` 无本次新增测试脚本；无调试日志残留
