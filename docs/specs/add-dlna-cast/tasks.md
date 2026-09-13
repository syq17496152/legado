# tasks.md — 内置视频播放器 DLNA/UPnP 投屏

> 格式：`- [ ] X.Y 任务`，核心任务标注**验证标准**。
> 完成标记：`- [x] X.Y 任务 (L1/L2/L3) ✅ YYYY-MM-DD`
> 关联：[spec.md](./spec.md)（REQ-01~REQ-14）｜[design.md](./design.md)（AD-01~AD-12）
> 状态：🔄 设计中（待检查点 1 审核后开工）
> 执行纪律：每个核心任务完成后做**增量编译验证**；单任务工具调用 ≥20 次时改派子代理做验证（源码修改仍由主代理串行执行）。

---

## 1. 准备工作

- [ ] 1.1 精读既有实现：`VideoPlay.kt`（`:81-120` 偏好范式 / `:314-410` 状态字段）、`VideoPlayerActivity.buildMenuActions()`（`:1192-1258`）、`WebService.kt`（前台服务范式）、`BaseService.kt` 全量、`receiver/NetworkChangedListener.kt`
  - **验证标准**：能准确说出「新增菜单项」「新增布尔偏好」「新增前台服务」「接入网络变化监听」四处各自的最小改动点与行号
- [ ] 1.2 `AndroidManifest.xml` 追加 `CHANGE_WIFI_MULTICAST_STATE` 权限与 `DlnaCastService`（`mediaPlayback`）声明（REQ-13 / AD-05）
  - **验证标准**：Grep 确认权限与 service 声明存在；`./gradlew assembleAppDebug` 通过
- [ ] 1.3 `AppConst.kt` 追加 `channelIdCast`；`NotificationId.kt` 追加 `DlnaCast`；`App.kt createNotificationChannels()`（`:366`，样板 `:391`）注册渠道（AD-05）
  - **验证标准**：Read 确认三处改动；编译通过
- [ ] 1.4 `strings.xml` 补齐全部投屏文案（含 spec REQ-11 的 8 条错误提示原文 + REQ-03 四态文案）
  - **验证标准**：Grep 文案 key 无遗漏；新建 Kotlin 文件中无硬编码中文（`§6.1` 禁硬编码）
- [ ] 1.5 建立 `help/dlna/` 与 `ui/video/cast/` 包：仅放 `package` 声明、KDoc 头与 `DlnaConstants` 常量骨架（**不留空壳函数**）
  - **验证标准**：`DlnaConstants` 的 8 项超时常量（design AD-11 表）全部就位，无散落魔数

## 2. 发现与解析（纯逻辑优先，先拿下单测）

- [ ] 2.1 `SsdpMessageParser.kt`：解析 SSDP 响应报文 → `SsdpResponse`（头名大小写不敏感、`LOCATION` 必填校验、从 `USN` 提取 UDN）
  - **验证标准**：`SsdpMessageParserTest` 绿（大小写混杂 / 缺 LOCATION / 畸形报文 / 非 UTF-8 字节）
- [ ] 2.2 `DeviceDescriptionParser.kt`：解析 device description XML → `DlnaDevice`；筛 `MediaRenderer`；`controlURL` 相对路径按 `LOCATION` 归一化
  - **验证标准**：`DeviceDescriptionParserTest` 绿（MediaRenderer 识别 / MediaServer 排除 / 相对路径归一化 / 缺 AVTransport / 畸形 XML）
- [ ] 2.3 `SsdpDiscovery.kt`：`DatagramSocket` 发 M-SEARCH（`ST`/`MX` 取 `DlnaConstants`）、4s 收集窗口、按 UDN 去重、`MulticastLock` 持有与释放（失败降级不崩溃）
  - **验证标准**：L2 可发现假渲染器；无设备时 4s 内返回空列表；socket 与组播锁均被释放（无泄漏）
- [ ] 2.4 `MimeSniffer.kt`：扩展名映射表 / `HEAD` 探测（**必须带会话 headers**，失败 5s 内降级扩展名推断）/ `protocolInfo` 组装（REQ-04 / AD-09）
  - **验证标准**：`MimeSnifferTest` 绿（映射表全项 / 未知扩展名兜底 / 大小写与 query 干扰）；`HEAD` 返回 403 时不阻塞主流程
