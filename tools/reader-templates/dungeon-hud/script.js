/* 幽夜回廊 · 行为脚本
   ①蓝条（本章/本页序列剩余）随翻页递减 ②红条走后端整书进度（CSS 侧 --rp-percent）
   ③整书进度触顶 ⇒ 页眉切「牺牲场景」 ④只读语义、无网络、无宿主对象。 */
(function () {
  var rt = window.readerTemplate;
  if (!rt) return;

  var FALLEN_AT = 99.5;

  function shell() {
    return rt.source ? rt.source.querySelector('.dh-page') : null;
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
  function sync() {
    var root = shell();
    if (!root) return;
    var total = rt.pageCount > 0 ? rt.pageCount : 1;
    var read = Math.min(1, (rt.pageIndex + 1) / total);
    root.style.setProperty('--dh-stamina', String(Math.max(0, (1 - read) * 100)));
    var fallen = progressNow() >= FALLEN_AT;
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
