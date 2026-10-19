/* 幽夜回廊 · 行为脚本
   ①段落语义分类（首段 / 对白段 / 场景分隔段，对齐 archive 的 as-lead / as-dialogue / as-scene）
   ②蓝条（本章/本页序列剩余）随翻页递减 ③红条走后端整书进度（CSS 侧 --rp-percent）
   ④整书进度触顶 ⇒ 页眉切「牺牲场景」 ⑤只读语义、无网络、无宿主对象。 */
(function () {
  var rt = window.readerTemplate;
  if (!rt) return;

  var FALLEN_AT = 99.5;

  /**
   * 取**页面壳**（`.dh-page`）。
   *
   * ⚠️ 不能用 `readerTemplate.source` —— 运行时的 `source` 是**正文根**
   * （`data-reader-flow="body"` 的那个元素，见 template-runtime.js 的 `get source()`），
   * 拿它去找 `.dh-page` 会恒为 null（壳是它的**祖先**）⇒ 脚本静默空转。
   * 正确取法：作者根 `#reader-template-root` 下的外壳（换壳会整块替换，故每次都要现取）。
   */
  function shell(event) {
    if (event && event.page && event.page.querySelector) {
      var viaEvent = event.page.querySelector('.dh-page');
      if (viaEvent) return viaEvent;
    }
    var root = document.getElementById('reader-template-root');
    if (!root) return null;
    if (root.classList && root.classList.contains('dh-page')) return root;
    return root.querySelector('.dh-page');
  }

  /** 整书进度（优先取运行时下发的 --rp-percent，取不到再退回宿主字段）。 */
  function progressNow() {
    var raw = '';
    try {
      raw = getComputedStyle(document.documentElement).getPropertyValue('--rp-percent');
    } catch (error) {
      raw = '';
    }
    var value = parseFloat(raw);
    if (isNaN(value)) {
      var fields = rt.fields || {};
      value = typeof fields.progress === 'number' ? fields.progress : 0;
    }
    return value;
  }

  /** R4 导演：把**分页序号**映射为「区域」与「演出态」，写在页壳的 `data-dh-*` 上由 CSS 切片。
   *
   *  为什么必须在 JS 里做：模板运行时只提供 `firstPageHtml` / `otherPageHtml` 两个 HTML 通道
   *  （没有"第 3 个壳"）⇒ "多区域 / 多演出态"只能靠**同一份 HTML + 属性驱动**（见 spec R4 第 12 条）。
   *
   *  映射纪律（保证观感"推进"而不是"闪烁"）：
   *   · **区域**按**章内进度**分四段（4 区轮换，`cathedral → town → grave → cave`）
   *     ⇒ 一次翻页不会跳区；`pageChange`/`beforePage` 上都调用（换壳会整块替换页壳，属性会丢）。
   *   · **演出态**按页号轮换（探索 / 打怪 / 对话）；**末页恒为对话**（收束），
   *     与 `.dh-fallen`（牺牲场景）共同承担"结尾演出"。
   *  幂等：属性不变则不写（避免无谓的属性变更触发样式重算）。 */
  var REGIONS = ['cathedral', 'town', 'grave', 'cave'];
  var PERFS = ['explore', 'battle', 'talk'];
  function direct(event) {
    var root = shell(event);
    if (!root || !root.setAttribute) return;
    var total = rt.pageCount > 0 ? rt.pageCount : 1;
    var index = rt.pageIndex >= 0 ? rt.pageIndex : 0;
    var perRegion = Math.max(1, Math.ceil(total / REGIONS.length));
    var region = REGIONS[Math.min(REGIONS.length - 1, Math.floor(index / perRegion))];
    var perf = index >= total - 1 ? 'talk' : PERFS[index % PERFS.length];
    if (root.getAttribute('data-dh-region') !== region) root.setAttribute('data-dh-region', region);
    if (root.getAttribute('data-dh-perf') !== perf) root.setAttribute('data-dh-perf', perf);
  }

  /** 同步蓝条与牺牲场景。幂等：可被任意钩子重复调用。 */
  function sync(event) {
    var root = shell(event);
    if (!root) return;
    direct(event);
    var total = rt.pageCount > 0 ? rt.pageCount : 1;
    var read = Math.min(1, (rt.pageIndex + 1) / total);
    root.style.setProperty('--dh-stamina', String(Math.max(0, (1 - read) * 100)));
    // 红条 = 整本剩余（宿主 --rp-percent）⇒ 读到末页它本就该归零。
    // 两个判据取"或"：宿主进度可能滞后一帧，而"已到末页"是沙箱自己算得准的末态；
    // 二者语义同源（末页 ⇔ 剩余 0），故合成一个判据，避免"分明读完了却没进牺牲场景"。
    var atLastPage = rt.pageCount > 0 && rt.pageIndex >= rt.pageCount - 1;
    var fallen = progressNow() >= FALLEN_AT || atLastPage;
    var marked = root.className.indexOf('dh-fallen') >= 0;
    if (fallen !== marked) root.classList.toggle('dh-fallen', fallen);
  }

  /**
   * 段落语义分类（**"分段正文样式"的可见主体**，2026-10-17 补齐）。
   *
   * 为什么必须有：archive 导出的主题无一例外都在 `beforeLayout` 里按**正文文本**给段落打语义类
   * （首段 / 对白段 / 场景分隔段），CSS 再据此给首行加重、对白描边、场景居中留白。本主题此前
   * 只在 CSS 里声明了 `.dh-lead` 规则，**没有任何代码给它加类** ⇒ 规则恒空（死规则），
   * 与 archive 主题逐套比对时正是用户报的「段落元素缺失」。
   *
   * 分类阈值**与 archive 严格同口径**（`/^[*＊※·•—_\-\s]{3,}$/` 判场景分隔；`/^[“「『"]/` 判对白；
   * 本章首个非分隔段判首段）⇒ 同一段正文在本主题与 archive 主题里得到**同一分类**，
   * 保证"导入导出 + 换主题"后分段观感连续，而不是各家一套判据。
   *
   * 时机：正文由运行时注入后才发 `beforeLayout`（首屏）与 `beforePage`（**换壳**后重新注入正文），
   * 二者都要挂 —— 只挂前者会让续页壳里的段落丢类（首屏有、翻页后没了）。
   * 幂等：`classList.toggle` 与 `data-dh-seq` 的一次性写入都可重复调用。
   */
  function classifyParagraphs() {
    var source = rt.source;                       // 正文根（与 archive 的 readerTemplate.source 同义）
    if (!source || !source.querySelectorAll) return 0;
    var paragraphs = source.querySelectorAll('.reader-paragraph');
    var lead = true, seq = 0;
    for (var i = 0; i < paragraphs.length; i++) {
      var p = paragraphs[i];
      var text = (p.textContent || '').trim();
      var scene = /^[*＊※·•—_\-\s]{3,}$/.test(text);
      p.classList.toggle('dh-scene', scene);
      p.classList.toggle('dh-dialogue', /^[“「『"]/.test(text));
      var isLead = lead && !!text && !scene;
      p.classList.toggle('dh-lead', isLead);
      if (text && !scene) lead = false;
      // 段序号（场景分隔段不编号）：供 CSS 侧做"悬挂读数"类装饰，重复调用不重号
      if (!p.hasAttribute('data-dh-seq')) p.setAttribute('data-dh-seq', String(++seq));
    }
    return paragraphs.length;
  }

  rt.on('beforeLayout', classifyParagraphs);
  rt.on('beforePage', classifyParagraphs);
  rt.on('beforeLayout', sync);
  rt.on('afterLayout', sync);
  rt.on('pageChange', sync);
  rt.on('fieldsChange', sync);

  // 换壳（C1）会整块替换作者根壳：新壳上的动画必须重新起，这里借 motionChange 再同步一次。
  rt.on('motionChange', function () {
    sync();
  });

  rt.on('dispose', function () {
    var root = shell();
    if (!root) return;
    root.classList.remove('dh-fallen');
    root.style.removeProperty('--dh-stamina');
  });

  sync();
})();
