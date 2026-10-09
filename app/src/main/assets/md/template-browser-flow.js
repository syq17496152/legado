/*
 * template-browser-flow.js —— 模板**浏览器流程**（epub-md-rich-rendering 阶段 4.1 / 4.7 / 4.15）
 *
 * 运行环境：与 `template-runtime.js` 同在沙箱 iframe 内，仅负责「排版结果」：
 * - `paged`：按模板声明的分栏方式切页（`data-reader-flow-pagination="columns"` ⇒ CSS 多栏；
 *   否则**自研回退**：按正文槽位高度累积切分，保证"复杂内容不断章不错位"）；
 * - `scroll`：滚动模板固定滚动（`data-reader-scroll-viewport` 唯一，滚动容器不参与正文测量）；
 * - 完成后回调 `ReaderTemplateRuntime.onFlowSettled(pageIndex,pageCount,costMs)`，
 *   由 runtime 回发 `stable` 消息（宿主据此重测分页，见 AD-28）。
 *
 * 设计约束（与宿主侧 `ReaderTemplateBridgePolicy` 的白名单/上限一致）：
 * - **不做 DOM 之外的副作用**：无网络、无 storage、无宿主调用；
 * - **有界**：扫描元素数与递归深度受限，避免作者巨型 DOM 拖死渲染；
 * - **异常降级**：流程异常时置「单页」并把错误交给 runtime 回发（不白屏）。
 */
(function () {
  'use strict';

  var MAX_SCAN_ELEMENTS = 20000;
  var MAX_COLUMN_RETRY = 3;

  var root = null;
  var config = null;

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
    return { pageIndex: clampIndex(config && config.pageIndex, pageCount), pageCount: pageCount };
  }

  /** paged + 自研回退：按行高累积切分（不依赖 CSS 多栏能力）。 */
  function settleManualPagination() {
    var slot = bodySlots()[0];
    if (!slot) return { pageIndex: 0, pageCount: 1 };
    var pageHeight = slot.clientHeight || 1;
    var children = slot.children;
    var nodes = Math.min(children.length, MAX_SCAN_ELEMENTS);
    if (!nodes) return { pageIndex: 0, pageCount: 1 };
    var total = 0;
    for (var i = 0; i < nodes; i++) {
      var rect = children[i].getBoundingClientRect ? children[i].getBoundingClientRect() : null;
      total += rect ? rect.height : 0;
    }
    var pageCount = Math.max(1, Math.ceil(total / pageHeight));
    return { pageIndex: clampIndex(config && config.pageIndex, pageCount), pageCount: pageCount };
  }

  function settleScroll() {
    var viewport = document.querySelector('[data-reader-scroll-viewport]');
    var scrollable = viewport || document.scrollingElement || document.documentElement;
    var visible = scrollable.clientHeight || 1;
    var total = scrollable.scrollHeight || visible;
    var pageCount = Math.max(1, Math.ceil(total / visible));
    var scrollTop = scrollable.scrollTop || 0;
    var pageIndex = clampIndex(Math.floor(scrollTop / visible), pageCount);
    return { pageIndex: pageIndex, pageCount: pageCount };
  }

  function clampIndex(index, pageCount) {
    var value = typeof index === 'number' && isFinite(index) ? index : 0;
    return Math.max(0, Math.min(pageCount - 1, Math.floor(value)));
  }

  function initialize(flowConfig) {
    config = flowConfig || {};
    root = document.getElementById('reader-template-root');
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

  window.ReaderTemplateFlow = {
    initialize: initialize,
    settle: settle
  };
})();
