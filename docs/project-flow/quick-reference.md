# 快速参考卡

## 构建 & 运行

| 操作 | 命令 |
|------|------|
| 构建 Debug（测试包） | `./gradlew assembleAppDebug` |
| 构建 Release（正式包） | `./gradlew assembleAppRelease` |
| 强制重新打包（修改签名/strings.xml 后必用） | `./gradlew assembleAppRelease --rerun-tasks` |
| 构建共存包（原版包名） | `build-legado.bat debug io.legado.app` |
| 一键脚本打包（自动配置环境） | `build-legado.bat [debug\|release] [package_name]` |
| 一键发布（构建→校验→Release→tag 五阶段） | `publish.bat` 或 `ai_tests\venv\Scripts\python.exe scripts\publish_release.py` |
| 发布预览（全流程模拟，无副作用） | `publish.bat --dry-run` |
| 版本回滚（tag = 版本号） | `git checkout <版本号>` |
| 运行测试 | `./gradlew test` |
| Lint 检查 | `./gradlew lint` |
| 清理构建 | `./gradlew clean` |
| Vue3 开发 | `npm run dev`（modules/web/ 目录下） |
| Vue3 构建 | `npm run build`（modules/web/ 目录下，含 type-check + vite build + sync.js） |
| Vue3 类型检查 | `npm run type-check`（modules/web/ 目录下） |

> **打包完整流程**：环境搭建、签名配置、构建后验证（签名/桌面显示名/安装/启动）详见 [build-apk-guide.md](./build-apk-guide.md)。

> **Gradle 任务名注意**：本项目 productFlavors 仅 `app` 一个，所以任务名是 `assembleAppDebug`/`assembleAppRelease`（App 首字母大写），不是 `assembleDebug`/`assembleRelease`。

## 关键文件速查

| 用途 | 文件路径 |
|------|----------|
| 应用入口 | `app/src/main/java/io/legado/app/App.kt` |
| 主界面 | `app/src/main/java/io/legado/app/ui/main/MainActivity.kt` |
| 规则引擎 | `app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt` |
| 网络书核心 | `app/src/main/java/io/legado/app/model/webBook/WebBook.kt` |
| 阅读核心 | `app/src/main/java/io/legado/app/model/ReadBook.kt` |
| Web 服务 | `app/src/main/java/io/legado/app/api/controller/` |
| 书源实体 | `app/src/main/java/io/legado/app/data/entities/BookSource.kt` |
| 数据库定义 | `app/src/main/java/io/legado/app/data/AppDatabase.kt` |
| ProGuard | `app/proguard-rules.pro` |
| 依赖版本 | `gradle/libs.versions.toml` |
| 2026-08 新增类入口 | 主界面顶栏 `ui/widget/MainTopBarView.kt` · 视频画质增强 `help/exoplayer/ImageEnhanceEffects.kt` + `ui/video/ImageEnhanceController.kt` · 图片画布 `ui/image/ImageCanvasViewModel.kt` · Exo 视频管理 `help/gsyVideo/ExoVideoManager.kt`（路径前缀 `app/src/main/java/io/legado/app/`） |

## 版本锁定依赖

| 依赖 | 版本 | 原因 |
|------|------|------|
| jsoup | 1.16.2 | 新版有破坏性变更（jsoup#2017） |
| rhino | 1.8.1 | API 33 以下不可用的 VarHandle（desugaring 不覆盖） |
| hutool | 5.8.22 | 书源加解密依赖，不可升级 |
| commons-text | 1.13.1 | API 24 以下不可用的 Arrays.setAll（desugaring 不覆盖） |
| protobuf | 4.26.1 | 兼容性锁定 |

## 数据库

| 项目 | 值 |
|------|------|
| 名称 | legado.db |
| 版本 | 108（以 AppDatabase.kt version 字段为准） |
| ORM | Room |
| 实体数 | 56 实体 + 1 视图（BookSourcePart） |
| DAO 数 | 43 |
| Schema | app/schemas/ |

## Web API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /getBookshelf | 获取书架 |
| GET | /getBookSources | 获取全部书源 |
| GET | /getChapterList | 获取章节列表 |
| GET | /getBookContent | 获取正文内容 |
| POST | /saveBookSource | 保存书源 |
| POST | /saveBookSources | 批量保存书源 |
| POST | /deleteBookSources | 删除书源 |
| POST | /saveBook | 保存书籍 |
| POST | /saveRssSource | 保存 RSS 源 |

## 新增配置参数（2026-07）

> 本轮网络性能与稳定性优化 + 延伸版本功能借鉴新增的配置参数。Spec：[specs/network-perf-stability/](../specs/network-perf-stability/)。

### 网络层配置（LRU 缓存上限）

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| OkHttp 连接池空闲连接数 | Int | 50 | 原 5，扩容提升高并发连接复用率 |
| 代理客户端 LRU 上限 | Int | 20 | 代理 OkHttpClient 缓存上限 |
| DNS IP 缓存 LRU 上限 | Int | 100 | 自定义 DNS 解析缓存上限 |
| failUrl LruCache 上限 | Int | 200 | 图片加载失败 URL 缓存上限 |
| stringRuleCache LruCache 上限 | Int | 64 | 规则解析缓存上限 |

### 调试日志悬浮球（F-P1-3）

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| debugLogFloatingBall | Boolean | false | 是否显示调试日志悬浮球 |
| AppLog 日志级别 | Enum | VERBOSE | 日志级别过滤（VERBOSE/DEBUG/INFO/WARN/ERROR） |

### 自动任务系统（F-P1-1）

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| 自动任务列表 | List | [] | cron 表达式 + JS 脚本的任务列表 |
| 单任务启用状态 | Boolean | false | 单个任务的启用 / 禁用 |

### 文件夹视图

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| 书源文件夹视图 | Boolean | false | 书源列表是否显示为文件夹视图 |
| 订阅源文件夹视图 | Boolean | false | 订阅源列表是否显示为文件夹视图 |
| Explore 文件夹视图 | Boolean | false | 发现页是否显示为文件夹视图 |
| Rss 文件夹视图 | Boolean | false | RSS 页是否显示为文件夹视图 |

### 高亮规则系统（F-P1-2）

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| 高亮规则列表 | List | [] | 9 通道高亮规则配置 |
| 高亮规则分组 | List | [] | 规则分组管理（启用 / 禁用整组） |

### 备份选择器（F-P0-2）

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| BackupSelectorConfig | Object | 全选 | 备份时各数据类型的勾选状态 |

## 新增 Web API 端点（2026-07）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET / POST | /backup/select | 获取 / 设置备份选择器配置 |
| POST | /backup/export | 按选择配置导出备份 |
| POST | /backup/import | 导入备份 |

## 新增调试 Activity（F-P0-1）

| Activity | 功能 |
|----------|------|
| 编码转换 | Base64 / URL / Unicode / Hex 互转 |
| HTTP 请求 | 自定义 URL / Header / Body 发起请求 |
| curl 转换 | curl 命令解析与转换 |
| ping 工具 | 网络连通性检测 |
| 正则测试 | 正则表达式匹配测试 |
| 时间戳转换 | Unix 时间戳与日期互转 |
