# 开发任务导航（按任务类型索引）

> **使用方法**：接手任务时，先定位任务类型，再读取对应 Wiki 文档和代码锚点。
> **来源**：从 AGENTS.md 外移，主规范仅保留链接。

---

## 新增/修改书源规则

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 理解规则引擎架构 | [architecture/rule-engine.md](./architecture/rule-engine.md) | - |
| 规则前缀解析逻辑 | `model/analyzeRule/AnalyzeRule.kt` | L601-631 |
| 规则分割(&&/\|\|/%%) | `model/analyzeRule/RuleAnalyzer.kt` | L165-237 |
| CSS选择器解析 | `model/analyzeRule/AnalyzeByJSoup.kt` | L72-123 |
| JSONPath解析 | `model/analyzeRule/AnalyzeByJSonPath.kt` | L31-71 |
| XPath解析 | `model/analyzeRule/AnalyzeByXPath.kt` | L52-133 |
| JS执行(Rhino) | `modules/rhino/.../RhinoScriptEngine.kt` | L88-125 |
| 书源实体/规则字段 | `data/entities/BookSource.kt` | L32-98 |
| 搜索规则定义 | `data/entities/rule/SearchRule.kt` | L12-25 |
| 正文规则定义 | `data/entities/rule/ContentRule.kt` | L12-24 |

## 新增/修改搜索功能

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 搜索并发调度 | `model/webBook/SearchModel.kt` | L52-114 |
| 搜索结果聚合去重 | `model/webBook/SearchModel.kt` | L116-197 |
| 单书源搜索 | `model/webBook/WebBook.kt` | L49-107 |
| 搜索结果解析 | `model/webBook/BookList.kt` | L35-151 |
| 搜索界面 | `ui/book/search/SearchActivity.kt` | L66-70 |

## 新增/修改阅读功能

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 阅读核心(文字) | `model/ReadBook.kt` | L61, L534-596 |
| 翻页/跳章 | `model/ReadBook.kt` | L304-419 |
| 翻页跳转/章节切换/内容加载状态 | `model/ReadBook.kt` | L693-789（skipToPage L693 / openChapter L726 / contentLoadFinish L789） |
| 阅读界面 | `ui/book/read/ReadBookActivity.kt` | L151-166 |
| 漫画阅读 | `model/ReadManga.kt` | L45, L154-240 |
| 音频播放 | `model/AudioPlay.kt` | L38, L171-222 |

## 新增/修改 Web API

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 路由定义 | `web/HttpServer.kt` | L25-146 |
| 书籍控制器 | `api/controller/BookController.kt` | L36 |
| 书源控制器 | `api/controller/BookSourceController.kt` | L13 |
| Vue3前端 | `modules/web/src/` | - |

## 新增/修改数据库

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 数据库定义(v108，以 AppDatabase.kt version 字段为准) | `data/AppDatabase.kt` | L69-149 |
| AutoMigration列表 | `data/AppDatabase.kt` | L78-125 |
| Book实体 | `data/entities/Book.kt` | L34-38 |
| BookChapter实体 | `data/entities/BookChapter.kt` | L30-42 |
| BookDao | `data/dao/BookDao.kt` | L18-19 |
| BookSourceDao | `data/dao/BookSourceDao.kt` | L20-21 |

## 新增/修改本地书籍解析

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 本地书入口(类型分发) | `model/localBook/LocalBook.kt` | L69, L120-213 |
| TXT解析(编码+目录规则) | `model/localBook/TextFile.kt` | L26, L89-154 |
| EPUB解析 | `model/localBook/EpubFile.kt` | L35, L125-359 |

## 新增/修改 UI 界面

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| UI 层架构总览 | [architecture/android-ui-core.md](./architecture/android-ui-core.md)（四册体系，页面/媒体主题/统计分见 android-ui-pages/media-theme/changelog） | - |
| UI 重构设计规范 | [specs/ui-redesign-m3/](../specs/ui-redesign-m3/) | - |
| 阅读器浮层 Compose 化（S5） | [specs/reader-overlay-compose/](../specs/reader-overlay-compose/) | - |
| 主界面/MainActivity | `ui/main/MainActivity.kt` | 核心逻辑 L2100-2490（底部导航配置 L2102 / ViewPager 适配器 L2484，全文 2548 行） |
| 阅读界面 | `ui/book/read/ReadBookActivity.kt` | L151-166 |
| 搜索界面 | `ui/book/search/SearchActivity.kt` | L66-70 |
| 书源编辑界面 | `ui/book/source/edit/BookSourceEditActivity.kt` | - |
| 自定义 Widget 体系 | `ui/widget/` 目录 | - |

