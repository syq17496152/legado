# tasks.md — video-proxy-m3u8-403

## 1. 准备工作

- [x] 1.1 重读 `VideoUrlExtractor.kt` 现状实现，确认 8 个调用点签名与 spec 一致（验证标准：Grep 调用点清单与 design.md File Changes 表吻合）
  - Action: Read 现状实现 + Grep 全仓 `resolvePlayerPageUrl|extractPlayerPageUrl`
  - Observation: 调用点 8 处（VideoPlay×5、VideoPlaybackPipeline×2、VideoUrlExtractor 内部×1），与 design 一致
  - Adapt: 无需调整
- [x] 1.2 确认 `VideoUrlExtractor` 是否可 JVM 单测（验证标准：结论写入本文件备注）
  - Action: Read object 属性初始化区 + 参考 `help/video/engine/EngineTest.kt` 先例
  - Observation: 初始化仅 ConcurrentHashMap/Regex/CoroutineScope，JVM 安全；AppLog 有 gradle returnDefaultValues 兜底
  - Adapt: 结论=可 JVM 单测

## 2. 核心实现

- [x] 2.1 (L1) 实现 `extractPlayerPageUrl` 守卫 A：剥离 query/fragment 及尾斜杠后，路径末段与 m3u8/mpd/mp4 全等判断（验证：单测 4 用例 + 真机壳 URL 未被解包日志 urlPath=path=/media/m3u8）
- [x] 2.2 (L1) 实现守卫 B：移除 url/playUrl 捕获片段后对外层 query 匹配鉴权参数正则（验证：单测含「内层编码 %26exp%3D 不误判」反例）
- [x] 2.3 (L1) KDoc 注释同步：守卫场景与 3003 修复场景区分依据（验证：Read 复核）
- [x] 2.5 (L1) [迭代回流 AD-04] 新增 `TlsFallbackDataSourceFactory`（Cronet TLS 失败自动回退 OkHttp），接入 `ExoPlayerHelper.cacheDataSourceFactory` upstreamFactory（验证：真机播放成功，回退日志与主路径分流正确）
- [ ] 2.4 （P2 可选，默认不做）403 失败回退壳 URL 重试 —— 守卫已前置化解主场景，暂无误伤数据，不实施

## 3. 验证测试

- [x] 3.1 (L2) JVM 单测：VideoUrlExtractorTest 15 用例 + TlsFallbackDataSourceFactoryTest 7 用例全绿（`testAppDebugUnitTest` 339 例中仅 3 个失败，均为 RhinoClassShutterTest 环境性失败：AppConfig 类 JVM 初始化缺 Android 上下文 + 限流采样断言，与本改动无关，diff 不触碰该链路）
- [x] 3.2 (L1) updateLog 已更新（第三十批两条，编译前完成）
- [x] 3.3 (L1) 编译测试包：`legado_miss_app_3.26.091211.apk` BUILD SUCCESSFUL
- [x] 3.4 (L2) 真机验证：安装测试包 → 直拉播放器播放该链接 → **first frame rendered latency=3094ms + state READY + 30 秒零错误**；AppLog 确认播放全程使用壳 URL（urlPath=path=/media/m3u8，未解包）；TlsFallback 对站点B 分片请求自动生效
- [x] 3.5 (L2) 回归验证：①成功播放中壳域名清单走 Cronet 主路径（无回退日志）→ 主链路无回归；②mux.dev 直连超时（环境性）中 TlsFallback 对非 TLS 错误正确不回退（原样抛出）；③l2_verify_video_player.py 场景5 向后兼容 + 场景8 错误模式（8 项修复点 0 错误、0 FATAL）通过；Feed 依赖场景（1/2/3/4/6/7）因单链接模式无文章流未触发（测试环境限制，非回归）
- [ ] 3.6 `ai_tests/run_e2e.py --tc all` 全量用例（⏳ 验收时用户裁决留待补跑：需 memuc 管理员提权包装，参照 builtin-replace-id-fix 先例；本改动触达面为视频播放链路，已由 3.4/3.5 定向验证覆盖）

## 4. 文档收尾

- [x] 4.1 文档同步：issues-found.md（4 项发现）、INDEX.md、设计文档 AD-04
- [x] 4.2 敏感词扫描：Grep 变更文件无真实域名/token/cookie，统一用 站点A/站点B 代号（命中 0）
- [x] 4.3 Grep `android.util.Log.d|e`：变更文件命中 0，无临时调试日志残留
- [x] 4.4 验收检查点：2026-09-12 用户确认验收通过，归档至 `docs/specs/archive/2026-09-12-video-proxy-m3u8-403/`
- [x] 4.5 检查点 1 确认后在 `docs/INDEX.md` 登记本 spec 为「进行中」

## 备注

- 1.2 结论：可 JVM 单测（object 初始化无 Android 依赖，AppLog 走 returnDefaultValues 兜底）
- 真机问题记录：见 issues-found.md（4 项：Cronet TLS 拒绝已修复 AD-04；MEmu DNS 失效已绕过 bind mount；Gradle kapt 挂死新陷阱；adb shell & 截断测试陷阱）

### AOAdapt 日志

- 3.1 执行遇阻：`testAppDebugUnitTest` 首跑命中 `journal-1.lock` 拒绝访问 → daemon-stop 清场（bat 末尾 pause 卡后台任务，改直连 gradlew）；清场后冷启动重编遇 daemon 在 kaptAppDebugUnitTestKotlin 挂死（CPU 增量≈0 + 日志 48 分钟无输出）
  - Action: 强杀 java + `gradlew --stop` + `--console=plain` 直连重跑
  - Observation: 5 分钟通过（编译产物已缓存）；真机播放发现第二层故障 Cronet TLS 被站点B 拒（ERR_SSL_VERSION_OR_CIPHER_MISMATCH）
  - Adapt: 迭代回流追加 AD-04 TLS 回退数据源 → 修复后播放成功；确立「Gradle 卡死判据 = CPU 两次采样增量≈0 且 daemon 日志停更」SOP
- 3.4 排查插曲：直拉播放器 400 —— 实为 adb shell 远端把 URL 在 `&` 处截断（测试方法陷阱，非代码问题），整体引号转义后消除
