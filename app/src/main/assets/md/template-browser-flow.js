/*
 * template-browser-flow.js —— 模板**浏览器流程**（epub-md-rich-rendering 阶段 4.1 / 4.7 / 4.15）
 *
 * 运行环境：与 `template-runtime.js` 同在沙箱 iframe 内，仅负责「排版结果」：
 * - `paged`：按模板声明的分栏方式切页（`data-reader-flow-pagination="columns"` ⇒ CSS 多栏；
 *   否则**自研回退**：把正文槽位冻结为页框（固定高 + 裁切）并按内容高切页，翻页改 `scrollTop`
 *   ⇒ 不依赖作者是否给正文加高度约束，"复杂内容不断章不错位"由引擎保证）；
 * - `scroll`：滚动模板固定滚动（`data-reader-scroll-viewport` 唯一，滚动容器不参与正文测量）；
 * - `initialize()` 必须在**正文注入之前**调用（页框基准＝此刻的文档高，即页眉/页脚等固定占位）；
 * - 完成后回调 `ReaderTemplateRuntime.onFlowSettled(pageIndex,pageCount,costMs)`，
 *   由 runtime 回发 `stable` 消息（宿主据此重测分页，见 AD-28）。
 *
 * 设计约束（与宿主侧 `ReaderTemplateBridgePolicy` 的白名单/上限一致）：
 * - **不做 DOM 之外的副作用**：无网络、无 storage、无宿主调用；
 * - **有界**：页数与页框高都有下限/上限守卫（`MIN_PAGE_HEIGHT` / `MAX_PAGES`），
 *   作者畸形 DOM 不得让宿主陷入超长分页或除零；
 * - **异常降级**：流程异常时置「单页」并把错误交给 runtime 回发（不白屏）。
 */