## 新增/修改网络请求

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 网络层架构 | [architecture/network-layer.md](./architecture/network-layer.md) | - |
| OkHttp 构建+拦截器 | `help/http/HttpHelper.kt` | L51-127 |
| SSL 证书处理 | `help/http/SSLHelper.kt` | L20-194 |
| Cookie 管理 | `help/http/CookieManager.kt` | - |
| Cronet 加速 | `help/http/Cronet.kt` | - |
| WebView 后台请求 | `help/http/BackstageWebView.kt` | - |

## 新增/修改配置项

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 配置系统架构 | [modules/config-system.md](./modules/config-system.md) | - |
| 全局配置 | `help/config/AppConfig.kt` | L29-825 |
| 阅读排版配置 | `help/config/ReadBookConfig.kt` | L40-100 |
| 主题配置 | `help/config/ThemeConfig.kt` | L48-79 |
| 书源评分 | `help/config/SourceConfig.kt` | L7-45 |
| 本地状态 | `help/config/LocalConfig.kt` | - |

## 新增/修改后台 Service

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| Service 层架构 | [modules/android-services.md](./modules/android-services.md) | - |
| 朗读服务基类 | `service/BaseReadAloudService.kt` | L72-783 |
| TTS 朗读 | `service/TTSReadAloudService.kt` | - |
| TTS 选角模板（数据层） | `help/readaloud/casting/TtsCastingStore.kt` `TtsCastingModel.kt` `TtsTagSplitter.kt` `TtsVoiceSource.kt` | - |
| TTS 选角管理页/编辑器（UI） | `ui/book/read/config/casting/TtsCastingManageFragment.kt` `TtsCastingEditorScreen.kt` `TtsCastingEditorWidgets.kt` | - |
| TTS 模板选择器/书级覆盖 | `ui/book/read/config/TtsCastingPickerDialog.kt` | - |
| TTS 试听控制器 | `ui/book/read/config/casting/TtsVoicePreviewController.kt` | - |
| TTS 批量预合成 | `help/readaloud/prebuild/TtsPrebuildManager.kt` `TtsSynthesizer.kt` + `service/TtsPrebuildService.kt` + `ui/book/read/config/TtsPrebuildDialog.kt` | - |
| TTS 缓存键单源 | `help/readaloud/prebuild/TtsCacheKeys.kt` | - |
| HTTP 朗读 | `service/HttpReadAloudService.kt` | - |
| 音频播放 | `service/AudioPlayService.kt` | - |
| 书籍缓存 | `service/CacheBookService.kt` | - |
| 书籍导出 | `service/ExportBookService.kt` | - |
| 书源检验 | `service/CheckSourceService.kt` | - |

## 新增/修改备份恢复

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 备份恢复系统 | [modules/backup-restore.md](./modules/backup-restore.md) | - |
| 备份核心 | `help/storage/Backup.kt` | L54-315 |
| 恢复核心 | `help/storage/Restore.kt` | L65-80 |
| AES 加密 | `help/storage/BackupAES.kt` | - |
| WebDAV 同步 | `help/AppWebDav.kt` | - |

## 新增/修改工具类/协程/加密

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 工具与辅助层总览 | [modules/tools-infrastructure.md](./modules/tools-infrastructure.md) | - |
| 编码检测 | `utils/EncodingDetect.kt` | L18-50 |
| 简繁转换 | `utils/ChineseUtils.kt` | L6-49 |
| Canvas录制(翻页) | `utils/canvasrecorder/CanvasRecorder.kt` | L5-27 |
| 对象池 | `utils/objectpool/ObjectPool.kt` | L3-11 |
| 压缩工具 | `utils/compress/ZipUtils.kt` | L26-40 |
| 链式协程 | `help/coroutine/Coroutine.kt` | L26-252 |
| 对称加密 | `help/crypto/SymmetricCryptoAndroid.kt` | L13-40 |
| 媒体按键接收器 | `receiver/MediaButtonReceiver.kt` | L27-121 |
| 网络变化监听 | `receiver/NetworkChangedListener.kt` | L17-77 |

## 新增/修改自定义库（MOBI/WebDAV/主题）

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 自定义库层总览 | [modules/custom-libraries.md](./modules/custom-libraries.md) | - |
| MOBI解析入口 | `lib/mobi/MobiReader.kt` | L16-50 |
| MOBI基类(解压+索引) | `lib/mobi/MobiBook.kt` | L33-99 |
| KF8章节处理 | `lib/mobi/KF8Book.kt` | L21-272 |
| KF6章节处理 | `lib/mobi/KF6Book.kt` | L1-140 |
| PDB文件容器 | `lib/mobi/PDBFile.kt` | L1-48 |
| WebDAV客户端 | `lib/webdav/WebDav.kt` | L41-456 |
| 主题存储 | `lib/theme/ThemeStore.kt` | L20-352 |
| 视图着色引擎 | `lib/theme/TintHelper.kt` | L36-487 |

