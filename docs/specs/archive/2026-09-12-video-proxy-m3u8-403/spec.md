# spec.md — video-proxy-m3u8-403

## Intent

让鉴权代理型 m3u8 链接（壳持有 token 签名、子资源由壳现签）在 Legado 内置播放器中原样播放，不再被「播放器页解包」逻辑拆掉鉴权壳导致裸内层 URL 403。

## Scope

### 做
- `VideoUrlExtractor.extractPlayerPageUrl` 增加跳过解包守卫（纯函数级修改）
- 守卫命中时 `resolvePlayerPageUrl` 原样返回外层壳 URL（调用点零改动）
- JVM 单元测试覆盖守卫正反用例
- 可选增强（P2）：解包后播放 403 → 以原始壳 URL 重试一次

### 不做
- 不重构 URL 解析管道、不改动降级链（fallback types）
- 不改动 HlsKeyDataSourceFactory / Cronet / OkHttp 数据源链路
- 不处理 HEAD 404（已实测 ExoPlayer HLS 清单请求走 GET，无影响）
- 不做壳响应 content-type 预探测（播放时序引入额外延迟，否决）

## Approach

### Selected Approach

在 `extractPlayerPageUrl` 入口处增加守卫，命中任一则直接返回 `null`（→ `resolvePlayerPageUrl` 返回原 URL）：

- **守卫 A（媒体端点路径）**：外层 URL 去除 query/fragment 并剥离尾斜杠后，路径**最后一段**精确等于 `m3u8`/`mpd`/`mp4`（如 `/media/m3u8`、`/media/m3u8/`、`/hls/mpd`）→ 外层自身即清单/媒体端点，解包必丢签名
- **守卫 B（外层鉴权参数）**：外层 query（排除被捕获的 `url`/`playUrl` 参数值）包含 `token=`/`sign=`/`auth_key=`/`exp=`/`expires=`/`deadline=` 任一 → 外层持有时效签名，解包会丢弃鉴权

落地理由：单点修复覆盖全部 8 个调用路径；守卫 A 精确匹配路径末段避免误伤 `/m3u8player/` 类播放器页；守卫 B 覆盖路径不含媒体段的代理形态（如 `/api/proxy?url=...&sign=...`）；两守卫均为纯字符串判断，无网络开销。

### Alternatives Considered

| 备选方案 | 否决理由 |
|---------|---------|
| 解包后播放 403 时回退原壳 URL（仅重试，不加守卫） | 每次失败多一轮网络往返+超时等待，首帧延迟不可接受；且降级链已存在，重试逻辑与 fallback 交织复杂度高。保留为 P2 增强而非主方案 |
| 壳 URL 先发探测请求看 Content-Type 再决定解包 | 播放主链路增加 1 次 RTT；代理壳对 HEAD 返回 404，探测请求方法难选；时序上阻塞播放准备 |
| 只做守卫 A（路径判断） | 覆盖不全：`/api/proxy?url=...&token=...` 这类路径无媒体段的鉴权代理仍被误解包，通病修不干净 |
| 在 8 个调用点各自加判断 | 逻辑重复 8 份，后续新调用点必然遗漏；单点收敛才是正确抽象 |

### Drawbacks

| 已知缺陷 | 风险 | 接受理由 | 兜底 |
|---------|------|---------|------|
| 守卫 B 可能误伤「HTML 播放页自带 token 参数」的极端形态 | 该场景不解包 → ExoPlayer 拿到 HTML → 3003 → 降级链耗尽后播放失败 | 真实播放器页极少在**外层** query 携带鉴权参数（鉴权通常在内层视频 URL）；概率远低于当前 100% 必失败的代理场景 | P2 增强：403/3003 失败后记录日志（Tag=ProxyUnwrap），为后续按需加「失败回退原壳重试」留数据依据 |
| 守卫 A 用路径末段精确匹配，`/media/m3u8/list` 类多级路径不命中 | 该形态壳仍被解包 | 此形态未见真实样本；守卫 B 可兜住带鉴权参数的变体 | 日志观测，出现样本再扩规则 |
| 内层 URL 与壳 host 不同域但壳不带鉴权参数（无签名代理） | 不在本次修复范围，行为与现状一致 | 无签名代理解包后内层本就可直接访问，不产生 403 | 无需兜底 |

## Requirements

### Requirement: 鉴权代理壳保留
`extractPlayerPageUrl` SHALL 在外层 URL 满足守卫 A 或守卫 B 时返回 `null`，使 `resolvePlayerPageUrl` 原样返回壳 URL。

#### Scenario: 媒体端点路径壳
- **WHEN** URL 为 `https://站点A/media/m3u8?url=<编码内层>&exp=<ts>&token=<sig>`
- **THEN** 返回 `null`，播放器请求壳 URL，壳返回带现签子资源的 m3u8，正常播放

#### Scenario: 无媒体段路径但带鉴权参数壳
- **WHEN** URL 为 `https://站点A/api/proxy?url=<编码内层m3u8>&token=<sig>`
- **THEN** 返回 `null`，壳 URL 原样交给播放器

### Requirement: 真播放器页解包行为不变
非守卫场景的解包行为 SHALL 与现状完全一致（回归保护）。

#### Scenario: 经典播放器页
- **WHEN** URL 为 `https://v.example.com/player/?url=https%3A%2F%2Fsrc.example.com%2Findex.m3u8`
- **THEN** 仍解包返回 `https://src.example.com/index.m3u8`

#### Scenario: playUrl 参数播放器页
- **WHEN** URL 为 `https://v.example.com/player/?playUrl=<编码mp4>`
- **THEN** 仍解包返回内层 mp4 URL

#### Scenario: 非视频 URL 不误判
- **WHEN** URL 为 `https://v.example.com/page?next=https%3A%2F%2Fv.example.com%2Flist.html`
- **THEN** 解码后无视频特征，返回 `null`（现状行为）

### Requirement: 路径末段误判防护
守卫 A SHALL 仅在路径末段**精确等于** `m3u8`/`mpd`/`mp4` 时命中。

#### Scenario: m3u8player 类播放器页不命中守卫 A
- **WHEN** URL 为 `https://v.example.com/m3u8player/?url=<编码内层>`
- **THEN** 守卫 A 不命中；若无守卫 B 参数则照常解包

## Scenarios（边界）

- 空/畸形 URL 输入：守卫判断不抛异常，走原逻辑（try-catch 现状保留）
- 壳 URL 同时含 `?url=` 与 fragment：守卫判断基于 query，fragment 剥离
- `exp` 作为内层 URL 一部分（已编码 `%26exp=`）：不得被守卫 B 误判 —— 守卫 B 只检查**外层 query 中被捕获参数之外**的部分
- 尾斜杠媒体端点（`/media/m3u8/`）：守卫 A 剥离尾斜杠后命中（红队 R2-1 修复项）
- 守卫 A 精确匹配：`/media/m3u8x` 末段非全等 → 不命中（防 `/m3u8player/` 类误伤同理）
- 解码失败：沿用现有 catch 返回 `null` 行为