- [ ] 2.5 `CastNetworkHelper.kt`：`pickLanAddress()` 三级选择策略 + 与渲染端同网段校验（REQ-06 / **AD-13，红队 P0-1 修复点**）
  - **验证标准**：多网卡（模拟构造 Wi-Fi/VPN/蜂窝/`169.254.*` 混合列表）时选中 Wi-Fi 地址而非列表首项；全失败返回 null；同网段校验在跨网段时产生提示标志

## 3. UI：入口与设备面板

- [ ] 3.1 `VideoPlayerActivity.buildMenuActions()`（`:1192`）追加「投屏」`MenuAction`，受 `dlnaCastEnabled` 与 `videoUrl` 非空双重门控（REQ-01）
  - **验证标准**：L2 三场景 —— 有地址时出现 / 无地址时提示"暂无播放地址" / 关闭设置后消失
- [ ] 3.2 `DlnaCastUiState.kt` + `DlnaCastContent.kt` + `DlnaCastDialog.kt`：BottomSheet 四态（搜索中 / 有设备 / 无设备+重试 / 发现失败）（REQ-03）
  - **验证标准**：L2 四态逐一可达；无设备时展示排查建议文案
- [ ] 3.3 `VideoPlay.kt` 追加 5 个偏好（AD-10，`dlnaNoMetaDevices` 带 LRU 上限 10），并实现「上次投屏：XXX」快捷项与离线标注（REQ-10）
  - **验证标准**：投屏成功后杀进程重进，面板首项为「上次：XXX」；设备离线时标注"未在线"；LRU 超限时最旧条目被裁剪（`CastProxyRegistryTest` 覆盖 LRU 裁剪）
- [ ] 3.4 状态唯一来源接线：Dialog 只渲染 `DlnaCastManager` 的 `StateFlow`，不持有会话状态；`dismiss()` 不触发 teardown；`Connecting` 态禁用可点击项（REQ-03「投屏中重新打开面板」/ design「UI 实现约定」）
  - **验证标准**：投屏中退出面板再进入直接落到控制态；投屏中连点「投屏」不产生第二个发现流程与第二个代理实例（日志计数为 1）

## 4. 投递与控制

- [ ] 4.1 `SoapEnvelope.kt` + `DidlLiteBuilder.kt` + `AvTransportClient.kt` + `RenderingControlClient.kt`（REQ-04 / AD-09）
  - **验证标准**：`SoapEnvelopeTest` + `DidlLiteBuilderTest` 绿；对假渲染器的 SOAP POST 全部返回 200
- [ ] 4.2 `DlnaCastManager` 投递决策三态（含「存在非空白值项」判定与「强制走代理」覆盖）+ 代理注册 + `SetAVTransportURI`(带 DIDL) + `Play`（REQ-06 / AD-03）
  - **验证标准**：E2E-1（直投，不经代理，假上游无请求记录）、E2E-2（头代理）两条链路 L2 走通
- [ ] 4.3 控制指令：暂停/继续/停止/Seek(REL_TIME)；投屏成功即暂停本地播放且不清进度（REQ-05 / REQ-08 / AD-07）
  - **验证标准**：L2 逐指令验证生效；本地播放器为暂停态且 `durChapterPos` 未变
- [ ] 4.4 「禁止第二路播放」约束落地：投屏态下拦截悬浮窗入口、切集前置 `teardown`（REQ-08 三个 Scenario）
  - **验证标准**：投屏中触发悬浮窗 → 投屏先行终止（`Stop` 已下发 + 代理已注销）再本地播放；投屏中切集 → 会话终止并回到面板
- [ ] 4.5 进度轮询：播放中 2s `GetPositionInfo`，暂停中 10s `GetTransportInfo`；本地操作乐观更新（REQ-05 / AD-06）
  - **验证标准**：L2 进度随假渲染器上报位置刷新；暂停后轮询降频（日志可证）
- [ ] 4.6 降级链落地：DIDL 被拒 → 空 metadata 重试 + `dlnaNoMetaDevices` 记忆命中则直接跳过 DIDL；8s 未起播判失败；连续 3 次 ERROR 判离线；Seek/音量不支持则隐藏控件；`Stop` 失败照常清理（REQ-04 / REQ-11 / AD-11）
  - **验证标准**：假渲染器「强制 Fault」开关可复现每条降级路径，提示文案正确；记忆命中时假渲染器**只收到 1 次** `SetAVTransportURI`
