/* 幽夜回廊 · 行为脚本
   ①蓝条（本章/本页序列剩余）随翻页递减 ②红条走后端整书进度（CSS 侧 --rp-percent）
   ③整书进度触顶 ⇒ 页眉切「牺牲场景」 ④只读语义、无网络、无宿主对象。 */
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

  /** 同步蓝条与牺牲场景。幂等：可被任意钩子重复调用。 */
  function sync(event) {
    var root = shell(event);
    if (!root) return;
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
