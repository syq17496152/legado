# video-proxy-m3u8-403 — 鉴权代理 m3u8 链接被误解包导致 403

> 状态：**已完成**（2026-09-12 验收通过并归档）｜L1 编译 ✅｜L2 真机播放成功 ✅｜L3/全量 E2E ⏳ 待补（memuc 需提权）

## 功能概述

修复视频播放通病：形如 `站点A/media/m3u8?url=<编码内层URL>&exp=<时间戳>&token=<签名>` 的鉴权代理 m3u8 链接，在 `VideoUrlExtractor.extractPlayerPageUrl` 中被「播放器页解包」逻辑错误拆壳，播放器实际请求不带签名的裸内层 URL，CDN 返回 403，播放必然失败（浏览器播壳 URL 正常）。

## 核心变更

- `VideoUrlExtractor.extractPlayerPageUrl` 增加**跳过解包守卫**：外层 URL 自身是媒体清单端点（路径末段精确为 m3u8/mpd/mp4）或外层 query 持有鉴权参数（token/sign/exp/auth_key）时，不解包、原样返回外层 URL
- **AD-04（开发期迭代回流）**：新增 `TlsFallbackDataSourceFactory` —— 真机实测发现第二层故障（站点B CDN 拒绝内置 Cronet 的 TLS 握手 `ERR_SSL_VERSION_OR_CIPHER_MISMATCH`，与项目既有「OkHttp 被拒、Cronet 能过」铁证方向相反），Cronet TLS 失败自动回退 OkHttp 重试同一请求
- 8 个 `resolvePlayerPageUrl` 调用点零改动

## 验证结果（L2 真机，2026-09-12）

- 播放该链接：**first frame rendered latency=3094ms → state READY → 30 秒零错误**
- 解包守卫生效：播放全程壳 URL（日志 urlPath=path=/media/m3u8）
- TLS 回退生效且分流正确：仅站点B 分片请求触发回退，壳域名走 Cronet 主路径
- JVM 单测 22 例全绿（守卫 15 + TLS 判定 7）；全量 testAppDebugUnitTest 339 例中 3 失败均为 Rhino 环境性失败（与本改动无关，diff 不触碰该链路）

## 文档索引

| 文档 | 内容 |
|------|------|
| [spec.md](./spec.md) | Intent / Scope / Approach（三要素）/ Requirements / Scenarios |
| [design.md](./design.md) | 技术方案 / ADR / 数据流 / 文件变更 |
| [tasks.md](./tasks.md) | 分级任务清单 |

## 根因证据链（实测，2026-09-12）

1. 代理壳 GET（任意 UA/Referer/Range/gzip）→ HTTP 200 `application/vnd.apple.mpegurl`，返回合法 AES-128 m3u8，子资源 URL 由壳现签 `auth_key`
2. 解包后的裸内层 URL（`站点B/videos5/{hash}.m3u8?v=3&time=0&via=douyin`）→ **HTTP 403**（裸请求与浏览器头均 403）
3. `extractPlayerPageUrl` 正则 `[?&](?:url|playUrl)=([^&]+)` 命中壳的 `?url=` 参数，解码后含 `.m3u8` 校验通过 → 返回内层 URL，壳与 exp/token 被丢弃
4. key（裸 200）/TS 分片（裸 206，Referer 无关）无防盗链 —— 服务端不挑请求，问题纯在客户端解包

## 变更日志

- 2026-09-12 创建，红队审查 5 轮完成（R2-1 尾斜杠边界、R4-2 INDEX 登记已修复闭环），检查点 1 确认后进入开发
- 2026-09-12 开发完成：守卫 A/B 实施 + JVM 单测 22 绿 + 真机播放成功（首帧 3094ms）；开发期实测发现第二层故障（Cronet TLS 被站点B 拒）→ 迭代回流 AD-04 TLS 回退数据源并真机验证通过；INDEX.md 状态「开发完成待验收」