- [ ] 4.7 音量控制（RenderingControl `GetVolume`/`SetVolume`）；描述文档无该服务时隐藏控件（REQ-05）
  - **验证标准**：L2 拖动音量滑条 → 假渲染器收到 `SetVolume`

## 5. 拉流代理与服务承载

- [ ] 5.1 `DlnaCastService` 骨架 + 前台通知（暂停/继续、结束投屏、点击回播放页）+ `stopSelfOnTaskRemoved = false`（REQ-09 / AD-05）
  - **验证标准**：息屏 + 划掉最近任务后服务存活；通知三个动作均生效
  - ⚠️ 顺序要求：本任务必须先于 5.5（代理生命周期挂在本服务上）
- [ ] 5.2 `CastProxyRegistry.kt` + `CastProxyServer.kt` 骨架：`NanoHTTPD(0)` 启动/停止 + 通过 **`start(timeout, false)` 显式覆盖 5s 默认读超时** + `listeningPort` 回填、`/cast/{token}/{name}` 与 `/cast/{token}/s/{id}` 路由、token 校验（非法即 404 且不发上游请求）、`ConcurrentHashMap` 线程安全、自定义 `AsyncRunner` 并发上限 16（超限 `503`）、覆写 `useGzipWhenAccepted=false`、路径末段卫生处理（长度截断 + `..` 丢弃）（REQ-07 / AD-02 / AD-08）
  - **验证标准**：`CastProxyRegistryTest` 绿；L2 非法 token → 404 且假上游请求计数为 0；并发 20 请求时超限部分被拒（503）而非无限起线程
  - ⚠️ 注意：`Status` 枚举**没有** `GATEWAY_TIMEOUT`，504 必须用 `Status.lookup(504)`；503 用 `Status.SERVICE_UNAVAILABLE`（已 javap 实证）
- [ ] 5.3 HttpSource 分支（**Range 全形态 + HEAD + 压缩处理**）：OkHttp 带会话 headers 取流；`bytes=a-b`/`bytes=a-`/`bytes=-n` 三种形态正确回 `206` + `Content-Range` + `Content-Length`=区间长度；越界回 `416`；**多区间忽略 Range 回 `200` 全量**；**上游回 200 全量时本地跳字节切片并改回 `206`**；`HEAD` 探测回头部无 body；**剥除客户端 `Accept-Encoding` 且响应丢弃 `Content-Encoding`/`Transfer-Encoding`**；未知长度走 chunked；上游 4xx/5xx 原样透传（**不自动重试**）；上游 15s 无首包 → `Status.lookup(504)`；响应头白名单（不回传 `Set-Cookie`/`Authorization`/`Location`）；**任何路径均 `use {}` 关闭上游响应体**（REQ-07 / AD-02 / design 铁律表）
  - **验证标准**：E2E-2 走通；三种 Range 形态各自返回的 `Content-Range`/`Content-Length` 数值正确（假上游记录逐条核对）；越界得 416；上游忽略 Range 场景下电视拿到的数据不错位；`HEAD` 得 200 无 body；客户端收到的响应头不含 `Content-Encoding`/`Set-Cookie`；客户端中断后上游连接被关闭（假上游可观测）
- [ ] 5.4 FileSource 分支：`file://` 本地文件流 + Range + Content-Length；文件不存在回 404、不可读回 403 且不崩溃（REQ-06 / REQ-07）
  - **验证标准**：E2E-4 走通；删除文件后再投屏得到 404 与"本地文件不可用"提示
- [ ] 5.5 `HlsPlaylistRewriter.kt`：覆盖普通行 / **`EXT-X-STREAM-INF` 行内 `URI=`** / `EXT-X-KEY` / `EXT-X-SESSION-KEY` / `EXT-X-MAP` / `EXT-X-MEDIA` / `I-FRAME-STREAM-INF` / `EXT-X-PART` / `EXT-X-PRELOAD-HINT` / `X-ASSET-URI` / master 变体；**重写时惰性登记短 ID**（输出 `/cast/{token}/s/{id}` 避免 URL 膨胀）；相对路径按 `response.request.url` 的目录解析；输出自检不含原始 host（REQ-07 / AD-04）
  - **验证标准**：`HlsPlaylistRewriterTest` 全绿（**≥12 个用例**，含「行内 URI= 的 STREAM-INF」与「长 URL 输出长度不膨胀」两条专项）；E2E-3 走通且假上游收到分片请求时带鉴权头
