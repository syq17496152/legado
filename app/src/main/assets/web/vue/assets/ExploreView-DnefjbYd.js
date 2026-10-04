import{Cn as e,Fr as t,Gn as n,Hn as r,Ir as i,Jn as a,Kn as o,Nr as s,On as c,Sn as l,Tn as u,Xn as d,ar as f,gn as p,hr as m,jn as h,kn as g,mn as _,pr as v,rn as y,sr as b,ur as x,vt as S,wn as C,xn as w,xr as T,zn as ee}from"./vendor-Beqdr9ys.js";import{A as te,C as ne,N as E,_ as re,a as ie,b as D,n as O,r as k,w as A}from"./vendor-naive-DfnL1-fH.js";import{$ as j,B as M,I as N,K as P,L as ae,N as F,S as oe,V as I,j as L,t as se,z as ce}from"./useOverlay-Brvn8_Zy.js";import{i as R,o as z,r as le}from"./useAppConfig-dtp6cem3.js";import{t as ue}from"./navigation-DJ1mwjZa.js";import{B as de,G as fe,I as pe,S as me,T as he,U as ge,X as _e,Z as ve,c as ye,d as be,y as xe}from"./vendor-icons-B8Zsul0q.js";import{t as B}from"./_plugin-vue_export-helper-BDNMzG2s.js";import{l as Se}from"./useSync-o50lQAtH.js";import{a as Ce,i as we,n as Te,r as Ee,t as De}from"./MobileToolbarMenu-BnpWk_tk.js";import{a as Oe,i as ke,n as Ae,o as je,r as Me,t as Ne}from"./AppPageHeader-IQYiZMbx.js";function Pe(e){let t=m(!1);function n(){}return{swiping:t,onSwipePointerDown:n,onSwipePointerMove:n,onSwipePointerUp:n,onSwipePointerCancel:n,onSwipeClickCapture:n}}var Fe=[`data-sidx`],Ie=[`onPointerdown`],Le={class:`ev-sort-item__name`},Re={style:{display:`flex`,"justify-content":`flex-end`,gap:`8px`}},ze=B(h({__name:`ExploreViewSortModal`,props:{show:{type:Boolean},sources:{}},emits:[`update:show`,`confirm`],setup(t,{emit:n}){let r=t,o=n;se(()=>r.show,()=>o(`update:show`,!1));let h=m([]);f(()=>r.show,e=>{e&&(h.value=[...r.sources])});function _(){o(`confirm`,h.value.map(e=>e.fileName)),o(`update:show`,!1)}let v=m(null),y=m(-1),x=m(-1);function S(e,t){e.preventDefault(),y.value=t,x.value=t;function n(e){if(!v.value)return;let t=v.value.querySelectorAll(`[data-sidx]`);for(let n of t){let t=n.getBoundingClientRect();if(e.clientY>=t.top&&e.clientY<=t.bottom){x.value=Number(n.dataset.sidx);break}}}function r(){window.removeEventListener(`pointermove`,n);let e=y.value,t=x.value;if(y.value=-1,x.value=-1,e>=0&&t>=0&&e!==t){let n=[...h.value],[r]=n.splice(e,1);n.splice(t,0,r),h.value=n}}window.addEventListener(`pointermove`,n),window.addEventListener(`pointerup`,r,{once:!0})}return(n,r)=>{let f=E,m=D;return a(),e(m,{show:t.show,preset:`card`,title:`书源排序`,class:`ev-sort-modal`,style:{width:`340px`,maxWidth:`95vw`},"mask-closable":!0,"onUpdate:show":r[1]||=e=>o(`update:show`,e)},{footer:b(()=>[l(`div`,Re,[g(f,{size:`small`,onClick:r[0]||=e=>o(`update:show`,!1)},{default:b(()=>[...r[2]||=[c(`取消`,-1)]]),_:1}),g(f,{size:`small`,type:`primary`,onClick:_},{default:b(()=>[...r[3]||=[c(`确定`,-1)]]),_:1})])]),default:b(()=>[l(`div`,{ref_key:`sortListEl`,ref:v,class:`ev-sort-list`},[(a(!0),u(p,null,d(h.value,(e,t)=>(a(),u(`div`,{key:e.fileName,"data-sidx":t,class:s([`ev-sort-item`,{"ev-sort-item--dragging":y.value===t,"ev-sort-item--drag-over":x.value===t&&y.value!==t}])},[l(`span`,{class:`ev-sort-item__handle`,onPointerdown:e=>S(e,t)},[g(T(fe),{size:16})],40,Ie),l(`span`,Le,i(e.name),1)],10,Fe))),128))],512)]),_:1},8,[`show`])}}}),[[`__scopeId`,`data-v-d2d6b15c`]]),Be=`legado-request`,Ve=`legado-response`,He=`legado-event`,V=`<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">`;function Ue(){return`
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
        type: '${Be}',
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

    if (d.type === '${Ve}' && d.id && pending[d.id]) {
      if (d.error) {
        pending[d.id].reject(new Error(d.error));
      } else {
        pending[d.id].resolve(d.result);
      }
      delete pending[d.id];
    }

    // 父页面推送的事件
    if (d.type === '${He}' && typeof window._legadoEventHandler === 'function') {
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
<\/script>`}function H(){return`
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
</style>`}function U(e){if(!/<head[\s>]/i.test(e))return e;let t=/<meta[^>]+name=(['"])viewport\1[^>]*>/i;return t.test(e)?e.replace(t,V):e.replace(/<head([^>]*)>/i,`<head$1>\n${V}`)}function We(e){let t=Ue(),n=H(),r=U(e);return/<head[\s>]/i.test(r)?r.replace(/<\/head>/i,`${n}\n${t}\n</head>`):/<html[\s>]/i.test(r)?r.replace(/<html([^>]*)>/i,`<html$1><head>
<meta charset="utf-8">
${V}
${n}
${t}
</head>`):`<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
${V}
${n}
${t}
</head>
<body>
${e}
</body>
</html>`}function W(e){if(!e||typeof e!=`object`)return!1;let t=e;return t.type===`html`&&typeof t.html==`string`}function G(e){if(typeof e==`string`){let t=e.trim();return t.startsWith(`http://`)||t.startsWith(`https://`)}if(e&&typeof e==`object`){let t=e;return t.type===`url`&&typeof t.url==`string`}return!1}function K(e){if(typeof e==`string`)return e.trim();if(e&&typeof e==`object`){let t=e;if(typeof t.url==`string`)return t.url}return``}var Ge=`explore.cats`;async function Ke(){await M(Ge)}function qe(e){let t=I(Ge,e);if(!t)return null;try{let e=JSON.parse(t);return Array.isArray(e)?e.filter(e=>typeof e==`string`):null}catch{return null}}function q(e,t){P(Ge,e,JSON.stringify(t))}var J=`explore.books`,Je=864e5;function Ye(e,t){return`${e}|${t}`}async function Xe(){await M(J)}function Ze(e,t){let n=I(J,Ye(e,t));if(!n)return null;try{let e=JSON.parse(n);return!e||typeof e.ts!=`number`||!Array.isArray(e.books)||Date.now()-e.ts>Je?null:e.books}catch{return null}}function Y(e,t,n){let r={ts:Date.now(),books:n};P(J,Ye(e,t),JSON.stringify(r))}var Qe={key:0,class:`skeleton-text-block`},$e=B(h({__name:`AppSkeleton`,props:{variant:{default:`rect`},width:{default:void 0},height:{default:void 0},lines:{default:3}},setup(e){let n=e,r=w(()=>{let e={};return n.width&&(e.width=n.width),n.height&&n.variant!==`cover`&&(e.height=n.height),e});return(i,o)=>e.variant===`text`?(a(),u(`div`,Qe,[(a(!0),u(p,null,d(e.lines,n=>(a(),u(`div`,{key:n,class:`skeleton skeleton--text`,style:t(n===e.lines&&e.lines>1?{width:`60%`}:{})},null,4))),128))])):e.variant===`circle`?(a(),u(`div`,{key:1,class:`skeleton skeleton--circle`,style:t(r.value),"aria-hidden":`true`},null,4)):e.variant===`cover`?(a(),u(`div`,{key:2,class:`skeleton skeleton--cover`,style:t(n.width?{width:n.width}:{}),"aria-hidden":`true`},null,4)):(a(),u(`div`,{key:3,class:`skeleton skeleton--rect`,style:t(r.value),"aria-hidden":`true`},null,4))}}),[[`__scopeId`,`data-v-ff01774b`]]),et={class:`ehr`},tt=[`srcdoc`],nt=B(h({__name:`ExploreHtmlRenderer`,props:{fileName:{},html:{}},emits:[`open-book`,`search`,`explore`],setup(e,{emit:t}){let i=e,o=t,s=re(),c=m(null),d=w(()=>We(i.html));function p(e){let t=e.data;if(!t||typeof t!=`object`||t.type!==`legado-request`)return;let n=c.value;if(!n?.contentWindow||e.source!==n.contentWindow)return;let{id:r,method:i,args:a}=t;h(r,i,a??[])}async function h(e,t,n){try{g(e,await _(t,n),null)}catch(t){g(e,null,t instanceof Error?t.message:String(t))}}function g(e,t,n){let r=c.value;r?.contentWindow&&r.contentWindow.postMessage({type:Ve,id:e,result:t,error:n},`*`)}async function _(e,t){switch(e){case`http.get`:{let[e,n]=t;return z(`booksource_http_proxy`,{url:e,method:`GET`,body:null,headers:n??null},35e3)}case`http.post`:{let[e,n,r]=t;return z(`booksource_http_proxy`,{url:e,method:`POST`,body:n??null,headers:r??null},35e3)}case`config.read`:{let[e,n]=t;return z(`config_read`,{scope:n??i.fileName,key:e},1e4)}case`config.readJson`:{let[e,n]=t;return z(`config_read_json`,{scope:n??i.fileName,key:e},1e4)}case`config.write`:{let[e,n,r]=t;return typeof n==`string`?await z(`config_write`,{scope:r??i.fileName,key:e,value:n},1e4):await z(`config_write_json`,{scope:r??i.fileName,key:e,value:n},1e4),null}case`config.writeJson`:{let[e,n,r]=t;return await z(`config_write_json`,{scope:r??i.fileName,key:e,value:n},1e4),null}case`callSource`:{let[e,...n]=t;return z(`booksource_call_fn`,{fileName:i.fileName,fnName:String(e),args:n},35e3)}case`explore`:{let[e,n]=t;return o(`explore`,e,n??1),null}case`toast`:{let[e,n]=t;switch(n){case`success`:s.success(e);break;case`error`:s.error(e);break;case`warning`:s.warning(e);break;default:s.info(e)}return null}case`openBook`:{let[e]=t;return o(`open-book`,e),null}case`search`:{let[e]=t;return o(`search`,e),null}case`log`:{let[e]=t;return console.log(`[${i.fileName}]`,e),null}case`installSource`:{let[e]=t;return window.dispatchEvent(new CustomEvent(`app:install-source`,{detail:{url:e}})),null}default:throw Error(`未知的 bridge 方法: ${e}`)}}return n(()=>{window.addEventListener(`message`,p)}),r(()=>{window.removeEventListener(`message`,p)}),f(d,()=>{let e=c.value;e&&(e.srcdoc=d.value)}),(e,t)=>(a(),u(`div`,et,[l(`iframe`,{ref_key:`iframeRef`,ref:c,class:`ehr__frame`,sandbox:`allow-scripts`,srcdoc:d.value,frameborder:`0`},null,8,tt)]))}}),[[`__scopeId`,`data-v-ae609c96`]]),rt={class:`eur`},it={key:0,class:`eur__loading`},at=[`src`],ot=B(h({__name:`ExploreUrlRenderer`,props:{url:{}},setup(e){let t=e,n=m(!0);f(()=>t.url,()=>{n.value=!0});function r(){n.value=!1}return(t,i)=>{let o=ie;return a(),u(`div`,rt,[g(y,{name:`eur-fade`},{default:b(()=>[n.value?(a(),u(`div`,it,[g(o,{size:`small`})])):C(``,!0)]),_:1}),l(`iframe`,{ref:`iframeRef`,class:`eur__frame`,src:e.url,sandbox:`allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox`,referrerpolicy:`no-referrer`,onLoad:r},null,40,at)])}}}),[[`__scopeId`,`data-v-74f00424`]]),st={key:0,class:`ses__skeleton`},ct={class:`ses__skeleton-cats`},lt={class:`ses__skeleton-grid`},ut={key:1,class:`ses__error`},dt={key:0,class:`ses__cats`},X=[`onClick`],ft={key:0,class:`ses__loading-overlay`},Z={key:0,class:`ses__error`},pt={key:4,class:`ses__empty`},mt={key:1,class:`ses__pagination`},ht=[`disabled`],gt=[`disabled`,`onClick`],_t=[`disabled`],vt={key:3,class:`ses__empty`},yt=B(h({__name:`SourceExploreSection`,props:{source:{},active:{type:Boolean},prefetch:{type:Boolean},showCovers:{type:Boolean},displayMode:{},reloadVersion:{}},emits:[`select`,`open-book`,`search`,`refreshing`],setup(t,{emit:r}){let o=t,c=r,h=re(),{runExplore:_,clearExploreCache:v}=oe(),x=m([]),S=m(!1),T=m(``);function ee(e,t){return e.length===t.length&&e.every((e,n)=>e===t[n])}function te(e,t){return e.length===t.length&&e.every((e,n)=>e.bookUrl===t[n].bookUrl)}let ne=!1,E=m(``),D=m([]),O=m(!1),k=m(``),A=m(null),j=m(null),M=m(!1),N=m(!1),P=m(!1),ae=m(!1),F=0,I=0,L=m(1),se=w(()=>{let e=Math.max(1,L.value-3),t=L.value+3,n=[];for(let r=e;r<=t;r++)n.push(r);return n});function ce(e){e<1||O.value||z(E.value,e)}async function R(e,t=!1){let n=++F;T.value=``;let r=qe(o.source.fileName);if(r!=null){x.value=r,S.value=!1;let i=r.length===0;!t&&!P.value&&z(i?``:e&&r.includes(e)?e:r[0]),(async()=>{try{let e=await _(o.source.fileName,`GETALL`);if(n!==F||!Array.isArray(e))return;let t=e.filter(e=>typeof e==`string`);ee(x.value,t)||(x.value=t,q(o.source.fileName,t),t.length&&!t.includes(E.value)&&z(t[0]))}catch{}})();return}S.value=!0;try{let r=await _(o.source.fileName,`GETALL`);if(n!==F)return;if(Array.isArray(r)){let n=r.filter(e=>typeof e==`string`),i=n.length===0||n.length===1&&n[0]===``;x.value=i?[]:n,q(o.source.fileName,x.value),t||(i?await z(``):n.length&&await z(e&&n.includes(e)?e:n[0]))}else(G(r)||W(r))&&(x.value=[`发现`],q(o.source.fileName,[`发现`]),t||(E.value=`发现`,P.value=!0,G(r)?(j.value=K(r),A.value=null):W(r)&&(A.value=r.html,j.value=null),D.value=[]))}catch(e){n===F&&(T.value=e instanceof Error?e.message:String(e))}finally{n===F&&(S.value=!1)}}async function z(e,t=1){let n=++I;E.value=e,L.value=t,k.value=``,P.value=!0;let r=ne;if(ne=!1,t===1&&!r){let t=Ze(o.source.fileName,e);if(t){A.value=null,j.value=null,D.value=t,O.value=!1,(async()=>{try{let t=await _(o.source.fileName,e,1);if(n!==I)return;if(G(t)){j.value=K(t),A.value=null,D.value=[];return}if(W(t)){A.value=t.html,j.value=null,D.value=[];return}let r=Array.isArray(t)?t:[];Y(o.source.fileName,e,r),te(D.value,r)||(D.value=r)}catch{}})();return}}O.value=!0;try{let r=await _(o.source.fileName,e,t);if(n!==I)return;if(G(r))j.value=K(r),A.value=null,D.value=[];else if(W(r))A.value=r.html,j.value=null,D.value=[];else{A.value=null,j.value=null;let n=Array.isArray(r)?r:[];D.value=n,t===1&&Y(o.source.fileName,e,n)}}catch(t){n===I&&(k.value=t instanceof Error?t.message:String(t),h.error(`加载 ${e} 失败: ${k.value}`))}finally{n===I&&(O.value=!1)}}async function le(){if((o.active||o.prefetch)&&!O.value&&!(S.value&&M.value&&!N.value)){if(!M.value||N.value){let e=N.value?E.value:void 0;M.value=!0,N.value=!1;let t=!o.active;t&&(ae.value=!0),await R(e,t)}else o.active&&x.value.length>0&&(!P.value||ae.value)&&(ae.value=!1,await z(E.value||x.value[0]))}}let ue=m(!1);async function de(){ue.value=!0,c(`refreshing`,!0);let e=E.value;try{await v(o.source.fileName),ne=!0,await R(e),h.success(`刷新成功`)}catch(e){ne=!1,h.error(`刷新失败: ${e instanceof Error?e.message:String(e)}`)}finally{ue.value=!1,c(`refreshing`,!1)}}return n(()=>{le()}),f(()=>[o.active,o.prefetch],([e,t])=>{(e||t)&&le()},{immediate:!0}),f(()=>o.reloadVersion,(e,t)=>{e!==void 0&&t!==void 0&&e!==t&&(o.active?de():(N.value=!0,le()))}),(n,r)=>{let o=ie;return a(),u(`div`,{class:s([`ses`,{"ses--fullheight":j.value!==null||A.value!==null}])},[S.value?(a(),u(`div`,st,[l(`div`,ct,[(a(),u(p,null,d(4,e=>g($e,{key:`cat-${e}`,variant:`rect`,width:`72px`,height:`28px`})),64))]),l(`div`,lt,[(a(),u(p,null,d(6,e=>g($e,{key:`card-${e}`,variant:`rect`,height:`112px`})),64))])])):T.value?(a(),u(`div`,ut,`加载失败: `+i(T.value),1)):x.value.length>0||j.value!==null||A.value!==null||P.value?(a(),u(p,{key:2},[x.value.length>0?(a(),u(`div`,dt,[(a(!0),u(p,null,d(x.value,e=>(a(),u(`button`,{key:e,class:s([`ses__cat-btn`,{"ses__cat-btn--active":e===E.value}]),onClick:t=>z(e)},i(e),11,X))),128))])):C(``,!0),l(`div`,{class:s([`ses__books-wrap`,{"ses__books-wrap--fullheight":j.value!==null||A.value!==null}])},[g(y,{name:`ses-fade`},{default:b(()=>[O.value?(a(),u(`div`,ft,[g(o,{size:`small`})])):C(``,!0)]),_:1}),k.value?(a(),u(`div`,Z,i(k.value),1)):j.value?(a(),e(ot,{key:1,url:j.value},null,8,[`url`])):A.value?(a(),e(nt,{key:2,html:A.value,"file-name":t.source.fileName,onOpenBook:r[0]||=e=>c(`open-book`,e),onSearch:r[1]||=e=>c(`search`,e),onExplore:z},null,8,[`html`,`file-name`])):D.value.length?(a(),u(`div`,{key:3,class:s([`ses__grid`,{"ses__grid--cover":t.displayMode===`cover`,"ses__grid--list":t.displayMode===`list`}])},[(a(!0),u(p,null,d(D.value,n=>(a(),e(Ae,{key:n.bookUrl,book:n,"show-cover":t.displayMode===`cover`?!0:t.showCovers??!0,"source-type":t.source.sourceType,"display-mode":t.displayMode??`card`,onSelect:e=>c(`select`,n,t.source.fileName)},null,8,[`book`,`show-cover`,`source-type`,`display-mode`,`onSelect`]))),128))],2)):O.value?C(``,!0):(a(),u(`div`,pt,`暂无数据`))],2),!A.value&&!j.value&&(D.value.length>0||L.value>1)?(a(),u(`div`,mt,[l(`button`,{class:`ses__page-btn`,disabled:L.value===1||O.value,onClick:r[2]||=e=>ce(L.value-1)},` 上一页 `,8,ht),(a(!0),u(p,null,d(se.value,e=>(a(),u(`button`,{key:e,class:s([`ses__page-btn`,{"ses__page-btn--active":e===L.value}]),disabled:O.value,onClick:t=>ce(e)},i(e),11,gt))),128)),l(`button`,{class:`ses__page-btn`,disabled:O.value,onClick:r[3]||=e=>ce(L.value+1)},` 下一页 `,8,_t)])):C(``,!0)],64)):(a(),u(`div`,vt,`该书源没有发现分类`))],2)}}}),[[`__scopeId`,`data-v-eff796b9`]]),bt={class:`ev-header__sub`},xt=[`onContextmenu`,`onPointerdown`],St={class:`ev-source-tab__name`},Ct={key:0,class:`ev-subtitle`},wt={class:`ev-subtitle__text`},Tt={key:1,class:`ev-subtitle ev-subtitle--empty`},Et={class:`ev-content app-scrollbar`},Dt={style:{display:`flex`,"justify-content":`flex-end`}},Ot=B(h({__name:`ExploreView`,setup(r){let h=ae(),y=ue(),ie=N(),M=L(),P=oe(),I=re(),z=Se(),{sources:fe}=S(h),{runChapterList:B,cancelTask:Ae,clearExploreCache:Fe}=P,{cardSizes:Ie,activeSizeKey:Le,activeSize:Re,style:Be,setSize:Ve}=we(`explore`),He=w(()=>h.explorableSources),V=m(!0),Ue=w(()=>V.value||h.loading||h.capabilityDetecting),H=F({namespace:`explore.activeTab`,version:1,defaults:()=>({tab:``}),migrate:()=>null,legacyKeys:[]}),U=w({get:()=>H.state.tab,set:e=>H.replace({tab:e})}),We=F({namespace:`explore.tabLayout`,version:1,defaults:()=>({mode:`single`}),migrate:()=>null,legacyKeys:[]});function W(e){return e===`multi`?`multi`:`single`}let G=w({get:()=>W(We.state.mode),set:e=>We.replace({mode:e})}),K=w(()=>G.value===`multi`);function Ge(){let e=K.value?`single`:`multi`;G.value=e,e===`single`&&ee(()=>{requestAnimationFrame(()=>{_n()})})}let qe=F({namespace:`explore.disclaimer`,version:1,defaults:()=>({hidden:!1}),migrate:()=>null,legacyKeys:[]}),q=m(!1),J=m(!1);async function Je(){J.value&&(await qe.replace({hidden:!0}),ce(`[Disclaimer] hidden=true 写入后端完成`)),q.value=!1}let Ye=F({namespace:`explore.tabOrder`,version:1,defaults:()=>({order:[]}),migrate:({readLegacy:e})=>{let t=e(`explore-tab-order`);if(!t)return null;try{let e=JSON.parse(t);return{order:Array.isArray(e)?e.filter(e=>typeof e==`string`):[]}}catch{return null}},legacyKeys:[`explore-tab-order`]}),Ze=w(()=>Ye.state.order),Y=w(()=>{let e=Ze.value,t=He.value.filter(e=>h.isExploreUserEnabled(e.fileName));if(!e.length)return t;let n=e.map(e=>t.find(t=>t.fileName===e)).filter(e=>!!e),r=t.filter(t=>!e.includes(t.fileName));return[...n,...r]}),Qe=w(()=>new Set(Y.value.map(e=>e.fileName)));function $e(e){Ye.replace({order:[...e]})}let et=m(!1);se(()=>q.value,()=>{q.value=!1});function tt(){et.value=!0}let nt=w(()=>He.value.find(e=>e.fileName===U.value)),rt=w(()=>{let e=U.value;return e?!!(h.getCachedCapabilities(e)?.has(`search`)&&h.isSearchUserEnabled(e)):!1}),it=m(!1),at=m(!1),ot=v({});function st(e){ot[e]=(ot[e]??0)+1}function ct(){for(let e of h.sources)st(e.fileName)}async function lt(){it.value=!0;try{U.value&&st(U.value)}finally{setTimeout(()=>{it.value=!1},600)}}function ut(e){it.value=e}async function dt(){if(!at.value){at.value=!0;try{await ln()}finally{at.value=!1}}}let X=m(!0),ft=F({namespace:`explore.displayMode`,version:1,defaults:()=>({mode:`card`}),migrate:()=>null,legacyKeys:[]}),Z=w({get:()=>ft.state.mode,set:e=>ft.replace({mode:e})}),pt=w(()=>[{label:`卡片模式`,key:`mode-card`,disabled:Z.value===`card`},{label:`封面模式`,key:`mode-cover`,disabled:Z.value===`cover`},{label:`列表模式`,key:`mode-list`,disabled:Z.value===`list`},...Z.value===`cover`?[]:[{label:X.value?`隐藏封面`:`显示封面`,key:`toggle-covers`}],...Ie.map(e=>({label:`卡片大小：${e.label}`,key:`size-${e.key}`,disabled:Le.value===e.key})),{label:`书源排序`,key:`sort`},{label:`刷新当前书源`,key:`refresh`},{label:`全部重载`,key:`reload-all`}]);function mt(e){e===`mode-card`?Z.value=`card`:e===`mode-cover`?Z.value=`cover`:e===`mode-list`?Z.value=`list`:e===`toggle-covers`?X.value=!X.value:e.startsWith(`size-`)?Ve(Ee(e.slice(5),Le.value)):e===`sort`?tt():e===`refresh`?lt():e===`reload-all`&&dt()}let{showDrawer:ht,drawerBookUrl:gt,drawerFileName:_t,drawerSourceName:vt,drawerSourceType:Ot,drawerBookMeta:kt,openDetail:At,openDetailByUrl:jt}=je({sources:fe,onOpenDetail:({book:e})=>{}});function Mt(){U.value&&y.navigateToSearch(U.value)}function Nt(e,t){jt(e,t)}let Q=v({visible:!1,x:0,y:0,source:null});se(()=>Q.visible,()=>{Q.visible=!1});let Pt=w(()=>{let e=Q.source;if(!e)return[];let t=h.getCachedCapabilities(e.fileName)?.has(`search`)&&h.isSearchUserEnabled(e.fileName),n=[];return t&&n.push({label:`使用此书源搜索`,key:`search`}),n.push({label:`禁用书源`,key:`disable`},{type:`divider`,key:`d1`,label:``},{label:`删除书源`,key:`delete`}),n});function Ft(e,t){e.preventDefault(),Q.source=t,Q.x=e.clientX,Q.y=e.clientY,Q.visible=!0}let $=null,It=0,Lt=0,Rt=null;function zt(e,t){Rt=t,It=e.clientX,Lt=e.clientY,$=setTimeout(()=>{$=null,Q.source=Rt,Q.x=It,Q.y=Lt,Q.visible=!0},600)}function Bt(e){if($===null)return;let t=e.clientX-It,n=e.clientY-Lt;Math.sqrt(t*t+n*n)>8&&(clearTimeout($),$=null)}function Vt(){$!==null&&(clearTimeout($),$=null)}async function Ht(e){Q.visible=!1;let t=Q.source;if(t){if(e===`search`)y.navigateToSearch(t.fileName);else if(e===`disable`)try{await h.toggleSource(t.fileName,!1,t.sourceDir),I.success(`已禁用「${t.name}」`)}catch(e){I.error(`禁用失败: ${e instanceof Error?e.message:String(e)}`)}else e===`delete`&&z.warning({title:`删除书源`,content:`确认删除「${t.name}」？此操作将删除磁盘文件，不可恢复。`,positiveText:`删除`,negativeText:`取消`,onPositiveClick:async()=>{try{await j(t.fileName,t.sourceDir),await le(`app:booksource-reload`,{scope:`single`,fileName:t.fileName}),I.success(`已删除`)}catch(e){I.error(`删除失败: ${e instanceof Error?e.message:String(e)}`)}}})}}let{getShelfId:Ut,ensureLoaded:Wt,isPrivateShelfBook:Gt}=ie,{privacyExitTick:Kt}=S(M),{showReader:qt,readerChapterUrl:Jt,readerChapterName:Yt,readerFileName:Xt,readerChapters:Zt,readerCurrentIndex:Qt,readerBookInfo:$t,readerSourceType:en,readerShelfId:tn,readerChapterGroups:nn,readerActiveGroupIndex:rn,applySourceSwitchToReader:an,onReadChapter:on}=Oe({showDrawer:ht,drawerBookUrl:gt,drawerFileName:_t,privacyExitTick:Kt,runChapterList:B,cancelTask:Ae,ensureShelfLoaded:Wt,getShelfId:Ut,isPrivateShelfBook:Gt,onTrackReaderOpen:()=>{}});f(U,(e,t)=>{});let sn=[];async function cn(e){let t=h.explorableSources.some(t=>t.fileName===e);h.invalidateCapability(e),await Fe(e),await h.loadSources();let n=h.explorableSources.some(t=>t.fileName===e);t&&n&&st(e)}async function ln(){h.invalidateAllCapabilities(),ct(),await Fe(),await h.loadSources()}n(async()=>{try{await Promise.all([qe.ready,H.ready,We.ready,h.ensureCapsLoaded(),Ke(),Xe()]),qe.state.hidden||(q.value=!0),await h.loadSources(),await h.detectAllCapabilities(),h.explorableSources.some(e=>e.fileName===U.value)||await H.replace({tab:h.explorableSources[0]?.fileName??``}),gn.value&&(yn=vn(gn.value)),await ee(),requestAnimationFrame(()=>{_n()}),sn.push(await R(`booksource:changed`,async e=>{let{fileName:t,reason:n}=e.payload??{};if(t){if(n===`toggle`)return;await cn(t)}else await ln()})),sn.push(await R(`app:booksource-reload`,async e=>{let{scope:t,fileName:n}=e.payload??{};t===`single`&&n?await cn(n):await ln()})),sn.push(await R(`app:view-reload`,async e=>{e.payload?.view===`explore`&&await dt()}))}finally{V.value=!1}}),o(()=>{yn?.(),sn.forEach(e=>e())});function un(e){let t=Y.value;if(t.length<2)return;let n=t.findIndex(e=>e.fileName===U.value);n<0||(e===`next`&&n<t.length-1?U.value=t[n+1].fileName:e===`prev`&&n>0&&(U.value=t[n-1].fileName))}let{onSwipePointerDown:dn,onSwipePointerMove:fn,onSwipePointerUp:pn,onSwipePointerCancel:mn,onSwipeClickCapture:hn}=Pe({onSwipeLeft:()=>un(`next`),onSwipeRight:()=>un(`prev`)}),gn=m(null);function _n(){K.value||gn.value&&gn.value.querySelector(`.n-tabs-tab--active`)?.scrollIntoView({block:`nearest`,inline:`center`,behavior:`smooth`})}function vn(e){let t,n=!1;function r(e){function n(t){if(K.value)return;let n=Math.abs(t.deltaX)>Math.abs(t.deltaY)?t.deltaX:t.deltaY;n!==0&&(t.preventDefault(),e.scrollLeft+=n)}e.addEventListener(`wheel`,n,{passive:!1}),t=()=>e.removeEventListener(`wheel`,n)}let i=e.querySelector(`.n-tabs-nav-scroll-wrapper`);if(i)n=!0,r(i);else{let i=new MutationObserver(()=>{let t=e.querySelector(`.n-tabs-nav-scroll-wrapper`);t&&(n=!0,i.disconnect(),r(t))});return i.observe(e,{childList:!0,subtree:!0}),setTimeout(()=>{n||i.disconnect()},5e3),()=>{i.disconnect(),t?.()}}return()=>t?.()}let yn;return f(()=>[U.value,Y.value.map(e=>e.fileName).join(`|`),G.value],async()=>{await ee(),requestAnimationFrame(()=>{_n()})}),(n,r)=>{let o=E,f=A,m=ne,h=k,v=O,y=te,S=D;return a(),u(`div`,{class:`explore-view`,style:t(T(Be))},[g(Ne,{title:`发现`},{"title-extra":b(()=>[l(`span`,bt,i(He.value.length)+` 个发现源`,1)]),actions:b(()=>[g(De,{options:pt.value,onSelect:mt},{default:b(()=>[g(f,{trigger:`hover`},{trigger:b(()=>[g(o,{size:`small`,quaternary:``,class:s([`ev-mode-btn`,{"ev-mode-btn--active":Z.value===`card`}]),onClick:r[0]||=e=>Z.value=`card`},{icon:b(()=>[g(T(de),{size:14})]),_:1},8,[`class`])]),default:b(()=>[r[19]||=c(` 卡片模式 `,-1)]),_:1}),g(f,{trigger:`hover`},{trigger:b(()=>[g(o,{size:`small`,quaternary:``,class:s([`ev-mode-btn`,{"ev-mode-btn--active":Z.value===`cover`}]),onClick:r[1]||=e=>Z.value=`cover`},{icon:b(()=>[g(T(ge),{size:14})]),_:1},8,[`class`])]),default:b(()=>[r[20]||=c(` 封面模式 `,-1)]),_:1}),g(f,{trigger:`hover`},{trigger:b(()=>[g(o,{size:`small`,quaternary:``,class:s([`ev-mode-btn`,{"ev-mode-btn--active":Z.value===`list`}]),onClick:r[2]||=e=>Z.value=`list`},{icon:b(()=>[g(T(pe),{size:14})]),_:1},8,[`class`])]),default:b(()=>[r[21]||=c(` 列表模式 `,-1)]),_:1}),Z.value===`cover`?C(``,!0):(a(),e(f,{key:0,trigger:`hover`},{trigger:b(()=>[g(o,{size:`small`,quaternary:``,onClick:r[3]||=e=>X.value=!X.value},{icon:b(()=>[X.value?(a(),e(T(_e),{key:0,size:14})):(a(),e(T(ve),{key:1,size:14}))]),_:1})]),default:b(()=>[c(` `+i(X.value?`隐藏封面`:`显示封面`),1)]),_:1})),g(m,{trigger:`click`,options:T(Ie).map(e=>({label:e.label,key:e.key})),value:T(Le),onSelect:T(Ve)},{default:b(()=>[g(f,{trigger:`hover`},{trigger:b(()=>[g(o,{size:`small`,quaternary:``},{icon:b(()=>[g(T(de),{size:14})]),_:1})]),default:b(()=>[c(` 卡片大小（`+i(T(Re).label)+`） `,1)]),_:1})]),_:1},8,[`options`,`value`,`onSelect`]),g(f,{trigger:`hover`},{trigger:b(()=>[g(o,{size:`small`,quaternary:``,onClick:tt},{icon:b(()=>[g(T(ye),{size:16})]),_:1})]),default:b(()=>[r[22]||=c(` 书源排序 `,-1)]),_:1}),g(f,{trigger:`hover`},{trigger:b(()=>[g(o,{size:`small`,quaternary:``,loading:at.value,onClick:dt},{default:b(()=>[...r[23]||=[c(` 全部重载 `,-1)]]),_:1},8,[`loading`])]),default:b(()=>[r[24]||=c(` 全量重载发现页书源与缓存 `,-1)]),_:1}),g(f,{trigger:`hover`},{trigger:b(()=>[g(o,{size:`small`,quaternary:``,loading:it.value,onClick:lt},{icon:b(()=>[g(T(he),{size:16})]),_:1},8,[`loading`])]),default:b(()=>[r[25]||=c(` 刷新当前书源 `,-1)]),_:1})]),_:1},8,[`options`])]),_:1}),g(ze,{show:et.value,"onUpdate:show":r[4]||=e=>et.value=e,sources:Y.value,onConfirm:$e},null,8,[`show`,`sources`]),Y.value.length?(a(),u(`div`,{key:0,ref_key:`evTabsRef`,ref:gn,class:`ev-tabs-wrap`,onPointerdown:r[7]||=(...e)=>T(dn)&&T(dn)(...e),onPointermove:r[8]||=(...e)=>T(fn)&&T(fn)(...e),onPointerup:r[9]||=(...e)=>T(pn)&&T(pn)(...e),onPointercancel:r[10]||=(...e)=>T(mn)&&T(mn)(...e),onClickCapture:r[11]||=(...e)=>T(hn)&&T(hn)(...e)},[g(v,{value:U.value,"onUpdate:value":r[6]||=e=>U.value=e,type:`line`,animated:``,class:s([`ev-tabs app-scrollbar-proxy--hidden`,{"ev-tabs--multi":K.value}])},{suffix:b(()=>[g(f,{trigger:`hover`},{trigger:b(()=>[g(o,{size:`small`,quaternary:``,class:s([`ev-tabs-layout-btn`,{"ev-tabs-layout-btn--active":K.value}]),onClick:_(Ge,[`stop`])},{icon:b(()=>[K.value?(a(),e(T(be),{key:0,size:15})):(a(),e(T(me),{key:1,size:15}))]),_:1},8,[`class`])]),default:b(()=>[c(` `+i(K.value?`切换为单行标签`:`切换为多行标签`),1)]),_:1})]),default:b(()=>[(a(!0),u(p,null,d(Y.value,t=>(a(),e(h,{key:t.fileName,name:t.fileName,"display-directive":`show`},{tab:b(()=>[l(`span`,{class:`ev-source-tab`,onContextmenu:_(e=>Ft(e,t),[`prevent`,`stop`]),onPointerdown:e=>zt(e,t),onPointermove:r[5]||=e=>Bt(e),onPointerup:Vt,onPointercancel:Vt},[l(`span`,St,i(t.name),1),t.sourceType&&t.sourceType!==`novel`?(a(),e(Te,{key:0,"source-type":t.sourceType,opaque:!0,size:10,class:`ev-source-tab__type-icon`},null,8,[`source-type`])):C(``,!0)],40,xt)]),default:b(()=>[nt.value?.description?(a(),u(`div`,Ct,[l(`span`,wt,i(nt.value.description),1),rt.value?(a(),e(o,{key:0,text:``,size:`tiny`,class:`ev-subtitle__search-btn`,onClick:Mt},{icon:b(()=>[g(T(xe),{size:14})]),default:b(()=>[r[26]||=c(` 使用此书源搜索 `,-1)]),_:1})):C(``,!0)])):rt.value?(a(),u(`div`,Tt,[g(o,{text:``,size:`tiny`,class:`ev-subtitle__search-btn`,onClick:Mt},{icon:b(()=>[g(T(xe),{size:14})]),default:b(()=>[r[27]||=c(` 使用此书源搜索 `,-1)]),_:1})])):C(``,!0),l(`div`,Et,[g(yt,{source:t,active:U.value===t.fileName,prefetch:Qe.value.has(t.fileName),"show-covers":X.value,"display-mode":Z.value,"reload-version":ot[t.fileName]??0,onSelect:T(At),onOpenBook:e=>Nt(e,t.fileName),onRefreshing:ut},null,8,[`source`,`active`,`prefetch`,`show-covers`,`display-mode`,`reload-version`,`onSelect`,`onOpenBook`])])]),_:2},1032,[`name`]))),128))]),_:1},8,[`value`,`class`])],544)):Ue.value?(a(),e(ke,{key:1,title:`书源系统启动中`,desc:`正在加载书源并检测发现能力`})):(a(),e(ke,{key:2,title:`暂无可用的发现书源`,desc:`请先在书源管理中添加支持「发现」的书源`})),g(Me,{show:T(ht),"onUpdate:show":r[12]||=e=>x(ht)?ht.value=e:null,"book-url":T(gt),"file-name":T(_t),"source-name":T(vt),"source-type":T(Ot),"book-meta":T(kt),onReadChapter:T(on)},null,8,[`show`,`book-url`,`file-name`,`source-name`,`source-type`,`book-meta`,`onReadChapter`]),g(Ce,{show:T(qt),"onUpdate:show":r[13]||=e=>x(qt)?qt.value=e:null,"current-index":T(Qt),"onUpdate:currentIndex":r[14]||=e=>x(Qt)?Qt.value=e:null,"chapter-url":T(Jt),"chapter-name":T(Yt),"file-name":T(Xt),chapters:T(Zt),"shelf-book-id":T(tn),"book-info":T($t),"source-type":T(en),"chapter-groups":T(nn),"initial-group-index":T(rn),onAddedToShelf:r[15]||=e=>tn.value=e,onSourceSwitched:T(an)},null,8,[`show`,`current-index`,`chapter-url`,`chapter-name`,`file-name`,`chapters`,`shelf-book-id`,`book-info`,`source-type`,`chapter-groups`,`initial-group-index`,`onSourceSwitched`]),g(S,{show:q.value,"onUpdate:show":r[17]||=e=>q.value=e,"mask-closable":!1,"close-on-esc":!1,preset:`card`,title:`使用须知`,style:{maxWidth:`480px`,width:`92vw`},bordered:!1},{footer:b(()=>[l(`div`,Dt,[g(o,{type:`primary`,onClick:Je},{default:b(()=>[...r[29]||=[c(`我已了解`,-1)]]),_:1})])]),default:b(()=>[r[30]||=l(`div`,{class:`disclaimer-body`},[l(`p`,null,` 本软件所有书源均来源于社区用户共享，软件本身不提供、不存储任何内容。 `),l(`p`,null,` 书源内容由第三方网站提供，版权归原作者及原网站所有。若您发现任何书源涉及侵权内容，请立即停止使用该书源，并通知相关内容提供方。 `),l(`p`,null,` 使用书源所产生的一切法律责任由使用者本人承担，与本软件开发者无关。 `)],-1),g(y,{checked:J.value,"onUpdate:checked":r[16]||=e=>J.value=e,class:`disclaimer-check`},{default:b(()=>[...r[28]||=[c(` 不再显示 `,-1)]]),_:1},8,[`checked`])]),_:1},8,[`show`]),g(m,{trigger:`manual`,show:Q.visible,x:Q.x,y:Q.y,options:Pt.value,onSelect:Ht,onClickoutside:r[18]||=e=>Q.visible=!1},null,8,[`show`,`x`,`y`,`options`])],4)}}}),[[`__scopeId`,`data-v-9d0fd162`]]);export{Ot as default};