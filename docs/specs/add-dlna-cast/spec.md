# spec.md — 内置视频播放器 DLNA/UPnP 投屏

## Intent

**为什么做**：Legado 的视频播放器目前只能手机上看。用户把视频投到大屏（电视/投影/机顶盒）的需求真实且高频，而现方案只有"复制播放地址 → 去第三方投屏 App 粘贴"，且第三方 App 拿不到 Legado 带防盗链头的地址、也无法读 `file://` 本地文件。

**解决什么问题**：
1. 播放地址带鉴权头（`VideoPlay.currentPlayHeaders` 可能含 Referer/Cookie/UA），电视无法携带 → 直投必 403
2. 本地已下载视频（`VideoPlay.videoUrl` 形如 `file://...`，见 `VideoFragment.kt:692`）无法投给电视
3. 没有发现-连接-控制-收尾的完整闭环，用户操作碎裂

**目标**：在播放器菜单里点一次，选设备，即可在大屏播放并全程遥控。

---

## Scope

### 做什么

| 编号 | 内容 |
|------|------|
| S1 | SSDP 主动发现局域网 UPnP AV `MediaRenderer`（电视/投影/盒子） |
| S2 | 播放器下拉菜单新增「投屏」入口（`buildMenuActions()` 追加项） |
| S3 | 设备选择 BottomSheet（发现中/有设备/无设备/发现失败 四态） |
| S4 | 三态媒体投递：直投 / HTTP 带头发代理 / 本地文件代理 |
| S5 | 本地拉流代理 `CastProxyServer`：上游头透传 + HTTP Range + m3u8 清单重写 |
| S6 | 投屏控制：SetAVTransportURI / Play / Pause / Stop / Seek(REL_TIME) / GetPositionInfo / GetTransportInfo / 音量（RenderingControl GetVolume·SetVolume） |
| S7 | 前台服务 `DlnaCastService` + 常驻通知（播放/暂停/结束投屏） |
| S8 | 投屏启动暂停本地播放；结束投屏按本地进度提示续播 |
| S9 | 设备记忆（UDN）+ 上次设备一键续投 |
| S10 | 视频设置页新增「投屏」分组：启用投屏功能 / 强制走代理 |
| S11 | 失败降级链与设备离线判定 |

### 不做什么（明确排除）

| 排除项 | 理由 |
|--------|------|
| Chromecast / AirPlay / Miracast / 屏幕镜像 | 协议族完全不同；`media3-cast` 需 GMS 且覆盖不到国产电视。本期只做 DLNA（`MediaRenderer`） |
| DLNA **MediaServer** 端（把手机书库/视频暴露给电视浏览） | 需求方向相反，工作量独立，另立 spec |
| UPnP **GENA 事件订阅** | 需额外局域网可达的回调 HTTP 端点 + SUBSCRIBE 续订，收益不抵复杂度；用轮询替代（AD-06） |
| DRM 加密流（Widevine/PlayReady） | 无合法播放路径，投屏必失败 |
| 多设备同时投屏 | 单会话模型，UI 与状态机不支持多路 |
| 投屏画质/码率切换、字幕轨道下发 | 属 DLNA 渲染端能力，控制器侧不可控 |
| 音频（听书/TTS/音乐）投屏 | 本期只覆盖视频播放器域 |
| 非局域网场景（手机走 4G/5G） | SSDP 不可用，仅做前置校验与提示 |
| 采集链（嗅探/解包）任何改动 | 投屏只消费 `VideoPlay.videoUrl`/`currentPlayHeaders`，不入侵采集 |

### 影响模块

| 模块 | 影响 |
|------|------|
| `ui/video/`（播放器 UI） | 新增菜单项、新增投屏 BottomSheet；`VideoSettingsPanelContent` GLOBAL 分支追加设置行 |
| 新建 `help/dlna/` | UPnP 客户端 + 代理 + 会话管理器（主要工作量） |
| 新建 `service/DlnaCastService.kt` | 前台服务 |
| `app/src/main/AndroidManifest.xml` | +1 权限、+1 service 声明 |
| `app/src/main/res/values/strings.xml` | 新增文案（默认中文，与既有新增条目风格一致） |
| `gradle/libs.versions.toml` | **零改动**（不新增依赖） |
| 数据库 | **零改动**（无新实体、无迁移） |

---

## Approach

### Selected Approach

**自研轻量 UPnP 客户端 + 内建 NanoHTTPD 拉流代理 + 前台服务承载**。

三段式：