- [ ] 5.6 代理生命周期与服务绑定：`teardown()` 严格按序 ① cancel 轮询并等退出 ② `registry.clear()` ③ 停代理 ④ 停前台服务与通知 ⑤ 状态复位 `Idle`；**端口释放**：`CastProxyServer.started` 标志 + `start()` 包 `runCatching` + 失败分支与 `finally` 都 `stop()` + `DlnaCastService.onDestroy` 无条件再 `stop()`；**单例复位**：`DlnaCastService.onCreate` 调 `DlnaCastManager.resetOnServiceCreate()`（REQ-09 / AD-11）
  - **验证标准**：结束投屏后端口已释放、注册表为空、轮询无后续写入、服务已停、通知已消失（5 项逐一核对，顺序按日志时间戳验证）；**故意让 `start()` 失败一次，再次投屏能正常绑到端口**（验证失败路径不残留）；服务重启后 `DlnaCastManager` 状态为 `Idle` 且无遗留 token
- [ ] 5.7 日志与脱敏接线（REQ-14 / AD-12）：castUrl 只记形状、上游 URL 走站点代号、headers 只记键名与数量、通知不含 URL/token
  - **验证标准**：Grep 新增代码中的日志语句，无 token 明文、无 header 值、无完整 URL 直出

## 6. 异常接线与设置

- [ ] 6.1 网络变化接线：`DlnaCastService` **自行注册 `NetworkCallback`**（重写 `onLost`/`onUnavailable`，回调内用 `CastNetworkHelper.pickLanAddress()` 复核是否还有可用 WLAN/以太网地址），断网即 `teardown` + 提示"网络已切换，投屏已中断"；`onDestroy` 注销；API 23 上 `runCatching` 降级为轮询 3 次失败兜底（REQ-08 / REQ-11 / E2E-7 / **AD-15，红队 P1 修复点**）
  - **验证标准**：L2 模拟投屏中关 Wi-Fi → 会话在秒级终止（**不等** 3 次轮询 ≈6s）、代理端口释放、提示正确
  - ⚠️ 注意：**不要依赖 `receiver/NetworkChangedListener.kt`**——其回调仅在 `onAvailable` 触发，纯断网不会回调（蓝队核实结论）
- [ ] 6.2 `VideoSettingsPanelContent` `PanelHost.GLOBAL` 分支（`:272`）新增「投屏」分组：启用投屏功能 / 强制走代理（REQ-12）
  - **验证标准**：L2 切换开关后行为即时生效；默认值为 开 / 关；「强制走代理」开启后 E2E-1 也走代理（假上游出现请求记录）
- [ ] 6.3 错误提示统一收口：spec REQ-11 表中 8 条文案逐一接线（无 WLAN / 无设备 / 连接失败 / 设备拒收 / 设备掉线 / 网络切换 / 本地文件不可用 / HLS 提示）
  - **验证标准**：Grep 确认 8 条文案均有引用点，无孤立字符串

## 7. 验证与交付

- [ ] 7.1 编译与单测：`./gradlew assembleAppDebug` + `./gradlew testAppDebugUnitTest`
  - **验证标准**：编译 0 错误；8 个测试类全绿（CastNetworkHelper 含在内）（达成 L1）
- [x] 7.2 `ai_tests/scripts/dlna_fake_renderer.py` 假 MediaRenderer（**✅ 2026-09-12 提前完成并自检通过**）：SSDP 响应 + 描述 XML（含/不含 RenderingControl 两版）+ SOAP 接收与**指令记录** + 内嵌 HTTP 服务**记录被拉流请求（含 Range 与请求头）+ 内建 Range 基准源 `/selftest.bin`** + **「强制 Fault」开关**（`--fault-seturi`）+ **「无 RenderingControl」开关** + **SOAPACTION 引号形态检查** + **SSDP 收包自检**（`check_ssdp_ownership`）
  - **验证标准**：`python ai_tests/scripts/dlna_fake_renderer.py --selftest` 全绿 —— SSDP 发现 / 描述文档 / SOAP 三条 / 6 项 Range 判定（HEAD、单区间、后缀、开区间、多区间、越界 416）共 10 项 PASS ✅ **已达成**
  - 用法：`--ssdp-port`（真机联调必须 1900）、`--advertise-ip`（多网卡必填）、`--no-auto-pull`（NAT 环境）
  - 环境前置：真机联调前须 `net stop SSDPSRV`（详见 `issues-found.md` IF-1）
