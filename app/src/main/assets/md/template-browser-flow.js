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

  var root = null;
  var config = null;

  // 自研回退分页的**页框基准**：在正文注入**之前**量取（此刻文档高 = 页眉/页脚/内外边距等固定占位）
  var viewportHeight = 0;
  var chromeHeight = 0;
  var slotEmptyHeight = 0;

  function bodySlots() {
    var slots = document.querySelectorAll('[data-reader-flow="body"]');
    return slots.length ? slots : null;
  }

  function isColumnsMode() {
    var marker = document.querySelector('[data-reader-flow-pagination="columns"]');
    return !!marker || (config && config.pagination === 'columns');
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
    var pageHeight = viewportHeight - chromeHeight + slotEmptyHeight;
    if (!(pageHeight > MIN_PAGE_HEIGHT)) return { pageIndex: 0, pageCount: 1 };
    if ((slot.scrollHeight || 0) > pageHeight + 1) {
      freezeSlot(slot, pageHeight);
    }
    var visible = slot.clientHeight || pageHeight;
    if (!(visible > 0)) return { pageIndex: 0, pageCount: 1 };
    var pageCount = Math.min(MAX_PAGES, Math.max(1, Math.ceil((slot.scrollHeight || visible) / visible)));
    // ⚠️ 页码必须**读容器实际滚动位置**，不能回读 `config.pageIndex`（后者是 init 时的静态值）：
    // 否则 `goto` 明明滚过去了，回发的 stable 仍是旧页号 ⇒ 宿主页码/边界判据全部失真
    // （症状："点了下一页，内容动了但页码不变，到底了也切不了章"——2026-10-10 真机实证）
    return { pageIndex: clampIndex(scrollIndex(slot.scrollTop, visible), pageCount), pageCount: pageCount };
  }

  /**
   * 把正文槽位冻结为**页框**：固定高 + 裁切（border-box ⇒ 含 padding，与量取口径一致）。
   *
   * 只改我们用契约拥有的槽位自身（不动作者的结构与后代样式）；`overflow:hidden` 的元素仍是
   * 可编程滚动容器 ⇒ 翻页只需改 `scrollTop`，无需包裹正文（包裹会破坏作者 `>选择器`）。
   */
  function freezeSlot(slot, pageHeight) {
    slot.style.boxSizing = 'border-box';
    slot.style.height = Math.floor(pageHeight) + 'px';
    slot.style.overflow = 'hidden';
  }

  function settleScroll() {
    var viewport = document.querySelector('[data-reader-scroll-viewport]');
    var scrollable = viewport || document.scrollingElement || document.documentElement;
    var visible = scrollable.clientHeight || 1;
    var total = scrollable.scrollHeight || visible;
    var pageCount = Math.max(1, Math.ceil(total / visible));
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

  function initialize(flowConfig) {
    config = flowConfig || {};
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
    var costMs = ((window.performance && performance.now) ? performance.now() : Date.now()) - startedAt;
    if (window.ReaderTemplateRuntime) {
      window.ReaderTemplateRuntime.onFlowSettled(result.pageIndex, result.pageCount, costMs);
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
    var target = clampIndex(pageIndex, Number.MAX_SAFE_INTEGER);
    try {
      var container = null;
      var axis = 'y';
      if (isScrollMode()) {
        container = scrollContainer();
      } else {
        var slot = bodySlots() ? bodySlots()[0] : null;
        if (slot) {
          container = slot;
          // 分栏模板横移；自研回退（页框已冻结为纵向裁切）纵移 —— 用错轴 = "点翻页没反应"
          axis = isColumnsMode() ? 'x' : 'y';
        }
      }
      if (container) {
        var visible = (axis === 'x' ? container.clientWidth : container.clientHeight) || 1;
        if (axis === 'x') {
          container.scrollLeft = target * visible;
        } else {
          container.scrollTop = target * visible;
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

  window.ReaderTemplateFlow = {
    initialize: initialize,
    settle: settle,
    goto: goto
  };
})();
