/*
 * template-runtime.js —— 阅读页模板**沙箱侧**运行时（epub-md-rich-rendering 阶段 4.1 / AD-23）
 *
 * 运行环境：模板文档的 iframe 内（`sandbox="allow-scripts"`，**与宿主不同源**）。
 * 因此本文件**没有**任何宿主对象暴露物，只能经 `postMessage` 与宿主通信
 * （宿主侧对应 `template-host.js`），且通信字段受 `ReaderTemplateBridgePolicy`
 * 白名单约束（类型表见下 `SEND_TYPES`）。
 *
 * 职责：
 * 1. 加载并隔离运行**作者 HTML/CSS**（作者代码只在 `renderShell()` 中被拼装进 DOM，不 eval）；
 * 2. 注入宿主真值到 `data-reader-field` 槽位（bookName/chapterTitle/progress/page/time/battery）
 *    与 `--rp-percent` / `--rp-hour` / `data-rp-phase`（内容感知模板运行时，4.15）；
 * 3. 承接 `template-browser-flow.js` 的分页/滚动结果，回发 `stable`/`error`/`metrics`；
 * 4. **降级**：作者 HTML/CSS 异常时保留已渲染页并回发 `error`，绝不白屏。
 */
(function () {
  'use strict';

  // 与 ReaderTemplateBridgePolicy 的 FROM_WEB 类型表保持一致（契约测试会校验一致性）。
  var SEND_TYPES = {
    // `progressRatio`（2026-10-12）：沙箱自报的**内容比例** = `正文偏移 / (内容高 − 页高)`。
    // 宿主保存进度时用它（而不是"页号/页数"）⇒ 与恢复侧的 `ratio × (内容高 − 页高)` **同一归一化**
    // ⇒ 重开精确落回同一内容位置（此前两种归一化不一致，恢复会偏 1~2 页）。
    stable: ['pageIndex', 'pageCount', 'progressRatio'],
    error: ['code', 'message'],
    metrics: ['costMs'],
    renderState: ['state'],
    contentChanged: ['revision'],
    textPosition: ['revision', 'charOffset'],
    selection: ['rects'],
    sourceImage: ['src', 'alias'],
    image: ['src', 'alias'],
    link: ['href'],
    annotationState: ['id', 'enabled'],
    boundary: ['pageIndex', 'pageCount'],
    embeddedInteraction: ['id']
  };

  var RECEIVE_TYPES = ['inject-mermaid', 'inject-katex', 'remeasure', 'set-theme', 'goto-page', 'set-motion'];

  var state = {
    template: null,
    pageIndex: 0,
    pageCount: 1,
    revision: 0,
    token: '',
    sessionId: '',
    themeId: 'day',
    fields: {},
    decoration: 'medium',
    decorCount: 0,
    motion: true,
    seed: '',
    // 响应式收紧（C9）：帧尺寸与断点判定（运行时量取自身帧得到，不依赖宿主下发）
    viewport: { w: 0, h: 0, narrow: false, landscape: false },
    // 竖排规格（C10）：正文字书写方向（读作者 CSS 的 computed writing-mode）
    writing: 'horizontal',
    // 素材治理（C11）：正文图片总数 / 已解码数 / **已解码素材 src 去重集合**
    imgCount: 0,
    decodedCount: 0,
    decodedSrcs: {},
    // 字段派生（C2）：电量文本（由 Battery Status API 异步填充，取不到则保持空）
    battery: '',
    // 作者 JS 是否已执行（**每份文档只执行一次**；重复执行会重复注册钩子）
    authorScriptMounted: false,
    clockTimer: null
  };

  /** 窄屏断点（CSS px）：与 `md/template-layout.css` 的 C9 节同源口径。 */
  var NARROW_WIDTH = 480;

  /** 素材解码有界等待（毫秒）：单图卡住也必须继续，绝不因一张图把分页永久拖住。 */
  var MATERIAL_TIMEOUT_MS = 1500;

  /**
   * 元素/动画预算（4.19 / NFR-08 / AD-33）：作者畸形 DOM 不得把渲染拖死。
   *
   * 口径（**只报告、不改行为**）：超限时在自证里标 `budget=over`，由宿主/真机 L2 判读；
   * 运行时不擅自删节点或停动画——那会改变作者作品且难以归因（症状会变成"模板莫名少了一块"）。
   * 门禁落点有两处：①内置模板在 `ReaderBuiltinTemplateCatalogTest` 按资产静态计数（提交期拦截）；
   * ②用户导入模板只能靠这里的运行期自证 + 真机 L2（导入期静态计数做不到：CSS 可能动态放宽）。
   */
  var MAX_ELEMENTS = 3000;
  var MAX_ANIMATIONS = 120;

  /**
   * 当前生效的页面结构（`opening` 首页 / `continuation` 续页 / `scroll` 滚动）——
   * **状态机**，唯一写入点是 [applyPageShell]（见其 KDoc 的理由）。
   *
   * 初值取空串（而非 `'opening'`）：[renderShell] 会在**首次** `applyPageShell('opening')`
   * 时真正挂载，若初值即 `'opening'`，该调用会因"状态未变"直接返回 ⇒ **根壳恒空**。
   */
  var pageShell = '';

  /** 装饰强度 → `--rp-decor-scale`（4.8d）。字面量与 Kotlin 侧
   * `ReaderTemplateDecorationPolicy.Intensity.cssValue` 一一对应（契约测试锁定）。
   */
  var DECOR_SCALE = { none: '0', light: '0.75', medium: '1', strong: '1.25' };

  /**
   * archive 契约：宿主变量（AD-38 / archive-template-compat）。
   *
   * archive 主题（如 `builtin.asuka_sync`）的 CSS 依赖这一组 `--reader-*`。取值与 archive
   * 的**兼容默认值**同口径（archive `EpubTemplateDocument.baseCss`）：**固定默认值**，
   * 不跟随阅读主题；**模板可用自己的 `:root{}` 覆盖**（覆盖能力由 [applyReaderVars] 的
   * "写样式表而非内联"保证）。缺这组变量时作者的 `var(--reader-font-size)` 等
   * 全部回落 UA 值 ⇒ 字号/纸色/缩进/安全区全线错位（用户实测「完全效果都不一样」）。
   *
   * 与既有 `--md-*` / `--rp-*` **并存**（不改写、不替换）：`--md-*` 仍供我方内置模板消费。
   */
  var READER_VAR_DEFAULTS = {
    '--reader-text-color': '#222',
    '--reader-page-background': '#fff',
    '--reader-accent': '#555',
    '--reader-font-size': '18px',
    '--reader-line-height': '1.7',
    '--reader-font-family': 'serif',
    '--reader-paragraph-indent': '2em',
    '--reader-paragraph-spacing': '.65em',
    '--reader-padding-left': '0px',
    '--reader-padding-top': '0px',
    '--reader-padding-right': '0px',
    '--reader-padding-bottom': '0px',
    '--reader-safe-left': '0px',
    '--reader-safe-top': '0px',
    '--reader-safe-right': '0px',
    '--reader-safe-bottom': '0px',
    // archive `baseCss` 另有选中色变量（主题 CSS 用 `var(--reader-selection-color, …)` 兜底）
    '--reader-selection-color': 'rgba(70,138,255,.2)'
  };

  /** 契约变量样式表 id（**先于作者样式表**落位，见 [applyReaderVars] 的理由）。 */
  var READER_VARS_STYLE_ID = 'reader-reader-vars';

  /**
   * 契约变量以 `:root{}` **样式表**形式落位（2026-10-17 用户报「archive 主题分段正文样式在我方不兼容展示」的根因修复）。
   *
   * ⚠️ 为什么不能写 `<html>` 的**内联** style：内联声明优先级高于**任何**没有 `!important` 的作者样式表规则
   * ⇒ archive 主题在 `:root{}` 里声明的 `--reader-line-height` / `--reader-font-family` /
   * `--reader-paragraph-spacing` / `--reader-page-background` / `--reader-accent` 会被我方默认值**全部压掉**。
   * 而 archive 主题恰恰靠这些变量排版（`builtin.asuka_sync`：`.as-flow{font:var(--reader-font-size)/var(--reader-line-height)
   * var(--reader-font-family)}`、`.as-flow .reader-paragraph{margin-bottom:var(--reader-paragraph-spacing)}`）
   * ⇒ 观感与 archive 全线不一致。
   *
   * 离线 A/B 实测（真实 Chromium，2026-10-17）：内联写法下 computed 值 = 我方默认（`1.7` / `serif` / `.65em` / `#fff`），
   * 改走样式表后 = 主题声明值（`1.84` / `'Noto Serif CJK SC', …` / `.72em` / `#b42d2d`）。
   *
   * 与 archive 同口径：archive 把同款默认值放进**作者样式表之前**的 `baseCss` 样式表，同为 `:root{}` 声明时
   * "后到即胜" ⇒ 作者声明照常生效。落位规则因此是**插到 `<head>` 最前**（[mountStyles] 把作者 CSS 追加到 head 末尾
   * ⇒ 作者样式表恒在我方之后）；重复调用只改写同一张表的文本，表位置不变。
   */
  function mountReaderVars(css) {
    var style = document.getElementById(READER_VARS_STYLE_ID);
    if (!style) {
      style = document.createElement('style');
      style.id = READER_VARS_STYLE_ID;
      var head = document.head || document.documentElement;
      head.insertBefore(style, head.firstChild);
    }
    style.textContent = css;
  }

  /** 契约变量默认值 + 宿主逐项覆盖（`config.readerVars`，如真机实测的安全区）⇒ 落成一张 `:root{}` 样式表。 */
  function applyReaderVars(config) {
    var overrides = (config && config.readerVars) || {};
    var css = '';
    for (var name in READER_VAR_DEFAULTS) {
      if (!Object.prototype.hasOwnProperty.call(READER_VAR_DEFAULTS, name)) continue;
      var value = Object.prototype.hasOwnProperty.call(overrides, name)
        ? overrides[name] : READER_VAR_DEFAULTS[name];
      if (value === null || value === undefined) continue;
      css += name + ':' + String(value) + ';';
    }
    mountReaderVars(':root{' + css + '}');
  }

  /**
   * 正文槽位（按 `data-reader-order` 升序；未声明 order 视为 0）。
   *
   * archive 契约：多正文槽（`data-reader-flow="body"`）以 `data-reader-order` 声明先后 ⇒
   * `readerTemplate.source` 与注入顺序都取首个。只声明一个槽位时与原行为完全一致。
   */
  function orderedBodySlots() {
    var list = Array.prototype.slice.call(
      document.querySelectorAll('[data-reader-flow="body"]')
    );
    list.sort(function (a, b) {
      var na = Number(a.getAttribute('data-reader-order')) || 0;
      var nb = Number(b.getAttribute('data-reader-order')) || 0;
      return na - nb;
    });
    return list;
  }

  /**
   * 段落/标题语义类归一（archive 契约）。
   *
   * archive 的正文由 `TextReaderDocument` 产出时已带 `.reader-paragraph` / `.reader-chapter-title`；
   * 我方的 md / 在线正文管线（`MdDocumentBuilder`）产出的是**裸 `<p>` / `<h*>`** ⇒ 从 archive
   * 导入的主题 JS（点选 `.reader-paragraph`）会**点空**，段落语义装饰（`as-scene`/对白等）全线失效
   * ⇒ 观感退回「死图」。这里在正文注入后补一次归一，与 archive 正文形态对齐。
   *
   * 对既有模板无副作用：`md/template-layout.css` 对 `p` 与 `.reader-paragraph` 是**同规则**。
   */
  function normalizeReaderClasses() {
    var slots = orderedBodySlots();
    for (var i = 0; i < slots.length; i++) {
      var paragraphs = slots[i].querySelectorAll('p');
      for (var p = 0; p < paragraphs.length; p++) {
        if (!paragraphs[p].classList.contains('reader-paragraph')) {
          paragraphs[p].classList.add('reader-paragraph');
        }
      }
      var headings = slots[i].querySelectorAll('h1,h2,h3,h4,h5,h6');
      for (var h = 0; h < headings.length; h++) {
        if (!headings[h].classList.contains('reader-chapter-title')) {
          headings[h].classList.add('reader-chapter-title');
        }
      }
    }
  }

  /**
   * 我方"页"= 模板根壳（作者装饰/页眉页脚所在层）。
   *
   * archive 的钩子载荷把 `page` / `pages` 传成**页元素**：主题据此在页内查询 `.as-scan` /
   * 查询 `.as-wave i` / `.as-live-dot` 并用 Web Animations 起"每页动图"，也在页元素上写
   * `data-as-progress` / `--as-progress`。我方分页是"单壳 + 滚动窗口"，页元素即根壳。
   */
  function authorPage() {
    return document.getElementById('reader-template-root');
  }

  /** 动效状态名（archive 口径：`settled` / `running` / `paused`）。 */
  function motionStateName() {
    return state.motion ? 'running' : 'paused';
  }

  /**
   * archive 契约 R6：页元素"进页动效"状态（`data-reader-entry`）。
   *
   * archive 官方 `syncMotion` 逐页维护该属性，取值 `pending` → `playing` → `done`；主题用它做
   * **每页进场**（如 `[data-reader-entry="pending"] .hero {opacity:0}` 配合 `playing` 起的过渡）。
   * 缺该属性 ⇒ 这类选择器**静默落空**，页眉/页脚/开篇插画只剩静态帧（用户报「页面动画呢」的一类成因）。
   * 迁移语义与 archive 一致：动效放行时 `pending` → `playing`；动效停止时 `playing` → `done`
   * （播完保留静态帧，而不是回落到 `pending` 让动画重播）。
   */
  function applyEntryAttribute(page, running) {
    var entry = page.getAttribute('data-reader-entry') || 'pending';
    if (running && entry === 'pending') entry = 'playing';
    else if (!running && entry === 'playing') entry = 'done';
    if (page.getAttribute('data-reader-entry') !== entry) page.setAttribute('data-reader-entry', entry);
  }

  var lastMotionPage = null, lastMotionState = '';

  /**
   * 组装 archive 形 `beforePage` 事件（page/pageIndex/first/type）。
   *
   * archive 主题用它做"每页逻辑"：`doraemon_scroll` 按当前时段在页元素上写 `data-dora-period`；
   * `builtin.vertical` 在页内量正文流并写竖排列宽/行距间隙变量。缺该钩子 ⇒ 这两套主题静默失效。
   */
  function pageEvent(shellName) {
    return {
      page: authorPage(),
      pageIndex: state.pageIndex,
      first: shellName !== 'continuation',
      type: (state.template && state.template.type === 'scroll') ? 'scroll' : 'paged'
    };
  }

  /** 组装 archive 形 motionChange 事件（page/state/previousPage/previousState/reducedMotion）。 */
  function motionEvent() {
    var page = authorPage();
    var name = motionStateName();
    var event = {
      page: page, state: name,
      previousPage: lastMotionPage, previousState: lastMotionState,
      reducedMotion: false
    };
    lastMotionPage = page;
    lastMotionState = name;
    return event;
  }

  /**
   * archive 作者契约：`window.readerTemplate`（AD-38）。
   *
   * archive 主题的 `javascript` 一律以 `readerTemplate.on('beforeLayout' | …)`
   * 与 `readerTemplate.source.querySelectorAll(...)` 驱动段落语义装饰与页脚更新；
   * **缺这个对象时主题 JS 首行即抛**（`readerTemplate is not defined`）⇒ 观感退化为
   * 「死图」（正文有、装饰/每页变化全无）。这正是此前 「archive 主题完全不一样」的根因。
   *
   * 只暴露**只读语义**（`source/fields/viewport/on/…`）：不发网络、不碰宿主对象，
   * 与沙箱隔离约束一致（AD-23）。
   */
  var authorHooks = Object.create(null);

  /** 同步分发钩子（作者可返回 Promise；异步失败并入模板错误路径，绝不阻断渲染）。 */
  function emitHook(name, value) {
    var callbacks = (authorHooks[name] || []).slice();
    for (var i = 0; i < callbacks.length; i++) {
      try {
        var outcome = callbacks[i](value, authorApi);
        if (outcome && typeof outcome.then === 'function') {
          outcome.then(null, function (error) { reportError('template-hook-failed', error); });
        }
      } catch (error) {
        reportError('template-hook-failed', error);
      }
    }
  }

  var authorApi = {
    /** 正文根（= 首个正文槽位）；archive 主题据此点选 `.reader-paragraph` 等段落元素。 */
    get source() { var slots = orderedBodySlots(); return slots.length ? slots[0] : null; },
    get fields() { return state.fields; },
    get viewport() { return { width: state.viewport.w, height: state.viewport.h }; },
    on: function (name, callback) {
      if (typeof callback !== 'function') throw new TypeError('readerTemplate.on requires a function');
      var list = authorHooks[name] || (authorHooks[name] = []);
      list.push(callback);
      // 返回注销句柄（archive 语义）
      return function () { var index = list.indexOf(callback); if (index >= 0) list.splice(index, 1); };
    },
    requestLayout: function () {
      try { flow.settle(true); } catch (error) { reportError('template-hook-failed', error); }
    },
    waitUntil: function (promise) {
      // 登记即时兜底 handler，避免未处理的 rejection 冒泡为宿主级错误
      try { if (promise && typeof promise.catch === 'function') promise.catch(function () {}); } catch (error) { }
      return promise;
    },
    get pageIndex() { return state.pageIndex; },
    get pageCount() { return state.pageCount; },
    get pages() { return []; },
    get currentPage() { return null; },
    get motionState() { return motionStateName(); },
    get type() { return (state.template && state.template.type === 'scroll') ? 'scroll' : 'paged'; }
  };
  window.readerTemplate = authorApi;

  function send(type, payload) {
    var allowed = SEND_TYPES[type];
    if (!allowed) return;                       // 未登记类型：静默丢弃（宿主侧同样会拒收）
    var message = { type: type, token: state.token, sessionId: state.sessionId };
    allowed.forEach(function (key) {
      if (payload && Object.prototype.hasOwnProperty.call(payload, key)) message[key] = payload[key];
    });
    try {
      parent.postMessage(message, '*');
    } catch (error) {
      // 宿主已销毁（切书/退出）时的正常路径，不视为错误
    }
  }

  /**
   * 动效**可见性**自证（2026-10-14 用户报「动图不展示」）。
   *
   * 只报"动画对象在跑"是不够的：用户看的是**屏幕**。跨源宿主读不到沙箱 DOM ⇒ 只能由沙箱回发
   * **动画目标的实际几何**（是否在视口内、有无尺寸、计算后的不透明度）。
   * 判读：`animVis` 远小于 `animTotal` ⇒ 动画打在不可见/零尺寸/被裁掉的元素上（观感＝动画没动）；
   * `animY` 落在视口外 ⇒ 动效发生在屏幕之外。
   */
  function animationFacts() {
    try {
      if (typeof document.getAnimations !== 'function') return ',animVis=na';
      var list = document.getAnimations();
      var vis = 0, y0 = 1e9, y1 = -1, opMin = 2;
      for (var i = 0; i < list.length; i++) {
        var target = list[i].effect && list[i].effect.target;
        if (!target || !target.getBoundingClientRect) continue;
        var rect = target.getBoundingClientRect();
        var opacity = parseFloat(window.getComputedStyle(target).opacity);
        if (!isFinite(opacity)) opacity = 1;
        if (opacity < 1) opMin = Math.min(opMin, opacity);
        if (rect.width > 0 && rect.height > 0 && opacity > 0.02) {
          vis++;
          y0 = Math.min(y0, rect.top);
          y1 = Math.max(y1, rect.bottom);
        }
      }
      return ',animTotal=' + list.length + ',animVis=' + vis +
        ',animY=' + (y1 > 0 ? Math.round(y0) + '..' + Math.round(y1) : '-') +
        ',animOpMin=' + (opMin < 2 ? opMin.toFixed(2) : '-');
    } catch (error) {
      return ',animVis=err';
    }
  }

  function reportError(code, error) {
    send('error', { code: code, message: String((error && error.message) || error || code) });
  }

  /** 作者 HTML 拼装（作者代码只作为字符串进入 DOM，不 eval）。 */
  function renderShell() {
    var template = state.template || {};
    mountStyles(template.css || '');
    if (template.type === 'scroll') {
      // 滚动模板是单份 `scrollHtml`（无首/续之分）
      pageShell = 'scroll';
      mountMarkup(template.scrollHtml || '');
      // archive 契约：滚动模板的页根同样要带 `data-reader-page="scroll"`（主题按它切换配色/尺寸）
      applyPageAttrs('scroll');
      return;
    }
    // paged：每个章节都从**首页结构**开始（C1 双结构；续页由流程层按页切换）。
    // ⚠️ 必须走 `applyPageShell`（不能直接 `mountMarkup`）：该函数是本结构的**唯一**写入点，
    // 绕过它会留下"DOM 已是首页、状态却是上章续页"的不一致（症状：本章开篇页再也切不回来）
    applyPageShell('opening');
  }

  function mountStyles(css) {
    var style = document.getElementById('reader-template-css');
    if (!style) {
      style = document.createElement('style');
      style.id = 'reader-template-css';
      document.head.appendChild(style);
    }
    style.textContent = css;
  }

  function mountMarkup(html) {
    var root = document.getElementById('reader-template-root');
    if (!root) {
      root = document.createElement('div');
      root.id = 'reader-template-root';
      document.body.appendChild(root);
    }
    try {
      root.innerHTML = html;
    } catch (error) {
      reportError('template-render-failed', error);
    }
  }

  /** 首页结构（paged 的 `firstPageHtml`）；空则视为无首页结构。 */
  function openingHtml() {
    return (state.template && state.template.firstPageHtml) || '';
  }

  /**
   * 作者 JS 执行（archive 契约的关键一环，**2026-10-14 补齐**）。
   *
   * 为什么必须有：archive 主题的**动效 / 段落语义 / 页脚同步全在 `javascript` 字段里** ——
   * 用 `readerTemplate.on('beforeLayout'|'afterLayout'|'pageChange'|'motionChange'|'dispose')`
   * 注册钩子，并在 `event.pages` / `event.page`（页元素）上用 Web Animations 起"每页动图"。
   * 此前我方**从未执行该字段**（`template.javascript` 全文零引用）⇒ 主题 JS 一行不跑 ⇒
   * 观感与 archive 的「每页元素有变化 / 有动图效果」差一大截（用户实测：「适配过来就是一个死图」）。
   *
   * 为什么用 `new Function(code)()` 而不是"插入 `<script>` 元素"：
   * 动态插入的经典脚本按规范是**异步执行**的 ⇒ 作者的 `readerTemplate.on(...)` 注册可能晚于
   * 首个 `beforeLayout`/`afterLayout` ⇒ 首屏装饰与动效**永久丢失**（跨源、只有真机能看出来）。
   * `new Function` 是**同步**执行、顺序完全可控；`window.readerTemplate` 已在模块顶层暴露。
   *
   * 安全：作者代码依旧只跑在**沙箱文档**内（不同源、无宿主对象、无 `addJavascriptInterface`），
   * 抛错只留痕（`template-script-failed`）并继续渲染 —— 绝不因作者代码把阅读页打死。
   * 执行次数：**每份文档一次**（失败也不重试，重复执行会重复注册钩子 ⇒ 事件被放大）。
   */
  function mountAuthorScript() {
    var code = (state.template && state.template.javascript) || '';
    if (!code.trim() || state.authorScriptMounted) return;
    state.authorScriptMounted = true;
    try {
      (new Function(code))();
      // 自证：作者脚本是否真的执行过（跨源、无调试器时这是唯一凭据）
      send('renderState', { state: 'author-script,ok=1,chars=' + code.length });
    } catch (error) {
      send('renderState', { state: 'author-script,ok=0,chars=' + code.length });
      reportError('template-script-failed', error);
    }
  }

  /** 续页结构（paged 的 `otherPageHtml`）；空则退回首页结构（作者只写一套时按一套渲染）。 */
  function continuationHtml() {
    var other = (state.template && state.template.otherPageHtml) || '';
    return other || openingHtml();
  }

  /**
   * 首页 / 续页**双结构按页换壳**（4.15 C1 / TPL-15）。
   *
   * 契约（archive 原文能力）：paged 模板的**首页是"封面/开篇页"**（整幅插画 + 题词 + 大标题），
   * **续页才是正文页**（窄页眉 + 正文流 + 页脚）。它们是**两套 HTML**，不是同一套加类名 ——
   * 所以必须真的换 DOM，只在首屏渲染一套就交差等于"开篇仪式感缺失"。
   *
   * **为什么换壳只发生在页边界（而不是每页重渲染）**：自研回退分页是"**单 DOM + 冻结页框 + 滚动**"
   * （见 `template-browser-flow.js`）——正文在 DOM 里是连续流，页只是滚动窗口。因此
   * ①结构与"第几页"只能按**开篇段 / 正文段**二分（首屏换不回：首屏内容已滚到窗口之外，
   * 重新换壳需要"按页无限延伸"的排版模型，属 4.19 的主干重建，AOAdapt 已登记）；
   * ②换壳会改变文档高 ⇒ 必须由**流程层**在 `goto` 里同步修正滚动偏移（见 `flow.setShell`）。
   *
   * 返回值：`true` 表示壳**真的换了**（流程层据此修正滚动偏移）。
   */
  function applyPageShell(which) {
    var target = which === 'continuation' ? 'continuation' : 'opening';
    var template = state.template || {};
    if (template.type === 'scroll') return false;   // 滚动模板是单份 scrollHtml，无首/续之分
    if (target === pageShell) return false;
    pageShell = target;
    mountMarkup(target === 'opening' ? openingHtml() : continuationHtml());
    // archive 契约：页元素属性随换壳同步（`first` / `other`）——主题按它区分开篇页与正文页
    applyPageAttrs(target);
    // archive 契约 R6：换壳即"新的一页" ⇒ 进页状态回到 `pending`。archive 每次新建页元素时同样重置；
    // 不重置则续页沿用首页的 `done` ⇒ 进场动效只在首页播一次（续页静默）。
    var shellEl = authorPage();
    if (shellEl) shellEl.setAttribute('data-reader-entry', 'pending');
    // ⚠️ 关键：换壳会**整块替换根壳 innerHTML**（新壳里当然没有正文）⇒ 必须把正文注入回来，
    // 否则翻到续页就是"页眉页脚还在、正文全空"（2026-10-10 C1 真机实证：slot 高塌成 16px、页数 1/1）。
    // 用 `applyBody` 一并回填字段槽位（新壳里的页码/时间等占位同样需要真值）。
    applyBody(state.bodyHtml);
    // 换壳会**整块替换根壳 innerHTML** ⇒ 新槽是全新元素，槽上的整行网格变量随之丢失
    // （真机实测续页 `grid=-`/`pt=0`，段距回落 1em ⇒ 续页末行重新被裁半）⇒ 必须重新写入
    applyLineGrid();
    // archive 契约：换壳后补发 `beforePage`（新页元素上的"每页逻辑"，如竖排量流/时段标记）。
    // 仅在正文已注入时发：首次 `renderShell()` 时正文尚未到达，那次由 continueInit 统一补发。
    if (state.bodyHtml) emitHook('beforePage', pageEvent(target));
    // 钩子会改 DOM（段落语义类/量取写变量）⇒ 网格必须**在钩子之后**再补一次（同 continueInit 的理由）
    applyLineGrid();
    return true;
  }

  /** 当前生效结构（诊断/契约用）。 */
  function currentPageShell() {
    return pageShell;
  }

  /**
   * 内容注入：把宿主真值写进模板声明的槽位。
   * 契约（blueprint §六）：`data-reader-field="bookName|chapterTitle|progress|page|time|battery"`；
   * 正文容器 `data-reader-flow="body"`（可多块 + `data-reader-order`）。
   */
  function applyFields(fields) {
    state.fields = fields || {};
    refreshFields();
    // archive 契约钩子：字段变化（fieldsChange）—— 主题据此刷新页眉书名的联动外观。
    emitHook('fieldsChange', { fields: state.fields });
  }

  /** 本地时间 HH:mm（沙箱时钟即设备时钟）。 */
  function localTime() {
    var now = new Date();
    var hh = now.getHours();
    var mm = now.getMinutes();
    return (hh < 10 ? '0' : '') + hh + ':' + (mm < 10 ? '0' : '') + mm;
  }

  /**
   * 字段真值的**派生源**（4.15 C2 / TPL-15）：宿主经 `init` 提供的字段之外，
   * `page` / `pageCount` / `percent` / `time` / `battery` 由**沙箱自己**填。
   *
   * 为什么必须补：内置模板（霓虹夜行等）页脚声明了 `data-reader-field="page"` 与 `"time"`，
   * 而宿主的 init 载荷只给 `bookName/chapterTitle/progress/hour` ⇒ 这两个槽位**恒为空**：
   * 观感上就是"页脚页码与时间永远不显示"，且**不报错**（典型静默失效）。
   * 页码只有沙箱知道（分页是它算的），时间/电量只有运行环境知道 ⇒ 派生只能放这里。
   */
  function derivedField(key) {
    switch (key) {
      case 'page': return String(state.pageIndex + 1);
      case 'pageIndex':
        // **1 基页码**（2026-10-14 双端截图铁证）：archive 页脚实测显示 `PAGE 1 / 6`（第 1 页）、
        // `PAGE 2 / 6`（第 2 页）⇒ archive 的 `pageIndex` 字段是**从 1 起**，不是 0 基。
        // ⚠️ 与**事件载荷**区分：给作者 JS 的 `pageChange.pageIndex` 仍为 **0 基**（archive 主题
        // 自己用 `(index + 1) / count` 算进度 —— asuka JS 即如此）⇒ 这里只改"展示派生"，
        // 不动 `state.pageIndex` 与事件口径（否则作者 JS 的进度会整体偏一页）。
        return String(state.pageIndex + 1);
      case 'pageCount': return String(state.pageCount);
      case 'percent':
        return typeof state.fields.progress === 'number' ? String(state.fields.progress) : null;
      // `progress` 页脚口径：**页进度百分比**（archive 同口径 —— 第 1 页 16.7% / 第 2 页 33.3%，
        // 与其页码同源）。宿主给的是"整书章节进度"，两者不同源，故该字段由沙箱权威给出。
      case 'progress': {
        var total = state.pageCount > 0 ? state.pageCount : 1;
        return (((state.pageIndex + 1) / total) * 100).toFixed(1) + '%';
      }
      case 'time': return localTime();
      case 'battery': return state.battery || null;   // 取不到电量不冒写假值
      default: return null;
    }
  }

  /**
   * 把字段真值写进槽位：**宿主提供的优先，其次派生**；两者都没有则保留作者占位文本
   * （不写空串——那会把作者的示意文案擦掉）。
   */
  function refreshFields() {
    var nodes = document.querySelectorAll('[data-reader-field]');
    for (var i = 0; i < nodes.length; i++) {
      var key = nodes[i].getAttribute('data-reader-field');
      var supplied = state.fields[key];
      var derived = derivedField(key);
      // `progress` 例外：宿主的"整书进度"与 archive 的"页进度"是**两个不同量**
      // ⇒ 该槽位一律取派生值（否则页脚百分比与旁边的页码自相矛盾）
      var value = (key === 'progress' && derived !== null) ? derived
        : ((supplied === undefined || supplied === null) ? derived : String(supplied));
      if (value === null) continue;
      nodes[i].textContent = value;
    }
    applyPhase();
  }

  /** 电量（Battery Status API，增强信息）：取不到就保持空，绝不冒写假值。 */
  function watchBattery() {
    try {
      if (typeof navigator.getBattery !== 'function') return;
      navigator.getBattery().then(function (manager) {
        var sync = function () {
          state.battery = Math.round((manager.level || 0) * 100) + '%';
          refreshFields();
        };
        manager.addEventListener('levelchange', sync, false);
        sync();
      }, function () { /* 拒绝授权/不支持：忽略 */ });
    } catch (error) {
      /* 电量是增强信息，失败不影响任何主路径 */
    }
  }

  /** 时间按分钟刷新（C5 时间驱动：≥15 分钟粒度即可；用分钟让"时钟"看起来在走）。 */
  function startClock() {
    if (state.clockTimer) return;
    state.clockTimer = setInterval(function () {
      refreshFields();
    }, 60000);
  }

  /** 内容感知：进度百分比与小时/阶段类（4.15 / AD-31）。 */
  function applyPhase() {
    var percent = typeof state.fields.progress === 'number' ? state.fields.progress : null;
    if (percent !== null) {
      document.documentElement.style.setProperty('--rp-percent', String(percent));
    }
    var hour = state.fields.hour;
    if (typeof hour === 'number') {
      document.documentElement.style.setProperty('--rp-hour', String(hour));
      var phase = hour < 6 ? 'night' : (hour < 12 ? 'morning' : (hour < 18 ? 'day' : 'evening'));
      document.documentElement.setAttribute('data-rp-phase', phase);
    }
  }

  /** 正文本体（`data-reader-flow="body"`）注入：模板只负责外观，正文由宿主提供。 */
  function applyBody(bodyHtml) {
    // 缓存正文 HTML：换壳（C1）会整块替换根壳，必须能把它再注入回来
    if (bodyHtml !== undefined) state.bodyHtml = bodyHtml || '';
    var containers = orderedBodySlots();
    if (!containers.length) {
      // 模板未声明正文槽位 ⇒ 视为作者错误，保留当前显示并报错（不静默吞掉）
      reportError('template-missing-body-slot', '模板未声明 data-reader-flow="body"');
      return;
    }
    for (var i = 0; i < containers.length; i++) {
      containers[i].innerHTML = state.bodyHtml;
    }
    // 段落/标题语义类归一（archive 契约）：见 [normalizeReaderClasses]
    normalizeReaderClasses();
    state.revision++;
    send('contentChanged', { revision: state.revision });
  }

  function setTheme(themeId) {
    state.themeId = themeId || 'day';
    // archive 契约：主题标识写在 <html data-reader-theme>（archive 主题 CSS/JS 按它取态；
    // 我方内置主题同样按它分「日间 / 夜间 / 水墨屏」三态）。
    document.documentElement.setAttribute('data-reader-theme', state.themeId);
    // 同步到 `data-md-theme`：`md/` 基线样式表（md-reader.css）的三态变量块按**该**属性选择。
    // 只写 reader 侧会让基线（代码块/引用/表格底纹/边框）在夜间与水墨屏下仍停在浅色默认值 ——
    // 这正是「内置主题日夜兼容不全」的成因（正文色由模板自带，基线色却停在天亮态）。
    document.documentElement.setAttribute('data-md-theme', state.themeId);
  }

  /**
   * archive 契约：页元素属性（`data-reader-page="first|other|scroll"` + `data-reader-active`）。
   *
   * archive 官方作者文档写明「页面根节点：data-reader-page="first|other"」，其主题 CSS 依赖它做
   * 首页/续页差异（`[data-reader-page]{padding:0}`、`[data-reader-page="first"] .tpl-magazine{gap:…}`、
   * `[data-reader-page="scroll"]{…}`），`builtin.minecraft_live` 还用
   * `[data-reader-active="true"][data-reader-motion="paused"]` 做页面级动效启停。
   * 缺失时这些选择器**静默落空** ⇒ 导入主题与 archive 观感不一致（用户实测「完全效果都不一样」）。
   */
  function applyPageAttrs(which) {
    var root = document.getElementById('reader-template-root');
    if (!root) return;
    var value = which === 'scroll' ? 'scroll' : (which === 'continuation' ? 'other' : 'first');
    root.setAttribute('data-reader-page', value);
    root.setAttribute('data-reader-active', 'true');
  }

  /**
   * 装饰强度与动效闸门（4.8d / AD-33 / TPL-17）：宿主真值写在 <html> 上，
   * 由引擎装饰基线 CSS（`md/template-decoration.css`）按属性选择器执行。
   *
   * 只设属性与变量、**不动 DOM 结构尺寸** ⇒ "装饰变更不得改变正文测量"由构造保证
   * （策略侧 `decorationChangesLayout` 另做一致性断言）。
   */
  function applyDecoration(config) {
    var root = document.documentElement;
    var level = config && config.decoration ? String(config.decoration) : 'medium';
    if (!Object.prototype.hasOwnProperty.call(DECOR_SCALE, level)) level = 'medium';
    state.decoration = level;
    var motion = !!(config && config.motion);
    // 稳定随机种子（4.15 C4）：宿主按书名+章节稳定哈希 ⇒ 同章重排/回首页/读快照不重抽
    if (config && config.seed !== undefined && config.seed !== null) {
      state.seed = String(config.seed);
      root.setAttribute('data-rp-seed', state.seed);
      root.style.setProperty('--rp-seed', state.seed);
    }
    root.setAttribute('data-rp-decoration', level);
    root.style.setProperty('--rp-decor-scale', DECOR_SCALE[level]);
    applyMotion(motion);
  }

  /**
   * 动效闸门（4.15 C6）：`html[data-reader-motion]` 是装饰动效的**唯一**开关
   * （引擎基线 CSS 按它停 `animation`，回到静态帧）。
   *
   * 两个来源：①init 时的 reduce-motion/专注模式判定；②运行中的宿主指令
   * （`set-motion`：用户拖动中 / 已离页 / 翻页结算期间）。后者是本函数存在的理由——
   * 沙箱**看不到**宿主的手势与生命周期，只能由宿主下令。
   */
  function applyMotion(running) {
    var next = !!running;
    var changed = state.motion !== next;
    state.motion = next;
    document.documentElement.setAttribute('data-reader-motion', motionStateName());
    // 同步到"页元素"（archive 把动效属性放在页上；部分主题 CSS 选择器按它启停动效）。
    var shell = authorPage();
    if (shell) {
      shell.setAttribute('data-reader-motion', motionStateName());
      // archive 契约 R6：页元素进页状态与动效闸门同步推进（`pending` → `playing` → `done`）。
      applyEntryAttribute(shell, next);
    }
    // archive 契约钩子：动效状态变化（motionChange）—— 主题据此启停每页动图/波形。
    if (changed) emitHook('motionChange', motionEvent());
  }

  /**
   * 装饰**自动分层**：模板根下"既不在正文槽位子树内、也不是其祖先"的元素即装饰。
   *
   * 为什么要自动而不只靠作者声明：用户导入的第三方模板（archive 导出）不会按我方契约标注，
   * 只认显式声明 ⇒ 装饰强度设置对它们**形同虚设**（"设置了没反应"，本项目已多次栽在这类静默失效上）。
   * 保留显式声明优先：作者可据此表达例外（例如希望某个元素始终不随强度变化）。
   *
   * 安全性来自"keep 集合"：正文槽位及其祖先/后代**永不被标记** ⇒ 不可能把正文压暗或隐藏。
   */
  function markDecorations() {
    var root = document.getElementById('reader-template-root');
    if (!root) return 0;
    var keep = [];
    var slots = root.querySelectorAll('[data-reader-flow="body"]');
    for (var i = 0; i < slots.length; i++) {
      keep.push(slots[i]);
      var parent = slots[i].parentElement;
      while (parent) {
        keep.push(parent);
        parent = parent.parentElement;
      }
      var inner = slots[i].querySelectorAll('*');
      for (var j = 0; j < inner.length; j++) keep.push(inner[j]);
    }
    var all = root.querySelectorAll('*');
    var marked = 0;
    for (var k = 0; k < all.length; k++) {
      var node = all[k];
      if (keep.indexOf(node) >= 0) continue;
      if (node.hasAttribute('data-reader-decor')) continue;
      node.setAttribute('data-reader-decor', 'auto');
      marked++;
    }
    state.decorCount = marked;
    return marked;
  }

  /**
   * 响应式收紧（4.15 C9）：量取**沙箱自身帧**尺寸并置于 `<html>`，供引擎布局基线
   * （`md/template-layout.css`）与作者 CSS 消费。
   *
   * 为什么不靠宿主下发：沙箱 iframe 已被宿主按 px 定尺寸（见宿主 `sizeSandboxContainer`），
   * 因此"沙箱自身文档的 clientWidth/Height"就是阅读区尺寸的真值——自量最省一条跨源通道，
   * 也天然随帧变化（宿主改尺寸后重测即得新值）。判定口径（窄屏 480 / 横屏宽>高）与
   * `md/template-layout.css` 的 C9 节同源。
   */
  function applyViewport() {
    var root = document.documentElement;
    var w = root.clientWidth || 0;
    var h = root.clientHeight || 0;
    var narrow = w > 0 && w <= NARROW_WIDTH;
    var landscape = w > 0 && h > 0 && w > h;
    state.viewport = { w: w, h: h, narrow: narrow, landscape: landscape };
    root.setAttribute('data-rp-narrow', narrow ? '1' : '0');
    root.setAttribute('data-rp-landscape', landscape ? '1' : '0');
    // 变量带 px 单位：布局基线用它做竖排"确定宽高"与图片约束，作者也可直接消费
    root.style.setProperty('--rp-vp-w', w + 'px');
    root.style.setProperty('--rp-vp-h', h + 'px');
    root.style.setProperty('--rp-chrome-scale', narrow ? '0.75' : (landscape ? '0.6' : '1'));
  }

  /**
   * 响应式收紧（C9，**布局级**）：**已废止**（2026-10-13 用户真机铁证）。
   *
   * 沿革：先用 `transform: scale()`（只缩视觉、不动布局盒 ⇒ 省下的像素变成空白、正文槽仍按未收紧占位算）；
   * 后改 `zoom`（会动布局盒）。`zoom` 有两个致命副作用：①缩放的是元素自身的**盒宽**，块级子元素
   * 本就占满容器宽 ⇒ 收窄后**右侧空出一条**（用户真机铁证：「现在左右为什么不占满屏幕？留一个左右黑边」）；
   * ②各模板外壳层级不同，"不含正文槽的极大子树"判定在 paged/scroll 两类模板上行为不一致。
   *
   * ⇒ 收紧的**正确落点**是模板自身用相对单位（`em`/`vh`）声明页眉页脚尺寸（天生随屏适配），
   * 由分页引擎把**剩余空间如实交还正文槽**（见 `template-browser-flow.js` 的恒定冻结）。引擎不再施加任何缩放。
   */

  /**
   * 正文**整行网格**（分页正确性的前提）：把正文槽的实际行高写进 `--rp-line-height`，
   * 由布局基线（`md/template-layout.css` 同名段）把"槽顶部内边距 + 段间距"归一到整行。
   *
   * 为什么必须有它（2026-10-13 用户真机铁证「最后一行只显示一半」）：分页按整行裁切页框，
   * 只有全部行盒落在同一网格上，页边界才恒在行与行之间。模板各行内边距/段距千差万别
   * （实测段距 ≈0.48 行 ⇒ 每段推移半行）⇒ 不归一必然出现半行裁切。
   *
   * 为什么必须在**页眉/页脚占位量取之前**：内边距与段距会改变正文高（影响分页口径）。
   */
  function applyLineGrid() {
    var slot = document.querySelector('[data-reader-flow="body"]');
    if (!slot) return;
    try {
      var lh = parseFloat(window.getComputedStyle(slot).lineHeight);
      if (!isFinite(lh) || !(lh > 0)) return;
      // ⚠️ 写在**正文槽**上（不是 `<html>`）：真机实测写在 `<html>` 会让模板页眉/插画整体被推高
      // 90px（正文区从 74% 掉到 58%，正是用户抱怨的「头尾太宽」回归）——本变量的消费者只有
      // 正文槽及其后代，作用域收在最内层最安全。
      // 代价：C1 双结构换壳会整块替换根壳 innerHTML ⇒ 槽上的变量会随新壳丢失，因此
      // [applyPageShell] 换壳后必须**重新写入**（否则续页段距回落 1em、网格失效、末行被裁半）。
      slot.style.setProperty('--rp-line-height', lh + 'px');
      roundChildHeights(slot, lh);
    } catch (error) {
      // 取不到就不写：基线回落 0px 内边距 / 1em 段距，分页仍可用（仅可能半行裁切）
    }
  }

  /** 网格残量容差（CSS px）：小于它的"不足一行"视为浮点噪声，不补。 */
  var GRID_RESIDUE_EPSILON = 0.02;
  /** 补齐的最小视在残量（相对行高）：太小就不补（否则每块都多出一条缝）。 */
  var GRID_RESIDUE_MIN_RATIO = 0.08;

  /**
   * 把每个**直接子块**的总占位向上补齐到**整行**（分页正确性的第二半）。
   *
   * 为什么光有"段距归一到整行"不够（2026-10-14 真机铁证，data-stream）：
   * 章名（`h1`/`.reader-chapter-title`）与图片/代码块的行盒高**不是正文行高的整数倍**
   * （章名字号更大 ⇒ 行盒 = 1.34 × 行高）⇒ 它们之后的每一行都被推移了这段非整行残量，
   * 分页裁切边界（按"整行倍数"算出的）就落到某一行中间 ⇒ **末行被裁半**
   * （实测 data-stream 末行只剩约 40% 高度；star-chart 只是残量恰好落在界外才没露出来）。
   *
   * 处理：对每个子块量其**已渲染盒高**，把"盒高 + 既有下边距"补到整行倍数。
   * ⚠️ 必须**叠加**在既有下边距上（不是改写）：段距由布局基线统一到一行（`!important`），
   * 直接改写会把段落间距清零；且必须用 `!important` 写内联（否则被布局基线的 `!important` 压回）。
   * 末块不补：它下面没有内容，补了只会凭空多出一页空白。
   */
  function roundChildHeights(slot, lh) {
    var children = slot.children;
    if (!children || children.length === 0) return;
    var last = children.length - 1;
    for (var i = 0; i < children.length; i++) {
      var el = children[i];
      // 先撤上一轮的补齐：本函数会在换壳/重测/重排时重复调用，不撤会累积
      try { el.style.removeProperty('margin-bottom'); } catch (error) { /* 旧内核 */ }
      if (i === last) continue;
      var box = 0;
      try { box = el.getBoundingClientRect().height; } catch (error) { box = 0; }
      if (!(box > 0)) continue;
      var lines = Math.ceil(box / lh - GRID_RESIDUE_EPSILON);
      var extra = lines * lh - box;
      if (!(extra > lh * GRID_RESIDUE_MIN_RATIO)) continue;
      var base = 0;
      try { base = parseFloat(window.getComputedStyle(el).marginBottom); } catch (error) { base = 0; }
      if (!isFinite(base) || base < 0) base = 0;
      try {
        el.style.setProperty('margin-bottom', (base + extra) + 'px', 'important');
      } catch (error) { /* 旧内核：不补，退化为改造前行为 */ }
    }
  }

  /**
   * 竖排规格（4.15 C10）：判定正文槽位的书写方向并置 `html[data-rp-writing]`，
   * 由引擎布局基线施加"确定宽高 / 段距走 block 轴 / 接排不重复缩进 / 独立图片按区域约束"。
   *
   * 判定优先级：①作者在正文槽位上显式声明 `data-rp-writing`（最直白、可离线验）；
   * ②否则读作者 CSS 生效后的 computed `writing-mode`（作者用类选择器写竖排时也能识别）。
   */
  function applyWritingMode() {
    var slot = document.querySelector('[data-reader-flow="body"]');
    var writing = 'horizontal';
    if (slot) {
      var explicit = slot.getAttribute('data-rp-writing');
      if (explicit) {
        writing = explicit === 'vertical' ? 'vertical' : 'horizontal';
      } else {
        try {
          var computed = window.getComputedStyle(slot).writingMode || '';
          if (computed.indexOf('vertical') === 0) writing = 'vertical';
        } catch (error) {
          writing = 'horizontal';                 // 取不到 computed：按横排兜底，不抛
        }
      }
    }
    state.writing = writing;
    document.documentElement.setAttribute('data-rp-writing', writing);
  }

  /**
   * 素材治理（4.15 C11）：正文图片**按需解码 + 去重**，**解码就绪后才回调提交可翻阅页面**。
   *
   * 三个失守点与对应防线：
   * 1. **有图即贴**（先提交页面、图片稍后才解码）⇒ 翻页时图片跳变/闪白 ⇒ 先解码再结算；
   * 2. **同一素材重复解码**（同图多页/多块）⇒ 浪费内存与解码时间 ⇒ `decodedSrcs` 按 src 去重；
   * 3. **单图卡死拖住整页**（解码永不作数）⇒ 有界等待（`MATERIAL_TIMEOUT_MS`）后照样提交。
   *
   * 无图（纯文字章节）时**同步**回调 ⇒ 保持"初始化即结算一次"的既有语义不变
   * （否则纯文字章节拿不到 stable，宿主只能 8s 超时回落 canvas）。
   */
  function materializeMaterials(done) {
    var slots = document.querySelectorAll('[data-reader-flow="body"]');
    var pending = [];
    var total = 0;
    for (var i = 0; i < slots.length; i++) {
      var imgs = slots[i].querySelectorAll('img');
      for (var j = 0; j < imgs.length; j++) {
        total++;
        var src = imgs[j].currentSrc || imgs[j].getAttribute('src') || '';
        if (!src) continue;                       // 无 src 的占位图无需解码
        if (state.decodedSrcs[src]) continue;     // 同一素材只解码一次（去重）
        state.decodedSrcs[src] = true;
        pending.push(imgs[j]);
      }
    }
    state.imgCount = total;
    state.decodedCount = 0;
    if (!pending.length) {
      done();
      return;
    }
    var remaining = pending.length;
    var finished = false;
    var timer = setTimeout(finish, MATERIAL_TIMEOUT_MS);
    function finish() {
      if (finished) return;                       // 幂等：超时与"全部完成"只取先到者
      finished = true;
      clearTimeout(timer);
      done();
    }
    function oneDone() {
      state.decodedCount++;
      remaining--;
      if (remaining <= 0) finish();
    }
    for (var k = 0; k < pending.length; k++) {
      decodeImage(pending[k], oneDone);
    }
  }

  /** 单图解码（优先 `img.decode()`；旧内核无该 API 时退化为 load/error 事件等待）。 */
  function decodeImage(img, done) {
    try {
      if (typeof img.decode === 'function') {
        // 解码失败也必须继续（单图坏掉不该让整页提交不了）
        img.decode().then(done, done);
        return;
      }
    } catch (error) {
      // 落到事件兜底
    }
    if (img.complete) {
      done();
      return;
    }
    var settled = false;
    function once() {
      if (settled) return;
      settled = true;
      done();
    }
    img.addEventListener('load', once, false);
    img.addEventListener('error', once, false);
  }

  /** 分页结果上报（由 template-browser-flow.js 调用）。 */
  function onFlowSettled(pageIndex, pageCount, costMs, progressRatio) {
    state.pageIndex = pageIndex;
    state.pageCount = pageCount;
    // 页码/总页数只有沙箱知道（分页是它算的）⇒ 每次结算后回填 `page`/`pageCount` 槽位，
    // 否则作者的页脚页码永远停在占位文本上（宿主 init 里没有这两个字段）
    refreshFields();
    var payload = { pageIndex: pageIndex, pageCount: pageCount };
    // 内容比例（可选）：宿主据此保存进度 ⇒ 与恢复侧同归一化（见 SEND_TYPES 注释）
    if (typeof progressRatio === 'number' && isFinite(progressRatio)) {
      payload.progressRatio = Math.max(0, Math.min(1, progressRatio));
    }
    send('stable', payload);
    if (typeof costMs === 'number') send('metrics', { costMs: costMs });
    // archive 契约钩子：排版完成（afterLayout，载荷 `pages`）与本页切换（pageChange，载荷 `page`）
    // —— 主题据此刷新"每页元素变化"（页眉/页脚/进度）与启动每页动图。缺 `page/pages` 时
    // 主题（如「明日香」）会在 `event.pages.forEach` / `page.querySelector` 上**直接失效**。
    var shell = authorPage();
    emitHook('afterLayout', { pages: shell ? [shell] : [], pageCount: state.pageCount, generation: 0 });
    emitHook('pageChange', { page: shell, pageIndex: state.pageIndex, pageCount: state.pageCount });
    // 初次/换壳后补一次动效事件：主题在 afterLayout 里 `stop()` 清了旧动画（换壳后旧节点已
    // 脱离文档），必须再给一次 motionChange 才会在新节点上重新起动画（否则首屏与续页都无动图）。
    emitHook('motionChange', motionEvent());
  }

  /**
   * 富渲染注入驱动（阶段 4.14）：调用阶段 3.8/3.9 注入器在**沙箱文档内**的运行时。
   *
   * 为什么不另写一套：注入器本身就是"对当前 document 里的 pre.mermaid / 公式定界符做渲染"，
   * 在沙箱里执行 ⇒ 渲染的正是模板正文，单源无分叉。
   *
   * ⚠️ 全局名必须与 Kotlin 侧 `MdRichRenderInjector.StatusGlobal` 一致（契约测试锁定）。
   */
  function runRichRender() {
    var status = window.__legadoMdRichRender;
    if (!status || typeof status.run !== 'function') {
      // 宿主按需注入（无富渲染元素时不注入运行时）⇒ 明确回报"不可用"，不静默、不报错
      send('renderState', { state: 'inject-unavailable' });
      return;
    }
    try {
      Promise.resolve(status.run())
        .then(function () {
          // 计数随状态回发（同一字段内编码）：宿主/真机 L2 据此判定"图表真的画出来了"，
          // 而不是只看"脚本跑了"（后者在空文档上也会成立）
          send('renderState', {
            state: 'inject-done,mermaid=' + (status.mermaid || 0) +
              ',math=' + (status.math || 0) +
              ',code=' + (status.code || 0) + domFacts()
          });
        })
        .catch(function (error) {
          reportError('rich-render-failed', error);
          send('renderState', { state: 'inject-failed' });
        });
    } catch (error) {
      reportError('rich-render-failed', error);
      send('renderState', { state: 'inject-failed' });
    }
  }

  /**
   * DOM 事实（诊断用，**不含正文内容**）：正文槽位数 / 槽位内 HTML 长度 / mermaid 节点数。
   *
   * 为什么随状态回发：沙箱跨源 ⇒ 宿主看不到这些事实；缺了它，"图表没画出来"只能靠猜
   * （是正文没进槽位？还是注入器没找到节点？）。
   */
  function domFacts() {
    var slots = document.querySelectorAll('[data-reader-flow="body"]');
    var bodyLen = '-1';
    if (slots.length) {
      try {
        bodyLen = String((slots[0].innerHTML || '').length);
      } catch (error) {
        bodyLen = '-2';
      }
    }
    var nodes = -1;
    try {
      nodes = document.querySelectorAll('pre.mermaid,div.mermaid').length;
    } catch (error) {
      nodes = -1;
    }
    // 布局盒宽自证（2026-10-14 用户报「屏幕左右有黑边」）：`window.innerWidth` **含**竖向滚动条、
    // `documentElement.clientWidth` **不含** ⇒ 两者之差 > 0 即"沙箱文档出现了竖向滚动条"（它会挤压
    // 主题可用宽 ~15px，从而在右侧露出引擎底色带 ⇒ 深色主题下观感即"黑边"）。同时给出根壳/正文槽
    // 的实际宽，直接定位"是谁窄了"，不必再靠截图反推。
    var winW = -1, docW = -1, rootW = -1, slotW = -1, bodyW = -1, bodyPad = '-';
    var bodyStyleAttr = '-', safeVars = '-', htmlPad = '-', padRules = '-';
    var bodyBorder = '-', rootGap = '-', readerVarsFacts = '-';
    try {
      winW = window.innerWidth;
      docW = document.documentElement.clientWidth;
      var rootEl = document.getElementById('reader-template-root');
      if (rootEl) rootW = Math.round(rootEl.getBoundingClientRect().width);
      if (slots.length) slotW = Math.round(slots[0].getBoundingClientRect().width);
      // `bodyW` 与 `bodyPad(L/R/mL/mR)` 用来定位"根壳比视口窄"的差额来源（用户报「左右黑边」）
      var bodyCs = window.getComputedStyle(document.body);
      bodyW = Math.round(document.body.getBoundingClientRect().width);
      bodyPad = parseFloat(bodyCs.paddingLeft).toFixed(1) + '/' + parseFloat(bodyCs.paddingRight).toFixed(1) +
        '/' + parseFloat(bodyCs.marginLeft).toFixed(1) + '/' + parseFloat(bodyCs.marginRight).toFixed(1);
      // 决定性自证（2026-10-14）：body 实测有左右内边距、而仓内 CSS/JS 均未声明 ⇒ 必须把
      // ①body 的**内联样式**（谁写的）、②四个 `--reader-safe-*` 的**实际计算值**、
      // ③html 自身的 padding 一并回发，才能定位到具体来源而不是继续猜。
      var rootCs2 = window.getComputedStyle(document.documentElement);
      bodyStyleAttr = String(document.body.getAttribute('style') || '(none)');
      safeVars = ['--reader-safe-left', '--reader-safe-right', '--reader-safe-top', '--reader-safe-bottom']
        .map(function (name) { return String(rootCs2.getPropertyValue(name)).trim() || '(empty)'; })
        .join('/');
      htmlPad = parseFloat(rootCs2.paddingLeft).toFixed(1) + '/' + parseFloat(rootCs2.paddingRight).toFixed(1);
      // 契约变量**落点自证**（2026-10-17 修「archive 主题 `:root{}` 里的 `--reader-*` 被我方**内联默认值**压掉」）：
      // 报 4 个代表性**计算值** + 变量来源 —— `src=inline` 即回归（有人把 `--reader-*` 写回 `<html>` 内联，
      // 内联优先级高于任何无 `!important` 的作者样式表规则 ⇒ 主题声明的行高/段距/字体族会再次静默失效）。
      // 真机（跨源读不到沙箱 DOM）只有这条自证能区分「主题没声明」与「主题声明被压掉」。
      readerVarsFacts = ['--reader-line-height', '--reader-paragraph-spacing', '--reader-font-size',
        '--reader-page-background']
        .map(function (name) {
          return name.slice('--reader-'.length) + '=' +
            (String(rootCs2.getPropertyValue(name)).trim() || '(empty)');
        })
        .join(',') +
        ',src=' + ((String(document.documentElement.getAttribute('style') || '').indexOf('--reader-') >= 0)
          ? 'inline' : 'css');
      // CSSOM 反查（决定性）：直接在沙箱里枚举**所有样式表规则**，报出"选择器命中 body 且声明了 padding"
      // 的那几条 —— 静态扫描已排除我方基线与作者 CSS，只剩运行时/第三方样式表这一可能。
      try {
        var hits = [];
        var sheets = document.styleSheets;
        for (var si = 0; si < sheets.length; si++) {
          var rules = sheets[si].cssRules || [];
          for (var ri = 0; ri < rules.length; ri++) {
            var rule = rules[ri];
            var selText = rule.selectorText || '';
            var ruleText = String(rule.cssText || '').replace(/\s+/g, ' ');
            if (!/(padding|border|margin|inline-size|block-size|width|height)/.test(ruleText) || !selText) continue;
            var hit = false;
            try {
              // 用**实际匹配**判定（`body.matches(sel)`）：这样 `*` / `:root` / 复杂选择器一律不漏
              hit = document.body.matches(selText);
            } catch (error) { hit = false; }
            if (hit) hits.push(selText + '=>' + ruleText.slice(0, 90));
          }
        }
        // 命中 body 且声明几何的规则（2026-10-14 扩展）：页根实测 left=3/宽 477 而 body 的
        // computed padding/margin 全为 0 ⇒ 缺口只可能来自 **border**（border 会吃掉内容盒宽度，
        // 且 border-box 下 rect 宽仍报 480，与自证完全吻合）或未登记的第三方规则 ⇒ 一并回发。
        bodyBorder = [
          'L' + parseFloat(bodyCs.borderLeftWidth).toFixed(1),
          'R' + parseFloat(bodyCs.borderRightWidth).toFixed(1),
          'T' + parseFloat(bodyCs.borderTopWidth).toFixed(1),
          'B' + parseFloat(bodyCs.borderBottomWidth).toFixed(1)
        ].join('/');
        rootGap = (function () {
          var rootEl = document.getElementById('reader-template-root');
          if (!rootEl) return '-';
          var rr = rootEl.getBoundingClientRect();
          var rcs = window.getComputedStyle(rootEl);
          return 'L' + parseFloat(rcs.marginLeft).toFixed(1) + '/W' + Math.round(rr.width) +
            '/ML' + Math.round(rr.left) + ',box=' + rcs.boxSizing;
        })();
        padRules = hits.length ? hits.slice(0, 5).join(' || ') : '(none)';
      } catch (error) {
        padRules = 'err:' + String((error && error.message) || error).slice(0, 40);
      }
    } catch (error) { /* 取不到不冒写假值 */ }
    // 模板外壳几何自证（2026-10-14 用户报「下面展示不全」）：真机截图上页脚**只露出第一行**
    // （`CHAPTER SYNC`），而模板 JSON 的页脚明明含 sync-track / footer-console 三段结构；同时
    // 页面底边与屏幕底之间还有一段空白。只有把"`html`/`body` 的计算高、页根与页根各子块（含页脚
    // 自身的子块）的 rect + display + height"一并回发，才能区分「内容确实没生成」「生成了但被裁」
    // 「百分比高度链断了（父级只有 min-height ⇒ 子 `height:100%` 落回 auto）」这三种完全不同的病因。
    var shellFacts = '-';
    try {
      var pageEl = document.querySelector('[data-reader-page]');
      var shellParts = [];
      function shortCls(node) {
        var name = String((node && node.className) || (node && node.tagName) || '?');
        return name.slice(0, 24);
      }
      function boxOf(node) {
        if (!node) return 'none';
        var r = node.getBoundingClientRect();
        var cs = window.getComputedStyle(node);
        // 同时回发 left/top 与 width：只报 top/height 时无法判断"页根是偏了还是窄了"
        // （左右各留 3 CSS px 与页根整体右移 3px 在截图上完全同形，但修法不同）
        return Math.round(r.left) + ',' + Math.round(r.top) + ',' +
          Math.round(r.width) + 'x' + Math.round(r.height) + ',h=' + cs.height +
          ',' + cs.display + ',op=' + (+cs.opacity);
      }
      shellParts.push('html=' + boxOf(document.documentElement));
      shellParts.push('body=' + boxOf(document.body));
      if (pageEl) {
        shellParts.push('page=' + boxOf(pageEl));
        // 正文槽与其**容器**的 rect（2026-10-14 用户报「续页正文下面大片空白」）：只有同时拿到
        // "槽实际高"与"容器实际高"，才能判定是"容器没被 1fr 拉伸"还是"内容不足一屏" ——
        // 前者是引擎/CSS 缺陷（可修），后者是分页范式固有限制（不该乱改）。
        var slotEl = slots.length ? slots[0] : null;
        if (slotEl) {
          shellParts.push('slot=' + boxOf(slotEl));
          var paperEl = slotEl.parentElement;
          if (paperEl) shellParts.push('paper=' + shortCls(paperEl) + ':' + boxOf(paperEl));
        }
        var pageKids = pageEl.children;
        for (var pi = 0; pi < pageKids.length && pi < 6; pi++) {
          shellParts.push(shortCls(pageKids[pi]) + '=' + boxOf(pageKids[pi]));
        }
        // 页眉/页脚等"非正文"块（archive 用 `data-reader-text-ignore` 标记，见 R4 契约）：
        // 逐个下钻一层 —— 真机截图上页脚**三段结构只露出第一段**，必须量到每段的 rect/display
        // 才能判定是"没生成""被裁"还是"高度为 0"，而不是继续靠截图猜。
        var ignored = pageEl.querySelectorAll('[data-reader-text-ignore]');
        for (var gi = 0; gi < ignored.length && gi < 4; gi++) {
          shellParts.push('ign[' + gi + ']=' + shortCls(ignored[gi]) + ':' + boxOf(ignored[gi]));
          var gk = ignored[gi].children;
          for (var hi = 0; hi < gk.length && hi < 6; hi++) {
            shellParts.push(' >' + shortCls(gk[hi]) + '=' + boxOf(gk[hi]));
          }
        }
        // **装饰层清单**（2026-10-14 用户报「我们自己的少元素」）：作者主题把纯装饰节点统一标
        // `aria-hidden="true"`（asuka 的 grain/hero-rules/portrait/orbit/sync-track/wave/... 都是），
        // 这是**与主题无关的通用信号** ⇒ 回发其数量与逐个 rect，才能证明"装饰层一个都没少"，
        // 而不是只能靠肉眼比截图（"少元素"正是这么被发现的，也必须这么被证明已修）。
        var deco = pageEl.querySelectorAll('[aria-hidden="true"]');
        var decoParts = ['n=' + deco.length];
        for (var di = 0; di < deco.length && di < 10; di++) {
          decoParts.push(shortCls(deco[di]) + ':' + Math.round(deco[di].getBoundingClientRect().width) +
            'x' + Math.round(deco[di].getBoundingClientRect().height));
        }
        shellParts.push('deco=' + decoParts.join(','));
      } else {
        shellParts.push('page=(absent)');
      }
      shellFacts = shellParts.join('|');
    } catch (error) { shellFacts = 'err:' + String((error && error.message) || error).slice(0, 40); }
    return ',iw=' + winW + '/' + docW + ',shell=' + shellFacts +
      ',rootW=' + rootW + ',slotW=' + slotW +
      ',bodyW=' + bodyW + ',bodyPad(L/R/mL/mR)=' + bodyPad +
      ',bodyStyle=' + bodyStyleAttr + ',safe=' + safeVars + ',rvars=' + readerVarsFacts + ',htmlPad=' + htmlPad +
      ',padRules=' + padRules + ',bodyBord(L/R/T/B)=' + bodyBorder + ',rootGap=' + rootGap +
      ',slots=' + slots.length + ',bodyLen=' + bodyLen + ',nodes=' + nodes +
      // 装饰自证（4.8d）：装饰档位与实际被标记的装饰元素数 —— 缺了它"选了档位却看不出差别"
      // 无法区分"沙箱没收到档位"与"模板本来就没有装饰"
      ',decor=' + state.decorCount + ',decorLevel=' + state.decoration +
      // 动效与种子自证（4.15）：改动效/换章后「到底有没有生效」只能靠沙箱回发（跨源读不到 <html>）
      ',motion=' + (state.motion ? 'running' : 'paused') + ',seed=' + state.seed +
      // 响应式/竖排/素材自证（4.15 C9/C10/C11）：这些状态同样只活在沙箱 <html> 上，
      // 缺了自证就只能靠"看起来对不对"判断（本项目已多次栽在"设置了没反应"的静默失效上）
      ',vp=' + state.viewport.w + 'x' + state.viewport.h +
      ',narrow=' + (state.viewport.narrow ? 1 : 0) +
      ',landscape=' + (state.viewport.landscape ? 1 : 0) +
      ',writing=' + state.writing +
      ',imgs=' + state.imgCount + ',decoded=' + state.decodedCount +
      // 字段槽位自证（4.15 C2）：`page`/`time` 这类派生字段失效时页面看起来是正常的（只有页码/时间恒空）
      ',fields=' + populatedFields() +
      // 预算自证（4.19 / NFR-08）：元素数与**当前真的在跑**的动画数 —— 重装饰模板卡顿的
      // 第一手数字，缺了它"P99 超标"只能反推、无法归因到元素/动画规模
      ',elems=' + elementCounts().elements + ',anims=' + elementCounts().animations +
      ',budget=' + budgetState() +
      // 底色自证（2026-10-13）：沙箱跨源 ⇒ 宿主读不到模板的底色，"底部空白是模板底还是引擎底"
      // 这类问题只能靠沙箱回发（缺了它会把"底色不一致"误判成"外壳没铺满"）。
      ',bg=' + backgroundFacts() +
      // 外壳分件自证（诊断）：根壳**直接子元素**的 `名:高@顶` —— "头尾太宽"必须能定位到具体元素
      ',chr=' + rootChildrenFacts();
  }

  /** 模板根壳直接子元素的高度/顶部（诊断用；类名是模板 CSS 名，不含业务数据）。 */
  function rootChildrenFacts() {
    try {
      var root = document.getElementById('reader-template-root');
      if (!root) return '-';
      var out = [];
      var kids = root.children;
      for (var i = 0; i < kids.length && i < 12; i++) {
        var rect = kids[i].getBoundingClientRect();
        var name = kids[i].tagName.toLowerCase();
        var cls = kids[i].getAttribute('class');
        if (cls) name += '.' + String(cls).split(/\s+/)[0];
        out.push(name + ':' + Math.round(rect.height) + '@' + Math.round(rect.top));
      }
      return out.join('/');
    } catch (error) {
      return '-';
    }
  }

  /**
   * 三层底色的 computed 值（`html` / `body` / 模板根壳）；取不到返回 `-`。
   *
   * 为什么值得常驻：模板外壳铺不满视口时，空隙里露出的是**谁**的底色决定了修法
   * （外壳底色≠画布底色 ⇒ 需要外壳铺满；相同 ⇒ 只是观感差）——没有这三个值就只能靠猜。
   */
  function backgroundFacts() {
    function colorOf(el) {
      if (!el) return '-';
      try {
        var c = window.getComputedStyle(el).backgroundColor;
        return c ? c.replace(/\s/g, '') : '-';
      } catch (error) {
        return '-';
      }
    }
    return colorOf(document.documentElement) + '|' + colorOf(document.body) + '|' +
      colorOf(document.getElementById('reader-template-root'));
  }

  /**
   * 已填充的字段槽位键（诊断）：用于区分"槽位没写"与"槽位写了但值取自占位文本"。
   *
   * 为什么需要：`page`/`time` 这类**派生字段**的失效是纯静默的（页面看起来正常，只是页码/时间
   * 恒空）⇒ 没有这个自证就只能靠肉眼逐模板比对页脚。
   */
  function populatedFields() {
    var nodes = document.querySelectorAll('[data-reader-field]');
    var seen = {};
    var out = [];
    for (var i = 0; i < nodes.length; i++) {
      var key = nodes[i].getAttribute('data-reader-field');
      var text = nodes[i].textContent || '';
      if (text.length > 0 && !seen[key]) {
        seen[key] = 1;
        out.push(key);
      }
    }
    out.sort();
    return out.join('|');
  }

  /**
   * 元素数与**实际运行中的动画数**（4.19 budget 自证）。
   *
   * 动画数取 `document.getAnimations()`（Chromium 84+ 的权威口径：只含**当前真的在跑**的动画，
   * 不像数 `animation-name` 那样把"声明了但元素不在当前页"的也算进来）；旧内核无该 API ⇒ 返回 -1。
   */
  function elementCounts() {
    var root = document.getElementById('reader-template-root');
    var scope = root || document.body;
    var elements = -1;
    try {
      elements = scope.querySelectorAll('*').length;
    } catch (error) {
      elements = -1;
    }
    var animations = -1;
    try {
      animations = (typeof document.getAnimations === 'function') ? document.getAnimations().length : -1;
    } catch (error) {
      animations = -1;
    }
    return { elements: elements, animations: animations };
  }

  /** 预算判据（只报告）：任一超限即 `over`。取不到计数（-1）不算超限，避免旧内核误报。 */
  function budgetState() {
    var counts = elementCounts();
    if (counts.elements > MAX_ELEMENTS) return 'over';
    if (counts.animations > MAX_ANIMATIONS) return 'over';
    return 'ok';
  }

  function handleMessage(event) {
    var data = event && event.data;
    if (!data || typeof data !== 'object') return;
    if (data.token !== state.token) return;              // token 不匹配：丢弃（宿主侧同样拒收）
    if (RECEIVE_TYPES.indexOf(data.type) < 0) return;    // 未登记类型：丢弃
    try {
      switch (data.type) {
        case 'set-theme':
          setTheme(data.themeId);
          break;
        case 'remeasure':
          // 帧尺寸可能已变（宿主改尺寸/富渲染改变文档高）⇒ 重测前刷新响应式断点，
          // 否则窄屏/横屏判定会停留在旧值上（`--rp-vp-w/h` 被作者 CSS 消费）；
          // 字号/主题变更也可能改变行高 ⇒ 同步刷新整行网格，否则页边界与网格错位（半行裁切）
          applyLineGrid();
          applyViewport();
          flow.settle(true);
          break;
        case 'goto-page':
          // 宿主驱动翻页：沙箱跨源，宿主无法直接操作本帧滚动
          // `ratio` 优先（进度恢复用**内容比例**口径 ⇒ 与首页壳/续页壳页数差异无关，恢复精确）
          if (typeof data.ratio === 'number') {
            flow.gotoRatio(data.ratio);
          } else {
            flow.goto(data.pageIndex);
          }
          break;
        case 'set-motion':
          // 动效生命周期（4.15 C6）：拖动中/离页/翻页结算期间由宿主下令停并回静态帧
          applyMotion(data.paused !== true);
          send('renderState', { state: 'motion,paused=' + (state.motion ? 0 : 1) });
          break;
        case 'inject-mermaid':
        case 'inject-katex':
          runRichRender();
          break;
        default:
          break;
      }
    } catch (error) {
      reportError('template-message-failed', error);
    }
  }

  /**
   * 安装厂商运行时（mermaid / KaTeX / hljs）。
   *
   * ⚠️ 为什么**不能**把厂商脚本内联进 srcdoc：
   * `mermaid.min.js` 正文里含 "小于号+感叹号+双横线" 序列（实测 11 处）⇒ 一旦内联进 `<script>`，
   * HTML 解析器会进入 "script data escaped" 态、脚本被截断/变形，`window.mermaid` 永不出现
   * （症状：沙箱里 `pre.mermaid` 节点在、但 mermaid 计数恒 0，且**不报错**）。
   * 故改为**按 URL 加载**（`<script src>`）：由宿主经 WebView 请求拦截从 assets 供给，
   * 体量走浏览器流式加载而非消息体（实测：2.4MB 经 `postMessage` 下发会**静默不达**，
   * 表现为"init 从未到达沙箱"），也不经过 HTML 解析 ⇒ 两个隐患同时消除。
   *
   * ⚠️ 本文件自身会被内联进沙箱 `<script>`（见 `ReaderTemplateSandboxDocument`），
   * 因此**注释里也不许写字面的该序列**：构造器虽已做 `<\!--` 转义，但源头保持干净更稳妥
   * （2026-10-10 真机实证：本文件注释里的该序列曾把整个沙箱打死，症状是"零回包"）。
   */
  function installVendors(urls, done) {
    if (!urls || !urls.length) {
      done();
      return;
    }
    var pending = urls.length;
    var finished = false;
    function finish() {
      if (finished) return;
      finished = true;
      done();
    }
    // 有界等待：厂商脚本加载失败也必须继续（降级为代码块展示，不白屏）
    var timer = setTimeout(function () {
      reportError('vendor-load-timeout', 'vendor load timeout');
      finish();
    }, 4000);
    function oneDone() {
      pending--;
      if (pending <= 0) {
        clearTimeout(timer);
        finish();
      }
    }
    for (var i = 0; i < urls.length; i++) {
      (function (url) {
        var script = document.createElement('script');
        script.src = url;
        script.onload = oneDone;
        script.onerror = function () {
          reportError('vendor-load-failed', String(url).split('/').pop());
          oneDone();
        };
        (document.head || document.documentElement).appendChild(script);
      })(urls[i]);
    }
  }

  function init(config) {
    if (!config || typeof config !== 'object') {
      reportError('template-init-invalid', 'init 配置非法');
      return;
    }
    state.token = String(config.token || '');
    state.sessionId = String(config.sessionId || '');
    state.template = config.template || {};
    // 到达即回报：区分"消息没到"与"到了但后续失败"（跨源无调试器，只能靠消息自证）
    send('renderState', { state: 'init-received,vendors=' + (config.vendorUrls ? config.vendorUrls.length : 0) });
    // 厂商运行时**按 URL 加载**（不走消息体：大脚本经 postMessage 下发实测不可靠，
    // 且内联进 srcdoc 会被上文所述序列拖入转义态）—— 加载完成后再继续初始化，
    // 否则 `inject-mermaid` 会在 `window.mermaid` 就位前到达（症状：计数恒 0 且不报错）
    installVendors(config.vendorUrls, function () {
      continueInit(config);
    });
  }

  function continueInit(config) {
    state.pageIndex = typeof config.pageIndex === 'number' ? config.pageIndex : 0;
    setTheme(config.themeId);
    // archive 契约变量必须在**渲染作者 CSS 之前**就位：作者的 `var(--reader-font-size)` 等
    // 在首帧就要解析到真值，晚一拍会先按回落值画一帧再跳变。
    applyReaderVars(config);
    try {
      renderShell();
    } catch (error) {
      reportError('template-render-failed', error);
      return;
    }
    // 作者 JS 必须在**首次钩子之前**同步执行（见 [mountAuthorScript]）：它的 `readerTemplate.on(...)`
    // 注册若晚于首个 `beforeLayout`/`afterLayout`，首屏装饰与动效就永久丢失（跨源、仅真机可见）。
    mountAuthorScript();
    // 整行网格（分页正确性前提）必须最先：它改的是正文槽的内边距/段距 ⇒ 之后再量占位与分页
    applyLineGrid();
    // 响应式断点（C9）必须在**页眉/页脚占位量取之前**刷新：`--rp-vp-w/h` 与 `data-rp-narrow/landscape`
    // 会被作者 CSS 消费（窄屏/横屏采用不同尺寸）⇒ 晚于 `flow.initialize` 就"滞后一拍"，
    // 首屏仍按**未刷新**的尺寸分页（症状：首屏页高与后续页不一致）。
    applyViewport();
    // ⚠️ 顺序即正确性：`flow.initialize` 必须在**正文注入之前**——自研回退分页以"此刻文档高"
    // 作为页眉/页脚等固定占位（注入后量就把正文自身算进占位，页框会被算成 0 高）
    flow.initialize(config.flow);
    applyFields(config.fields);
    applyBody(config.bodyHtml);
    // 整行网格必须**在正文注入之后**再补一次（2026-10-14 真机铁证：data-stream 末行仍被裁半）：
    // 上面的第一次 `applyLineGrid` 时槽里还没有正文 ⇒ 无法对子块（章名/图片/代码块）做整行补齐
    // ⇒ 章名之后的每一行都被推移非整行残量，分页裁切边界落到行中间。此处补齐后立即结算，首屏即正确。
    applyLineGrid();
    // 装饰强度/动效闸门 + 装饰自动分层（4.8d）：在结算之前完成，
    // 让首个 `stable` 就带上最终外观（否则会先按默认档位闪一帧，再等下一次重测）
    applyDecoration(config);
    markDecorations();
    // 字段派生（C2）：电量异步填充；时间按分钟刷新（页码在每次结算后回填）
    watchBattery();
    startClock();
    // 竖排规格（C10）：`data-rp-writing` 同样必须在结算之前就位（滞后一拍就会错轴分页）；
    // 响应式收紧（C9）已在上方 `flow.initialize` 之前完成（见那段注释）。
    applyWritingMode();
    // archive 契约钩子：排版前（beforeLayout）—— 主题在此为段落打语义类
    // （`as-scene`/`as-dialogue`/`as-lead`），这些类必须早于分页结算才参与测量与外观。
    emitHook('beforeLayout', { generation: 0, viewport: authorApi.viewport });
    // archive 契约：`beforePage`（本页每页逻辑）—— 必须在首次结算之前，作者的量取才参与分页
    emitHook('beforePage', pageEvent(currentPageShell()));
    // ⚠️ 两个钩子**会改 DOM**（主题在 beforeLayout 给段落打语义类、在 beforePage 量取并写变量）
    // ⇒ 网格必须**在钩子之后**再补一次：否则语义类带来的高度差会把其后每一行推出网格
    // （症状与 data-stream 同源：分页裁切边界落到行中间、末行被裁半）。
    applyLineGrid();
    // 素材治理（C11）：图片按需解码 + 去重，**解码就绪后才提交可翻阅页面**；
    // 无图（纯文字章节）时同步回调 ⇒ 保持"立即结算一次"的语义不变。
    // 这一次结算是 `stable` 的唯一来源：章节不含富渲染元素时不会有后续 `remeasure`，
    // 不结算则宿主只能 8s 超时回落 canvas（症状：纯文字章节套模板后仍是旧排版）。
    materializeMaterials(function () {
      try {
        flow.settle(false);
      } catch (error) {
        reportError('template-flow-failed', error);
      }
      // ready 携带 DOM 事实：正文是否真的进了槽位，在"就绪"这一刻即可判定
      send('renderState', { state: 'ready' + domFacts() + animationFacts() });
    });
  }

  var flow = window.ReaderTemplateFlow || {
    initialize: function () {},
    settle: function () {}
  };

  window.ReaderTemplateRuntime = {
    init: init,
    applyFields: applyFields,
    applyBody: applyBody,
    setTheme: setTheme,
    applyDecoration: applyDecoration,
    markDecorations: markDecorations,
    applyPageShell: applyPageShell,
    currentPageShell: currentPageShell,
    applyViewport: applyViewport,
    applyLineGrid: applyLineGrid,
    applyWritingMode: applyWritingMode,
    materializeMaterials: materializeMaterials,
    onFlowSettled: onFlowSettled,
    send: send
  };

  window.addEventListener('message', handleMessage, false);
  // archive 契约钩子：销毁（dispose）—— 主题据此清理定时器/动画；`pagehide` 即帧卸载。
  window.addEventListener('pagehide', function () { emitHook('dispose', null); }, { once: true });
})();