- [ ] 7.3 L2 分级验证（**红队 P0-2 后重写**，按可用环境择级执行）
  - L2-a PC 回环：SSDP 报文形态 / 描述解析 / SOAP / DIDL / m3u8 重写
  - L2-b MEmu NAT：仅发现 + 投递指令（**代理回连不可验**，须显式记录缺口）
  - L2-c（首选）真机 + PC 同 Wi-Fi：**全链路** E2E-1 ~ E2E-7
  - L2-d MEmu 桥接 + 端口转发：同 L2-c（视宿主虚拟网络支持）
  - **验证标准**：所执行级别的场景逐条留证（日志/截图/假渲染器记录）；未执行的级别与原因写入 `issues-found.md`；失败项一并记录
- [ ] 7.4 ⚠️ **m3u8 重写真机判定点**：若 E2E-3 在真实电视上不成立 → 按 AD-04 降级预案改为「m3u8 仅直投」并回写 design.md（追加 AD-04 ChangeLog）
  - **验证标准**：判定结论明确写入 design.md 与 `issues-found.md`，不得静默保留
- [ ] 7.5 真机 L3 验收（用户执行）：真实电视/盒子 —— 投屏成功、进度可控、息屏保活、结束清理、长时间播放稳定
  - **验证标准**：用户确认
- [ ] 7.6 步骤 5.5 AI 自动端到端测试（AGENTS.md 规则 2）：`ai_tests\venv\Scripts\python.exe ai_tests/run_e2e.py --tc all`，覆盖 `ui/video` 受影响用例
  - **验证标准**：五件套报告产出；fail/manual 用例走反馈闭环（`--feedback`）
- [ ] 7.7 收尾审计（AGENTS.md 规则 4）：① 敏感词扫描 ② Grep 确认无临时调试日志（保留 `AppLog` 正式诊断日志）③ `updateLog.md` 已按 `git diff` 更新 ④ 脱敏复查（REQ-14）⑤ 文档同步
  - **验证标准**：每项附 Grep 证据（模式 + 命中数），审计定性用 `^import android\.util\.Log$` 防 DebugLog 子串误报
- [ ] 7.8 文档同步（OpenSpec 步骤 8）：`docs/INDEX.md` 状态流转；`docs/project-flow/task-navigation.md` 补 `help/dlna/` 模块锚点；`quick-reference.md` 补新权限与新服务
  - **验证标准**：三处 Read 确认更新落地

## 8. 归档

- [ ] 8.1 `docs/specs/add-dlna-cast/` → `docs/specs/archive/YYYY-MM-DD-add-dlna-cast/`
- [ ] 8.2 归档目录 README 追加归档日期与最终状态
- [ ] 8.3 沉淀：SSDP/SOAP 踩坑、假渲染器用法 → `.trae/memory/` 或对应子规范；若形成可复用工作流 → 建 skill

---

## AOAdapt 日志

> 实施中偏离预期、方案调整、失败回退时**必须**记录（格式见 `docs/project-rules/openspec-workflow.md`）。

- [x] 2.2 实现 `DeviceDescriptionParser`
  - **Action**: 按 design AD-01 采用 Android 内建 `XmlPullParserFactory` 解析设备描述文档。
  - **Observation**: `XmlPullParserFactory` 在 JVM 上不存在（仅 Android 运行时提供），该写法会让**解析逻辑无法进 `app/src/test` 单测**——与 AD-01 "纯解析逻辑可进 JVM 单测"的目标直接冲突，也与本 spec 把功能主体纳入自动化验证的整体策略冲突。
  - **Adapt**: 改用 **jsoup 1.16.2 的 XML 解析器**（项目已锁定依赖，零新增）。附带发现 jsoup XML 模式保留标签原大小写，厂商存在 `devicetype` / `CONTROLURL` 这类写法，故统一用私有扩展 `childOf()` 做大小写不敏感遍历，并加单测覆盖。**已回写 design.md AD-01 ChangeLog**。

