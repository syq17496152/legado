# 内置视频播放器 DLNA/UPnP 投屏

> **状态**：🔄 开发中（检查点 1 已通过 2026-09-12；L2 环境定为「真机 + PC 同 Wi-Fi」）
> **创建日期**：2026-09-12
> **最后更新**：2026-09-12
> **路径判定**：扩展路径（新功能 + 跨模块 + 新增权限/端口服务 + 无法单测全覆盖）

## 功能概述

为内置视频播放器新增 **DLNA/UPnP 投屏**能力：用户点击播放器菜单「投屏」，自动发现同一局域网内支持 UPnP AV `MediaRenderer` 的电视/投影/机顶盒，选中后把当前正在播放的视频交给大屏播放，手机退化为遥控器（播放/暂停/停止/进度/音量）。

核心难点不是"发指令"，而是**让电视拿到得流的地址**：Legado 的播放地址大量带防盗链请求头（Referer/Cookie/UA，见 `VideoPlay.currentPlayHeaders`），而电视端无法携带这些头。因此本方案内建一个**本地拉流代理**（复用项目已锁定的 NanoHTTPD），由手机带头上游取流、再以标准 HTTP（含 Range / m3u8 清单重写）喂给电视。

## 核心能力

1. **SSDP 设备发现**：UDP M-SEARCH 打 239.255.255.250:1900，解析 unicast 响应 → 拉取 device description → 仅保留 `MediaRenderer`
2. **投屏入口与设备面板**：播放器下拉菜单新增「投屏」→ BottomSheet 展示设备列表（含"上次投屏设备"快速入口）
3. **三态媒体投递**：无鉴权头直投原地址 / 有鉴权头走本地 HTTP 代理 / 本地 `file://` 走代理文件分支
4. **本地拉流代理**：`CastProxyServer`（NanoHTTPD，系统临时端口）— 上游请求头透传、HTTP Range 断点、m3u8 清单重写（含 `EXT-X-KEY`/`EXT-X-MAP`/相对路径）
5. **投屏控制面板**：播放/暂停/停止/进度 seek/音量/结束投屏；状态由 2s 轮询 `GetPositionInfo` 驱动
6. **会话保活**：`DlnaCastService` 前台服务（`mediaPlayback`）+ 常驻通知（带 播放/暂停/结束 动作）
7. **本机播放协同**：投屏启动即暂停本地播放，结束时按本地进度提示续播
8. **设备记忆**：记住上次设备的 UDN，下次优先展示并可一键续投
9. **降级链路**：DIDL 元数据被拒 → 无元数据重试；设备掉线 → 3 次 ERROR 后判离线并结束会话

## 文档索引

| 文档 | 说明 |
|------|------|
| [spec.md](./spec.md) | Intent / Scope / Approach（Selected + Alternatives + Drawbacks）/ Requirements（REQ-01~REQ-14）/ Scenarios（含 E2E-1~E2E-7）/ 边界清单 |
| [design.md](./design.md) | Technical Approach / 术语约定 / UI 实现约定 / Architecture Decisions（**AD-01~AD-15**）/ 协议与 HTTP 实现铁律 / Data Flow（mermaid）/ File Changes / 回滚方案 / 验证策略 |
| [tasks.md](./tasks.md) | 分级任务清单（8 章）+ AOAdapt 日志位 + 已知验证风险 |
| [issues-found.md](./issues-found.md) | 环境/真机问题记录（IF-1 SSDPSRV 占用 1900、IF-2 多网卡实证、IF-3 出口 IP 探测） |

## 红队对抗性审查记录

### 第一轮：主代理自审（五轮视角）

发现 22 项（P0×0 / P1×10 / P2×12），全部修复回写。

### 第二轮：红蓝双代理对抗 + 主代理裁决（用户专项要求）

红队只攻击、蓝队只核实与给最小修复，主代理对冲突项裁决。共发现 **23 项（P0×2 / P1×13 / P2×8）**，全部处置。