1. **发现**：`DatagramSocket` 向 `239.255.255.250:1900` 发 SSDP M-SEARCH（`ST=urn:schemas-upnp-org:device:MediaRenderer:1`，`MX=2`），收集 unicast 响应中的 `LOCATION`，逐个拉取 device description XML，筛出 `MediaRenderer` 并抽出 `AVTransport` / `RenderingControl` 的 `controlURL`（相对路径按 LOCATION 归一化）。
2. **投递**：依据"是否带鉴权头 / 是否 `file://`"决策三态，必要时把地址换成本机代理 URL（`http://<WLAN_IP>:<临时端口>/cast/{token}/<name>`），再用 `DidlLiteBuilder` 拼 DIDL-Lite 元数据，`AvTransportClient.setAvTransportURI()` + `play()`。
3. **代理**：`CastProxyServer : NanoHTTPD(0)`（端口 0 = 系统分配，`listeningPort` 回读），`/cast/{token}/...` 路由经 `CastProxyRegistry` 白名单校验后，用 OkHttp 带头上游取流并透传 Range；m3u8 响应经 `HlsPlaylistRewriter` 把每条 URI 改写成同一 token 下的代理地址。

生命周期挂 `DlnaCastService`（前台服务，`mediaPlayback`），保证息屏/切后台不中断。

**为什么选它**：① 零新增依赖，完全绕开项目依赖锁文化（Landmines）；② 只实现 MediaRenderer + AVTransport/RenderingControl 的 10 个 action，用不到通用 UPnP 栈 90% 的能力；③ 代理直接复用已锁定 NanoHTTPD 与 OkHttp，不引新网络栈；④ 全部纯解析逻辑（SSDP 报文/SOAP/DIDL/m3u8 重写）可抽成无 Android 依赖的纯函数，进 JVM 单测覆盖。

### Alternatives Considered

| 方案 | 否决理由 |
|------|---------|
| **引入 Cling 2.1.2（`org.fourthline.cling:cling-core`）** | 2016 年后停更；依赖 `java.beans`/`javax.*` 命名空间在 Android 需大量排除与 hack；含 Jetty/seamless 等无用传递依赖，APK 体积 +≈1MB；与项目"依赖版本锁定、能不加就不加"的既有约束直接冲突。且我们只用 10 个 action，性价比极低 |
| **引入 jUPnP（Cling 继任者）** | 同样重（含 `AndroidUpnpService` 全套 OSGi-less 组装 + Jetty/Grizzly 可选实现），且需自行拼装 `UpnpServiceConfiguration`，接入成本高于自研 |
| **`androidx.media3:media3-cast`（Cast SDK 桥）** | 只覆盖 Google Cast 协议。国产电视/盒子绝大多数不支持 Chromecast，且依赖 Google Play Services（项目另有 Cronet/无 GMS 考量），命中不了目标场景 |
| **复用现有 `WebService` / `HttpServer` 做代理** | `WebService` 是用户开关的"Web 书源管理服务"，端口 1122 用户可改；耦合会带来端口冲突、服务生命周期纠缠（用户关掉 Web 服务 = 投屏断流）、安全暴露面扩大（把书源管理 API 与视频代理混在同一端点）。独立实例成本极低（NanoHTTPD 一个类） |
| **不做代理，只直投 URL** | 命中率崩盘：带 Referer/Cookie 的源直投必 403；`file://` 完全不可用。等于只服务少数无防盗链源 |
| **代理 + 不做 m3u8 重写（只直投 HLS）** | m3u8 在 Legado 视频源中占比高，且带鉴权的 m3u8 直投必失败。放弃重写会让"带鉴权的 HLS 源"整体不可投，功能残缺 |
| **用 GENA 事件订阅替代轮询** | 需在手机上再起一个局域网可达的 HTTP 事件接收端点 + NOTIFY 解析 + SUBSCRIBE 续订（默认 1800s 过期），复杂度与收益严重失衡 |
| **投屏时手机继续静音播放（双路）** | 流量翻倍（4K 源手机需同时拉一路）、功耗高、无任何收益 |
| **不建前台服务，Activity 内直跑** | 息屏/切后台后进程被限，代理断流、轮询停摆，用户看到"投着投着就黑了"。与项目既有的服务化保活范式（`WebService`/`VideoPlayService`）也不一致 |

### Drawbacks