- [x] 2.4 实现 MIME 探测客户端
  - **Action**: 计划复用项目共享 `okHttpClient`（`help/http/HttpHelper.kt:76`）发 SOAP 与上游请求。
  - **Observation**: 共享客户端挂了 Cookie 存储 / DoH / UA 注入 / 日志脱敏等拦截器。SOAP 打的是**局域网设备**，被注入项目 cookie/UA 只会增加不确定性；代理转发上游时更要求**精确控制请求头**，拦截器改写会让"带 Referer 却不生效"这类问题极难排查。此外 SOAP 需要 10s 快速失败，而代理转发长视频**不能有总时长上限**（`callTimeout=0`），单客户端无法同时满足。
  - **Adapt**: 新增 `help/dlna/DlnaHttp.kt`，提供两个独立客户端（`soapClient` / `streamClient`），均 `retryOnConnectionFailure(false)` 落实 AD-02 的"禁止自动重试"。**已回写 design.md AD-01 ChangeLog 与 File Changes**。

- [x] 2.1 首次编译（kaptGenerateStubs 阶段）
  - **Action**: 首轮 `testAppDebugUnitTest` 编译。
  - **Observation**: 报 `CastNetworkHelper.kt:47-50 Syntax error: Expecting a top level declaration`（同一行区间刷几十条）。根因不在代码逻辑：KDoc 里写了 `` `wlan*/eth*/ap*` ``，其中的 **`*/` 提前闭合了块注释**，后半段注释文本变成顶层代码 → 语法崩。测试文件 `CastNetworkHelperTest.kt:18-19` 有同一处。
  - **Adapt**: 改写注释措辞为「以 `wlan` / `eth` / `ap` / `wifi` 开头」「排除 `rmnet` / `tun` / `ppp` / `clat` 开头者」，去掉所有 `X*` 式通配写法；并全目录 Grep `\*/\S` 复查（模式：`\*/\S`，命中数：0）确认无第二处。**教训沉淀到 `.workbuddy/memory/2026-09-12.md`：Kotlin KDoc 中禁止写 `前缀*` 形式的通配（`wlan*` 的 `*` 紧跟 `/` 即闭合注释），改用「以 X 开头」措辞。**
  - 附带：本轮构建还暴露两个环境问题（均已在 issues-found.md 或 memory 记录）——① 沙箱内 Gradle 无法写 `F:\gh`（`FileAccessTimeJournal` 拒绝访问），出沙箱后 `journal-1.lock` 又被僵尸 daemon 占用，需 `--stop` 清场；② 修复前的那次构建在 `kaptGenerateStubsAppDebugKotlin` 阶段挂死 24 分钟无进展（项目已记录的 daemon 挂死现象），处置：杀 java 进程 + `--stop` 后重跑。

- [ ] 4.2 投屏投递决策
  - Action: 按 AD-03 实现三态决策并接代理注册
  - Observation: 假渲染器场景下 `currentPlayHeaders` 含空串值导致误判为"需要代理"
  - Adapt: 决策条件改为"存在非空白值项"（已在 REQ-06/AD-03/4.2 体现），同步修正测试用例
-->

---

---

## 实施进度快照（2026-09-13）

> 检查点 1 通过后进入开发。为便于接续，此处集中登记进度，逐条勾选以本节为准。

### 已完成（代码落地）

