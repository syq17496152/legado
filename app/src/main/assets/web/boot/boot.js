/*
 * 控制台引导壳（四期 §1.1 / REQ-4-101~103）。零外部依赖：fetch + 手动 Bearer 头（不引 axios）。
 * 端点：GET /consoleStatus、POST /uploadConsoleZip（multipart 本地兜底）、POST /consoleInstall。
 * 统一信封 {isSuccess,errorMsg,data,code} ⇒ 取 data；失败一律如实显示，不假装成功。
 */
(function () {
    'use strict';
    var TOKEN_KEY = 'accessToken'; // 与老页同键（modules/web/src/api/axios.ts）
    var POLL_MS = 1200, MAX_POLL = 40; // 轮询间隔 ≥1s，最多 40 次

    var $ = function (id) { return document.getElementById(id); };
    var stateEl = $('state'), hintEl = $('hint'), progressEl = $('progress'), barEl = $('bar');
    var btnInstall = $('btnInstall'), btnUpdate = $('btnUpdate'), btnRollback = $('btnRollback');
    var btnUpload = $('btnUpload'), fileEl = $('file');
    var last = {}, polling = false;

    function hint(m, c) { hintEl.className = 'hint' + (c ? ' ' + c : ''); hintEl.textContent = m || ''; }
    function setBar(p) { barEl.style.width = Math.max(0, Math.min(100, p)) + '%'; }
    function showProgress(on) { progressEl.className = on ? 'progress on' : 'progress'; if (!on) setBar(0); }

    function authHeaders(extra) {
        var h = extra || {}, t = localStorage.getItem(TOKEN_KEY) || '';
        if (t) h['Authorization'] = 'Bearer ' + t;
        return h;
    }

    // 统一信封解析；非 JSON（未注册端点被静态兜底返回 HTML）⇒ 「接口未就绪」如实报错
    function api(path, opts) {
        opts = opts || {};
        opts.headers = authHeaders(opts.headers);
        return fetch(path, opts).then(function (res) {
            var ct = res.headers.get('content-type') || '';
            if (ct.indexOf('application/json') < 0) {
                return res.text().then(function () { throw new Error('接口未就绪（HTTP ' + res.status + '）'); });
            }
            return res.json().then(function (j) {
                if (!res.ok || !j || j.isSuccess !== true) {
                    throw new Error((j && (j.errorMsg || (j.data && j.data.error))) || ('HTTP ' + res.status));
                }
                return j.data;
            });
        });
    }

    // 渲染三态：未安装 / 已装 vX / 有新版 vX（可回退由 previousAvailable 提示）
    function render(d) {
        last = d || {};
        var st = last.state || 'NOT_INSTALLED';
        var iv = (last.installed && last.installed.version) || '';
        var lv = (last.latest && last.latest.version) || '';
        var text;
        if (st === 'INSTALLING') text = '正在安装…';
        else if (st === 'ROLLED_BACK') text = '已回退' + (iv ? ' 到 v' + iv : '') + '，可刷新查看';
        else if (st === 'UPDATE_AVAILABLE') text = '已安装 v' + iv + '，发现新版 v' + lv + (last.previousAvailable ? '（可回退）' : '');
        else if (st === 'INSTALLED') text = '已安装 v' + iv;
        else text = '未安装控制台';
        var chanOk = !!last.channel && last.channel !== 'none';
        text += chanOk ? '（通道：' + last.channel + '）' : '（无可用下载通道）';
        if (last.minAppApiLevelOk === false) text += '  · 请先升级 App';
        stateEl.textContent = text;

        var apiOk = last.minAppApiLevelOk !== false;
        // 三态判定与 App 侧 ConsoleInstaller.State 对齐（NOT_INSTALLED / INSTALLING / INSTALLED / FAILED）；
        // 「有新版」= 已装且 latest 版本 ≠ 已装版本（**不臆造 UPDATE_AVAILABLE 枚举**，防按钮永久禁用）
        var installedVer = last.installed && last.installed.version;
        var latestVer = last.latest && last.latest.version;
        var hasUpdate = st === 'INSTALLED' && !!latestVer && latestVer !== installedVer;
        btnInstall.disabled = !(st === 'NOT_INSTALLED' && chanOk && apiOk);
        btnUpdate.disabled = !(hasUpdate && chanOk && apiOk);
        // 回退：仅当本机留有上一版（previousAvailable）才可点（REQ-4-110 / SC-4-06）
        btnRollback.disabled = !last.previousAvailable;
    }

    function load() {
        return api('/consoleStatus', { method: 'GET' }).then(render).catch(function (e) {
            stateEl.textContent = '状态读取失败';
            hint(e.message, 'err');
        });
    }

    // 通道安装（tasks 1.5.4：入参 {channel?}，出参 {stage,percent} 或 {taskId}）
    function install() {
        hint('正在触发安装…'); showProgress(true); setBar(8);
        var channel = (last.channel && last.channel !== 'none') ? last.channel : undefined;
        return api('/consoleInstall', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ channel: channel })
        }).then(function (d) {
            if (d && d.ok === false) throw new Error(d.error || '安装失败');
            if (d && typeof d.percent === 'number') setBar(d.percent);
            startPoll();
        }).catch(function (e) { showProgress(false); hint(e.message, 'err'); });
    }

    // 轮询安装进度：≥1s，最多 MAX_POLL 次；到终态 location.reload()
    function startPoll() {
        if (polling) return;
        polling = true;
        var n = 0;
        (function tick() {
            setTimeout(function () {
                if (n++ >= MAX_POLL) { polling = false; showProgress(false); hint('安装未完成，请稍后手动刷新页面。', 'err'); return; }
                api('/consoleStatus', { method: 'GET' }).then(function (d) {
                    var st = (d && d.state) || 'NOT_INSTALLED';
                    if (st === 'INSTALLING') { setBar(70); return tick(); }
                    if (st === 'INSTALLED' || st === 'UPDATE_AVAILABLE' || st === 'ROLLED_BACK') {
                        polling = false; setBar(100); hint('安装完成，正在刷新…', 'ok');
                        setTimeout(function () { location.reload(); }, 600);
                        return;
                    }
                    tick(); // 仍为未装态：继续等待
                }).catch(function () { tick(); });
            }, POLL_MS);
        })();
    }

    btnInstall.onclick = install;
    btnUpdate.onclick = install;
    // 回退（REQ-4-110 / SC-4-06）：POST /consoleRollback 后按安装同款轮询刷新
    btnRollback.onclick = function () {
        hint('正在回退到上一版本…'); showProgress(true); setBar(10);
        api('/consoleRollback', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
            .then(function (d) {
                if (!d || d.ok !== true) throw new Error((d && d.error) || '回退失败');
                startPoll();
            }).catch(function (e) { showProgress(false); hint(e.message, 'err'); });
    };
    btnUpload.onclick = function () { fileEl.click(); };

    // 本地上传兜底（REQ-4-104）：multipart 字段名 file（对齐 ContentRoutes ctx.files["file"] 惯例）
    fileEl.onchange = function () {
        var f = fileEl.files && fileEl.files[0];
        if (!f) return;
        if (!/\.zip$/i.test(f.name)) { hint('请选择 zip 安装包', 'err'); fileEl.value = ''; return; }
        var fd = new FormData();
        fd.append('file', f);
        hint('正在上传 ' + f.name + ' …'); showProgress(true); setBar(10);
        api('/uploadConsoleZip', { method: 'POST', body: fd }).then(function (d) {
            // 桩实现信封 isSuccess=true 但 data.ok=false（error 文案）⇒ 如实显示 error
            if (!d || d.ok !== true) throw new Error((d && d.error) || '上传安装失败');
            hint('上传成功，正在安装…', 'ok'); startPoll();
        }).catch(function (e) { showProgress(false); hint(e.message, 'err'); })
            .then(function () { fileEl.value = ''; }, function () { fileEl.value = ''; });
    };

    load();
})();