| 缺陷 / 风险 | 说明 | 接受理由 | 兜底预案 |
|------------|------|---------|---------|
| **代理模式下手机上行带宽成为瓶颈** | 4K/高码率源经手机转发，Wi-Fi 上行不足会卡顿 | 无鉴权头场景走直投（AD-03），代理只服务"必须代理"的源 | 控制面板显示"代理转发中"标识；设置项「强制走代理」默认关闭；卡顿时提示改用直投或降低清晰度 |
| **m3u8 重写覆盖面不可能 100%** | 少数站点用非标准 `#EXT-X-*` 自定义标签或清单内嵌嵌套清单 | HLS 规范内可枚举的 URI 承载点已全部覆盖（AD-04） | 重写后若渲染端报错，降级为该源「仅直投 m3u8」并 toast 提示用户可能失败；v1 若真机验证不过则整体降级（见 tasks T7.4） |
| **DLNA 渲染端兼容性碎片化** | 部分老设备拒收 DIDL 元数据、部分不认 `DLNA.ORG_OP` 而失去 seek 能力 | 这是 DLNA 生态固有问题，任何方案都躲不掉 | 降级链（AD-11）：元数据被拒 → 无元数据重试；seek 失败 → 隐藏进度条仅保留播放/暂停/停止 |
| **轮询带来 ≤2s 进度延迟** | 暂停/seek 后 UI 不即时刷新 | 换取 GENA 的复杂度豁免（AD-06） | 本地操作后立即乐观更新 UI，轮询结果回来再校正 |
| **SSDP 在部分模拟器/受限 Wi-Fi 上不可用** | 组播被 AP 隔离或 ROM 拦截 | 真机 + 正常家用路由器是主场景 | 发现失败给明确提示与「重试」；前置校验网络类型（非 WLAN 直接拦截并提示） |
| **代理端点暴露在局域网** | 同一网段他人可访问代理 URL | 局域网内、且仅白名单 URL 可达，非开放代理 | token 32 位随机 + 会话结束即注销（AD-08）；响应不暴露上游头 |
| **新增组播权限** | `CHANGE_WIFI_MULTICAST_STATE` 属敏感权限表述 | DLNA 发现的事实必需品，仅发现窗口内持锁并立即释放 | 权限仅用于 `MulticastLock`，不做其他用途；获取失败不崩溃（runCatching 降级为无锁发现） |
| **`file://` 投屏需读本地文件并外发** | 视频内容会经局域网传给电视 | 用户主动点击投屏即视为授权 | 仅在用户显式投屏该文件时注册，会话结束注销 |
| **无 GENA，设备端主动暂停（用遥控器按了暂停）需等下一轮轮询才发现** | 状态同步有延迟 | 同上 | 轮询周期 2s，延迟可接受 |
| **多网卡环境下投递地址可能选错** | VPN/蜂窝/虚拟网卡与 Wi-Fi 并存时，选到错误 IP 会导致电视永远连不上且无报错 | 家用场景绝大多数只有一路 Wi-Fi | AD-13 定义三级选择策略 + 同网段校验 + 多条地址时 UI 显式提示；全部失败则前置拦下并提示（不猜） |
| **`Seek` 用 `REL_TIME`，上限 24 小时且无毫秒精度** | 超 24h 单文件无法精确 seek | 家用场景无此量级视频；补偿收益为零 | 目标值钳制到 `23:59:59`，记录为已知限制 |
| **代理无缓存、无自动重试，防盗链源可能被限流** | 渲染端重试或多次 seek 会重复打上游 | 有缓存需处理一致性与磁盘占用；自动重试会放大源站风控 | 并发上限 16 + 失败原样透传（不自动重试）+ 面板显示「代理转发中」；用户可切直投 |
| **MEmu 默认 NAT 下代理回连路径无法本地验证** | 无真机时 L2 只能覆盖发现与投递半链路 | 属测试环境限制，非方案缺陷 | 优先真机 + PC 同 Wi-Fi 做全链路；只有模拟器时显式记录验证缺口到 `issues-found.md`，由 L3 补 |

### Prior Art

- `docs/specs/archive/2026-09-12-video-proxy-m3u8-403`（采集链 m3u8 解包修复）——本 spec 的 m3u8 重写与其**同源不同阶段**，实现时可直接复用其对 m3u8/mpd 路径的判别口径（路径末段全等 + 外层鉴权参数），避免重新踩坑。
- `WebService` / `WebTileService`——前台服务 + 通知 + `NetworkChangedListener` + `NetworkUtils.getLocalIPAddress()` 的现成范式，`DlnaCastService` 直接对标。
- `VideoSettingsPanel` / `AppDropdownMenu`（`ui/widget/components/AppMenuSheet.kt:52` `MenuAction`）——菜单项与 BottomSheet 的现成组件族，不新造 UI 轮子。

---

## Requirements

> 关键字：SHALL = 必须；SHOULD = 建议。每条需求编号用于 design/tasks 交叉引用。

### REQ-01 投屏入口（→ design File Changes；tasks T3.1）

系统 SHALL 在内置视频播放器的下拉菜单中提供「投屏」入口，且当 `VideoPlay.videoUrl` 为空时该入口 SHALL 不可用（置灰或点击提示"暂无播放地址"）。

#### Scenario: 有播放地址时显示入口
- **WHEN** 用户在播放器页面展开下拉菜单，且 `VideoPlay.videoUrl` 非空
- **THEN** 菜单出现「投屏」项，点击后打开设备选择面板

#### Scenario: 无播放地址
- **WHEN** `VideoPlay.videoUrl` 为空或为空白串
- **THEN** 点击「投屏」提示"暂无播放地址"，不打开面板

