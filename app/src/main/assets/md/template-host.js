/*
 * template-host.js —— 阅读页模板**宿主侧**运行时（epub-md-rich-rendering 阶段 4.1 / AD-23）
 *
 * 运行环境：宿主 WebView 主文档（**不是**模板沙箱）。职责与安全底线：
 * 1. 用 `sandbox="allow-scripts"` 创建模板 iframe（**只放行脚本、不带同源标记** ⇒ 与宿主隔离）；
 * 2. 生成一次性 token，经 `postMessage` 下发 init（**只走消息通道**，不向页面暴露任何宿主对象）；
 * 3. 接收沙箱消息并按 `ReaderTemplateBridgePolicy` 白名单做**前置粗筛**
 *    （类型/字段/长度/深度/矩形数；token 由 Kotlin 侧再校验一次，双保险）；
 * 4. 模板素材别名统一交 Kotlin 侧解析为可访问 URL（`ReaderAssetPathPolicy`），本文件不自行拼路径；
 * 5. 沙箱异常/超时 ⇒ 保留已显示内容并把错误交回 Kotlin（降级，不白屏）。
 */
(function () {
  'use strict';

  // 与 ReaderTemplateBridgePolicy 的 FROM_WEB 表保持一致（Kotlin 侧另有一份权威表 + 契约测试）。
  var INBOUND_TYPES = {
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

  var MAX_RAW_CHARS = 256 * 1024;
  var MAX_JSON_DEPTH = 4;
  var MAX_SELECTION_RECTS = 64;

  var frame = null;
  var session = { token: '', sessionId: '', onMessage: null };

  function randomToken() {
    var bytes = new Uint8Array(16);
    if (window.crypto && window.crypto.getRandomValues) {
      window.crypto.getRandomValues(bytes);
    } else {
      for (var i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
    }
    var hex = '';
    for (var j = 0; j < bytes.length; j++) hex += ('0' + bytes[j].toString(16)).slice(-2);
    return hex;
  }

  function depthOf(value, limit) {
    if (value === null || typeof value !== 'object') return 1;
    if (limit <= 0) return Number.MAX_SAFE_INTEGER;
    var max = 1;
    for (var key in value) {
      if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
      var child = depthOf(value[key], limit - 1);
      if (child + 1 > max) max = child + 1;
      if (max > MAX_JSON_DEPTH) return max;
    }
    return max;
  }

  /** 前置粗筛：拒收即丢弃并回报诊断；返回 null 表示可交给 Kotlin 侧做权威校验。 */
  function prefilter(raw, message) {
    if (typeof raw !== 'string') raw = JSON.stringify(message || {});
    if (raw.length > MAX_RAW_CHARS) return 'too-long';
    if (!message || typeof message !== 'object') return 'not-object';
    if (message.token !== session.token) return 'bad-token';
    var allowed = INBOUND_TYPES[message.type];
    if (!allowed) return 'unknown-type';
    var depth = depthOf(message, MAX_JSON_DEPTH + 1);
    if (depth > MAX_JSON_DEPTH) return 'too-deep';
    for (var key in message) {
      if (!Object.prototype.hasOwnProperty.call(message, key)) continue;
      if (key === 'type' || key === 'token' || key === 'sessionId') continue;
      if (allowed.indexOf(key) < 0) return 'unexpected-field:' + key;
    }
    if (message.type === 'selection' && message.rects && message.rects.length > MAX_SELECTION_RECTS) {
      return 'too-many-rects';
    }
    return null;
  }

  function handleSandboxMessage(event) {
    // 仅接受本会话模板帧的消息（不同源 + 引用比对，双重约束）
    if (!frame || event.source !== frame.contentWindow) return;
    var message = event.data;
    var rejection = prefilter(typeof message === 'string' ? message : JSON.stringify(message || {}), message);
    if (rejection) {
      if (session.onMessage) {
        session.onMessage({ type: 'error', code: 'bridge-rejected', message: rejection });
      }
      return;
    }
    if (session.onMessage) session.onMessage(message);
  }

  /**
   * 初始化：创建沙箱帧并下发配置。
   *
   * @param {Object} config
   *   - container: 宿主容器元素
   *   - srcdoc: 模板沙箱文档（含 template-runtime.js + template-browser-flow.js）
   *   - template / fields / bodyHtml / themeId / flow / pageIndex
   *   - onMessage(message): 通过粗筛的消息回调（Kotlin 侧仍需权威校验）
   */
  function init(config) {
    if (!config || !config.container) throw new Error('template-host: 缺少 container');
    session.token = randomToken();
    session.sessionId = String(config.sessionId || Date.now());
    session.onMessage = config.onMessage || null;

    if (frame && frame.parentNode) frame.parentNode.removeChild(frame);
    frame = document.createElement('iframe');
    // 安全底线：只放行脚本，**不带**同源标记 ⇒ 与宿主不同源，无法触达宿主 DOM/存储
    frame.setAttribute('sandbox', 'allow-scripts');
    frame.setAttribute('referrerpolicy', 'no-referrer');
    frame.style.width = '100%';
    frame.style.height = '100%';
    frame.style.border = '0';
    frame.srcdoc = String(config.srcdoc || '');
    config.container.appendChild(frame);

    window.addEventListener('message', handleSandboxMessage, false);

    frame.addEventListener('load', function () {
      frame.contentWindow.postMessage({
        type: 'init',
        token: session.token,
        sessionId: session.sessionId,
        template: config.template || {},
        fields: config.fields || {},
        bodyHtml: config.bodyHtml || '',
        themeId: config.themeId || 'day',
        pageIndex: config.pageIndex || 0,
        flow: config.flow || {}
      }, '*');
    }, { once: true });

    return session.token;
  }

  /** 宿主 → 沙箱：白名单内的四种指令。 */
  function post(type, payload) {
    if (!frame || !frame.contentWindow) return false;
    if (['inject-mermaid', 'inject-katex', 'remeasure', 'set-theme'].indexOf(type) < 0) return false;
    var message = { type: type, token: session.token, sessionId: session.sessionId };
    if (payload) {
      for (var key in payload) {
        if (Object.prototype.hasOwnProperty.call(payload, key)) message[key] = payload[key];
      }
    }
    frame.contentWindow.postMessage(message, '*');
    return true;
  }

  function destroy() {
    window.removeEventListener('message', handleSandboxMessage, false);
    if (frame && frame.parentNode) frame.parentNode.removeChild(frame);
    frame = null;
    session.token = '';
    session.onMessage = null;
  }

  window.ReaderTemplateHost = {
    init: init,
    post: post,
    destroy: destroy
  };
})();