## 修复 Bug

1. 定位 Bug 所属模块 → 参考上方任务导航表
2. 读取对应 Wiki 文档了解设计思想
3. 查看代码锚点定位核心逻辑
4. 修改后运行 `./gradlew test` 验证

## 异常处理相关

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 异常体系架构 | [modules/exception-system.md](./modules/exception-system.md) | - |
| NoStackTraceException 基类 | app/src/main/java/io/legado/app/exception/NoStackTraceException.kt | - |
| ContentEmptyException | app/src/main/java/io/legado/app/exception/ContentEmptyException.kt | - |
| ConcurrentException | app/src/main/java/io/legado/app/exception/ConcurrentException.kt | - |
| EmptyFileException | app/src/main/java/io/legado/app/exception/EmptyFileException.kt | - |
| RegexTimeoutException | app/src/main/java/io/legado/app/exception/RegexTimeoutException.kt | - |
| TocEmptyException | app/src/main/java/io/legado/app/exception/TocEmptyException.kt | - |
| 异常使用场景 | [modules/exception-system.md](./modules/exception-system.md) | 4 异常使用场景分析 |

## 常量/配置键相关

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 常量系统架构 | [modules/constant-system.md](./modules/constant-system.md) | - |
| AppLog 日志缓冲 | constant/AppLog.kt | - |
| AppConst 应用常量 | constant/AppConst.kt | - |
| BookType 位标志 | constant/BookType.kt | - |
| BookSourceType 书源内容类型 | constant/BookSourceType.kt | - |
| SourceType 源类型 | constant/SourceType.kt | - |
| PreferKey 偏好键常量 | constant/PreferKey.kt | - |
| PageAnim 翻页动画类型 | constant/PageAnim.kt | - |
| NotificationId 通知ID | constant/NotificationId.kt | - |
| IntentAction Intent动作 | constant/IntentAction.kt | - |
| Status 播放状态 | constant/Status.kt | - |
| EventBus 事件总线 | constant/EventBus.kt | - |
| AppPattern 预编译正则 | constant/AppPattern.kt | - |
| Theme 主题枚举 | constant/Theme.kt | - |

## 图片/视频/WebView相关

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 三模块架构总览 | [modules/glide-video-webview.md](./modules/glide-video-webview.md) | - |
| Glide ModelLoader+Fetcher | help/glide/MediaService.kt | - |
| OkHttpStreamFetcher 图片加载 | help/glide/OkHttpStreamFetcher.kt | - |
| ImageLoader 统一入口 | help/glide/ImageLoader.kt | - |
| VideoPlayer 主播放器 | ui/video/VideoPlayer.kt | - |
| ExoPlayerHelper ExoPlayer 工具（嗅探+MediaItem 创建） | help/exoplayer/ExoPlayerHelper.kt | exoplayer-resilience |
| MimeSniffer magic number 检测（MP4/M3U8/FLV/TS/MKV/MPD） | help/exoplayer/MimeSniffer.kt | exoplayer-resilience |
| MimeSnifferCache 嗅探结果 LRU 缓存（100 容量+1h TTL） | help/exoplayer/MimeSnifferCache.kt | exoplayer-resilience |
| Exo2MediaPlayer ExoPlayer 实现（协程嗅探+不可恢复错误累计+WebView 降级） | help/gsyVideo/Exo2MediaPlayer.kt | exoplayer-resilience |
| ImageGalleryActivity 图片浏览器 | ui/image/ImageGalleryActivity.kt | - |
| ImagePlay 状态单例（跨文章切换） | ui/image/ImagePlay.kt | - |
| ImageGalleryViewModel 图片加载 | ui/image/ImageGalleryViewModel.kt | - |
| ImageArticlePagerAdapter 外层适配器 | ui/image/ImageArticlePagerAdapter.kt | - |
| ImagePageAdapter 内层适配器（旋转/缩放） | ui/image/ImagePageAdapter.kt | - |
| FloatingPlayer 浮窗播放 | ui/video/FloatingPlayer.kt | - |
| 弹幕系统 | ui/video/DanmakuAdapter.kt | - |
| WebViewPool 对象池（三 scope 分层 GLOBAL/DISCOVERY/RSS；pauseTimers/resumeTimers 进程级 API 全局互斥判断） | help/webView/WebViewPool.kt | - |
| WebJsExtensions JS桥接 | help/http/WebJsExtensions.kt | - |

## 2026-08 新增模块