#### Scenario: 用户关闭了投屏功能
- **WHEN** 设置项「启用投屏功能」为 false
- **THEN** 下拉菜单不出现「投屏」项

### REQ-02 设备发现（→ AD-01；tasks T2.1, T2.2, T2.3）

系统 SHALL 通过 SSDP M-SEARCH 发现同一局域网内的 UPnP AV `MediaRenderer` 设备，SHALL 在单次发现中最多等待 4 秒并去重（按 UDN），SHALL 只保留描述文档中 `deviceType` 含 `MediaRenderer` 的设备。

#### Scenario: 正常发现
- **WHEN** 局域网内存在 MediaRenderer，用户打开投屏面板
- **THEN** 面板先显示"正在搜索设备…"，在 4 秒窗口内陆续回填设备名（`friendlyName`），并显示设备数量

#### Scenario: 无任何响应
- **WHEN** 4 秒内无任何 SSDP 响应
- **THEN** 面板显示"未发现可投屏设备"+「重试」按钮 + 排查提示（电视是否开启投屏/是否同一 Wi-Fi）

#### Scenario: 响应中混有非渲染设备
- **WHEN** 响应包含 `MediaServer`、`InternetGatewayDevice` 等非渲染设备
- **THEN** 这些设备不出现在列表中

#### Scenario: 重复响应
- **WHEN** 同一 UDN 收到多条响应
- **THEN** 列表只出现一次（保留首个成功解析的描述）

#### Scenario: 网络不可用
- **WHEN** 当前连接非 WLAN（或未连接网络）
- **THEN** 不发起发现，直接提示"请先连接 Wi-Fi"

### REQ-03 设备选择面板（→ design「UI 实现约定」；tasks T3.2, T3.4）

面板 SHALL 是可复用的 BottomSheet，SHALL 展示设备名/型号，SHALL 在发现结束后提供「重新搜索」，SHALL 在存在历史设备时优先展示「上次投屏：XXX」快捷项。

#### Scenario: 有历史设备时优先展示
- **WHEN** 存在已记录的设备 UDN 且该设备本次也在线
- **THEN** 列表首项为「上次：XXX」，点击即投

#### Scenario: 历史设备不在线
- **WHEN** 历史设备本次未被发现
- **THEN** 快捷项仍展示但标注"未在线"，点击提示需先让电视进入投屏待机

#### Scenario: 投屏中重新打开面板
- **WHEN** 会话已处于连接中或投屏中，用户再次从菜单进入投屏
- **THEN** 面板跳过发现流程，直接落到控制态（不得重新发起发现、不得丢失当前会话）

#### Scenario: 投屏中切换到另一个设备
- **WHEN** 用户处于投屏中，在面板里选择另一台设备
- **THEN** 系统先停止当前投屏（暂停本地无需恢复），再对新设备执行投递；旧会话的代理注册项 SHALL 被注销

### REQ-04 投屏启动（→ AD-09, AD-11；tasks T4.1, T4.2, T4.6）

系统 SHALL 通过 `SetAVTransportURI(InstanceID=0, CurrentURI=<投递地址>, CurrentURIMetaData=<DIDL-Lite>)` 后紧接 `Play(Speed=1)` 启动投屏；SHALL 在 DIDL 中声明 `protocolInfo` 与 `<upnp:class>object.item.videoItem</upnp:class>`；SOAP 请求 SHALL 使用服务 URN `...:service:AVTransport:1` 且 `SOAPACTION` 头值 SHALL 带双引号。

#### Scenario: 首次投屏成功
- **WHEN** 设备可用且投递地址可被设备拉取
- **THEN** 面板切换为控制态，显示"正在大屏播放：<标题>"，本地播放暂停

#### Scenario: 老设备拒收 DIDL 元数据
- **WHEN** `SetAVTransportURI` 返回 SOAP Fault（如 UPnPError 402/714）
- **THEN** 系统自动以空 `CurrentURIMetaData` 重试一次；成功则继续，同时记录该设备为"无元数据兼容"（写入偏好，后续对该设备跳过元数据）

#### Scenario: 地址设备拉不动
- **WHEN** `SetAVTransportURI` 返回成功但 8 秒内 `GetTransportInfo` 仍为 `STOPPED`
- **THEN** 判定拉流失败，结束会话，提示"电视无法访问该播放地址"，并给出「改用其他播放器打开」兜底入口

#### Scenario: 重复点击投屏入口
- **WHEN** 用户连续快速点击「投屏」或在连接过程中再次点击
- **THEN** 不发起第二个发现流程与第二个代理实例；SHALL 复用当前会话状态（连接中则等待，投屏中则进控制态）

#### Scenario: DIDL 兼容记忆命中
- **WHEN** 目标设备 UDN 已在"无元数据兼容"名单中
- **THEN** 直接以空 `CurrentURIMetaData` 投递，不再重复失败一次

