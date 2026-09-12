# issues-found.md — compose-shell-binding-fix 真机验证记录

> 2026-09-12 真机（MEmu 127.0.0.1:21503，测试包 io.legado.miss.app.debug 3.26.091212debug）

## 验证结论

| 场景 | 结果 |
|------|------|
| 场景一：RssArticleInfoActivity（原必崩页）am start 直启 onCreate | ✅ PASS——进程存活、无 FATAL/StackOverflow（崩溃点 setContentView(binding.root) 已越过） |
| 场景二：AiImageGalleryActivity + AiImageProviderEditActivity（潜伏雷页） | ✅ PASS——两页均正常创建，无崩溃 |
| 场景三：自由布局 style=5 滚动日志占比 | ⚠️ **无法执行**——见下"新发现问题 1"，自由布局在模拟器环境白屏，LM 未挂载，布局日志无从产生。节流代码已确认编入 APK（classes8.dex 含 `putThrottledLog`/`THROTTLE_WINDOW_MS`），逻辑为纯时间窗计数（主线程、无并发），L3 占比验证待白屏修复后补测 |
| Grep 审计（合入卡点） | ✅ PASS——全仓 `object : ViewBinding` 匿名对象剩余 6 处（工厂本身 + 5 处安全写法），3 雷区已迁移工厂，无类级 `root` 危险写法残留；SimpleViewBinding 命名类安全（rootView ≠ root） |
| 全量 E2E `run_e2e.py --tc all` | ⏳ 待补——memuc 需管理员提权（沿用 output/run_e2e_admin.bat 模式），本会话未跑 |

## 新发现问题（均为既有问题，非本次改动回归）

### 1. 自由布局 style=5 "空白"新复现路径（P1，建议立项）
- **现象**：style=5 纯启动（无图片比例缓存）白屏，连 `RssFree[数据] setItems` 都不输出（数据 collect 被首屏门控阻塞）。
- **A/B 铁证**：改动前构建 091200（与用户今早测试包同代码）在同模拟器同 DB 下**同样白屏零日志** → 非本次回归。
- **机理推断**：`RssArticlesFragment.gateFirstScreen` 首屏尺寸预热依赖网络拉取图片解析比例，无超时兜底；预热不完成则 collect 阻塞、setItems 永不执行。用户真机正常是因为 20 天比例缓存（CacheManager）+ 网络可达。
- **修复方向**：门控加超时（如 3s 后放行 setItems）+ 预热失败降级（比例用占位值渲染）。
- **测试环境附带坑**：验证过程中 push DB 需同步 `chown` 到当前 app uid（卸装重装后 uid 会变），否则全 DB SQLITE_CANTOPEN 白屏——已记测试基建陷阱。

### 2. 视频缓存 MediaMuxer native SIGABRT（P1，已留立项 `hls-download-muxer-overflow`）
- `HlsDownloader.kt:400` MediaMuxer(MPEG_4) 长时间写 MP4（~68min）触发系统 MPEG4Writer ubsan mul-overflow，logcat 两次 tombstone（09-10/09-11 各一次，进程 io.legado.miss.app.debug）。

### 3. 视频嗅探 5s 超时失效（P2，已留立项 `sniff-timeout-ineffective`）
- `ExoPlayerHelper` `SNIFF_TIMEOUT_MS=5000` 被 `videoStreamClient.execute()` 阻塞调用架空，实际超时 = OkHttp callTimeout 60s（日志实测 60006ms）。慢源点播卡 1 分钟才走后缀兜底。

### 4. 测试基建陷阱（本轮新增，已交 AI 测试 SOP 参考）
- `adb logcat` 输出混有非 UTF-8 字节，Python `subprocess(text=True)` 读线程会 UnicodeDecodeError 崩死、stdout 静默变空——必须 `errors="replace"`。
- MEmu `logcat -c` 可能静默不清空，统计前应以时间戳过滤而非依赖 -c。
- UI 自动化点「切换布局」循环切 style 不可靠（点击未生效也无报错），改 DB `articleStyle` 直改法更确定，但 push 后必须 `chown $(当前uid)`。
