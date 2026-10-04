import{Gn as e,Hn as t,Ir as n,Jn as r,Nr as i,Sn as a,Tn as o,Xn as s,gn as c,hr as l,jn as u,xr as d}from"./vendor-Beqdr9ys.js";import{t as f}from"./_plugin-vue_export-helper-BDNMzG2s.js";import{t as p}from"./vendor-markdown-Si0eVEWm.js";var m=`# 「网页服务 + MCP」使用帮助

这份文档教你把手机上的 Legado 变成一台 **AI 也能操作的书架服务**：App 里打开 Web 服务后，同一个地址既提供**网页控制台**（用浏览器管书架），又提供 **MCP 接口**（让 Claude、Codex 等 AI 客户端直接替你查书架、看正文、改书源）。

> 端口默认 **1122**；对外 MCP 挂在同一个地址下的 **\`/mcp\`** 路径上，所以「开一次 Web 服务」= 同时得到网页控制台 + AI 接入，不需要再开第二个开关。

## 总览：它是什么、谁能用

打开 Web 服务后，手机会变成一个局域网内的小服务器，提供两件事：

1. **网页控制台**：手机浏览器或电脑浏览器访问地址，就能管理书库、书源、订阅、主题、AI 配置等。
2. **MCP 接口**：把手机变成一个「MCP Server」，支持 MCP 的 AI 客户端填上地址和令牌后，就能调用手机里的能力。

针对三类人，用法不同：

| 角色 | 想做的事 | 主要入口 |
|---|---|---|
| 小白用户 | 在电脑大屏上整理书架 / 换主题 / 备份 | 网页控制台（浏览器打开地址即可，只读令牌就够看） |
| AI 玩家 | 让 AI 客户端管书架、查正文、批量改配置 | MCP（把地址 + 令牌填进 AI 客户端） |
| 书源作者 | 让 AI 帮调书源、迭代规则、跑调试 | 控制台「调试工作台」页 + MCP 调试工具（**需测试包**才带调试类工具） |

> 能力范围与安全边界：所有操作都在**这台手机**上执行，不经过云端；但令牌若泄露，别人也能操作你的书架，所以令牌要按「密码」对待（见文末《安全须知》）。

## 第一步：打开 Web 服务

1. 打开 App，进入 **设置 → Web 服务与 AI 接入**（承载页面：控制台「设置」页 P14）。
2. 打开 **「Web 服务」开关**。
3. 开关打开后，页面上会显示 **服务地址**（形如 \`http://192.168.x.x:1122\`）与端口。**记下这个地址**，后面接客户端要用。
4. 手机与要访问的设备（电脑 / 另一台手机）必须连在**同一个 Wi-Fi / 局域网**下。

> 提示：地址就是「手机在当前网络里的 IP + 端口」。换个 Wi-Fi 后 IP 会变，需回到本页重新看一眼地址。

## 第二步：生成访问令牌

AI 客户端和控制台的写操作都要带令牌。App 里可分别生成**三级令牌**，权限递增：

| 级别 | 用途 | 典型行为 |
|---|---|---|
| \`readonly\`（只读） | 只看不改 | 浏览书架、读正文、看统计；不能保存任何修改 |
| \`manage\`（管理） | 日常管理 | 增删改书源、书签、规则、订阅等；不能导出整包数据、不能安装控制台 |
| \`admin\`（管理员） | 高风险操作 | 备份导出、日志 / 网络诊断、控制台安装等一次性「能拉走全部数据」的操作 |

- 高级别令牌**自带**低级别的全部权限（\`admin\` ⊇ \`manage\` ⊇ \`readonly\`）。
- 令牌在 App 中生成后可**复制**，请立刻粘贴到你自己的客户端，不要发到群里、论坛或截图里。
- 令牌内容一旦**泄露或怀疑泄露**，回到本页**重新生成**（旧令牌立即失效）；App 也可用「关闭服务并吊销全部令牌」一键作废全部令牌。

> 安全提醒：给 AI 客户端**优先用 \`readonly\`**；只有在需要 AI 帮你改东西时才换 \`manage\`；\`admin\` 仅在你明确知道要做什么、且当场能盯着 AI 操作时才用。

## 第三步：把 AI 客户端接进来

所有支持 MCP 的客户端，接入方式都可归结为**两种传输**二选一：

- **HTTP（Streamable HTTP）**：直接填手机的 \`http://<手机IP>:1122/mcp\`（**推荐本机 / 局域网场景**）。
- **stdio（本地命令行进程）**：客户端启动一个本地进程，由它去连上面的 HTTP 地址（用于那些只支持 stdio 的客户端，需要一个本地「HTTP→stdio 桥」）。

下面先给**通用步骤**，再给四家客户端的对应说明。

### 通用接入步骤（各客户端适用）

1. **确认地址**：\`http://<手机IP>:1122/mcp\`。把 \`<手机IP>\` 换成第一步里记下的那个 IP。
2. **确认请求头**：\`Authorization: Bearer <令牌>\`。把 \`<令牌>\` 换成第二步拿到的令牌。
3. **在客户端里新增一个 MCP Server**：
   - 若客户端支持 **HTTP 类型**：类型选 \`HTTP\`（或 \`streamable-http\` / \`SSE\`），填入上面的地址，并在「请求头 / headers」里加 \`Authorization: Bearer <令牌>\`。
   - 若客户端**只支持 stdio**：用一个本地桥接工具（如通用 MCP 代理）把「HTTP 端点 + 请求头」包成本地命令，再把该命令填进客户端的 \`command\` / \`args\`。
4. **保存并刷新 / 重启客户端**，让它去拉取工具列表。
5. **验证成功**：在客户端里让 AI 调用一个只读工具，例如让它「列出书架」——工具名 \`bookshelf_list\`。能返回书架书目数量，就说明连通了。

> ⚠️ **具体字段名以该客户端文档为准**：不同版本客户端的配置字段（比如叫 \`headers\` 还是 \`requestHeaders\`）、配置文件位置（\`claude_desktop_config.json\` / \`config.toml\` / 图形界面表单）会随版本变化。本节只保证「地址 + Bearer 请求头」这两个**内容**是对的，字段写法请以你所用客户端的官方文档为准，不要照抄猜测。

### TeleAgent

- **入口**：在 TeleAgent 的「MCP / 工具 / 连接」设置里新增一个 MCP Server。
- **填什么**：地址 \`http://<手机IP>:1122/mcp\`；请求头 \`Authorization: Bearer <令牌>\`。
- **验证**：保存后刷新工具列表，能看到一批以域前缀开头的工具（如 \`bookshelf_*\`、\`source_*\`）；调用 \`bookshelf_list\` 能返回书目即成功。
- **常见报错**：看不到工具 → 地址没带 \`/mcp\`，或手机与电脑不在同一网段；调用报 401 → 令牌写错 / 没带 \`Bearer \`前缀。

### Claude Desktop

- **入口**：Claude Desktop 通过一个配置文件声明 MCP Server（图形界面里也有相应入口，随版本不同）。
- **填什么**：地址 \`http://<手机IP>:1122/mcp\`，请求头 \`Authorization: Bearer <令牌>\`。
- **stdio 场景**：若你的版本只支持本地进程方式，请先用本地 HTTP→stdio 桥把上面的端点包成命令，再把命令写进配置。
- **验证**：改完配置后**完全退出并重启** Claude Desktop，在会话里让它「列出我的书架」→ 能返回数量即成功。
- **常见报错**：改配置不生效 → 没有完全重启进程；连不上 → 配置里的地址写成了 \`localhost\`（应写成手机的局域网 IP）。
- ⚠️ **配置字段名与文件位置请以 Claude Desktop 当前版本文档为准**（不同版本差异较大），本文只确认「地址 + Bearer 请求头」这两个内容。

### Codex

- **入口**：在 Codex 的 MCP 配置（配置文件或命令行）里新增一个 server。
- **填什么**：地址 \`http://<手机IP>:1122/mcp\`；请求头 \`Authorization: Bearer <令牌>\`。
- **验证**：保存后让 Codex 列出可用工具并调用 \`bookshelf_list\`；返回书目即成功。
- **常见报错**：工具列表为空 → 地址 / 请求头未生效，重载配置；401 → 令牌级别不足或写错。
- ⚠️ **Codex 的配置文件路径与字段名以你所装版本为准**，不要照抄本文未给出的字段。

### Cherry Studio

- **入口**：Cherry Studio 的「设置 → MCP 服务器」里新增一个服务器。
- **填什么**：类型选 **HTTP / SSE**（视版本），地址 \`http://<手机IP>:1122/mcp\`，请求头里加 \`Authorization: Bearer <令牌>\`。
- **验证**：添加后刷新，工具列表出现 \`bookshelf_list\` 等工具；调用返回书目即成功。
- **常见报错**：连不上 → 电脑与手机不同网段、或手机 Web 服务没开；401 → 令牌错 / 权限不足；工具数偏少 → 令牌级别低（\`readonly\` 只看到只读类工具，属正常裁剪）。
- ⚠️ **界面字段（headers 名称等）以 Cherry Studio 当前版本文档为准**。

## 常见问题排查

**1. 客户端连不上 / 一直转圈**
- 手机与客户端设备是否在**同一个局域网**（同一 Wi-Fi，非访客网、非手机热点跨网）。
- 手机是否开着**省电 / 防火墙**拦截；路由器的「AP 隔离」会阻断同网段互访，需关闭。
- 地址是否用了 \`localhost\`（错，要用手机 IP）、是否漏了 \`/mcp\`。
- 手机 Web 服务开关是否仍开着（切后台后可能被系统回收，回 App 确认）。

**2. 返回 401（未授权）**
- 请求头是否是 \`Authorization: Bearer <令牌>\`（注意 \`Bearer\` 后有**一个空格**）。
- 令牌是否复制完整（前后没多空格）。
- 令牌级别是否够：\`readonly\` 只能调只读工具，写操作需要 \`manage\`，备份 / 诊断 / 控制台安装需要 \`admin\`。

**3. 工具数量对不上**
- 正常口径：**正式包（release）约 216 个工具，测试包（debug）约 230 个工具**——多出的部分是**只在测试包里编入**的调试观测类工具（如 \`log_query\`、\`source_search_test\`），正式包**一行都不带**，属预期差异。
- 只看到一部分工具：是**按令牌级别裁剪**的结果——\`readonly\` 只见只读类，换更高级别令牌能看到更多，属正常。

**4. AI 要执行危险操作时，手机弹确认**
- 删除书源、清空数据等**破坏性操作**在真机上有**端侧确认闸门**：AI 发起后，手机 App 会弹出确认，你点「允许」才会执行。
- 这是刻意设计的安全阀，不是故障；不确认就不会执行。

**5. 网页控制台打不开 / 提示未安装**
- 控制台是**按需分发**的：App 安装包里只有一个小启动壳，首次访问时会自动下载控制台包。
- 若下载失败（断网 / 中继不可达），可在启动页用**本地上传**控制台包（zip）安装；安装后会在手机私有目录保留当前版本与上一版本，可**回退**。

**6. 「去哪操作」速查（网页控制台页面）**
- 看 / 管书架：书库、阅读器｜书源：书源管理、调试工作台
- 订阅：订阅｜规则：规则管理｜书签与名场面：书签管理
- 听书 / 配音：TTS 与听书｜统计：记录与统计｜备份：备份与恢复
- 自动化：自动任务｜排查：日志与诊断｜服务与令牌：设置
- 外观：主题工坊、外观资源管理｜发现 / 多形态：发现、图片漫画阅读器、视频播放器、有声书播放
- AI 相关：AI 对话、世界书、AI 图库、AI 智能配置｜角色：角色管理｜存储：下载与存储管理、缓存与任务

## 安全须知

- **令牌等同密码**：拿到令牌的人能像你一样操作这台手机的这些数据。不要贴到公开渠道（群聊、论坛、GitHub issue、截图）。
- **最小权限**：给 AI 默认用 \`readonly\`；确实需要改数据时再临时换 \`manage\`。
- **\`admin\` 慎用**：备份导出、诊断下载、控制台安装等会「一次性拿走大量数据」或「改动服务本身」，使用前先想清楚 AI 要干什么。
- **用完就关**：不使用时关闭 Web 服务；怀疑泄露时用「关闭服务并吊销全部令牌」立即作废全部令牌。
- **同网段可见**：Web 服务默认只在本局域网可用；不要在不可信的公共 Wi-Fi 下长时间开启。`,h={class:`help-page`},g={class:`hp-body`},_={class:`hp-toc`},v=[`onClick`],y=[`innerHTML`],b=`help-h-`,x=f(u({__name:`Help`,setup(u){let f=(e=>{let t=[];for(let n of e.split(/\r?\n/)){let e=/^##\s+(.+?)\s*$/.exec(n);e&&t.push({id:`${b}${t.length}`,text:e[1].replace(/`/g,``)})}return t})(m),x=0,S=new p({html:!1,linkify:!0,breaks:!1}),C=S.renderer.rules,w=C.heading_open??((e,t,n,r,i)=>i.renderToken(e,t,n));C.heading_open=(e,t,n,r,i)=>{let a=e[t];return a.tag===`h1`?a.attrSet(`id`,`help-top`):a.tag===`h2`&&(a.attrSet(`id`,`${b}${x}`),x+=1),w(e,t,n,r,i)};let T=S.render(m),E=l(null),D=l(``),O=null,k=e=>{D.value=e,(E.value?.querySelector(`#${e}`))?.scrollIntoView({behavior:`smooth`,block:`start`})};return e(()=>{let e=E.value;if(!e||f.length===0)return;let t=f.map(t=>e.querySelector(`#${t.id}`)).filter(e=>e!==null);t.length!==0&&(O=new IntersectionObserver(e=>{let t=e.filter(e=>e.isIntersecting).sort((e,t)=>e.boundingClientRect.top-t.boundingClientRect.top);t.length>0&&(D.value=t[0].target.id)},{rootMargin:`0px 0px -70% 0px`,threshold:0}),t.forEach(e=>O?.observe(e)))}),t(()=>{O?.disconnect(),O=null}),(e,t)=>(r(),o(`div`,h,[t[1]||=a(`header`,{class:`hp-head`},[a(`h1`,{class:`hp-title`},`帮助 · 网页服务与 MCP`),a(`p`,{class:`hp-subtitle`},` 从打开 Web 服务、拿访问令牌到接入 AI 客户端（TeleAgent / Claude Desktop / Codex / Cherry Studio）的逐步说明 `)],-1),a(`div`,g,[a(`aside`,_,[t[0]||=a(`p`,{class:`hp-toc-title`},`目录`,-1),a(`nav`,null,[(r(!0),o(c,null,s(d(f),e=>(r(),o(`button`,{key:e.id,type:`button`,class:i([`hp-toc-item`,{"is-active":d(D)===e.id}]),onClick:t=>k(e.id)},n(e.text),11,v))),128))])]),a(`article`,{ref_key:`contentRef`,ref:E,class:`hp-content markdown-body`,innerHTML:d(T)},null,8,y)])]))}}),[[`__scopeId`,`data-v-9cf729a5`]]);export{x as default};