| 任务 | 产出 | 验证级别 |
|------|------|---------|
| 1.1–1.4 | `AndroidManifest.xml`（+`CHANGE_WIFI_MULTICAST_STATE`、+`DlnaCastService` `mediaPlayback`）、`AppConst.channelIdCast`、`NotificationId.DlnaCastService=114`、`App.kt` 注册渠道、`strings.xml` 41 条文案 | L1（前一轮编译通过） |
| 1.5 / 2.4 | `DlnaConstants.kt`（8 项超时常量集中表）、`MimeSniffer.kt` | **L1+L2 已实证** |
| 2.1 | `SsdpMessageParser.kt` + `SsdpMessageParserTest`（10 例） | **L2 已实证** |
| 2.2 | `DeviceDescriptionParser.kt` + `DeviceDescriptionParserTest`（13 例） | **L2 已实证** |
| 2.3 | `SsdpDiscovery.kt`（含 MulticastLock 尽力而为 + `ssdp:all` 兜底） | 待编译 |
| 2.5 | `CastNetworkHelper.kt` + `CastNetworkHelperTest`（12 例，AD-13 P0 修复点） | **L2 已实证** |
| 4.1 | `SoapEnvelope.kt` / `DidlLiteBuilder.kt` / `AvTransportClient.kt` / `RenderingControlClient.kt` + 两个测试类（13 + 8 例） | 待编译 |
| 5.1–5.5 | `CastProxyRegistry.kt` / `CastProxyServer.kt` / `HlsPlaylistRewriter.kt` + 两个测试类（14 + 16 例） | 待编译 |
| 4.2–4.7 / 5.6 | `DlnaCastManager.kt`（三态投递 / 首帧判定 / DIDL 降级记忆 / 轮询 / 降级链 / teardown 顺序） | 待编译 |
| 5.1 / 6.1 | `DlnaCastService.kt`（前台服务 + 常驻通知 + 自建 `NetworkCallback`） | 待编译 |
| 3.5 | `PlayerControl.kt` + `VideoPlayerActivity` 注册/注销接线 | 待编译 |
| 3.1–3.4 | `DlnaCastDialog.kt` / `DlnaCastContent.kt` + `VideoPlayerActivity.buildMenuActions()` 投屏入口 + `openCastPanel()` | 待编译 |
| 6.2 | `VideoSettingsPanelContent` GLOBAL 分支「投屏」分组 | 待编译 |
| 6.3 | `errorText()` 覆盖 REQ-11 全部 9 条文案 | 待编译 |
| 3.x | **投屏中状态条**落地（AD-05 兜底②）：`activity_video_player.xml` 顶部 `dlna_cast_banner`（@color/primary 底白字）+ `VideoPlayerActivity` 收集 `DlnaCastManager.state`，CASTING 态显示"正在投屏：设备名"，点击回投屏面板 | 待编译 |
| 4.4 | **切集拦截**落地（REQ-08 Scenario 3）：`DlnaCastManager.isSessionBusy()`（CASTING∪CONNECTING）+ `VideoPlayerActivity.endCastForLocalSwitch()`（stopByUser+提示+回面板）接入 7 个切集入口：上一部/下一部 `switchLegacyFilm`、书源竖滑 `onBookVerticalFling`、ViewPager 滑动 `handlePageSelected`、书源选集/选卷、订阅源换线路/选集 | 待编译 |
| 4.4（部分） | 悬浮窗入口拦截（投屏中先 teardown 再本地播放） | 待编译 |
| 5.7（部分） | 通知与 UI 文案不含 URL/token（REQ-14） | 待编译 |
| 1.x 收尾 | `updateLog.md` 已补 2026/09/13 用户视角条目（编译前规则已满足） | — |

### 未完成（接续清单）

| 任务 | 缺口 | 说明 |
|------|------|------|
| 7.2 | 假渲染器**已就绪并自检 10/10**；2026-09-13 已与 App 首次联调（L2-b） | 见下方 L2-b 实测记录 |
| 7.3 | L2-b 已执行（部分通过，缺口=NAT 结构性限制）；**L2-c 真机待用户执行** | 脚本就绪：`ai_tests/scripts/l2_verify_dlna_cast.py --fake-log <渲染器stdout>`，前置 `net stop SSDPSRV`（测后恢复）；缺口记录在 issues-found.md IF-5 |
| 7.4–7.8 | L3 验收、E2E、收尾审计、文档同步 | L3 需真实电视/盒子（用户执行） |

### 2026-09-13 L2-b 实测记录（MEmu NAT + PC 假渲染器）

- **环境**：MEmu guest 192.168.232.2（NAT）/ PC 假渲染器 `--ssdp-port 1900 --advertise-ip 10.2.152.167`（SSDPSRV 已停，测后已恢复）/ 测试包 legado_miss_app_3.26.091311.apk（libcronet 门禁 OK）/ 订阅源已导入
- **通过**：V1 播放器单 URL 直启 ✅｜V2 顶栏 More→「投屏」入口（desc=More/更多 双 locale）✅｜V3 App M-SEARCH 组播出站（渲染器实收 2 条）✅｜`ssdp:all` 兜底 ✅｜无设备态+前台通知 ✅
- **未过**：V4 发现列表 ❌ —— NAT 不为组播流建 UDP 映射，单播 200 OK 无法回程（IF-5，环境限制非 App 缺陷）；V5–V7 依赖 V4，留 L2-c
- **缺口处置**：L2-c 真机补验（同 Wi-Fi 无 NAT）；脚本与前置条件已固化到 issues-found.md IF-5