| 轮次 | 视角 | 发现 | 关键项 |
|------|------|:----:|--------|
| 1 | 需求覆盖 | 3 | REQ-08 无落地接线（`object` 够不到播放器）→ AD-14；代理未覆盖 `HEAD`；`Seek` 上限未声明 |
| 2 | 边界与异常 | 7 | **本机 IP 无选择策略（P0）**；Range 仅覆盖单区间；上游忽略 Range；206 `Content-Length` 未钉死；无退避；通知被划掉成孤儿会话；旋转后面板不重挂 |
| 3 | 可落地性 | 4 | NanoHTTPD API 原为"凭印象"→ **已 `javap` 反查 jar 实证**（并发现 `Status` 枚举无 504，必须 `lookup`）；读超时须显式覆盖；`SOAPACTION` 引号与服务 URN `:1` 未写死 |
| 4 | 完整性与一致性 | 4 | **m3u8 漏改 `EXT-X-STREAM-INF` 行内 `URI=`（真实缺陷）**；相对路径基准须用 `response.request.url`；URL 长度膨胀 → 短 ID 登记表；纠正 `EXT-X-BYTERANGE` 误判 |
| 5 | 对抗性破坏测试 | 5 | **MEmu NAT 下 L2 方案不可行（P0）**；`NetworkChangedListener` 无法感知纯断网 → AD-15；代理端口异常路径未释放；`object` 单例跨服务重启残留；`Accept-Encoding` 透传致压缩体喂给电视 |

**裁决要点**（主代理对红蓝冲突的判定）：

- 红队称"5s 读超时会截断 4K 长传输"——**机制描述不成立**（`SO_TIMEOUT` 作用于客户端读取而非响应写出），但"必须显式覆盖超时"的结论成立，故采纳修复、纠正表述，并追加真机长视频专项验证（tasks 风险表）。
- 红队称"漏改 `EXT-X-BYTERANGE`"——**不成立**，该标签不是 URI 承载点，已纠正；但其"同 URL 多次不同区间"的隐含要求已并入 tasks。
- 蓝队核实的既有引用（VideoPlay / MenuAction / BaseService / PanelHost / App 通知渠道 / Manifest 行号 / strings.xml 中文默认 / 依赖坐标）**全部成立**，无悬空引用。

### 结论

无遗留 P0/P1。2 项 P0（本机 IP 选择、L2 环境方案）与 13 项 P1 均已落入 design/tasks 并带验证标准；遗留 P2 写入 spec `Drawbacks` 或 design 已知限制。

## 与现有 spec / 模块的关系

| 关联对象 | 关系 |
|-----------|------|
| `video-player-dual-layout` | 同域（视频播放器 UI）。本 spec 只**追加**菜单项与设置项，不改布局分发链 |
| `rss-video-player-enhancement` | 提供 RSS 视频采集链。本 spec 消费其产物 `VideoPlay.videoUrl` / `currentPlayHeaders`，不侵入采集逻辑 |
| `video-back-fullscreen-fix` / `douyin-style-video-player` | 播放器 UI 层，本 spec 零改动 |
| `docs/specs/archive/2026-09-12-video-proxy-m3u8-403` | 采集链上的 m3u8 鉴权解包修复。本 spec 的 m3u8 重写发生在**投屏出口**，两者阶段不同、互不替代 |
| `WebService` / `HttpServer` | **不复用**（AD-02）。仅复用其依赖 NanoHTTPD 2.3.1 与 `NetworkUtils.getLocalIPAddress()` |

## 关键约束

- 零数据库变更、零 Room 迁移；偏好全部落 `video_config`（`VideoPlay.videoPrefs`）
- 零新增第三方依赖（SSDP 用 `DatagramSocket`，SOAP 用已锁 OkHttp 5.4.0，代理用已锁 NanoHTTPD 2.3.1）
- 新增 1 项权限：`CHANGE_WIFI_MULTICAST_STATE`（SSDP 组播，仅在发现窗口内持锁）
- 不触碰 `gradle/libs.versions.toml` 的任何 Landmines 锁定版本

## 变更日志

| 日期 | 内容 |
|------|------|
| 2026-09-12 | 初版四文档生成 |
| 2026-09-12 | 红队自审五轮 22 项闭环（P1×10 / P2×12） |
| 2026-09-12 | 红蓝双代理对抗审查 23 项闭环（P0×2 / P1×13 / P2×8）：新增 AD-13（本机 IP 选择）/ AD-14（播放器接线通道）/ AD-15（自建 NetworkCallback）、新增「协议与 HTTP 实现铁律」章、m3u8 补齐 `EXT-X-STREAM-INF` 行内 `URI=`、L2 验证分级重写；NanoHTTPD 2.3.1 API 经 javap 实证 |
