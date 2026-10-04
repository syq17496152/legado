import{$i as e,$n as t,An as n,Dn as r,Er as i,Fi as a,Ji as o,Jn as s,Li as c,Mi as l,Nn as u,Pi as d,Pr as ee,Sa as f,Ta as p,Vi as m,Wn as h,Xi as g,Zn as te,ar as ne,di as _,fi as v,gn as re,hi as y,ia as b,ii as x,ir as S,ki as C,mi as w,na as T,oi as E,pi as D,qr as ie,ua as O,vi as k,wa as A,xi as j,yi as M,yn as ae,zi as N}from"./vendor-D6hwKDpf.js";import{$ as oe,B as P,I as se,K as F,L as ce,N as I,S as le,V as L,j as R,t as z,z as ue}from"./useOverlay-DE-kNMoB.js";import{i as de,o as B,r as fe}from"./useAppConfig-D-uMgnug.js";import{t as pe}from"./navigation-CMj3oqtr.js";import{t as V}from"./_plugin-vue_export-helper-BDNMzG2s.js";import{l as me}from"./useSync-B8F3cyO3.js";import{a as he,i as ge,n as _e,r as ve,t as ye}from"./MobileToolbarMenu-o_jl2k4B.js";import{a as be,i as xe,n as Se,o as Ce,r as we,t as Te}from"./AppPageHeader-C4RLEfRT.js";function Ee(e){let t=b(!1);function n(){}return{swiping:t,onSwipePointerDown:n,onSwipePointerMove:n,onSwipePointerUp:n,onSwipePointerCancel:n,onSwipeClickCapture:n}}var De=[`data-sidx`],Oe=[`onPointerdown`],ke={class:`ev-sort-item__name`},Ae={style:{display:`flex`,"justify-content":`flex-end`,gap:`8px`}},je=V(j({__name:`ExploreViewSortModal`,props:{show:{type:Boolean},sources:{}},emits:[`update:show`,`confirm`],setup(e,{emit:n}){let r=e,i=n;z(()=>r.show,()=>i(`update:show`,!1));let a=b([]);o(()=>r.show,e=>{e&&(a.value=[...r.sources])});function s(){i(`confirm`,a.value.map(e=>e.fileName)),i(`update:show`,!1)}let l=b(null),u=b(-1),d=b(-1);function ee(e,t){e.preventDefault(),u.value=t,d.value=t;function n(e){if(!l.value)return;let t=l.value.querySelectorAll(`[data-sidx]`);for(let n of t){let t=n.getBoundingClientRect();if(e.clientY>=t.top&&e.clientY<=t.bottom){d.value=Number(n.dataset.sidx);break}}}function r(){window.removeEventListener(`pointermove`,n);let e=u.value,t=d.value;if(u.value=-1,d.value=-1,e>=0&&t>=0&&e!==t){let n=[...a.value],[r]=n.splice(e,1);n.splice(t,0,r),a.value=n}}window.addEventListener(`pointermove`,n),window.addEventListener(`pointerup`,r,{once:!0})}return(n,r)=>{let o=m(`n-button`),h=m(`n-modal`);return c(),D(h,{show:e.show,preset:`card`,title:`书源排序`,class:`ev-sort-modal`,style:{width:`340px`,maxWidth:`95vw`},"mask-closable":!0,"onUpdate:show":r[1]||=e=>i(`update:show`,e)},{footer:g(()=>[v(`div`,Ae,[M(o,{size:`small`,onClick:r[0]||=e=>i(`update:show`,!1)},{default:g(()=>[...r[2]||=[k(`取消`,-1)]]),_:1}),M(o,{size:`small`,type:`primary`,onClick:s},{default:g(()=>[...r[3]||=[k(`确定`,-1)]]),_:1})])]),default:g(()=>[v(`div`,{ref_key:`sortListEl`,ref:l,class:`ev-sort-list`},[(c(!0),y(E,null,N(a.value,(e,n)=>(c(),y(`div`,{key:e.fileName,"data-sidx":n,class:f([`ev-sort-item`,{"ev-sort-item--dragging":u.value===n,"ev-sort-item--drag-over":d.value===n&&u.value!==n}])},[v(`span`,{class:`ev-sort-item__handle`,onPointerdown:e=>ee(e,n)},[M(O(t),{size:16})],40,Oe),v(`span`,ke,p(e.name),1)],10,De))),128))],512)]),_:1},8,[`show`])}}}),[[`__scopeId`,`data-v-d2d6b15c`]]),Me=`legado-request`,Ne=`legado-response`,Pe=`legado-event`,H=`<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">`;function Fe(){return`
<script>
(function() {
  'use strict';

  // 挂起中的请求: id → { resolve, reject }
  var pending = {};

  // 生成唯一 ID（兼容无 crypto 环境的 fallback）
  function uuid() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
      var r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
  }

  // 向父页面发送请求并返回 Promise
  function request(method, args) {
    return new Promise(function(resolve, reject) {
      var id = uuid();
      pending[id] = { resolve: resolve, reject: reject };
      window.parent.postMessage({
        type: '${Me}',
        id: id,
        method: method,
        args: args
      }, '*');
      // 超时保护（60s）
      setTimeout(function() {
        if (pending[id]) {
          pending[id].reject(new Error('Bridge request timeout: ' + method));
          delete pending[id];
        }
      }, 60000);
    });
  }

  // 监听父页面的响应
  window.addEventListener('message', function(e) {
    var d = e.data;
    if (!d || typeof d !== 'object') return;

    if (d.type === '${Ne}' && d.id && pending[d.id]) {
      if (d.error) {
        pending[d.id].reject(new Error(d.error));
      } else {
        pending[d.id].resolve(d.result);
      }
      delete pending[d.id];
    }

    // 父页面推送的事件
    if (d.type === '${Pe}' && typeof window._legadoEventHandler === 'function') {
      window._legadoEventHandler(d.event, d.data);
    }
  });

  // ── 定义 window.legado 对象 ───────────────────────────────────────────

  window.legado = {
    http: {
      get: function(url, headers) {
        return request('http.get', [url, headers || null]);
      },
      post: function(url, body, headers) {
        return request('http.post', [url, body || '', headers || null]);
      }
    },

    config: {
      read: function(key, scope) {
        return request('config.read', [key, scope || null]);
      },
      readJson: function(key, scope) {
        return request('config.readJson', [key, scope || null]);
      },
      write: function(key, value, scope) {
        if (typeof value === 'string') {
          return request('config.write', [key, value, scope || null]);
        }
        return request('config.writeJson', [key, value, scope || null]);
      },
      writeJson: function(key, value, scope) {
        return request('config.writeJson', [key, value, scope || null]);
      }
    },

    // 调用当前书源的任意导出函数
    callSource: function(fnName) {
      var args = Array.prototype.slice.call(arguments, 1);
      return request('callSource', [fnName].concat(args));
    },

    // 导航到另一个发现分类（会替换当前 iframe 内容）
    explore: function(category, page) {
      return request('explore', [category, page || 1]);
    },

    // 显示 toast 通知
    toast: function(msg, type) {
      request('toast', [String(msg), type || 'info']);
    },

    // 打开书籍详情抽屉
    openBook: function(bookUrl) {
      request('openBook', [String(bookUrl)]);
    },

    // 触发全局搜索
    search: function(keyword) {
      request('search', [String(keyword)]);
    },

    // 控制台日志
    log: function(msg) {
      request('log', [String(msg)]);
    },

    // 安装书源（弹出确认安装对话框）
    // url 可以是 legado:// 链接或 https:// 直链
    installSource: function(url) {
      request('installSource', [String(url)]);
    }
  };
})();
<\/script>`}function Ie(){return`
<style>
  :root {
    --bg: #1e1e2e;
    --bg-card: #2a2a3c;
    --bg-hover: #33334a;
    --text: #e0e0e0;
    --text-secondary: #a0a0b0;
    --primary: #64b5f6;
    --primary-hover: #90caf9;
    --border: #3a3a4c;
    --radius: 8px;
    --radius-sm: 4px;
    --shadow: 0 2px 8px rgba(0,0,0,0.3);
    --font: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  }
  @media (prefers-color-scheme: light) {
    :root {
      --bg: #f5f5f5;
      --bg-card: #ffffff;
      --bg-hover: #f0f0f0;
      --text: #333333;
      --text-secondary: #666666;
      --primary: #1976d2;
      --primary-hover: #1565c0;
      --border: #e0e0e0;
      --shadow: 0 2px 8px rgba(0,0,0,0.1);
    }
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html,
  body {
    min-height: 100%;
  }
  body {
    font-family: var(--font);
    background: var(--bg);
    color: var(--text);
    font-size: 14px;
    line-height: 1.5;
    padding: 12px;
    overflow-x: hidden;
  }
  a { color: var(--primary); text-decoration: none; }
  a:hover { color: var(--primary-hover); }
  button, .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 6px 14px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-card);
    color: var(--text);
    cursor: pointer;
    font-size: 13px;
    transition: background 0.15s, border-color 0.15s;
  }
  button:hover, .btn:hover {
    background: var(--bg-hover);
    border-color: var(--primary);
  }
  button.primary, .btn-primary {
    background: var(--primary);
    border-color: var(--primary);
    color: #fff;
  }
  button.primary:hover, .btn-primary:hover {
    background: var(--primary-hover);
  }
  input, select, textarea {
    padding: 6px 10px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-card);
    color: var(--text);
    font-size: 13px;
    outline: none;
    transition: border-color 0.15s;
  }
  input:focus, select:focus, textarea:focus {
    border-color: var(--primary);
  }
  .card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 12px;
    box-shadow: var(--shadow);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    gap: 10px;
  }
  .flex { display: flex; }
  .flex-wrap { flex-wrap: wrap; }
  .gap-sm { gap: 6px; }
  .gap-md { gap: 10px; }
  .mt-sm { margin-top: 8px; }
  .mt-md { margin-top: 16px; }
  .mb-sm { margin-bottom: 8px; }
  .text-sm { font-size: 12px; }
  .text-secondary { color: var(--text-secondary); }
  .truncate { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>`}function Le(e){if(!/<head[\s>]/i.test(e))return e;let t=/<meta[^>]+name=(['"])viewport\1[^>]*>/i;return t.test(e)?e.replace(t,H):e.replace(/<head([^>]*)>/i,`<head$1>\n${H}`)}function U(e){let t=Fe(),n=Ie(),r=Le(e);return/<head[\s>]/i.test(r)?r.replace(/<\/head>/i,`${n}\n${t}\n</head>`):/<html[\s>]/i.test(r)?r.replace(/<html([^>]*)>/i,`<html$1><head>
<meta charset="utf-8">
${H}
${n}
${t}
</head>`):`<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
${H}
${n}
${t}
</head>
<body>
${e}
</body>
</html>`}function W(e){if(!e||typeof e!=`object`)return!1;let t=e;return t.type===`html`&&typeof t.html==`string`}function Re(e){if(typeof e==`string`){let t=e.trim();return t.startsWith(`http://`)||t.startsWith(`https://`)}if(e&&typeof e==`object`){let t=e;return t.type===`url`&&typeof t.url==`string`}return!1}function G(e){if(typeof e==`string`)return e.trim();if(e&&typeof e==`object`){let t=e;if(typeof t.url==`string`)return t.url}return``}var K=`explore.cats`;async function ze(){await P(K)}function Be(e){let t=L(K,e);if(!t)return null;try{let e=JSON.parse(t);return Array.isArray(e)?e.filter(e=>typeof e==`string`):null}catch{return null}}function q(e,t){F(K,e,JSON.stringify(t))}var J=`explore.books`,Ve=864e5;function He(e,t){return`${e}|${t}`}async function Ue(){await P(J)}function We(e,t){let n=L(J,He(e,t));if(!n)return null;try{let e=JSON.parse(n);return!e||typeof e.ts!=`number`||!Array.isArray(e.books)||Date.now()-e.ts>Ve?null:e.books}catch{return null}}function Ge(e,t,n){let r={ts:Date.now(),books:n};F(J,He(e,t),JSON.stringify(r))}var Y={key:0,class:`skeleton-text-block`},Ke=V(j({__name:`AppSkeleton`,props:{variant:{default:`rect`},width:{default:void 0},height:{default:void 0},lines:{default:3}},setup(e){let t=e,n=_(()=>{let e={};return t.width&&(e.width=t.width),t.height&&t.variant!==`cover`&&(e.height=t.height),e});return(r,i)=>e.variant===`text`?(c(),y(`div`,Y,[(c(!0),y(E,null,N(e.lines,t=>(c(),y(`div`,{key:t,class:`skeleton skeleton--text`,style:A(t===e.lines&&e.lines>1?{width:`60%`}:{})},null,4))),128))])):e.variant===`circle`?(c(),y(`div`,{key:1,class:`skeleton skeleton--circle`,style:A(n.value),"aria-hidden":`true`},null,4)):e.variant===`cover`?(c(),y(`div`,{key:2,class:`skeleton skeleton--cover`,style:A(t.width?{width:t.width}:{}),"aria-hidden":`true`},null,4)):(c(),y(`div`,{key:3,class:`skeleton skeleton--rect`,style:A(n.value),"aria-hidden":`true`},null,4))}}),[[`__scopeId`,`data-v-ff01774b`]]),qe={class:`ehr`},Je=[`srcdoc`],Ye=V(j({__name:`ExploreHtmlRenderer`,props:{fileName:{},html:{}},emits:[`open-book`,`search`,`explore`],setup(e,{emit:t}){let n=e,r=t,i=ee(),a=b(null),s=_(()=>U(n.html));function u(e){let t=e.data;if(!t||typeof t!=`object`||t.type!==`legado-request`)return;let n=a.value;if(!n?.contentWindow||e.source!==n.contentWindow)return;let{id:r,method:i,args:o}=t;f(r,i,o??[])}async function f(e,t,n){try{p(e,await m(t,n),null)}catch(t){p(e,null,t instanceof Error?t.message:String(t))}}function p(e,t,n){let r=a.value;r?.contentWindow&&r.contentWindow.postMessage({type:Ne,id:e,result:t,error:n},`*`)}async function m(e,t){switch(e){case`http.get`:{let[e,n]=t;return B(`booksource_http_proxy`,{url:e,method:`GET`,body:null,headers:n??null},35e3)}case`http.post`:{let[e,n,r]=t;return B(`booksource_http_proxy`,{url:e,method:`POST`,body:n??null,headers:r??null},35e3)}case`config.read`:{let[e,r]=t;return B(`config_read`,{scope:r??n.fileName,key:e},1e4)}case`config.readJson`:{let[e,r]=t;return B(`config_read_json`,{scope:r??n.fileName,key:e},1e4)}case`config.write`:{let[e,r,i]=t;return typeof r==`string`?await B(`config_write`,{scope:i??n.fileName,key:e,value:r},1e4):await B(`config_write_json`,{scope:i??n.fileName,key:e,value:r},1e4),null}case`config.writeJson`:{let[e,r,i]=t;return await B(`config_write_json`,{scope:i??n.fileName,key:e,value:r},1e4),null}case`callSource`:{let[e,...r]=t;return B(`booksource_call_fn`,{fileName:n.fileName,fnName:String(e),args:r},35e3)}case`explore`:{let[e,n]=t;return r(`explore`,e,n??1),null}case`toast`:{let[e,n]=t;switch(n){case`success`:i.success(e);break;case`error`:i.error(e);break;case`warning`:i.warning(e);break;default:i.info(e)}return null}case`openBook`:{let[e]=t;return r(`open-book`,e),null}case`search`:{let[e]=t;return r(`search`,e),null}case`log`:{let[e]=t;return console.log(`[${n.fileName}]`,e),null}case`installSource`:{let[e]=t;return window.dispatchEvent(new CustomEvent(`app:install-source`,{detail:{url:e}})),null}default:throw Error(`未知的 bridge 方法: ${e}`)}}return d(()=>{window.addEventListener(`message`,u)}),l(()=>{window.removeEventListener(`message`,u)}),o(s,()=>{let e=a.value;e&&(e.srcdoc=s.value)}),(e,t)=>(c(),y(`div`,qe,[v(`iframe`,{ref_key:`iframeRef`,ref:a,class:`ehr__frame`,sandbox:`allow-scripts`,srcdoc:s.value,frameborder:`0`},null,8,Je)]))}}),[[`__scopeId`,`data-v-ae609c96`]]),Xe={class:`eur`},Ze={key:0,class:`eur__loading`},Qe=[`src`],$e=V(j({__name:`ExploreUrlRenderer`,props:{url:{}},setup(e){let t=e,n=b(!0);o(()=>t.url,()=>{n.value=!0});function r(){n.value=!1}return(t,i)=>{let a=m(`n-spin`);return c(),y(`div`,Xe,[M(ie,{name:`eur-fade`},{default:g(()=>[n.value?(c(),y(`div`,Ze,[M(a,{size:`small`})])):w(``,!0)]),_:1}),v(`iframe`,{ref:`iframeRef`,class:`eur__frame`,src:e.url,sandbox:`allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox`,referrerpolicy:`no-referrer`,onLoad:r},null,40,Qe)])}}}),[[`__scopeId`,`data-v-74f00424`]]),et={key:0,class:`ses__skeleton`},tt={class:`ses__skeleton-cats`},nt={class:`ses__skeleton-grid`},rt={key:1,class:`ses__error`},it={key:0,class:`ses__cats`},at=[`onClick`],X={key:0,class:`ses__loading-overlay`},ot={key:0,class:`ses__error`},Z={key:4,class:`ses__empty`},st={key:1,class:`ses__pagination`},ct=[`disabled`],lt=[`disabled`,`onClick`],ut=[`disabled`],dt={key:3,class:`ses__empty`},ft=V(j({__name:`SourceExploreSection`,props:{source:{},active:{type:Boolean},prefetch:{type:Boolean},showCovers:{type:Boolean},displayMode:{},reloadVersion:{}},emits:[`select`,`open-book`,`search`,`refreshing`],setup(e,{emit:t}){let n=e,r=t,i=ee(),{runExplore:a,clearExploreCache:s}=le(),l=b([]),u=b(!1),h=b(``);function te(e,t){return e.length===t.length&&e.every((e,n)=>e===t[n])}function ne(e,t){return e.length===t.length&&e.every((e,n)=>e.bookUrl===t[n].bookUrl)}let re=!1,x=b(``),S=b([]),C=b(!1),T=b(``),O=b(null),k=b(null),A=b(!1),j=b(!1),ae=b(!1),oe=b(!1),P=0,se=0,F=b(1),ce=_(()=>{let e=Math.max(1,F.value-3),t=F.value+3,n=[];for(let r=e;r<=t;r++)n.push(r);return n});function I(e){e<1||C.value||R(x.value,e)}async function L(e,t=!1){let r=++P;h.value=``;let i=Be(n.source.fileName);if(i!=null){l.value=i,u.value=!1;let o=i.length===0;!t&&!ae.value&&R(o?``:e&&i.includes(e)?e:i[0]),(async()=>{try{let e=await a(n.source.fileName,`GETALL`);if(r!==P||!Array.isArray(e))return;let t=e.filter(e=>typeof e==`string`);te(l.value,t)||(l.value=t,q(n.source.fileName,t),t.length&&!t.includes(x.value)&&R(t[0]))}catch{}})();return}u.value=!0;try{let i=await a(n.source.fileName,`GETALL`);if(r!==P)return;if(Array.isArray(i)){let r=i.filter(e=>typeof e==`string`),a=r.length===0||r.length===1&&r[0]===``;l.value=a?[]:r,q(n.source.fileName,l.value),t||(a?await R(``):r.length&&await R(e&&r.includes(e)?e:r[0]))}else(Re(i)||W(i))&&(l.value=[`发现`],q(n.source.fileName,[`发现`]),t||(x.value=`发现`,ae.value=!0,Re(i)?(k.value=G(i),O.value=null):W(i)&&(O.value=i.html,k.value=null),S.value=[]))}catch(e){r===P&&(h.value=e instanceof Error?e.message:String(e))}finally{r===P&&(u.value=!1)}}async function R(e,t=1){let r=++se;x.value=e,F.value=t,T.value=``,ae.value=!0;let o=re;if(re=!1,t===1&&!o){let t=We(n.source.fileName,e);if(t){O.value=null,k.value=null,S.value=t,C.value=!1,(async()=>{try{let t=await a(n.source.fileName,e,1);if(r!==se)return;if(Re(t)){k.value=G(t),O.value=null,S.value=[];return}if(W(t)){O.value=t.html,k.value=null,S.value=[];return}let i=Array.isArray(t)?t:[];Ge(n.source.fileName,e,i),ne(S.value,i)||(S.value=i)}catch{}})();return}}C.value=!0;try{let i=await a(n.source.fileName,e,t);if(r!==se)return;if(Re(i))k.value=G(i),O.value=null,S.value=[];else if(W(i))O.value=i.html,k.value=null,S.value=[];else{O.value=null,k.value=null;let r=Array.isArray(i)?i:[];S.value=r,t===1&&Ge(n.source.fileName,e,r)}}catch(t){r===se&&(T.value=t instanceof Error?t.message:String(t),i.error(`加载 ${e} 失败: ${T.value}`))}finally{r===se&&(C.value=!1)}}async function z(){if((n.active||n.prefetch)&&!C.value&&!(u.value&&A.value&&!j.value)){if(!A.value||j.value){let e=j.value?x.value:void 0;A.value=!0,j.value=!1;let t=!n.active;t&&(oe.value=!0),await L(e,t)}else n.active&&l.value.length>0&&(!ae.value||oe.value)&&(oe.value=!1,await R(x.value||l.value[0]))}}let ue=b(!1);async function de(){ue.value=!0,r(`refreshing`,!0);let e=x.value;try{await s(n.source.fileName),re=!0,await L(e),i.success(`刷新成功`)}catch(e){re=!1,i.error(`刷新失败: ${e instanceof Error?e.message:String(e)}`)}finally{ue.value=!1,r(`refreshing`,!1)}}return d(()=>{z()}),o(()=>[n.active,n.prefetch],([e,t])=>{(e||t)&&z()},{immediate:!0}),o(()=>n.reloadVersion,(e,t)=>{e!==void 0&&t!==void 0&&e!==t&&(n.active?de():(j.value=!0,z()))}),(t,n)=>{let i=m(`n-spin`);return c(),y(`div`,{class:f([`ses`,{"ses--fullheight":k.value!==null||O.value!==null}])},[u.value?(c(),y(`div`,et,[v(`div`,tt,[(c(),y(E,null,N(4,e=>M(Ke,{key:`cat-${e}`,variant:`rect`,width:`72px`,height:`28px`})),64))]),v(`div`,nt,[(c(),y(E,null,N(6,e=>M(Ke,{key:`card-${e}`,variant:`rect`,height:`112px`})),64))])])):h.value?(c(),y(`div`,rt,`加载失败: `+p(h.value),1)):l.value.length>0||k.value!==null||O.value!==null||ae.value?(c(),y(E,{key:2},[l.value.length>0?(c(),y(`div`,it,[(c(!0),y(E,null,N(l.value,e=>(c(),y(`button`,{key:e,class:f([`ses__cat-btn`,{"ses__cat-btn--active":e===x.value}]),onClick:t=>R(e)},p(e),11,at))),128))])):w(``,!0),v(`div`,{class:f([`ses__books-wrap`,{"ses__books-wrap--fullheight":k.value!==null||O.value!==null}])},[M(ie,{name:`ses-fade`},{default:g(()=>[C.value?(c(),y(`div`,X,[M(i,{size:`small`})])):w(``,!0)]),_:1}),T.value?(c(),y(`div`,ot,p(T.value),1)):k.value?(c(),D($e,{key:1,url:k.value},null,8,[`url`])):O.value?(c(),D(Ye,{key:2,html:O.value,"file-name":e.source.fileName,onOpenBook:n[0]||=e=>r(`open-book`,e),onSearch:n[1]||=e=>r(`search`,e),onExplore:R},null,8,[`html`,`file-name`])):S.value.length?(c(),y(`div`,{key:3,class:f([`ses__grid`,{"ses__grid--cover":e.displayMode===`cover`,"ses__grid--list":e.displayMode===`list`}])},[(c(!0),y(E,null,N(S.value,t=>(c(),D(Se,{key:t.bookUrl,book:t,"show-cover":e.displayMode===`cover`?!0:e.showCovers??!0,"source-type":e.source.sourceType,"display-mode":e.displayMode??`card`,onSelect:n=>r(`select`,t,e.source.fileName)},null,8,[`book`,`show-cover`,`source-type`,`display-mode`,`onSelect`]))),128))],2)):C.value?w(``,!0):(c(),y(`div`,Z,`暂无数据`))],2),!O.value&&!k.value&&(S.value.length>0||F.value>1)?(c(),y(`div`,st,[v(`button`,{class:`ses__page-btn`,disabled:F.value===1||C.value,onClick:n[2]||=e=>I(F.value-1)},` 上一页 `,8,ct),(c(!0),y(E,null,N(ce.value,e=>(c(),y(`button`,{key:e,class:f([`ses__page-btn`,{"ses__page-btn--active":e===F.value}]),disabled:C.value,onClick:t=>I(e)},p(e),11,lt))),128)),v(`button`,{class:`ses__page-btn`,disabled:C.value,onClick:n[3]||=e=>I(F.value+1)},` 下一页 `,8,ut)])):w(``,!0)],64)):(c(),y(`div`,dt,`该书源没有发现分类`))],2)}}}),[[`__scopeId`,`data-v-eff796b9`]]),pt={class:`ev-header__sub`},mt=[`onContextmenu`,`onPointerdown`],ht={class:`ev-source-tab__name`},gt={key:0,class:`ev-subtitle`},_t={class:`ev-subtitle__text`},vt={key:1,class:`ev-subtitle ev-subtitle--empty`},yt={class:`ev-content app-scrollbar`},bt={style:{display:`flex`,"justify-content":`flex-end`}},xt=V(j({__name:`ExploreView`,setup(t){let l=ce(),ie=pe(),j=se(),P=R(),F=le(),L=ee(),B=me(),{sources:V}=i(l),{runChapterList:Se,cancelTask:De,clearExploreCache:Oe}=F,{cardSizes:ke,activeSizeKey:Ae,activeSize:Me,style:Ne,setSize:Pe}=ge(`explore`),H=_(()=>l.explorableSources),Fe=b(!0),Ie=_(()=>Fe.value||l.loading||l.capabilityDetecting),Le=I({namespace:`explore.activeTab`,version:1,defaults:()=>({tab:``}),migrate:()=>null,legacyKeys:[]}),U=_({get:()=>Le.state.tab,set:e=>Le.replace({tab:e})}),W=I({namespace:`explore.tabLayout`,version:1,defaults:()=>({mode:`single`}),migrate:()=>null,legacyKeys:[]});function Re(e){return e===`multi`?`multi`:`single`}let G=_({get:()=>Re(W.state.mode),set:e=>W.replace({mode:e})}),K=_(()=>G.value===`multi`);function Be(){let e=K.value?`single`:`multi`;G.value=e,e===`single`&&C(()=>{requestAnimationFrame(()=>{dn()})})}let q=I({namespace:`explore.disclaimer`,version:1,defaults:()=>({hidden:!1}),migrate:()=>null,legacyKeys:[]}),J=b(!1),Ve=b(!1);async function He(){Ve.value&&(await q.replace({hidden:!0}),ue(`[Disclaimer] hidden=true 写入后端完成`)),J.value=!1}let We=I({namespace:`explore.tabOrder`,version:1,defaults:()=>({order:[]}),migrate:({readLegacy:e})=>{let t=e(`explore-tab-order`);if(!t)return null;try{let e=JSON.parse(t);return{order:Array.isArray(e)?e.filter(e=>typeof e==`string`):[]}}catch{return null}},legacyKeys:[`explore-tab-order`]}),Ge=_(()=>We.state.order),Y=_(()=>{let e=Ge.value,t=H.value.filter(e=>l.isExploreUserEnabled(e.fileName));if(!e.length)return t;let n=e.map(e=>t.find(t=>t.fileName===e)).filter(e=>!!e),r=t.filter(t=>!e.includes(t.fileName));return[...n,...r]}),Ke=_(()=>new Set(Y.value.map(e=>e.fileName)));function qe(e){We.replace({order:[...e]})}let Je=b(!1);z(()=>J.value,()=>{J.value=!1});function Ye(){Je.value=!0}let Xe=_(()=>H.value.find(e=>e.fileName===U.value)),Ze=_(()=>{let e=U.value;return e?!!(l.getCachedCapabilities(e)?.has(`search`)&&l.isSearchUserEnabled(e)):!1}),Qe=b(!1),$e=b(!1),et=T({});function tt(e){et[e]=(et[e]??0)+1}function nt(){for(let e of l.sources)tt(e.fileName)}async function rt(){Qe.value=!0;try{U.value&&tt(U.value)}finally{setTimeout(()=>{Qe.value=!1},600)}}function it(e){Qe.value=e}async function at(){if(!$e.value){$e.value=!0;try{await nn()}finally{$e.value=!1}}}let X=b(!0),ot=I({namespace:`explore.displayMode`,version:1,defaults:()=>({mode:`card`}),migrate:()=>null,legacyKeys:[]}),Z=_({get:()=>ot.state.mode,set:e=>ot.replace({mode:e})}),st=_(()=>[{label:`卡片模式`,key:`mode-card`,disabled:Z.value===`card`},{label:`封面模式`,key:`mode-cover`,disabled:Z.value===`cover`},{label:`列表模式`,key:`mode-list`,disabled:Z.value===`list`},...Z.value===`cover`?[]:[{label:X.value?`隐藏封面`:`显示封面`,key:`toggle-covers`}],...ke.map(e=>({label:`卡片大小：${e.label}`,key:`size-${e.key}`,disabled:Ae.value===e.key})),{label:`书源排序`,key:`sort`},{label:`刷新当前书源`,key:`refresh`},{label:`全部重载`,key:`reload-all`}]);function ct(e){e===`mode-card`?Z.value=`card`:e===`mode-cover`?Z.value=`cover`:e===`mode-list`?Z.value=`list`:e===`toggle-covers`?X.value=!X.value:e.startsWith(`size-`)?Pe(ve(e.slice(5),Ae.value)):e===`sort`?Ye():e===`refresh`?rt():e===`reload-all`&&at()}let{showDrawer:lt,drawerBookUrl:ut,drawerFileName:dt,drawerSourceName:xt,drawerSourceType:St,drawerBookMeta:Ct,openDetail:wt,openDetailByUrl:Tt}=Ce({sources:V,onOpenDetail:({book:e})=>{}});function Et(){U.value&&ie.navigateToSearch(U.value)}function Dt(e,t){Tt(e,t)}let Q=T({visible:!1,x:0,y:0,source:null});z(()=>Q.visible,()=>{Q.visible=!1});let Ot=_(()=>{let e=Q.source;if(!e)return[];let t=l.getCachedCapabilities(e.fileName)?.has(`search`)&&l.isSearchUserEnabled(e.fileName),n=[];return t&&n.push({label:`使用此书源搜索`,key:`search`}),n.push({label:`禁用书源`,key:`disable`},{type:`divider`,key:`d1`,label:``},{label:`删除书源`,key:`delete`}),n});function kt(e,t){e.preventDefault(),Q.source=t,Q.x=e.clientX,Q.y=e.clientY,Q.visible=!0}let $=null,At=0,jt=0,Mt=null;function Nt(e,t){Mt=t,At=e.clientX,jt=e.clientY,$=setTimeout(()=>{$=null,Q.source=Mt,Q.x=At,Q.y=jt,Q.visible=!0},600)}function Pt(e){if($===null)return;let t=e.clientX-At,n=e.clientY-jt;Math.sqrt(t*t+n*n)>8&&(clearTimeout($),$=null)}function Ft(){$!==null&&(clearTimeout($),$=null)}async function It(e){Q.visible=!1;let t=Q.source;if(t){if(e===`search`)ie.navigateToSearch(t.fileName);else if(e===`disable`)try{await l.toggleSource(t.fileName,!1,t.sourceDir),L.success(`已禁用「${t.name}」`)}catch(e){L.error(`禁用失败: ${e instanceof Error?e.message:String(e)}`)}else e===`delete`&&B.warning({title:`删除书源`,content:`确认删除「${t.name}」？此操作将删除磁盘文件，不可恢复。`,positiveText:`删除`,negativeText:`取消`,onPositiveClick:async()=>{try{await oe(t.fileName,t.sourceDir),await fe(`app:booksource-reload`,{scope:`single`,fileName:t.fileName}),L.success(`已删除`)}catch(e){L.error(`删除失败: ${e instanceof Error?e.message:String(e)}`)}}})}}let{getShelfId:Lt,ensureLoaded:Rt,isPrivateShelfBook:zt}=j,{privacyExitTick:Bt}=i(P),{showReader:Vt,readerChapterUrl:Ht,readerChapterName:Ut,readerFileName:Wt,readerChapters:Gt,readerCurrentIndex:Kt,readerBookInfo:qt,readerSourceType:Jt,readerShelfId:Yt,readerChapterGroups:Xt,readerActiveGroupIndex:Zt,applySourceSwitchToReader:Qt,onReadChapter:$t}=be({showDrawer:lt,drawerBookUrl:ut,drawerFileName:dt,privacyExitTick:Bt,runChapterList:Se,cancelTask:De,ensureShelfLoaded:Rt,getShelfId:Lt,isPrivateShelfBook:zt,onTrackReaderOpen:()=>{}});o(U,(e,t)=>{});let en=[];async function tn(e){let t=l.explorableSources.some(t=>t.fileName===e);l.invalidateCapability(e),await Oe(e),await l.loadSources();let n=l.explorableSources.some(t=>t.fileName===e);t&&n&&tt(e)}async function nn(){l.invalidateAllCapabilities(),nt(),await Oe(),await l.loadSources()}d(async()=>{try{await Promise.all([q.ready,Le.ready,W.ready,l.ensureCapsLoaded(),ze(),Ue()]),q.state.hidden||(J.value=!0),await l.loadSources(),await l.detectAllCapabilities(),l.explorableSources.some(e=>e.fileName===U.value)||await Le.replace({tab:l.explorableSources[0]?.fileName??``}),un.value&&(pn=fn(un.value)),await C(),requestAnimationFrame(()=>{dn()}),en.push(await de(`booksource:changed`,async e=>{let{fileName:t,reason:n}=e.payload??{};if(t){if(n===`toggle`)return;await tn(t)}else await nn()})),en.push(await de(`app:booksource-reload`,async e=>{let{scope:t,fileName:n}=e.payload??{};t===`single`&&n?await tn(n):await nn()})),en.push(await de(`app:view-reload`,async e=>{e.payload?.view===`explore`&&await at()}))}finally{Fe.value=!1}}),a(()=>{pn?.(),en.forEach(e=>e())});function rn(e){let t=Y.value;if(t.length<2)return;let n=t.findIndex(e=>e.fileName===U.value);n<0||(e===`next`&&n<t.length-1?U.value=t[n+1].fileName:e===`prev`&&n>0&&(U.value=t[n-1].fileName))}let{onSwipePointerDown:an,onSwipePointerMove:on,onSwipePointerUp:sn,onSwipePointerCancel:cn,onSwipeClickCapture:ln}=Ee({onSwipeLeft:()=>rn(`next`),onSwipeRight:()=>rn(`prev`)}),un=b(null);function dn(){K.value||un.value&&un.value.querySelector(`.n-tabs-tab--active`)?.scrollIntoView({block:`nearest`,inline:`center`,behavior:`smooth`})}function fn(e){let t,n=!1;function r(e){function n(t){if(K.value)return;let n=Math.abs(t.deltaX)>Math.abs(t.deltaY)?t.deltaX:t.deltaY;n!==0&&(t.preventDefault(),e.scrollLeft+=n)}e.addEventListener(`wheel`,n,{passive:!1}),t=()=>e.removeEventListener(`wheel`,n)}let i=e.querySelector(`.n-tabs-nav-scroll-wrapper`);if(i)n=!0,r(i);else{let i=new MutationObserver(()=>{let t=e.querySelector(`.n-tabs-nav-scroll-wrapper`);t&&(n=!0,i.disconnect(),r(t))});return i.observe(e,{childList:!0,subtree:!0}),setTimeout(()=>{n||i.disconnect()},5e3),()=>{i.disconnect(),t?.()}}return()=>t?.()}let pn;return o(()=>[U.value,Y.value.map(e=>e.fileName).join(`|`),G.value],async()=>{await C(),requestAnimationFrame(()=>{dn()})}),(t,i)=>{let a=m(`n-button`),o=m(`n-tooltip`),l=m(`n-dropdown`),d=m(`n-tab-pane`),ee=m(`n-tabs`),_=m(`n-checkbox`),b=m(`n-modal`);return c(),y(`div`,{class:`explore-view`,style:A(O(Ne))},[M(Te,{title:`发现`},{"title-extra":g(()=>[v(`span`,pt,p(H.value.length)+` 个发现源`,1)]),actions:g(()=>[M(ye,{options:st.value,onSelect:ct},{default:g(()=>[M(o,{trigger:`hover`},{trigger:g(()=>[M(a,{size:`small`,quaternary:``,class:f([`ev-mode-btn`,{"ev-mode-btn--active":Z.value===`card`}]),onClick:i[0]||=e=>Z.value=`card`},{icon:g(()=>[M(O(s),{size:14})]),_:1},8,[`class`])]),default:g(()=>[i[19]||=k(` 卡片模式 `,-1)]),_:1}),M(o,{trigger:`hover`},{trigger:g(()=>[M(a,{size:`small`,quaternary:``,class:f([`ev-mode-btn`,{"ev-mode-btn--active":Z.value===`cover`}]),onClick:i[1]||=e=>Z.value=`cover`},{icon:g(()=>[M(O(te),{size:14})]),_:1},8,[`class`])]),default:g(()=>[i[20]||=k(` 封面模式 `,-1)]),_:1}),M(o,{trigger:`hover`},{trigger:g(()=>[M(a,{size:`small`,quaternary:``,class:f([`ev-mode-btn`,{"ev-mode-btn--active":Z.value===`list`}]),onClick:i[2]||=e=>Z.value=`list`},{icon:g(()=>[M(O(h),{size:14})]),_:1},8,[`class`])]),default:g(()=>[i[21]||=k(` 列表模式 `,-1)]),_:1}),Z.value===`cover`?w(``,!0):(c(),D(o,{key:0,trigger:`hover`},{trigger:g(()=>[M(a,{size:`small`,quaternary:``,onClick:i[3]||=e=>X.value=!X.value},{icon:g(()=>[X.value?(c(),D(O(S),{key:0,size:14})):(c(),D(O(ne),{key:1,size:14}))]),_:1})]),default:g(()=>[k(` `+p(X.value?`隐藏封面`:`显示封面`),1)]),_:1})),M(l,{trigger:`click`,options:O(ke).map(e=>({label:e.label,key:e.key})),value:O(Ae),onSelect:O(Pe)},{default:g(()=>[M(o,{trigger:`hover`},{trigger:g(()=>[M(a,{size:`small`,quaternary:``},{icon:g(()=>[M(O(s),{size:14})]),_:1})]),default:g(()=>[k(` 卡片大小（`+p(O(Me).label)+`） `,1)]),_:1})]),_:1},8,[`options`,`value`,`onSelect`]),M(o,{trigger:`hover`},{trigger:g(()=>[M(a,{size:`small`,quaternary:``,onClick:Ye},{icon:g(()=>[M(O(re),{size:16})]),_:1})]),default:g(()=>[i[22]||=k(` 书源排序 `,-1)]),_:1}),M(o,{trigger:`hover`},{trigger:g(()=>[M(a,{size:`small`,quaternary:``,loading:$e.value,onClick:at},{default:g(()=>[...i[23]||=[k(` 全部重载 `,-1)]]),_:1},8,[`loading`])]),default:g(()=>[i[24]||=k(` 全量重载发现页书源与缓存 `,-1)]),_:1}),M(o,{trigger:`hover`},{trigger:g(()=>[M(a,{size:`small`,quaternary:``,loading:Qe.value,onClick:rt},{icon:g(()=>[M(O(u),{size:16})]),_:1},8,[`loading`])]),default:g(()=>[i[25]||=k(` 刷新当前书源 `,-1)]),_:1})]),_:1},8,[`options`])]),_:1}),M(je,{show:Je.value,"onUpdate:show":i[4]||=e=>Je.value=e,sources:Y.value,onConfirm:qe},null,8,[`show`,`sources`]),Y.value.length?(c(),y(`div`,{key:0,ref_key:`evTabsRef`,ref:un,class:`ev-tabs-wrap`,onPointerdown:i[7]||=(...e)=>O(an)&&O(an)(...e),onPointermove:i[8]||=(...e)=>O(on)&&O(on)(...e),onPointerup:i[9]||=(...e)=>O(sn)&&O(sn)(...e),onPointercancel:i[10]||=(...e)=>O(cn)&&O(cn)(...e),onClickCapture:i[11]||=(...e)=>O(ln)&&O(ln)(...e)},[M(ee,{value:U.value,"onUpdate:value":i[6]||=e=>U.value=e,type:`line`,animated:``,class:f([`ev-tabs app-scrollbar-proxy--hidden`,{"ev-tabs--multi":K.value}])},{suffix:g(()=>[M(o,{trigger:`hover`},{trigger:g(()=>[M(a,{size:`small`,quaternary:``,class:f([`ev-tabs-layout-btn`,{"ev-tabs-layout-btn--active":K.value}]),onClick:x(Be,[`stop`])},{icon:g(()=>[K.value?(c(),D(O(ae),{key:0,size:15})):(c(),D(O(n),{key:1,size:15}))]),_:1},8,[`class`])]),default:g(()=>[k(` `+p(K.value?`切换为单行标签`:`切换为多行标签`),1)]),_:1})]),default:g(()=>[(c(!0),y(E,null,N(Y.value,e=>(c(),D(d,{key:e.fileName,name:e.fileName,"display-directive":`show`},{tab:g(()=>[v(`span`,{class:`ev-source-tab`,onContextmenu:x(t=>kt(t,e),[`prevent`,`stop`]),onPointerdown:t=>Nt(t,e),onPointermove:i[5]||=e=>Pt(e),onPointerup:Ft,onPointercancel:Ft},[v(`span`,ht,p(e.name),1),e.sourceType&&e.sourceType!==`novel`?(c(),D(_e,{key:0,"source-type":e.sourceType,opaque:!0,size:10,class:`ev-source-tab__type-icon`},null,8,[`source-type`])):w(``,!0)],40,mt)]),default:g(()=>[Xe.value?.description?(c(),y(`div`,gt,[v(`span`,_t,p(Xe.value.description),1),Ze.value?(c(),D(a,{key:0,text:``,size:`tiny`,class:`ev-subtitle__search-btn`,onClick:Et},{icon:g(()=>[M(O(r),{size:14})]),default:g(()=>[i[26]||=k(` 使用此书源搜索 `,-1)]),_:1})):w(``,!0)])):Ze.value?(c(),y(`div`,vt,[M(a,{text:``,size:`tiny`,class:`ev-subtitle__search-btn`,onClick:Et},{icon:g(()=>[M(O(r),{size:14})]),default:g(()=>[i[27]||=k(` 使用此书源搜索 `,-1)]),_:1})])):w(``,!0),v(`div`,yt,[M(ft,{source:e,active:U.value===e.fileName,prefetch:Ke.value.has(e.fileName),"show-covers":X.value,"display-mode":Z.value,"reload-version":et[e.fileName]??0,onSelect:O(wt),onOpenBook:t=>Dt(t,e.fileName),onRefreshing:it},null,8,[`source`,`active`,`prefetch`,`show-covers`,`display-mode`,`reload-version`,`onSelect`,`onOpenBook`])])]),_:2},1032,[`name`]))),128))]),_:1},8,[`value`,`class`])],544)):Ie.value?(c(),D(xe,{key:1,title:`书源系统启动中`,desc:`正在加载书源并检测发现能力`})):(c(),D(xe,{key:2,title:`暂无可用的发现书源`,desc:`请先在书源管理中添加支持「发现」的书源`})),M(we,{show:O(lt),"onUpdate:show":i[12]||=t=>e(lt)?lt.value=t:null,"book-url":O(ut),"file-name":O(dt),"source-name":O(xt),"source-type":O(St),"book-meta":O(Ct),onReadChapter:O($t)},null,8,[`show`,`book-url`,`file-name`,`source-name`,`source-type`,`book-meta`,`onReadChapter`]),M(he,{show:O(Vt),"onUpdate:show":i[13]||=t=>e(Vt)?Vt.value=t:null,"current-index":O(Kt),"onUpdate:currentIndex":i[14]||=t=>e(Kt)?Kt.value=t:null,"chapter-url":O(Ht),"chapter-name":O(Ut),"file-name":O(Wt),chapters:O(Gt),"shelf-book-id":O(Yt),"book-info":O(qt),"source-type":O(Jt),"chapter-groups":O(Xt),"initial-group-index":O(Zt),onAddedToShelf:i[15]||=e=>Yt.value=e,onSourceSwitched:O(Qt)},null,8,[`show`,`current-index`,`chapter-url`,`chapter-name`,`file-name`,`chapters`,`shelf-book-id`,`book-info`,`source-type`,`chapter-groups`,`initial-group-index`,`onSourceSwitched`]),M(b,{show:J.value,"onUpdate:show":i[17]||=e=>J.value=e,"mask-closable":!1,"close-on-esc":!1,preset:`card`,title:`使用须知`,style:{maxWidth:`480px`,width:`92vw`},bordered:!1},{footer:g(()=>[v(`div`,bt,[M(a,{type:`primary`,onClick:He},{default:g(()=>[...i[29]||=[k(`我已了解`,-1)]]),_:1})])]),default:g(()=>[i[30]||=v(`div`,{class:`disclaimer-body`},[v(`p`,null,` 本软件所有书源均来源于社区用户共享，软件本身不提供、不存储任何内容。 `),v(`p`,null,` 书源内容由第三方网站提供，版权归原作者及原网站所有。若您发现任何书源涉及侵权内容，请立即停止使用该书源，并通知相关内容提供方。 `),v(`p`,null,` 使用书源所产生的一切法律责任由使用者本人承担，与本软件开发者无关。 `)],-1),M(_,{checked:Ve.value,"onUpdate:checked":i[16]||=e=>Ve.value=e,class:`disclaimer-check`},{default:g(()=>[...i[28]||=[k(` 不再显示 `,-1)]]),_:1},8,[`checked`])]),_:1},8,[`show`]),M(l,{trigger:`manual`,show:Q.visible,x:Q.x,y:Q.y,options:Ot.value,onSelect:It,onClickoutside:i[18]||=e=>Q.visible=!1},null,8,[`show`,`x`,`y`,`options`])],4)}}}),[[`__scopeId`,`data-v-9d0fd162`]]);export{xt as default};