### REQ-05 投屏控制（→ AD-06；tasks T4.3, T4.5, T4.7）

控制面板 SHALL 提供 播放/暂停/停止/进度 seek/音量 控制，并 SHALL 以 2 秒周期轮询 `GetPositionInfo` 刷新进度；暂停态 SHALL 停止进度轮询（仅保留低频 `GetTransportInfo`）。

#### Scenario: 暂停与继续
- **WHEN** 用户点「暂停」
- **THEN** 立即下发 `Pause`，UI 乐观切换为"已暂停"，进度轮询降为 10 秒一次的 `GetTransportInfo`

#### Scenario: 拖动进度
- **WHEN** 用户拖动进度条到 T 秒
- **THEN** 下发 `Seek(Unit=REL_TIME, Target=HH:MM:SS)`，本地乐观更新到 T，下一轮轮询校正

#### Scenario: 渲染端不支持 seek
- **WHEN** `Seek` 返回 SOAP Fault，或 `GetPositionInfo` 的 `TrackDuration` 恒为 `00:00:00`
- **THEN** 隐藏/置灰进度条，仅保留播放/暂停/停止，并提示"该设备不支持进度控制"

#### Scenario: 音量控制
- **WHEN** 用户调节音量滑条
- **THEN** 通过 RenderingControl `SetVolume(Channel=Master, DesiredVolume=v)` 下发；设备未提供 RenderingControl 时该控件隐藏

### REQ-06 媒体地址投递策略（→ AD-03；tasks T4.2）

系统 SHALL 按以下顺序决策投递地址：

1. `VideoPlay.videoUrl` 以 `file://` 开头 → **文件代理**
2. `VideoPlay.currentPlayHeaders` 非空（含非空值项）→ **HTTP 代理**
3. 否则 → **直投**（原地址）
4. 设置项「强制走代理」为 true 时，除 `file://` 外一律改为 **HTTP 代理**

#### Scenario: 无鉴权头直投
- **WHEN** `currentPlayHeaders` 为空，URL 为 `https://.../a.mp4`
- **THEN** 投递地址即原 URL，不启动代理服务

#### Scenario: 带防盗链头必须代理
- **WHEN** `currentPlayHeaders` 含 `Referer`
- **THEN** 投递地址为 `http://<本机WLAN_IP>:<端口>/cast/{token}/a.mp4`，代理在拉上游时带上该 Referer

#### Scenario: 本地文件投屏
- **WHEN** `videoUrl` 为 `file:///storage/.../a.mp4`
- **THEN** 投递地址为代理 URL，代理以本地文件流响应，支持 Range

#### Scenario: 拿不到 WLAN IP
- **WHEN** `NetworkUtils.getLocalIPAddress()` 返回空
- **THEN** 不启动代理，提示"未获取到局域网 IP，请检查 Wi-Fi"

#### Scenario: 多网卡环境选择投递地址
- **WHEN** 设备同时存在 Wi-Fi、VPN（`tun*`）、蜂窝（`rmnet*`）等多个非回环 IPv4 地址
- **THEN** 系统 SHALL 选择**当前活动网络为 Wi-Fi/以太网**的那个地址用于投递，SHALL NOT 使用列表首项；无法确定唯一地址时 SHALL 采用规则并提示用户"检测到多个网络接口，使用的是 X.X.X.X"

#### Scenario: 投递地址与设备不同网段
- **WHEN** 选出的本机地址与渲染端 `LOCATION` 的 host 不在同一 /24 网段
- **THEN** 不阻断投屏，但提示"手机与电视可能不在同一网络"，并记录一条诊断日志

### REQ-07 本地拉流代理（→ AD-02, AD-04, AD-08；tasks T5.2, T5.3, T5.4, T5.5）

代理 SHALL 监听系统分配端口，SHALL 仅响应已注册 token 的路径，SHALL 透传上游请求头与 HTTP Range，SHALL 对 m3u8 响应做 URI 重写；SHALL 支持 `HEAD` 探测、SHALL 支持后缀与开区间 Range、SHALL 在上游忽略 Range 时本地切片并回 `206`；SHALL 剥除客户端 `Accept-Encoding` 且输出未压缩体；SHALL 以线程安全方式维护注册表，SHALL 约束并发请求数上限，SHALL 在任何返回/中断路径上释放上游资源，SHALL NOT 回传上游响应头、SHALL NOT 缓存或落盘、SHALL NOT 自动重试。

#### Scenario: 断点续传
- **WHEN** 电视发 `Range: bytes=1024-2047`
- **THEN** 代理向上游透传该 Range，回 `206 Partial Content` + `Content-Range: bytes 1024-2047/<total>` + `Content-Length: 1024`（**区间长度，非全文件长度**）+ `Accept-Ranges: bytes`