(function () {
  'use strict';

  var MAX_COLUMN_RETRY = 3;

  /** 页框可用高的下限：低于此值视为"页框不可用"（避免 0/负高导致除零或整章被裁成 1 页）。 */
  var MIN_PAGE_HEIGHT = 40;

  /** 页数上限（有界：作者畸形 DOM 不得让宿主陷入超长分页）。 */
  var MAX_PAGES = 5000;

  /** 页框高校正的容差（px）：根壳底与视口底的偏差在此内即视为"贴底"，不再校正。 */
  var FRAME_CORRECT_TOL = 2;

  /** 页框高校正的最大迭代数（占位→页框是 1:1 关系 ⇒ 一次即收敛；留一次用于子像素抖动）。 */
  var FRAME_CORRECT_MAX = 2;

  /**
   * 按壳占位的"漂移"阈值（px）：两次量取差超过它 ⇒ 判为"首帧装饰未定型"，
   * 下一帧重结算一次（见 [chromeForShell] / [scheduleResettle]）。取 2px：装饰带（SVG）
   * 未定型时差一整条行高（实测 20px），而子像素抖动在 1px 内。
   */
  var CHROME_DRIFT_TOL = 2;

  /**
   * 该壳**首次**量取占位后的重测延迟（ms）：装饰带（内联 SVG）首帧尚未定型 ⇒ 即使改到 rAF 仍可能
   * 量到偏小值（实测首帧 `71`、定型后 `91`，差一整行）⇒ 首次量取后延迟一小段再重测重结算。
   * 取 150ms：远超一次 SVG 布局定型所需、又远小于可感卡顿阈值；**仅该壳首次量取触发一次**。
   */
  var CHROME_FIRST_MEASURE_DELAY_MS = 150;

  var root = null;
  var config = null;

  // 自研回退分页的**页框基准**：在正文注入**之前**量取（此刻文档高 = 页眉/页脚/内外边距等固定占位）
  var viewportHeight = 0;
  var chromeHeight = 0;
  var slotEmptyHeight = 0;

  /**
   * 正文在**根壳内**的偏移（= 页眉 + 正文上方装饰，不含冻结页框自身的滚动）。
   *
   * 为什么单独维护：C1 首页/续页双结构的**页眉高度不同**（开篇页满幅、续页窄页眉）⇒
   * "滚动位置"不是跨结构可比量。把它换算成**与结构无关**的"正文偏移量"（[bodyOffset]），
   * 换壳时只改 `scrollTop` 的补偿量，正文停留位置保持不变（页码因此不跳变）。
   */
  var bodyOffset = 0;

  /**
   * `bodyOffset` 是否已是**权威值**（由 `goto` 按宿主指令算出）。
   *
   * ⚠️ 为什么需要（2026-10-10 C1 真机实证）：`goto` 先把正文移到目标页，随后 `settle` 会
   * **从滚动位置反解**正文偏移。换壳（`applyPageShell` 重挂 `innerHTML`）之后布局**尚未 reflow**
   * 就开始反解 ⇒ `root.scrollHeight − slot.scrollHeight` 读到未生效的旧布局 ⇒ 反解出 0，
   * 把刚定位好的正文又拉回第一页（症状：翻到续页后停在首屏、页码恒 1/1）。
   * 由 `goto` 置位、`settle` 消费并复位 ⇒ **宿主指令是权威**，只有"用户手指拖动"那次才需要反解。
   */
  var bodyOffsetAuthoritative = false;

  /**
   * 宿主 `goto` 给出的**权威页号**（-1 = 无）。
   *
   * ⚠️ 为什么存页号而不是像素偏移（2026-10-12 真机铁证）：`goto` 那一刻算出的像素偏移用的是
   * "当时已知的页高"，而紧接着的 `settle` 会按占位算出**页高并做一次贴底校正**（见
   * [correctFrameHeight]）⇒ 若沿用 `goto` 的像素偏移，校正前后页高不一致会把正文停在**半页**处
   * （真机实测：目标第 2 页、页高 748，校正到 728 后 `bodyOffset/visible` 落到 1.027 之外的档位）。
   * 存页号 ⇒ 由 `settle` 用**最终页高**折算偏移，两处口径必然一致。
   */
  var authoritativePage = -1;

  /**
   * 宿主 `gotoRatio` 给出的**权威内容比例**（-1 = 无；0..1）。
   *
   * ⚠️ 为什么要有它（2026-10-12 真机铁证，顾问复核后判定必须根治）：模板面是**双壳**结构
   * （首页壳帧高 510 / 续页壳 729）⇒ 两壳页数不同（实测 17 / 12），而"内容比例 → 页号"只拿得到
   * 当前壳的页数 ⇒ 恢复会偏 ≤2 页（保存 10/12 恢复成 12/12，用户可感知"进度不对"）。
   * 按内容比例定位 = `bodyOffset = ratio × (内容高 − 页高)`，**与壳无关** ⇒ 恢复精确。
   */
  var authoritativeRatio = -1;

  /**
   * 流程层记录的"当前结构"（`opening` / `continuation`）。
   *
   * 为什么不直接问运行时：运行时的 `pageShell` 是**权威状态**，但流程层需要它自己的副本，
   * 才能在"同一结构连续换页"时**跳过无谓的 DOM 重建**（每次 `applyPageShell('opening')`
   * 都会重挂 innerHTML ⇒ 正文注入与富渲染全部白做）。
   */
  var flowShell = '';

  /** 最近一次结算结果（供换壳时夹取目标页与复用页高，避免"换壳瞬间用未知量"）。 */
  var pageCount = 1;
  /** 最近一次结算的**可见窗口高**（= 页步长；翻页与页数口径用它）。 */
  var lastPageHeight = 0;
  /**
   * 最近一次结算的**箱高**（页框 border-box 高 = 可用高；换壳时用它把新壳冻结成"铺满"状态）。
   *
   * 为什么与 [lastPageHeight] 分开存：两者语义不同（箱高负责"铺满/不可拖/页脚贴底"，
   * 窗口高负责"整行对齐/不裁字"）⇒ 混用会退回"箱体比视口矮一截、可上下扒拉"的老症状。
   */
  var lastBoxHeight = 0;

  /**
   * 页（可翻阅）区高：**由视口与页眉占位推出**，不读槽位 `clientHeight`。
   *
   * ⚠️ 为什么不能读槽位尺寸（2026-10-10 C1 真机实证）：`applyPageShell` 刚重挂 `innerHTML` 后，
   * 外层文档**尚未完成 reflow** ⇒ 读到的是**上一个结构**的尺寸（实测换壳后仍报旧的 3294，
   * 用它冻结 ⇒ 页框高 = 整段内容高 ⇒ 页数恒 1、翻页失效）。而视口高与页眉占位在 `initialize`
   * 时已量好、且与结构无关，据此推出的页高是稳定的。
   */
  function visibleHeight() {
    var vp = currentViewportHeight() || viewportHeight;
    var slot = bodySlots() ? bodySlots()[0] : null;
    if (root && slot) {
      // ⚠️ 占位必须**按当前壳**取：首页壳与续页壳结构不同（首页常有 hero/封面大块，续页没有）
      // ⇒ 沿用"注入前按首页测的那一份"会让续页**多扣一次首页专属装饰**：页框变小、页脚上移、
      // 页脚与视口底之间留出大片空白（用户实测：「阅读页底部这么大空白」，两张对照截图：首页到底、
      // 第 2 页起留白）。故按壳缓存并随壳切换重算，见 [chromeForShell]。
      // ⚠️ 此处**不加** `slotEmptyHeight`：[chromeForShell] 逐子元素累加时**已排除正文槽**，
      // 槽的 border-box 高就等于页框高；再叠加槽空高会让页框多出这一截、把页脚压到视口底之下
      // （首页同样错位）。`slotEmptyHeight` 只服务于"整体高"旧口径（`chromeHeight`），两口径不可混用。
      return vp - chromeForShell(shellName(), slot);
    }
    return vp - chromeHeight + slotEmptyHeight;
  }

  /**
   * 当前壳的"非正文占位"高（页眉 + 装饰带 + 页脚等）。
   *
   * 口径：**布局事实** = 模板根壳与正文槽的 `getBoundingClientRect` 之差 ——
   * 上占位 = 槽 `rect.top` − 根壳 `rect.top`；下占位 = 根壳 `rect.bottom` − 槽 `rect.bottom`。
   *
   * ⚠️ 为什么不用"逐子元素累加"（2026-10-12 真机铁证）：① `offsetHeight` 是 `HTMLElement` 专有，
   * 对 **SVG 装饰带**恒为 `undefined`；② 子元素 margin / 伪元素 / 折叠边距都不在 `rect.height` 里
   * ⇒ 累加恒偏小（实测偏小 59px：占位 241 竟小于正文槽顶 244，页框反被撑高、页脚压出视口）。
   * rect 之差由浏览器算好，HTML/SVG/边距一并覆盖。按 `shell` 名缓存 ⇒ 同一壳只测一次
   * （读布局属性会强制同步布局，不在翻页热路径反复读）。
   */
  var chromeByShell = {};

  /** 最近一次结算实际生效的占位（诊断自证用；见 [reportPageFrame]）。 */
  var shellChromeNow = 0;

  /** 最近一次占位拆解（上占位 = 页眉/装饰带；下占位 = 页脚/装饰带）——R2 负占位链的取证依据。 */
  var shellChromeTop = NaN;
  var shellChromeBottom = NaN;

  /** 最近一次占位量取的 rect 事实（诊断自证用；"占位不对"时能一眼看出是上/下哪一段错）。 */
  var shellRectDebug = '';
  /**
   * 当前壳的页眉页脚占位（`slot` 视口顶 + 根壳底与槽底之差）。
   *
   * ⚠️ **必须每次都实测**（2026-10-13 真机铁证）：换壳后**首帧**量取时，页脚装饰（内联 SVG 带）
   * 尚未定型 ⇒ 占位偏小整整一条行高（实测续页首帧 `chromeNow=71` vs 后续 `91`，差 20px ⇒
   * 箱高 782 vs 763）⇒ 该壳**首帧比后续页多一行**，且保存/恢复会跨一档页码（用户实测症状：
   * 「每页文字个数的不同，页脚还能自己变动」）。旧实现"同壳只测一次"把这份偏小值缓存下来
   * ⇒ 偏差一直保留到下一次 remeasure（真机对照：*同一续页*首帧 `h=744`、翻到第 2 页后 `716`、
   * **回访第 1 页也变回 716** ⇒ 证明是"首帧量取过早"而不是"该页真的矮"）。
   * ⇒ 改为**每次实测** + 与上次差异 > 2px 时**下一帧重结算一次**（幂等收敛，见 [scheduleResettle]）。
   */
  function chromeForShell(shell, slot) {
    if (!root || !slot) return chromeHeight;
    // ⚠️ **量取前必须先解除本槽的冻结**（2026-10-14 真机铁证，用户报「第 2 页正文下面大量空白」）：
    // 本函数的口径是"槽以外"的占位 = 由**自然布局**的槽 rect 反推，故前提是"此刻槽尚未冻结"。
    // 该前提在**首次结算**成立，但**同一壳内重复结算**（换页/重测）时槽仍带着上一轮写入的
    // `height`/`padding-bottom`/`clip-path` ⇒ 量到的是"被冻结后的几何" ⇒ `占位 = f(页框)` 与
    // `页框 = 视口 − 占位` **互为因果、自我锁死**：实测续页量到 `chromeNow=337`（＝首页壳的值），
    // 于是页框恒 517，而续页真实占位只有 ~207（`80px 26px 1fr 76px` 的网格）⇒ 正文下方固定留白
    // 约 130px（换算物理约 195px，与用户截图的"大量空白"一致）。
    unfreezeSlot(slot);
    var key = shell || 'none';
    // 此刻正文槽**尚未冻结**（调用点在 freezePagedSlot 之前）⇒ 根壳内"槽以外"的占位与槽自身高无关。
    // ⚠️ 上占位必须取**槽的视口顶** `sr.top`（不是 `sr.top - rr.top`）：根壳自身离视口顶还有一段
    // 偏移（实测 10px，来自沙箱宿主文档）——只算"根壳内"的相对量会漏掉它，页框总高多 10px ⇒
    // 页脚下沿被挤出视口（真机实测：内容底 864 > 视口 854）。目标口径 = **内容底边贴视口底**。
    var total = chromeHeight;
    var rr = null;
    var sr = null;
    try {
      rr = root.getBoundingClientRect();
      sr = slot.getBoundingClientRect();
    } catch (error) {
      rr = null;
      sr = null;
    }
    shellChromeTop = NaN;
    shellChromeBottom = NaN;
    if (rr && sr) {
      // ⚠️ 上占位必须取**槽的视口顶** `sr.top`（不是 `sr.top - rr.top`）：根壳自身离视口顶还有一段
      // 偏移（实测 10px，来自沙箱宿主文档）——只算"根壳内"的相对量会漏掉它，页框总高多 10px ⇒
      // 页脚沿被挤出视口（真机实测：内容底 864 > 视口 854）。目标口径 = **内容底边贴视口底**。
      var top = sr.top;
      var bottom = rr.bottom - sr.bottom;
      // ⚠️ **负下占位守门**（2026-10-19 铁证，R2）：正文槽若**未受显式高度约束**（作者 CSS 让正文槽
      // 随内容自由增高，如本仓内置主题的历史写法），`槽 rect.bottom` 会**远超根壳底** ⇒ 下占位为
      // **负值**（实测 −8341）⇒ `total = 上占位 + 下占位` 变小到负 ⇒ `可见高 = vp − total` 变成
      // **整章内容高**（实测 h=9020 ≈ 261 行 × 34.56）⇒ 分页退化为"整章一屏"、`page`/`body` 恒 0
      // ⇒ 双向翻页失效（用户报"只能翻下一页、无法翻上一页"）。
      // 负下占位**不是"页脚占位"的合法量**（它只是"槽溢出"的副产品）⇒ 截零，回落到"仅页眉占位"口径，
      // 余量交给 [correctFrameHeight] 的贴底校正兜（它按根壳底 vs 视口底的真实布局事实收敛）。
      if (!isFinite(bottom) || bottom < 0) {
        bottom = 0;
      }
      total = top + bottom;
      shellChromeTop = top;
      shellChromeBottom = bottom;
      shellRectDebug = 'rt' + Math.round(rr.top) + ',rb' + Math.round(rr.bottom) +
        ',st' + Math.round(sr.top) + ',sb' + Math.round(sr.bottom) +
        ',top' + Math.round(top) + ',bot' + Math.round(bottom) +
        ',sy' + Math.round(window.scrollY || 0);
    }
    var prev = chromeByShell[key];
    chromeByShell[key] = total;
    shellChromeNow = total;
    // ⚠️ **该壳的第一次量取必然偏小**（装饰带尚未定型）⇒ 延迟一小段后重测重结算；
    // 之后若与上次仍差一整行（> CHROME_DRIFT_TOL）则下一帧再收敛一次。差异消失即停（不自激）。
    if (prev === undefined) scheduleResettle(CHROME_FIRST_MEASURE_DELAY_MS);
    else if (Math.abs(prev - total) > CHROME_DRIFT_TOL) scheduleResettle(0);
    return total;
  }

  /** 换章/重开时清空按壳的占位缓存（结构可能整体变化）。 */
  function resetChromeCache() {
    chromeByShell = {};
  }

  /**
   * 解除正文槽的冻结，回到"作者 CSS 的自然布局"（供 [chromeForShell] 量取占位前调用）。
   *
   * 为什么必须存在（2026-10-14 真机铁证）：占位与页框互为因果（`页框 = 视口 − 占位`），
   * 只有让槽回到自然高，量出的占位才是"模板真实占位"；否则量到的是上一轮结果，二者互相锁死。
   * 只清引擎自己写进去的四个属性（`height`/`paddingBottom`/`clipPath`/`overflow`），
   * **不碰** `boxSizing`（`freezePagedSlot` 写入的 `border-box` 与作者口径一致，保留无害）。
   */
  function unfreezeSlot(slot) {
    if (!slot || !slot.style) return;
    slot.style.height = '';
    slot.style.paddingBottom = '';
    slot.style.clipPath = '';
    slot.style.webkitClipPath = '';
    slot.style.overflow = '';
  }

  /**
   * 占位定型后重结算一次（幂等收敛）。
   *
   * 为什么需要：装饰带（内联 SVG）首帧高与定型后差一整行（见 [chromeForShell]）⇒ 首帧冻出的页框
   * 与后续页不一致（多一行）；且保存/恢复跨一档页码（用户实测：「每页文字个数的不同，页脚还能
   * 自己变动」）。该壳**首次**量取必然偏小 ⇒ 用 `delay > 0` 把它推迟到装饰带定型之后；
   * 后续只在"与上次差一整行"时补一次（`delay = 0` ⇒ 下一帧）。
   * `resettleScheduled` 保证同时只有一个待执行回调 ⇒ 不会自激。
   */
  var resettleScheduled = false;
  function scheduleResettle(delay) {
    if (resettleScheduled) return;
    resettleScheduled = true;
    var run = function () {
      resettleScheduled = false;
      try { settle(true); } catch (error) { }
    };
    if (delay > 0) {
      setTimeout(run, delay);
      return;
    }
    var later = (typeof requestAnimationFrame === 'function')
      ? requestAnimationFrame
      : function (fn) { return setTimeout(fn, 16); };
    later(run);
  }

  /**
   * 允许的正文偏移上限（= 正文内容高 − 页高）。
   *
   * 为什么需要：宿主只给"第几页"，而 `goto` 之后用户仍可手指拖动而**不经宿主**（滚动是原生手势）
   * ⇒ 下次 `goto` 若从自维护的 `bodyOffset` 推算，会把用户拖出的位置丢掉。故先从
   * `root.scrollHeight − slot.scrollHeight` 反推当前 `bodyOffset`（对冻结页框恒成立），
   * 再做边界夹取。
   */
  function maxBodyOffset(slot, rootEl) {
    var total = (slot && slot.scrollHeight) || 0;
    // 口径：`scrollHeight` 含上下内边距，箱高（`clientHeight`）同样含**上**内边距 ⇒ 两者之差恰为
    // "最后一行底贴到窗口底"的最大滚动量。**不能用可见窗口高**（`lastPageHeight`）：窗口高被
    // `padding-bottom` 吃掉了一段残量，用它算最大偏移会**多滚一段**（章末页底部露出空白）。
    var box = (slot && slot.clientHeight) || 0;
    var page = box > 0 ? box : (lastPageHeight > 0 ? lastPageHeight : visibleHeight());
    return Math.max(0, total - page);
  }

  /**
   * 从当前 `scrollTop` 反推正文偏移（跨结构可比量），并夹到合法区间。
   *
   * `root.scrollHeight − slot.scrollHeight` 即"页眉 + 正文上方装饰"在**当前结构**下的高
   * （冻结页框不参与 root 的滚动高）⇒ `scrollTop − 该值` 就是正文偏移。
   */
  function readBodyOffset(slot, rootEl) {
    if (!slot) return 0;
    var value = (slot.scrollTop || 0) - pageChrome(slot, rootEl);
    return clampBodyOffset(value, slot, rootEl);
  }

  /**
   * 页眉补偿量（"正文偏移 ↔ `scrollTop`"的换算项）。
   *
   * ⚠️ **冻结页框必须为 0**（2026-10-12 真机铁证）：冻结后槽自身 `overflow:hidden` + 固定高，
   * 槽内滚动就是"页内定位" ⇒ `scrollTop` **本身**即正文偏移；此时
   * `root.scrollHeight − slot.scrollHeight` 会因 `slot.scrollHeight` 是**内容高**而得名负值
   * （实测 −7595）⇒ 旧实现回退成 `bodyOffset`，使 `readBodyOffset` 恒解出 **0**
   * ⇒ 任何"非宿主驱动的结算"（换壳后再结算/用户拖动）都把正文**打回章首**
   * （真机表现：进度恢复落在第 1 页而非保存页）。
   */
  function pageChrome(slot, rootEl) {
    if (slotIsFrozen(slot)) return 0;
    var chrome = ((rootEl && rootEl.scrollHeight) || 0) - (slot.scrollHeight || 0);
    return (chrome >= 0 && isFinite(chrome)) ? chrome : 0;
  }

  function clampBodyOffset(value, slot, rootEl) {
    var max = maxBodyOffset(slot, rootEl);
    if (!isFinite(value)) return 0;
    return Math.max(0, Math.min(max, value));
  }

  /** 按正文偏移定位（与结构无关；补偿量见 [pageChrome]）。 */
  function applyBodyOffset(slot, rootEl) {
    slot.scrollTop = bodyOffset + pageChrome(slot, rootEl);
  }

  function bodySlots() {
    var slots = document.querySelectorAll('[data-reader-flow="body"]');
    return slots.length ? slots : null;
  }

  /**
   * 竖排判据（**单源**：运行时写在 `<html data-rp-writing>` 上，
   * 见 `template-runtime.js` 的 `applyWritingMode`；此处不重复判定 `writing-mode`）。
   *
   * 为什么分栏判据需要它：竖排正文按块轴排布 ⇒ 翻页必须**横移**，退回纵移的自研回退会**打错轴**。
   */
  function isVerticalWriting() {
    return document.documentElement.getAttribute('data-rp-writing') === 'vertical';
  }

  /**
   * 作者是否**真的提供了列布局**（R9：只认"声明"不够）。
   *
   * ⚠️ 真机铁证（2026-10-13，archive 主题「明日香」）：作者只声明
   * `data-reader-flow-pagination="columns"` 却**未写 `column-*`** ⇒ 旧判据直接掉进
   * `settleColumns` 的"只读结果"路径：`scrollWidth == clientWidth` ⇒ **页数恒 1**、且该路径
   * **从不冻结页框**（`lastPageHeight == 0`）⇒ 正文溢出视口、页脚缺失、无法翻页
   * （用户实测「正文内容还超了/底部展示不全」）。故必须要求"列布局证据"：
   * ①正文槽已产生**横向溢出**（作者的 `column-*` 真在起作用）；或
   * ②作者**显式**声明了列数/列宽。
   */
  function authorProvidesColumns() {
    var slot = bodySlots() ? bodySlots()[0] : null;
    if (!slot) return false;
    // ① 已产生横向溢出 ⇒ 列布局真的生效
    if ((slot.scrollWidth || 0) > (slot.clientWidth || 0) + 1) return true;
    // ② 显式声明了列数 / 列宽（computed 为 `auto` 表示作者没写）
    try {
      var cs = window.getComputedStyle(slot);
      var count = String(cs.columnCount || 'auto');
      if (count !== 'auto' && count !== 'normal' && count !== '1') return true;
      var width = parseFloat(cs.columnWidth);
      if (isFinite(width) && width > 0 && width < (slot.clientWidth || 0) * 0.9) return true;
    } catch (error) {
      return false;
    }
    return false;
  }

  function isColumnsMode() {
    var marker = document.querySelector('[data-reader-flow-pagination="columns"]');
    if (!marker && !(config && config.pagination === 'columns')) return false;
    // 竖排 ⇒ 必须分栏（横移），不得退回纵移路径
    if (isVerticalWriting()) return true;
    return authorProvidesColumns();
  }

  function isScrollMode() {
    return !!(config && config.type === 'scroll') || !!document.querySelector('[data-reader-scroll-viewport]');
  }

  /** paged + columns：交给 CSS 多栏（由 md-reader.css/模板 CSS 提供 column-* ），只读结果。 */
  function settleColumns() {
    var slot = bodySlots()[0];
    if (!slot) return { pageIndex: 0, pageCount: 1 };
    var scrollWidth = slot.scrollWidth || slot.getBoundingClientRect().width || 1;
    var pageWidth = slot.clientWidth || 1;
    var pageCount = Math.max(1, Math.ceil(scrollWidth / pageWidth));
    // 同上：分栏模板的页码同样读实际横移量（不能回读静态 config.pageIndex）
    return { pageIndex: clampIndex(scrollIndex(slot.scrollLeft, pageWidth), pageCount), pageCount: pageCount };
  }

  /**
   * paged + **自研回退分栏**（4.7 默认路径，不依赖 CSS 多栏能力）。
   *
   * 为什么要自己冻结页框：模板契约只要求正文槽位带 `data-reader-flow="body"`，作者 CSS 常让正文
   * **自由增高**（实测素笺 `.mi-body` 无 `height`/`overflow`）⇒ 不冻结就会出现"页数恒 1、
   * 翻页无效、正文溢出页框（读不到后半章）"。
   *
   * 页框高度口径 = **沙箱视口高 − 正文注入前的文档高（页眉/页脚/边距等占位）+ 槽位自身空高**：
   * 前者让作者写的页眉页脚被自然扣掉（不必额外约定契约），后者还原槽位 padding 的占位（border-box）。
   * 只在**真的溢出**时才冻结 ⇒ 未溢出的模板外观零改动。
   */
  function settleManualPagination() {
    var slot = bodySlots() ? bodySlots()[0] : null;
    if (!slot) return { pageIndex: 0, pageCount: 1 };
    // ⚠️ 视口高必须**结算时实时读**：`initialize` 那一刻沙箱帧可能还没布局完（实测 vp=0），
    // 把它缓存下来会让页框算成负数 ⇒ 直接放弃分页（症状：预览/阅读页"页数恒 1 且正文空白"）。
    // 只缓存 `chromeHeight`（正文注入前的固定占位）——它**必须**在注入前量，才是正确的占位口径。
    var box = visibleHeight();
    if (!(box > MIN_PAGE_HEIGHT)) {
      lastPageHeight = box;
      lastBoxHeight = box;
      reportPageFrame(slot, box);
      return { pageIndex: 0, pageCount: 1 };
    }
    // ① **恒定冻结**（2026-10-13 用户真机铁证：「阅读页面底部这么大空白」「页脚还能自己变动」）：
    // 原实现只在"内容溢出一屏"时才冻结 ⇒ **短章（内容不足一屏）永不冻结**，模板外壳便塌成内容高：
    // 页脚紧贴正文末尾、其下留出大片空白（实测 25% 屏高，与用户截图逐像素吻合）；续页壳因页高变大
    // 也因同一守卫不重冻，同样留 91px 空白。改为**不论是否溢出都冻结** ⇒ 外壳高恒 = 可用高 = 视口高 − 占位。
    // ⑤ **两个高度分开**（2026-10-13 用户真机铁证：「一页还可以上下拖动？而不是刚刚好的」「最后一行只显示一半」）：
    // 箱高 = 可用高（铺满、不可拖、页脚贴底）；可见窗口 = 整行对齐高（由 `clip-path` 把渲染面
    // 裁在整行边界 ⇒ 不裁字、不丢内容；**不能**只靠 padding-bottom，理由见 [freezePagedSlot]）。
    var visible = freezePagedSlot(slot, box);
    // ⚠️ 贴底校正（2026-10-12 真机铁证）：占位是**注入前估算**（模板装饰多为 vh 单位 SVG，
    // 注入后最终布局可能与估算差一截）⇒ 冻结后必须用**布局事实**（根壳底 vs 视口底）再校一次，
    // 否则页框超高会把页脚压出视口（用户可直接上下扒拉）；同时把校正结果写回按壳占位缓存。
    box = correctFrameHeight(slot, box);
    // 校正改变了箱高 ⇒ 可见窗口（以及对齐后的底部留白）必须**重算**（否则裁切边界又落到行中间）
    visible = freezePagedSlot(slot, box);
    lastBoxHeight = box;
    lastPageHeight = visible;
    reportPageFrame(slot, visible);
    // 页数口径与既有实现保持**逐字一致**（`ceil(内容高/页高)`）——口径一变，真机上的
    // 既有页数断言会整体漂移，无法与历史证据对照
    var totalPages = Math.min(MAX_PAGES, Math.max(1, Math.ceil((slot.scrollHeight || visible) / visible)));
    var maxOffset = maxBodyOffset(slot, root);
    // 宿主指令给的**页号即结果**，不得再从"夹取后的像素偏移"反解：末页多为**部分页**，
    // `target * visible` 会超过 maxOffset 被夹到 maxOffset，`floor(offset/visible)` 因此退回**前一页**
    // （真机铁证 2026-10-12：2 页章节点"下一页"恒停在 `page=0/2`，症状与用户报的"只能翻两页"完全一致）
    var authoritativeIndex = -1;
    if (bodyOffsetAuthoritative) {
      // 宿主 `goto` 已给出权威**页号** ⇒ 用**最终页高**折算偏移并只写不读地落到滚动位置：
      // 换壳（重挂 `innerHTML`）之后布局尚未 reflow，此刻读 `root/slot.scrollHeight` 会拿到
      // 未生效的旧值（真机实测把 667 反解成 0 ⇒ 页面被拉回首屏、页码恒 1/1）。
      bodyOffsetAuthoritative = false;
      if (authoritativeRatio >= 0) {
        // **滚动区间比例口径**：`bodyOffset = ratio × (内容高 − 箱高)`，分母恒取 [maxBodyOffset]。
        // ⚠️ 必须与保存侧 [progressRatio] **完全同源** —— 保存侧用的就是 `maxBodyOffset`；恢复侧
        // 若改用 `可见窗口高`（被 `padding-bottom` 吃掉一段残量），分母就差 (clientHeight − visible)
        // 个像素区间 ⇒ 真机实测由精确恢复**退化为 ±1 页**（`4/14 → 3/14`；页脚变薄时偏差被放大）。
        // （2026-10-12 二次校正：曾用 `ratio × 内容高` 混用 ⇒ 同样偏 1 页。同一铁律的第 3 次复现。）
        bodyOffset = clampBodyOffset(
          authoritativeRatio * maxBodyOffset(slot, root), slot, root
        );
        authoritativeRatio = -1;
      } else if (authoritativePage >= 0) {
        authoritativeIndex = clampIndex(authoritativePage, totalPages);
        bodyOffset = clampBodyOffset(authoritativePage * visible, slot, root);
        authoritativePage = -1;
      }
      slot.scrollTop = bodyOffset;
    } else {
      // 非宿主驱动（用户在页内拖动）⇒ 从滚动位置**反解**正文偏移（跨结构可比量）
      if (slotIsFrozen(slot)) bodyOffset = readBodyOffset(slot, root);
      // 冻结后按"结构无关的正文偏移"定位：换壳导致页眉高变化时，正文停留位置保持不变
      applyBodyOffset(slot, root);
      bodyOffset = clampBodyOffset(bodyOffset, slot, root);
    }
    return {
      pageIndex: authoritativeIndex >= 0
        ? authoritativeIndex
        : indexFromOffset(bodyOffset, visible, totalPages, maxOffset),
      pageCount: totalPages
    };
  }

  /**
   * 由正文偏移推页码，**贴底即末页**。
   *
   * ⚠️ 为什么不能只 `floor(offset / visible)`（2026-10-12 真机铁证）：末页通常是**部分页**，
   * `offset` 最大只能到 `maxOffset = 内容高 − 页高 < (末页号) × 页高` ⇒ floor 恒退回**前一页**
   * ⇒ 宿主 `edge().atBottom` 永远为 false、翻不到章末（症状＝"只能翻两页/翻不动"）。
   */
  function indexFromOffset(offset, visible, totalPages, maxOffset) {
    if (totalPages > 1 && maxOffset > 0 && offset >= maxOffset - 1) return totalPages - 1;
    // ⚠️ 必须**四舍五入**（与 [scrollIndex] 同一既有铁证，分页路径此前漏改）：
    // 分页偏移是按"页号 × 可见窗口高"取整生成的，恢复侧再把"比例 × 区间"回乘回来
    // ⇒ 结果**恰好落在页边界上**；此时任何浮点误差 / 箱高微差都会让 `Math.floor` 判退到
    // **前一页**（真机实测：`page=4/14` 冷启恢复成 `3/14`，用户可感知"进度不对"）。
    if (!(visible > 0)) return 0;
    var value = (offset || 0) / visible;
    if (!isFinite(value)) return 0;
    return clampIndex(Math.round(value), totalPages);
  }

  /**
   * 当前**内容比例**（0..1）= `正文偏移 / (内容高 − 页高)`；宿主据此保存进度。
   *
   * ⚠️ 为什么保存侧也要用它（2026-10-12 真机二次校正）：宿主此前存的是"页号 / 页数"，
   * 与恢复侧 `ratio × (内容高 − 页高)` **不是同一种归一化**（分页网格末页是部分页 ⇒
   * `页数 × 页高 > 内容高`）⇒ 往返必然偏 1~2 页。两侧同归一化后重开**精确**落回同一内容位置。
   */
  function progressRatio() {
    try {
      if (isScrollMode()) {
        var sc = scrollContainer();
        var maxScroll = Math.max(0, (sc.scrollHeight || 0) - (sc.clientHeight || 0));
        return maxScroll > 0 ? Math.max(0, Math.min(1, (sc.scrollTop || 0) / maxScroll)) : 0;
      }
      var slot = bodySlots() ? bodySlots()[0] : null;
      if (!slot) return 0;
      var max = maxBodyOffset(slot, root);
      return max > 0 ? Math.max(0, Math.min(1, bodyOffset / max)) : 0;
    } catch (error) {
      return 0;
    }
  }

  /**
   * 页框自证（诊断，随 `renderState` 回发；不算新消息类型，字段仍只用白名单内的 `state`）。
   *
   * 为什么值得常驻：模板沙箱**跨源**，宿主读不到它的尺寸与 DOM ⇒ "页数恒 1 / 正文空白 /
   * 翻页不动"这几类症状在没有这几个数时只能靠猜（本项目已多次因此多跑整轮真机验证）。
   */
  function reportPageFrame(slot, pageHeight) {
    if (!window.ReaderTemplateRuntime) return;
    var rect = null;
    var rootRect = null;
    try {
      rect = slot.getBoundingClientRect();
      rootRect = root ? root.getBoundingClientRect() : null;
    } catch (error) {
      rect = null;
      rootRect = null;
    }
    window.ReaderTemplateRuntime.send('renderState', {
      state: 'page-frame,h=' + Math.round(pageHeight) +
        ',client=' + Math.round(slot.clientHeight || 0) +
        ',scroll=' + Math.round(slot.scrollHeight || 0) +
        // `vp` 必须**实时读**（结算那一刻的真实视口）：`initialize` 抓的 `viewportHeight` 在沙箱
        // 帧尚未布局完时会退化成 0 ⇒ 用它做"页框底是否贴视口底"的判据会**假通过**（空真）。
        ',vp=' + Math.round(currentViewportHeight() || viewportHeight) +
        ',vpInit=' + Math.round(viewportHeight) +
        ',chrome=' + Math.round(chromeHeight) +
        // 按壳占位与壳名（本次修复的自证）：`chrome` 与实际生效占位不一致才能看出
        // "续页仍按首页占位扣减"（症状＝页脚上移、页脚后留白），也才能与 `shell` 对照定位
        ',chromeNow=' + Math.round(shellChromeNow) + ',shell=' + shellName() +
        // 占位拆解（R2 取证）：`ctop`/`cbot` 分别给出上/下占位 —— 只有拆开才能看出
        // "下占位是否为负"（负值即"槽自增高溢出根壳"的直接证据，是分页退化为整章高的成因）。
        ',ctop=' + (isFinite(shellChromeTop) ? Math.round(shellChromeTop) : -1) +
        ',cbot=' + (isFinite(shellChromeBottom) ? Math.round(shellChromeBottom) : -1) +
        // 宽度维度（R6 前置）：`vw` 沙箱视口宽 + 页根左右边界 `pl`/`pr` —— 缺了它们，
        // 用户报的"左右比原版窄一点"**无法证伪**（旧自证串只有高度类字段）。
        ',vw=' + Math.round((document.documentElement && document.documentElement.clientWidth) || 0) +
        ',pl=' + (rootRect ? Math.round(rootRect.left) : -1) +
        ',pr=' + (rootRect ? Math.round(rootRect.right) : -1) +
        // 占位量取的 rect 事实（`crf`：根壳/槽的视口 rect 与滚动位）：「占位不对」时一眼看出是哪一段错
        ',crf=' + shellRectDebug +
        ',empty=' + Math.round(slotEmptyHeight) +
        ',top=' + (rect ? Math.round(rect.top) : -1) +
        // 内容底边自证：根壳底（`rb`）与正文槽底（`sb`）⇒ 加下占位（`rb − sb`）即**内容底边**；
        // 它是判定"是否贴满视口/是否溢出"的**独立**布局事实（不依赖页框高公式本身，故可证伪）
        ',rb=' + (rootRect ? Math.round(rootRect.bottom) : -1) +
        ',sb=' + (rect ? Math.round(rect.bottom) : -1) +
        ',sy=' + Math.round(window.scrollY || 0) +
        // 结构无关定位（C1）的自证：root 高（页眉+正文+页脚）与正文偏移 —— 缺了它
        // "换壳后正文错位/页码跳飞"只能靠肉眼比对，无法定位是哪一项量错了
        ',root=' + Math.round((root && root.scrollHeight) || 0) +
        ',body=' + Math.round(bodyOffset) +
        // 整行网格自证（2026-10-13）：`lh`=computed 行高、`grid`=运行时写入的网格变量、
        // `pt`=正文槽顶部内边距 —— 三者缺一，"末行被裁半"就只能靠猜（分不清是网格没生效还是对齐没跑）
        ',lh=' + Math.round(lineHeightOf(slot) * 100) / 100 +
        ',grid=' + (slot.style.getPropertyValue('--rp-line-height') || '-') +
        ',pt=' + Math.round(slotPaddingTop(slot))
    });
  }

  /**
   * 正文槽位的**实际行高**（px；取不到返回 0 = 不做整行对齐）。
   *
   * 为什么读 computed 而不是读模板 CSS：行高可能来自任何一层（模板 `html,body` 的
   * `line-height`、引擎基线 `--md-line-height`、正文元素自身的相对值）——computed 是**最终生效值**，
   * 作者怎么写都能拿到；`line-height: normal` 时 computed 也返回像素值（浏览器已折算）。
   */
  function lineHeightOf(slot) {
    try {
      var cs = window.getComputedStyle(slot);
      var lh = parseFloat(cs.lineHeight);
      if (isFinite(lh) && lh > 0) return lh;
      // `line-height: normal` + 旧内核：用字号 × 1.2 兜底（避免整行对齐整体失效）
      var fs = parseFloat(cs.fontSize);
      if (isFinite(fs) && fs > 0) return fs * 1.2;
    } catch (error) {
      return 0;
    }
    return 0;
  }

  /** 正文槽的 computed 顶部内边距（诊断自证用；取不到返回 0）。 */
  function slotPaddingTop(slot) {
    try {
      var value = parseFloat(window.getComputedStyle(slot).paddingTop);
      return isFinite(value) ? value : 0;
    } catch (error) {
      return 0;
    }
  }

  /**
   * 正文**可见窗口高**（= 页步长）：把"箱高 − 槽顶部内边距"向下取整到**整行**。
   *
   * ⚠️ 前提：**正文必须落在整行网格上**——由引擎布局基线（`md/template-layout.css` 的
   * `[data-reader-flow="body"]` 段）把"槽顶部内边距 + 子元素段间距"归一到 `--rp-line-height`
   * 的整数倍（该变量由运行时按 computed 行高写入，见 `template-runtime.js` 的 `applyLineGrid`）。
   * 没有这一步，段间距会逐段推移行网格（实测 0.48 行/段）⇒ 从槽顶算起的整行倍数落不到行边界上，
   * 末行照样被裁半（2026-10-13 用户真机铁证：「最后一行只显示一半」）。
   *
   * 只**向下**取整：向上会让窗口高过可用高 ⇒ 正文被挤出页框（可上下扒拉）。取不到行高 ⇒ 返回可用量。
   */
  function alignToWholeLines(slot, boxHeight) {
    var room = boxHeight - slotPaddingTop(slot);
    var lh = lineHeightOf(slot);
    if (!(lh > 0) || !(room > lh)) return Math.max(0, room);
    var lines = Math.floor(room / lh);
    var windowHeight = lines * lh;
    return windowHeight > MIN_PAGE_HEIGHT ? windowHeight : Math.max(0, room);
  }

  /**
   * 把正文槽位冻结为**页框**（`paged` 自研回退分页的落点）。
   *
   * 两个高度**必须分开**（2026-10-13 用户真机铁证「一页还可以上下拖动？而不是刚刚好的」）：
   * - **箱高 = 可用高**：页框铺满视口 ⇒ 页脚恒贴视口底、外壳不可上下拖（观感"刚刚好一屏"）；
   * - **可见窗口 = 整行对齐高**：裁切边界必须落在行与行之间 ⇒ 末行**完整显示**（不裁字）、
   *   **不丢内容**（下一屏从该行起）。
   * 若把两者混用（箱高 = 整行高），箱体就比视口矮一截 —— 正是"能上下扒拉一截"的根因。
   *
   * ⚠️ 裁切**必须用 `clip-path`，不能用 `padding-bottom`**（2026-10-14 真机铁证，行带剖面实测）：
   * 正文是**溢出**的（滚动容器）⇒ 溢出内容会**画进内边距区**，而 `overflow:hidden` 的裁切边是
   * **内边距盒**（= 箱底），并非内容盒底 ⇒ 只加 `padding-bottom` 时正文可见区实际是
   * 「箱高 − 上内边距」（star-chart 实测 646.46px = 23.47 行）⇒ 第 24 行被裁掉大半
   * （截图行带剖面：末行只剩 6px 高的字形顶）。
   * `clip-path: inset(0 0 <残量> 0)` 把**渲染面**裁在「上内边距 + 整行窗口」处，且**不改布局**
   * ⇒ 不参与"按壳占位 / 贴底校正"的迭代（若改用 margin/gap 补偿，占位的下沿含该补偿 ⇒
   * 形成 `箱高 ← f(补偿) ← f(箱高)` 的反馈环，收敛点不再铺满视口）。旧内核不支持时静默降级为
   * "仅 padding-bottom"（即改造前行为），不会破坏可用性。
   *
   * @returns 可见窗口高（= 页步长，供翻页与页数口径使用）
   */
  function freezePagedSlot(slot, boxHeight) {
    slot.style.boxSizing = 'border-box';
    slot.style.height = Math.floor(boxHeight) + 'px';
    slot.style.overflow = 'hidden';
    var windowHeight = alignToWholeLines(slot, boxHeight);
    var gutter = Math.max(0, Math.floor(boxHeight - slotPaddingTop(slot) - windowHeight));
    slot.style.paddingBottom = gutter + 'px';
    var clip = 'inset(0 0 ' + gutter + 'px 0)';
    slot.style.clipPath = clip;
    slot.style.webkitClipPath = clip;
    return windowHeight;
  }

  /**
   * 页框高**贴底校正**：把"按占位估算的页框"用冻结后的布局事实再校一次。
   *
   * 判据（可证伪）：**根壳底 == 视口底**（`root.getBoundingClientRect().bottom` vs `currentViewportHeight()`）。
   * 为什么不能只信占位估算：占位在**正文注入之前**量取（口径要求，见 [chromeForShell]），而模板装饰
   * 多用 `vh` 单位与 `<svg>`（如 neon-night `.rn-art{height:8.6vh}` / `.rn-edge` / `.rn-footmark`）
   * ⇒ 注入/换壳后最终布局与估算可能不一致。**真机铁证（2026-10-12）**：估算页框 748，冻结后根壳底 874
   * > 视口 854 ⇒ 页脚被压出视口 20px，用户可直接上下扒拉（且自证串 `rb=874/sb=787` 与该结论一致）。
   *
   * ⚠️ **双向**校正（2026-10-13 用户真机铁证后由"只收缩"改为双向）：占位是**冻结前**量取的，
   * 而冻结（`overflow:hidden`）会把正文槽变成 BFC ⇒ 末段落的外边距不再塌出槽外 ⇒
   * **冻结后的下占位比冻结前的估算小**（实测 52 → 35px）⇒ 单向收缩会让根壳底**够不到视口底**
   * （实测 818 vs 854，留 36px 空白）。故欠填时同样按布局事实**放大**页框，直到根壳底贴视口底。
   * 仍受 [FRAME_CORRECT_MAX] 与 [MIN_PAGE_HEIGHT] 双重边界保护 ⇒ 作者畸形 DOM 不会抖动或除零。
   *
   * 占位↔页框是 **1:1** 关系（页框 = 视口 − 占位）⇒ 一次迭代即收敛；校正结果写回按壳占位缓存
   * ⇒ 同一壳后续结算零额外读。
   *
   * @returns 校正后的页框高（未冻结 / 无 `root` / 读数异常 ⇒ 原样返回）
   */
  function correctFrameHeight(slot, boxHeight) {
    if (!root || !slot || !slotIsFrozen(slot)) return boxHeight;
    var corrected = boxHeight;
    for (var i = 0; i < FRAME_CORRECT_MAX; i++) {
      var delta = 0;      // > 0 = 根壳底**超出**视口底（须收缩）；< 0 = 根壳底**欠填**（须放大）
      try {
        delta = root.getBoundingClientRect().bottom - currentViewportHeight();
      } catch (error) {
        return corrected;
      }
      if (!isFinite(delta) || Math.abs(delta) <= FRAME_CORRECT_TOL) break;
      var next = corrected - delta;
      if (!(next > MIN_PAGE_HEIGHT) || next === corrected) break;
      if (next > corrected) {
        // **放大前先验**：部分模板的根壳高被 CSS 钳定（根壳底恒 = 视口底）⇒ 放大页框不会让根壳底下移。
        // 此时放大只是把正文挤出根壳（内容被裁），故试放一次、若无位移即回退并终止。
        freezePagedSlot(slot, next);
        var after = 0;
        try {
          after = root.getBoundingClientRect().bottom - currentViewportHeight();
        } catch (error) {
          after = delta;
        }
        if (!isFinite(after) || after <= delta + FRAME_CORRECT_TOL) {
          freezePagedSlot(slot, corrected);
          break;
        }
        corrected = next;
        continue;
      }
      corrected = next;
      freezePagedSlot(slot, corrected);
    }
    if (corrected === boxHeight) return boxHeight;
    // 写回按壳占位：`chrome = 视口 − 页框`（与 [visibleHeight] 同口径），后续结算直接命中
    var vp = currentViewportHeight();
    if (vp > 0) chromeByShell[shellName() || 'none'] = Math.max(0, vp - corrected);
    shellChromeNow = Math.max(0, vp - corrected);
    return corrected;
  }

  function settleScroll() {
    var viewport = document.querySelector('[data-reader-scroll-viewport]');
    var scrollable = viewport || document.scrollingElement || document.documentElement;
    var visible = scrollable.clientHeight || 1;
    var total = scrollable.scrollHeight || visible;
    var pageCount = Math.max(1, Math.ceil(total / visible));
    // 宿主 `goto` 已给出权威偏移 ⇒ 只写不读地落到滚动位置：滚动容器的"总高"在滚动模板里
    // 依赖内容高度，紧接 `goto` 的那次结算同样可能读到未 reflow 的旧值（同 C1 换壳的成因）
    if (bodyOffsetAuthoritative) {
      bodyOffsetAuthoritative = false;
      authoritativePage = -1;   // 滚动面走**偏移**口径（不是页号/比例），此处只清标记
      authoritativeRatio = -1;
      scrollable.scrollTop = bodyOffset;
    }
    var scrollTop = scrollable.scrollTop || 0;
    var pageIndex = clampIndex(scrollIndex(scrollTop, visible), pageCount);
    // 结算侧自证：与 `goto-applied` 同口径回发（两者不一致即"滚了但结算读不到"）
    if (window.ReaderTemplateRuntime) {
      window.ReaderTemplateRuntime.send('renderState', {
        state: 'scroll-settle,top=' + (scrollTop || 0).toFixed(2) +
          ',visible=' + Math.round(visible) + ',total=' + Math.round(total) +
          ',index=' + pageIndex + ',viaViewport=' + (viewport ? 1 : 0)
      });
    }
    return { pageIndex: pageIndex, pageCount: pageCount };
  }

  function clampIndex(index, pageCount) {
    var value = typeof index === 'number' && isFinite(index) ? index : 0;
    return Math.max(0, Math.min(pageCount - 1, Math.floor(value)));
  }

  /**
   * 由滚动偏移与可见尺寸推出页码（容器不可滚动时返回 0）。
   *
   * ⚠️ 必须**四舍五入**、不能用 `Math.floor`：`scrollTop`/`clientHeight` 可能是小数
   * （真机实测 `scrollTop=782.x`、可见高 783 ⇒ `floor(0.9995)=0`），floor 会把"已翻到第 2 页"
   * 一律判成第 1 页 —— 症状是"内容明明翻过去了，页码/边界判据却停在第一页"（2026-10-10 铁证）。
   */
  function scrollIndex(offset, visible) {
    if (!(visible > 0)) return 0;
    var value = (offset || 0) / visible;
    return isFinite(value) ? Math.round(value) : 0;
  }

  /** 当前生效的页面结构名（诊断用；运行时不具备时回落 `none`）。 */
  function shellName() {
    var runtime = window.ReaderTemplateRuntime;
    if (!runtime || typeof runtime.currentPageShell !== 'function') return 'none';
    return runtime.currentPageShell();
  }

  /** 当前视口高（实时读；沙箱文档此时可能尚未布局完毕 ⇒ 调用方需容错）。 */
  function currentViewportHeight() {
    return document.documentElement.clientHeight || window.innerHeight || 0;
  }

  function initialize(flowConfig) {
    config = flowConfig || {};
    // 换章/重开 ⇒ 结构状态归位（C1）：每个章节都从"首页"结构开始（否则上一章的"已切续页"
    // 会带到新章节的首页，症状是新章开篇失去封面页）
    bodyOffset = 0;
    pageCount = 1;
    lastPageHeight = 0;                 // 换章 ⇒ 页高作废（新章结构/字号可能不同，须重新结算）
    lastBoxHeight = 0;                  // 箱高同理（它参与"铺满"判定，不得跨章复用）
    authoritativePage = -1;             // 换章 ⇒ 清空上一章的权威页号（防串章）
    authoritativeRatio = -1;            // 同理清比例口径
    resetChromeCache();                 // 换章 ⇒ 按壳占位缓存作废（结构可能整体变化）
    // 结构状态以运行为准（runtime 的 `renderShell` 已在 initialize 之前把首页结构挂上）
    flowShell = shellName();
    root = document.getElementById('reader-template-root');
    // ⚠️ 必须在**正文注入之前**调用（runtime 的 continueInit 已按此顺序）：此刻文档高即固定占位
    var slot = bodySlots() ? bodySlots()[0] : null;
    viewportHeight = document.documentElement.clientHeight || window.innerHeight || 0;
    // ⚠️ 占位高必须量**模板根壳**（`#reader-template-root`）而不是 `documentElement.scrollHeight`：
    // 后者在"内容比视口短"时会被撑到视口高 ⇒ 占位=整屏 ⇒ 页框被算成几像素 ⇒ 直接放弃分页
    // （实测症状：页数恒 1，stable 一路 1/1）。根壳是普通流内元素，其 scrollHeight 即真实内容高。
    chromeHeight = root ? (root.scrollHeight || 0) : (document.body.scrollHeight || 0);
    slotEmptyHeight = slot ? Math.ceil(slot.getBoundingClientRect().height) : 0;
  }

  /**
   * 结算分页。
   *
   * @param {boolean} remeasure 由宿主 `remeasure` 触发（富渲染注入完成后）。
   */
  function settle(remeasure) {
    var startedAt = (window.performance && performance.now) ? performance.now() : Date.now();
    var result = { pageIndex: 0, pageCount: 1 };
    var attempts = 0;
    while (attempts < MAX_COLUMN_RETRY) {
      attempts++;
      try {
        if (isScrollMode()) {
          result = settleScroll();
        } else if (isColumnsMode()) {
          result = settleColumns();
        } else {
          result = settleManualPagination();
        }
        break;
      } catch (error) {
        if (attempts >= MAX_COLUMN_RETRY) {
          // 降级：单页呈现，交由 runtime 回发错误（不白屏）
          if (window.ReaderTemplateRuntime) {
            window.ReaderTemplateRuntime.send('error', {
              code: 'template-flow-failed',
              message: String((error && error.message) || error)
            });
          }
          result = { pageIndex: 0, pageCount: 1 };
        }
      }
    }
    pageCount = result.pageCount;
    // 兜底复位：分栏路径不消费该标记（它直接写 `scrollLeft`）⇒ 不留悬空状态给下一次结算
    bodyOffsetAuthoritative = false;
    authoritativePage = -1;
    authoritativeRatio = -1;
    var costMs = ((window.performance && performance.now) ? performance.now() : Date.now()) - startedAt;
    if (window.ReaderTemplateRuntime) {
      // 结算自证（C1）：结构（首页/续页）与正文偏移随 stable 一并留痕 ——
      // "换了壳但页码没动"与"换壳后页码跳飞"两种失守在小屏上肉眼几乎无法区分
      window.ReaderTemplateRuntime.send('renderState', {
        state: 'settle,shell=' + shellName() + ',body=' + Math.round(bodyOffset) +
          ',page=' + result.pageIndex + '/' + result.pageCount +
          // 诊断（C1 换壳定位）：正文偏移是"与结构无关"的定位量，出问题时必须能一眼看出
          // 是"偏移没接力上"还是"页高算错"——只有 body 一个数说不清是哪一侧失守。
          // `slotTop` 取只读布局事实（`offsetTop`，不受冻结页框自身 scrollTop 影响）。
          ',h=' + Math.round(lastPageHeight) +
          ',slotTop=' + Math.round((bodySlots() && bodySlots()[0] ? bodySlots()[0].offsetTop : -1))
      });
      window.ReaderTemplateRuntime.onFlowSettled(
        result.pageIndex, result.pageCount, costMs, progressRatio()
      );
    }
    if (remeasure) {
      // 重测不额外上报（stable 已含新页数）；此处仅保留语义分支便于诊断
    }
    return result;
  }

  /**
   * 跳转到指定页并重新结算（宿主驱动翻页，4.14 收口）。
   *
   * 为什么必须在沙箱内做：宿主与模板帧**跨源**，宿主读不到也改不了本帧的滚动位置
   * ⇒ 只能由这里按模板类型操作对应容器，再把新的 `stable` 回发宿主。
   */
  function goto(pageIndex) {
    // ⚠️ 先把目标页夹到**当前已知页数**内：`applyPageShell` 会重建 DOM，若目标页非法（如宿主
    // 号到 999），换壳后 `settle` 才夹取会让"结构"与"页号"短暂不一致（多一次无谓重排）
    var target = clampIndex(pageIndex, pageCount);
    // 权威标记必须在**任何**滚动改动之前置位：换壳（`switchShellForPage`）内部也会接力偏移，
    // 若标记晚置，换壳那一步的反解仍会用未 reflow 的布局（见 [bodyOffsetAuthoritative]）
    bodyOffsetAuthoritative = true;
    // 权威量是**页号**（不是此刻算出的像素）：由 `settle` 按最终页高折算，见 [authoritativePage]
    authoritativePage = target;
    try {
      var container = null;
      var axis = 'y';
      if (isScrollMode()) {
        container = scrollContainer();
      } else {
        // C1 双结构按页换壳：**首屏 → 续页**、**回到首屏**两个方向都要切
        // （只切一个方向 = "从第 2 页翻回第 1 页时开篇页不再出现"）
        switchShellForPage(target);
        var slot = bodySlots() ? bodySlots()[0] : null;
        if (slot) {
          container = slot;
          // 分栏模板横移；自研回退（页框已冻结为纵向裁切）纵移 —— 用错轴 = "点翻页没反应"
          axis = isColumnsMode() ? 'x' : 'y';
        }
      }
      if (container) {
        // ⚠️ 步长必须与 `settle` 的页高**同源**（2026-10-12 真机铁证）：纵移若读
        // `container.clientHeight`，可能拿到与结算页高不一致的值（实测 656.67 vs 结算 748）
        // ⇒ `bodyOffset` 偏小、`pageIndex` 恒 0 ⇒ 宿主永远判不到"章末"，卡在第 2 页翻不动。
        // 分栏（x）仍按容器宽（横移口径本就以列为单位）。
        var visible = (axis === 'x'
          ? container.clientWidth
          : (lastPageHeight > 0 ? lastPageHeight : container.clientHeight)) || 1;
        if (axis === 'x') {
          container.scrollLeft = target * visible;
        } else {
          // 纵移 + 自研回退：按**与结构无关的正文偏移**定位（换壳后页眉高不同，
          // 沿用 `target * visible` 会把正文停在错位处 —— 见 [bodyOffset]）
          bodyOffset = target * visible;
          if (slotIsFrozen(container)) {
            container.scrollTop = bodyOffset;
          }
        }
        reportGoto(target, axis, container);
      }
    } catch (error) {
      if (window.ReaderTemplateRuntime) {
        window.ReaderTemplateRuntime.send('error', {
          code: 'template-goto-failed',
          message: String((error && error.message) || error)
        });
      }
    }
    return settle(false);
  }

  /** 冻结页框判据（只有冻结后才能按 scrollTop 纵移；未冻结说明内容不足一屏）。 */
  function slotIsFrozen(element) {
    return !!(element && element.style && element.style.overflow === 'hidden');
  }

  /**
   * 按目标页切换首/续页结构（C1），并把"换壳前的正文偏移"接力下来。
   *
   * 为什么由流程层而非运行时自己做：换壳会改变文档高，**必须同步修正滚动量**，
   * 而滚动量只属流程层（沙箱运行时不碰布局）。
   */
  function switchShellForPage(target) {
    var runtime = window.ReaderTemplateRuntime;
    if (!runtime || typeof runtime.applyPageShell !== 'function') return false;
    if (isScrollMode() || isColumnsMode()) return false;
    var slot = bodySlots() ? bodySlots()[0] : null;
    if (!slot || !slotIsFrozen(slot)) return false;      // 未冻结 ⇒ 单页内容，无需换壳
    var want = target <= 0 ? 'opening' : 'continuation';
    if (want === flowShell) return false;                // 同结构连续换页：不重建 DOM（见 [flowShell]）
    // 换壳后必须**冻结新页框**（旧壳的冻结内联高属于旧结构）；正文偏移的接力与定位由调用方
    // （`goto` 的权威偏移 + `settle` 的只写定位）负责 —— 此处读写会拿到未 reflow 的旧布局
    // 优先复用**上次结算的箱高**（已含贴底校正）⇒ 换壳瞬间就把新壳冻结成"铺满"状态；
    // 首次（尚无历史箱高）才回落到按视口推出的估算（见 [visibleHeight]）。
    var boxHeight = lastBoxHeight > 0 ? lastBoxHeight : visibleHeight();
    var changed = runtime.applyPageShell(want);
    if (changed) {
      flowShell = want;
      var fresh = bodySlots() ? bodySlots()[0] : null;
      if (fresh) {
        // 新壳刚挂上 ⇒ 页框需重设（旧壳的冻结内联高属于旧结构）。
        // ⚠️ 这里**不**定位滚动：重挂 `innerHTML` 后布局尚未 reflow，任何基于 `scrollHeight`
        // 的换算都会读到旧值（真机实测把偏移反解成 0）。定位统一交给随后 `settle` 的权威分支
        // （`slot.scrollTop = bodyOffset`，只写不读）。
        freezePagedSlot(fresh, boxHeight);
      }
    }
    return changed;
  }

  /** 滚动容器（滚动模板：天地画框；缺省退回文档滚动元素）。 */
  function scrollContainer() {
    return document.querySelector('[data-reader-scroll-viewport]') ||
      document.scrollingElement || document.documentElement;
  }

  /**
   * 翻页结果**自证**：沙箱跨源（宿主读不到它的滚动位置），"有没有真的滚过去"只能用消息回发；
   * 缺了它只能靠猜——"点击没到宿主""轴用错""容器不可滚"三种原因的症状完全一样。
   */
  function reportGoto(target, axis, container) {
    if (!window.ReaderTemplateRuntime) return;
    window.ReaderTemplateRuntime.send('renderState', {
      state: 'goto-applied,target=' + target + ',axis=' + axis +
        ',top=' + (container.scrollTop || 0).toFixed(2) +
        ',left=' + (container.scrollLeft || 0).toFixed(2) +
        ',client=' + Math.round(container.clientHeight || 0) +
        ',scroll=' + Math.round(container.scrollHeight || 0)
    });
  }

  /**
   * 按**章内内容比例**定位（0..1；0=章首、1=章末）。进度恢复用。
   *
   * 与 [goto]（页号口径）的关系：两者都经由 `settle` 的权威分支落位，但本函数把目标表达为
   * `bodyOffset = ratio × 内容高`（clamp 到 `0..maxOffset`），**与首页壳/续页壳的页数差异无关** ⇒ 恢复精确。
   * 为什么不能只用页号（2026-10-12 真机铁证）：双壳帧高不同（510 / 729）⇒ 页数不同（17 / 12），
   * `ratio × 当前壳页数` 会偏 ≤2 页（保存 10/12 恢复成 12/12）。
   */
  function gotoRatio(ratio) {
    var r = (typeof ratio === 'number' && isFinite(ratio)) ? Math.max(0, Math.min(1, ratio)) : 0;
    bodyOffsetAuthoritative = true;
    authoritativePage = -1;      // 本指令走内容比例口径（互斥）
    authoritativeRatio = r;
    try {
      if (isScrollMode()) {
        var scrollable = scrollContainer();
        var max = Math.max(0, (scrollable.scrollHeight || 0) - (scrollable.clientHeight || 0));
        bodyOffset = r * max;
        scrollable.scrollTop = bodyOffset;
      } else {
        // 目标不在章首 ⇒ 必须切到**续页壳**（首屏壳只服务第 1 页）；定位统一交给随后的 settle
        switchShellForPage(r > 0 ? 1 : 0);
      }
    } catch (error) {
      if (window.ReaderTemplateRuntime) {
        window.ReaderTemplateRuntime.send('error', {
          code: 'template-goto-failed',
          message: String((error && error.message) || error)
        });
      }
    }
    return settle(false);
  }

  window.ReaderTemplateFlow = {
    initialize: initialize,
    settle: settle,
    goto: goto,
    gotoRatio: gotoRatio
  };
})();
