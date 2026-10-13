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
    // ⚠️ 关键：换壳会**整块替换根壳 innerHTML**（新壳里当然没有正文）⇒ 必须把正文注入回来，
    // 否则翻到续页就是"页眉页脚还在、正文全空"（2026-10-10 C1 真机实证：slot 高塌成 16px、页数 1/1）。
    // 用 `applyBody` 一并回填字段槽位（新壳里的页码/时间等占位同样需要真值）。
    applyBody(state.bodyHtml);
    // 换壳会**整块替换根壳 innerHTML** ⇒ 新槽是全新元素，槽上的整行网格变量随之丢失
    // （真机实测续页 `grid=-`/`pt=0`，段距回落 1em ⇒ 续页末行重新被裁半）⇒ 必须重新写入
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
      case 'pageCount': return String(state.pageCount);
      case 'percent':
        return typeof state.fields.progress === 'number' ? String(state.fields.progress) : null;
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
      var value = (supplied === undefined || supplied === null) ? derivedField(key) : String(supplied);
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
    var containers = document.querySelectorAll('[data-reader-flow="body"]');
    if (!containers.length) {
      // 模板未声明正文槽位 ⇒ 视为作者错误，保留当前显示并报错（不静默吞掉）
      reportError('template-missing-body-slot', '模板未声明 data-reader-flow="body"');
      return;
    }
    for (var i = 0; i < containers.length; i++) {
      containers[i].innerHTML = state.bodyHtml;
    }
    state.revision++;
    send('contentChanged', { revision: state.revision });
  }

  function setTheme(themeId) {
    state.themeId = themeId || 'day';
    document.documentElement.setAttribute('data-reader-theme', state.themeId);
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
    state.motion = !!running;
    document.documentElement.setAttribute('data-reader-motion', state.motion ? 'running' : 'paused');
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
    } catch (error) {
      // 取不到就不写：基线回落 0px 内边距 / 1em 段距，分页仍可用（仅可能半行裁切）
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
    return ',slots=' + slots.length + ',bodyLen=' + bodyLen + ',nodes=' + nodes +
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
    try {
      renderShell();
    } catch (error) {
      reportError('template-render-failed', error);
      return;
    }
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
      send('renderState', { state: 'ready' + domFacts() });
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
})();