| 模块 | 读取文件 | 一句话职责 |
|------|----------|-----------|
| 视频画质增强 | `help/exoplayer/ImageEnhanceEffects.kt` + `ui/video/ImageEnhanceController.kt` | ExoPlayer 视频画质增强效果引擎 + UI 开关控制器 |
| 图片画布 | `ui/image/ImageCanvasViewModel.kt` | 图片画布交互的状态管理（ViewModel） |
| 主界面顶栏 | `ui/widget/MainTopBarView.kt` | 主界面顶栏自定义 View（866 行），TOP_BAR_CHANGED 事件驱动重载（EventBus.kt L72） |
| Exo 视频管理 | `help/gsyVideo/ExoVideoManager.kt` | GSY 播放器的 ExoPlayer 内核管理封装 |
| RSS 双模式 | `ui/main/rss/RssFragment.kt` | RSS 页 modern/classic 双模式切换（AppConfig.modernRssPage，默认 modern） |

## HTTP请求/Cookie/WebView辅助

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| HTTP辅助层架构 | [modules/http-helper-layer.md](./modules/http-helper-layer.md) | - |
| okHttpClient 拦截器链 | help/http/HttpHelper.kt | L51-127 |
| CookieManager 会话分层 | help/http/CookieManager.kt | - |
| BackstageWebView 双模式 | help/http/BackstageWebView.kt | - |
| SSLHelper 信任策略 | help/http/SSLHelper.kt | L20-194 |
| DecompressInterceptor 解压 | help/http/DecompressInterceptor.kt | - |
| Cronet 加速引擎 | help/http/Cronet.kt | - |
| OkHttpUtils 请求工具 | help/http/OkHttpUtils.kt | - |

## 应用更新

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 更新系统架构 | [modules/update-system.md](./modules/update-system.md) | - |
| AppUpdate 门面 | app/src/main/java/io/legado/app/help/update/AppUpdate.kt | - |
| AppUpdateGitHub 实现 | app/src/main/java/io/legado/app/help/update/AppUpdateGitHub.kt | - |
| AppUpdateGitee 实现 | app/src/main/java/io/legado/app/help/update/AppUpdateGitee.kt | - |
| AppReleaseInfo 数据结构 | app/src/main/java/io/legado/app/help/update/AppReleaseInfo.kt | - |
| AppVariant 变体枚举 | app/src/main/java/io/legado/app/help/update/AppReleaseInfo.kt（enum class AppVariant，无独立文件） | - |

## 源验证/源扩展

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 源辅助与扩展架构 | [modules/source-management.md](./modules/source-management.md)（§7/§11/§12 源扩展体系） | - |
| SourceHelp 门面 | model/source/SourceHelp.kt | - |
| BookSourceExtensions 扩展 | model/source/BookSourceExtensions.kt | - |
| RssSourceExtensions 扩展 | model/source/RssSourceExtensions.kt | - |
| exploreKinds 三级缓存 | model/source/BookSourceExtensions.kt | - |
| sortUrls 排序地址 | model/source/RssSourceExtensions.kt | - |
| SourceVerificationHelp 校验 | model/source/SourceVerificationHelp.kt | - |
| BaseSourceExtensions 类型判断 | model/source/BaseSourceExtensions.kt | - |

## Rhino 脚本引擎

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| Rhino 模块架构 | [modules/rhino-module.md](./modules/rhino-module.md) | - |
| 引擎单例+JSR-223入口 | `modules/rhino/.../RhinoScriptEngine.kt` | L88-125 |
| 沙箱类名白名单 | `modules/rhino/.../RhinoClassShutter.kt` | - |
| Java→JS类型桥接+安全过滤 | `modules/rhino/.../RhinoWrapFactory.kt` | - |
| 协程桥接(suspendContinuation) | `modules/rhino/.../RhinoExtensions.kt` | - |
| 递归保护+指令计数中断 | `modules/rhino/.../RhinoContext.kt` | - |
| 顶层作用域(bindings/scope/sync) | `modules/rhino/.../RhinoTopLevel.kt` | - |
| 受保护Java类包装 | `modules/rhino/.../ProtectedNativeJavaClass.kt` | - |

## 自定义控件体系

| 步骤 | 读取文件 | 行号 |
|------|----------|------|
| 控件体系架构 | [modules/widget-system.md](./modules/widget-system.md) | - |
| 控件继承体系总览 | `ui/widget/` 目录 | - |
| 阅读界面状态栏+进度条 | `ui/widget/ReaderInfoBarView.kt` | - |
| 电池控件 | `ui/widget/BatteryView.kt` | - |
| 竖向滑动条 | `ui/widget/VerticalSeekBar.kt` | - |
| 精细滑动条 | `ui/widget/DetailSeekBar.kt` | - |
| 主题感知机制 | `ui/widget/` + `lib/theme/ThemeStore.kt` | - |