#### Scenario: 后缀区间与开区间
- **WHEN** 电视发 `bytes=-500` 或 `bytes=500-`
- **THEN** 分别回 `Content-Range` 为 `bytes <total-500>-<total-1>/<total>` 与 `bytes 500-<total-1>/<total>` 的 `206`

#### Scenario: 区间越界
- **WHEN** 请求的起始偏移 >= 文件总长度
- **THEN** 回 `416`

#### Scenario: 多区间请求
- **WHEN** 电视发 `bytes=0-100,200-300`
- **THEN** 代理按 RFC 7233 允许的方式**忽略 Range**，回 `200` + 全量内容（不实现 `multipart/byteranges`）

#### Scenario: 上游忽略 Range
- **WHEN** 代理带 Range 请求上游，上游却回 `200` 全量
- **THEN** 代理 SHALL 本地跳过前缀字节、只回请求区间并置状态为 `206`，保证电视 seek 数据不错位

#### Scenario: HEAD 探测
- **WHEN** 渲染端先发 `HEAD` 探测资源
- **THEN** 代理回 `200` + `Content-Type`/`Content-Length`（或 `Accept-Ranges`）且**无 body**，不返回 405

#### Scenario: 客户端带 Accept-Encoding
- **WHEN** 渲染端请求头含 `Accept-Encoding: gzip`
- **THEN** 代理 SHALL 剥掉该头再向上游请求，且响应 SHALL NOT 含 `Content-Encoding`/`Transfer-Encoding`（代理输出恒为未压缩体）

#### Scenario: 上游无 Content-Length
- **WHEN** 上游响应没有 `Content-Length`（直播/未知长度）
- **THEN** 代理以 chunked 方式回传，不猜测长度

#### Scenario: 无 Range 的普通拉流
- **WHEN** 电视发不带 Range 的 GET
- **THEN** 代理回 `200 OK` + `Content-Type: <推断 MIME>` + `Content-Length`（上游有则透传，无则分块）

#### Scenario: 非法 token
- **WHEN** 请求路径的 token 不在注册表
- **THEN** 回 `404`，不发起任何上游请求

#### Scenario: 会话已结束
- **WHEN** 投屏结束后再有空请求到达
- **THEN** 回 `404`（注册表已清空）

#### Scenario: m3u8 清单重写
- **WHEN** 上游响应 `Content-Type` 为 `application/vnd.apple.mpegurl` / `application/x-mpegURL`，或 URL 路径末段为 `.m3u8`
- **THEN** 代理重写清单内所有 URI（绝对/相对/`#EXT-X-KEY`/`#EXT-X-MAP`/`#EXT-X-MEDIA`/`#EXT-X-I-FRAME-STREAM-INF`）指向同一 token 的代理路径，且校验改写后的清单中不再出现原始域名

#### Scenario: 上游返回错误
- **WHEN** 上游返回 4xx/5xx
- **THEN** 代理原样透传状态码与状态描述，不吞错误（便于用户与日志定位）

#### Scenario: 上游超时
- **WHEN** 上游 15 秒无响应
- **THEN** 代理断开该连接并回 `504`，不影响后续请求

#### Scenario: 上游响应头不外泄
- **WHEN** 代理返回任何响应
- **THEN** 响应头 SHALL 只含必要项（`Content-Type`/`Content-Length`/`Content-Range`/`Accept-Ranges`/`Connection` 等），SHALL NOT 回传上游的 `Set-Cookie`、`Authorization`、`Location` 或其他自定义头

#### Scenario: 客户端中途断开
- **WHEN** 电视在传输过程中关闭连接（切台/退出播放）
- **THEN** 代理 SHALL 立即释放上游响应体与连接，不得泄漏连接或线程

#### Scenario: 本地文件已不存在
- **WHEN** 投屏的 `file://` 文件被删除或不可读
- **THEN** 代理回 `404`（不存在）/`403`（不可读），不崩溃；投屏侧提示"本地文件不可用"

#### Scenario: 并发分片拉取
- **WHEN** 渲染端对 HLS 并发发起多个分片请求
- **THEN** 注册表读取 SHALL 线程安全；请求并发数 SHALL 受上限约束，超出时拒绝而非无限创建线程

#### Scenario: 代理不做缓存
- **WHEN** 同一路径被重复请求（如直播清单刷新、播放器重试）
- **THEN** 代理每次独立向上游取流，不落盘、不缓存；因此长视频与直播均无本地内存/磁盘累积增长

### REQ-08 投屏中本地播放处理（→ AD-07；tasks T4.4, T6.1）

投屏成功建立后，系统 SHALL 暂停本地播放但 SHALL 保留本地进度；结束投屏后 SHOULD 提供"回到手机继续播放"入口。

