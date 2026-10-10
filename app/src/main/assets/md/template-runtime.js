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
    stable: ['pageIndex', 'pageCount'],
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
    seed: ''
  };

  /**
   * 装饰强度 → `--rp-decor-scale`（4.8d）。字面量与 Kotlin 侧
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
    var scrolling = template.type === 'scroll';
    var html = scrolling ? (template.scrollHtml || '') : (state.pageIndex === 0
      ? (template.firstPageHtml || '')
      : (template.otherPageHtml || ''));
    mountStyles(template.css || '');
    mountMarkup(html);
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

  /**
   * 内容注入：把宿主真值写进模板声明的槽位。
   * 契约（blueprint §六）：`data-reader-field="bookName|chapterTitle|progress|page|time|battery"`；
   * 正文容器 `data-reader-flow="body"`（可多块 + `data-reader-order`）。
   */
  function applyFields(fields) {
    state.fields = fields || {};
    var nodes = document.querySelectorAll('[data-reader-field]');
    for (var i = 0; i < nodes.length; i++) {
      var key = nodes[i].getAttribute('data-reader-field');
      var value = state.fields[key];
      if (value === undefined || value === null) continue;
      nodes[i].textContent = String(value);
    }
    applyPhase();
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
    var containers = document.querySelectorAll('[data-reader-flow="body"]');
    if (!containers.length) {
      // 模板未声明正文槽位 ⇒ 视为作者错误，保留当前显示并报错（不静默吞掉）
      reportError('template-missing-body-slot', '模板未声明 data-reader-flow="body"');
      return;
    }
    for (var i = 0; i < containers.length; i++) {
      containers[i].innerHTML = bodyHtml || '';
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

  /** 分页结果上报（由 template-browser-flow.js 调用）。 */
  function onFlowSettled(pageIndex, pageCount, costMs) {
    state.pageIndex = pageIndex;
    state.pageCount = pageCount;
    send('stable', { pageIndex: pageIndex, pageCount: pageCount });
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
      ',motion=' + (state.motion ? 'running' : 'paused') + ',seed=' + state.seed;
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
          flow.settle(true);
          break;
        case 'goto-page':
          // 宿主驱动翻页：沙箱跨源，宿主无法直接操作本帧滚动
          flow.goto(data.pageIndex);
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
    // ⚠️ 顺序即正确性：`flow.initialize` 必须在**正文注入之前**——自研回退分页以"此刻文档高"
    // 作为页眉/页脚等固定占位（注入后量就把正文自身算进占位，页框会被算成 0 高）
    flow.initialize(config.flow);
    applyFields(config.fields);
    applyBody(config.bodyHtml);
    // 装饰强度/动效闸门 + 装饰自动分层（4.8d）：在结算之前完成，
    // 让首个 `stable` 就带上最终外观（否则会先按默认档位闪一帧，再等下一次重测）
    applyDecoration(config);
    markDecorations();
    // 立即结算一次：`stable` 是宿主判定"模板渲染成功并拿到页数"的**唯一**信号。
    // 章节不含富渲染元素时不会有后续 `remeasure` ⇒ 若这里不结算，宿主只能等到 8s 超时回落
    // canvas（症状：纯文字章节套模板后仍是旧排版，且日志报 sandbox stable timeout）。
    try {
      flow.settle(false);
    } catch (error) {
      reportError('template-flow-failed', error);
    }
    // ready 携带 DOM 事实：正文是否真的进了槽位，在"就绪"这一刻即可判定
    send('renderState', { state: 'ready' + domFacts() });
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
    onFlowSettled: onFlowSettled,
    send: send
  };

  window.addEventListener('message', handleMessage, false);
})();