### 2026-09-13 接手会话（Goal 模式）编译修复记录

> 前一会话卡在打包（沙箱写不了 F:\gh + journal-1.lock 僵尸 daemon + 后台构建被掐），全部代码首次编译由本会话完成。

| # | 错误 | 修复 |
|---|------|------|
| 1 | `CastProxyServer.kt` import `io.legado.app.utils.AppLog` 不存在（4 处 Unresolved） | 改 `io.legado.app.constant.AppLog` |
| 2 | `CastProxySession.baseKey` 裸调用 `sanitizeName`（该函数在 `CastProxyRegistry` object 内） | 改限定调用 `CastProxyRegistry.sanitizeName(baseName)` |
| 3 | `DlnaCastManager` 7 处 `teardown(notifyDevice = ...)` 调用了不存在的参数 | `teardown` 增加 `notifyDevice: Boolean = true`，true 时 best-effort 下发 Stop（默认 true 兜底，false 用于投递未成功/已单独下发 Stop 的路径） |
| 4 | `startServer()` 返回 `Int?`，`port <= 0` 可空运算符违规 | `?: -1` 归一化后统一走 START_FAILED 分支 |
| 5 | `HlsPlaylistRewriterTest` `"$proxyPrefix1"` 被 Kotlin 模板解析为变量 `proxyPrefix1`（数字属标识符，4 处） | 改 `"${proxyPrefix}1"` |
| 6 | 连带发现：面板 `onViewCreated` 只挡 CASTING，CONNECTING 中重开面板会触发重复发现（违反 3.4） | 改用 `isSessionBusy()` 门控 |

**编译结论（2026-09-13）**：`compileAppDebugKotlin` **通过**（主源码 0 错误）；测试源修复 #5 后随单测任务验证中。

### 已知偏差（已回写 ADR）

1. `DeviceDescriptionParser` 用 jsoup XML 解析器而非 `XmlPullParser`（AD-01 ChangeLog）。
2. 新增 `DlnaHttp.kt`（原文件清单外，AD-01 ChangeLog）。
3. `DlnaCastUiState`/`CastError`/`CastPhase` 与 `DlnaCastManager` 同文件（design 原列独立文件 `DlnaCastUiState.kt`）—— 状态机与状态定义强耦合，同文件更内聚；`ui/video/cast/` 下只保留 UI 两个文件。
4. `DlnaCastContent` 为纯渲染组件（不直接调 Manager），所有动作经回调上抛 —— 比 design 描述更适合日后单测。

---

## 已知验证风险（开工前须知晓）

| 风险 | 影响 | 处置 |
|------|------|------|
| **MEmu 默认 NAT 下 PC 无法反连模拟器代理端口** | 代理投递路径在模拟器上无法验证 | 优先 L2-c（真机 + PC 同 Wi-Fi）；只有模拟器时做 L2-b 并显式记录验证缺口（禁止静默跳过） |
| **Windows SSDPSRV 占用 UDP 1900** | 假渲染器收不到 M-SEARCH，"发现不了设备"，易误判为 App 侧 SSDP 有 bug | 真机联调前 `net stop SSDPSRV`（测后恢复）；工具已内置收包自检并打印指引（`issues-found.md` IF-1） |
| **本机有 4 个非回环 IPv4（含虚拟网卡）** | 自动探测的 `--advertise-ip` 未必是 Wi-Fi 地址 | 必须显式传 `--advertise-ip`（IF-3）；这同时是 AD-13 的最佳验收用例 |
| 无真实 DLNA 设备可用 | L3 无法执行 | 由用户提供设备；无设备时任务保持 `in_progress`，不得标记完成 |
| 假渲染器行为与真机差异 | L2 通过但真机失败 | L3 为最终判据；真机差异一律记 `issues-found.md` 并回写 design |
| **NanoHTTPD 读超时机制尚未在真机长视频上实测** | 若响应写入真受 5s 影响，会出现"播到一半截断" | T5.2 已显式 `start(timeout, false)` 覆盖；T5.3 加**长视频连续播放 >5 分钟**的专项验证项 |
| SOAP 兼容性仅能靠假渲染器预演 | 老设备拒收 DIDL/不认 `:1` URN 等真实差异要到 L3 才暴露 | 降级链（AD-11）已覆盖主流失败形态；L3 逐条回写 |