#### Scenario: 投屏建立后
- **WHEN** `SetAVTransportURI` + `Play` 成功
- **THEN** 本地播放器暂停，`VideoPlay` 的播放进度不被清空

#### Scenario: 结束投屏
- **WHEN** 用户点「结束投屏」
- **THEN** 下发 `Stop`，本地播放器保持暂停，UI 提示"已在手机暂停，可继续播放"

#### Scenario: 投屏期间用户切换集数
- **WHEN** 用户在投屏中切到下一集/下一篇
- **THEN** 系统 SHALL 结束当前投屏会话并回到投屏面板（不做静默续投，避免状态错乱），用户可重新点投屏

### REQ-09 会话保活（→ AD-05；tasks T5.1, T5.6）

系统 SHALL 用前台服务承载投屏会话，SHALL 在通知中提供 播放/暂停/结束投屏 动作，SHALL 在会话结束时停止前台服务。

#### Scenario: 息屏后仍可控
- **WHEN** 用户锁屏
- **THEN** 投屏继续，通知保持，从通知点暂停可生效

#### Scenario: 会话结束清理
- **WHEN** 用户结束投屏或投屏失败退出
- **THEN** 代理停止、注册表清空、前台服务停止、通知消失

#### Scenario: 前台服务启动失败
- **WHEN** Android 13+ 未授予通知权限导致前台服务无法展示通知
- **THEN** 不崩溃；会话降级为"仅前台可见"并提示用户授予通知权限

### REQ-10 设备记忆与续投（→ AD-10；tasks T3.3）

系统 SHALL 记录上次成功投屏设备的 `UDN` 与显示名，SHALL 在下次打开面板时优先展示该设备的快捷投屏入口。

#### Scenario: 记忆持久化
- **WHEN** 用户成功投屏到设备 X 后重启 App
- **THEN** 打开投屏面板首项为「上次：X」

### REQ-11 错误处理与降级（→ AD-11；tasks T4.6, T6.1, T6.3）

系统 SHALL 将以下情形转化为用户可理解的中文提示，且 SHALL 保证任何失败路径都不残留代理服务与前台服务：

| 情形 | 提示 |
|------|------|
| 无 WLAN | 请先连接 Wi-Fi |
| 无设备 | 未发现可投屏设备（附排查建议） |
| 连接设备失败 | 无法连接到该设备，请确认电视投屏功能已开启 |
| 设备拒收地址 | 电视无法访问该播放地址（附「用浏览器打开」兜底） |
| 投屏中设备掉线 | 投屏已断开，设备可能已关闭 |
| m3u8 直投（无头）场景 | 部分电视不支持 HLS，如无画面请改用其他方式 |

#### Scenario: 投屏中设备掉线
- **WHEN** `GetTransportInfo` 连续 3 次返回 `ERROR_OCCURRED` 或网络请求连续 3 次超时
- **THEN** 结束会话、清理资源、提示"投屏已断开，设备可能已关闭"

#### Scenario: 结束投屏时设备已不可达
- **WHEN** 用户点「结束投屏」时 `Stop` 请求失败
- **THEN** 忽略该错误，照常清理本地资源（不阻塞用户退出）

### REQ-12 设置项（→ AD-10；tasks T6.2）

系统 SHALL 在视频播放器全局设置页（`PanelHost.GLOBAL` 分支）新增「投屏」分组，含：

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| 启用投屏功能 | 布尔 | true | 关闭后隐藏投屏菜单入口 |
| 强制走代理 | 布尔 | false | 所有流经手机转发（仅在直投失败排查时使用） |

#### Scenario: 关闭投屏功能
- **WHEN** 用户关闭「启用投屏功能」
- **THEN** 播放器菜单不再出现「投屏」；若已有会话在跑，不强制中断但后续入口不可用

#### Scenario: 默认值
- **WHEN** 首次安装/首次进入设置页
- **THEN** 「启用投屏功能」为开、「强制走代理」为关

### REQ-13 权限与清单（→ AD-01, AD-05；tasks T1.2）

系统 SHALL 声明 `CHANGE_WIFI_MULTICAST_STATE` 权限，SHALL 将 `DlnaCastService` 注册为 `foregroundServiceType="mediaPlayback"`。

#### Scenario: Android 14 前台服务
- **WHEN** 在 Android 14+ 上启动投屏
- **THEN** 前台服务以 `mediaPlayback` 类型合法启动，不抛 `MissingForegroundServiceTypeException`

#### Scenario: 权限被系统拒绝
- **WHEN** `MulticastLock` 获取失败
- **THEN** 不崩溃，退化为无锁发现并继续尝试

### REQ-14 日志与脱敏（→ AD-12；tasks T5.7, T7.7）

系统 SHALL 复用项目既有的网络日志脱敏通道，SHALL NOT 将代理 token、上游播放地址、防盗链请求头原文写入任何用户可见日志或通知。

#### Scenario: 投屏会话日志
- **WHEN** 记录投屏相关日志（castUrl、上游 URL、headers）
- **THEN** castUrl SHALL 以站点/地址代号形式呈现，headers 只记录**键名**与数量，不记录值

#### Scenario: 通知文案
- **WHEN** 前台服务通知展示当前投屏内容
- **THEN** 只展示视频标题与设备名，不含任何 URL 或 token

---

## Scenarios — 端到端串联

### E2E-1：无鉴权 MP4 直投（happy path）

```
用户在播放器播放 https://cdn.example.com/a.mp4（currentPlayHeaders 为空）
 → 菜单点「投屏」
 → 面板 4s 内发现「客厅电视」
 → 点击 → 直投模式（无代理）
 → SetAVTransportURI(a.mp4, DIDL) → Play
 → 本地暂停，面板进入控制态，进度 2s 轮询
 → 用户拖动进度条到 10:00 → Seek(REL_TIME) → 电视跳转
 → 用户点「结束投屏」→ Stop → 资源清理 → 提示"已在手机暂停"
```

### E2E-2：带鉴权头 MP4 代理投递

```
用户在播放器播放 source 站视频（currentPlayHeaders = {Referer: https://site/})
 → 菜单点「投屏」→ 选「卧室盒子」
 → 投递策略判定为 HTTP 代理
 → CastProxyRegistry.register(url, headers, video/mp4) → token=T
 → 代理启动于 45678 → 投递 http://192.168.1.7:45678/cast/T/a.mp4
 → 盒子 GET 该地址（Range: bytes=0-）
 → 代理带 Referer 向上游取流 → 回 206
 → 播放成功
```

### E2E-3：带鉴权 HLS 代理投递

```
播放 source 站 m3u8（currentPlayHeaders 非空）
 → 投屏 → 代理注册，投递 /cast/T/index.m3u8
 → 盒子 GET index.m3u8 → 代理带头上游取清单 → 重写每条 URI → 回 200
 → 盒子 GET /cast/T/seg_001.ts → 代理带头上游取分片 → 回 206
 → 播放成功
```

### E2E-4：本地文件投屏

```
用户播放已下载视频（videoUrl = file:///storage/emulated/0/Download/a.mp4）
 → 投屏 → 文件代理模式
 → 盒子 GET → 代理 open 本地文件流 → 200/206 + Content-Length = 文件大小
```

### E2E-5：失败路径（无设备）

```
用户手机连的是 4G → 点「投屏」→ 直觉提示"请先连接 Wi-Fi"，不启动发现
（另一种）连了 Wi-Fi 但电视未开投屏 → 4s 后"未发现可投屏设备" + 重试
```

### E2E-6：失败路径（投屏中断线）

```
投屏中用户关掉电视电源
 → GetTransportInfo 连续 3 次 ERROR_OCCURRED
 → 会话终止 → 代理停止 → 前台服务停止 → 通知消失
 → 提示"投屏已断开，设备可能已关闭"
 → 本地播放仍处暂停，用户可继续手机播放
```

### E2E-7：失败路径（投屏中网络切换）

```
投屏中用户关闭 Wi-Fi / 走到信号外
 → NetworkChangedListener 上报断开
 → 会话立即 teardown（不等待 3 次轮询失败）
 → 提示"网络已切换，投屏已中断"
 → 代理端口释放、前台服务停止
```

---

## 边界与异常清单（供 design/tasks 对齐）

| 类别 | 边界 |
|------|------|
| 空值 | `videoUrl` 为空 / `currentPlayHeaders` 为 null 或全空串 / 设备 `friendlyName` 为空 / `controlURL` 为空 |
| 网络 | 非 WLAN、WLAN 无 IP、AP 隔离、切换到移动网络的瞬间 |
| 并发 | 投屏中重复点「投屏」、连续快速切换设备、投屏中切集、结束投屏与轮询竞态 |
| 超时 | SSDP 等待 4s、描述文档拉取 5s、SOAP 请求 10s、上游取流 15s、首帧判定 8s |
| 失败回退 | DIDL 被拒重试、seek 不支持隐藏控件、音量不支持隐藏控件、Stop 失败照常清理 |
| 大数据量 | 4K 高码率转发、直播 m3u8 无限拉取、长视频 Range 请求碎片化 |
| 兼容迁移 | 覆盖安装（新增偏好键，读取缺失即默认值）、老设备无 RenderingControl、老设备不认 `DLNA.ORG_OP` |
| 权限 | `CHANGE_WIFI_MULTICAST_STATE` 未授予、Android 13+ 通知权限未授予、Android 14 FGS 类型 |
| 生命周期 | 息屏、切后台、进程被回收后重进 App、投屏中旋转屏幕、多 Activity（悬浮窗 + 投屏） |
