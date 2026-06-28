# Legado（阅读Sigma）

[English](English.md) | 中文

<div align="center">

<img width="100" height="100" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>

**Android 开源电子书阅读器**

自定义书源规则引擎 | CSS / JSONPath / XPath / 正则 / JS 五种解析

继承自 [gedoor/legado](https://github.com/gedoor/legado)，在原版基础上扩展更多功能

</div>

---

## 主要功能

- **自定义书源** — 自己设置规则抓取网页数据，规则简单易懂，软件内置规则说明
- **书架管理** — 列表书架、网格书架自由切换
- **搜索与发现** — 书源规则支持搜索及发现，找书看书功能全部自定义
- **订阅源** — 订阅想看的任何内容，RSS/网页均可
- **替换净化** — 去除广告、替换内容，一键净化
- **本地阅读** — 支持 TXT、EPUB，手动浏览 + 智能扫描
- **深度自定义** — 字体、颜色、背景、行距、段距、加粗、简繁转换等
- **多种翻页** — 覆盖、仿真、滑动、滚动等翻页模式
- **在线朗读** — TTS 语音朗读，支持自定义朗读引擎
- **Web 管理** — 内置 Web 服务，浏览器管理书架和书源
- **开源无广告** — 持续优化，完全无广告

---

## 版本说明

| 版本 | 包名 | 说明 |
|------|------|------|
| **测试版 (beta)** | 与原版相同 | 可覆盖更新，版本更新频繁 |
| **正式版 (plus)** | 新的共存包名 | 不会覆盖原版，每到一个稳定阶段更新一次 |

---

## 项目结构

```
legado/
├── app/                    # Android 主应用模块
│   └── src/main/java/     # Kotlin 业务源码 (io.legado.app.*)
├── modules/
│   ├── book/              # EPUB/UMD 书籍解析模块
│   ├── rhino/             # Rhino JS 引擎模块
│   └── web/               # Vue3 Web 管理前端
├── docs/
│   ├── project-rules/      # 编码规范（7个文件）
│   ├── project-flow/       # 项目流程与架构
│   │   ├── architecture/   # 架构文档（13个文件）
│   │   ├── modules/        # 模块文档（19个文件）
│   │   └── database/       # 数据库文档（3个文件）
│   └── specs/              # 功能设计文档（OpenSpec）
├── .trae/skills/          # AI Skill 工具链（书源创建/审查/审计）
├── .github/               # CI/CD 工作流
└── AGENTS.md              # AI Agent 主规范
```

---

## AI Skill 工具链

本项目集成三个 AI Skill，形成「审查 → 创建 → 审计」完整闭环：

| Skill | 能力 |
|-------|------|
| **legado-source-creator** | 书源/订阅源智能创建器，79 条陷阱检查 + 5 阶段闭环工作流 + JVM 仿真器 |
| **legado-skill-auditor** | Skill 质量审查器，8 维度 42 检查点深度审查 |
| **legado-workflow-auditor** | 任务执行证据审计器，8 项检查输出审计报告 |

详见 [AGENTS.md](AGENTS.md) 和 [.trae/skills/](./.trae/skills/)

---

## 项目文档

> 统一入口：[docs/INDEX.md](docs/INDEX.md)

### 编码规范 — `docs/project-rules/`

AI Agent 编码时必须遵循的项目特有规则：

| 文档 | 内容 |
|------|------|
| [命名规范](docs/project-rules/naming_rules.md) | 类后缀约定、up/dur/Await 缩写、常量风格、包结构 |
| [代码风格](docs/project-rules/checkstyle_rules.md) | Coroutine 链式封装、双版本模式、kotlin.runCatching、object 单例 |
| [异常处理](docs/project-rules/exception_rules.md) | NoStackTraceException 体系、四种捕获模式、网络错误链 |
| [日志规范](docs/project-rules/logging_rules.md) | AppLog + LogUtils + DebugLog 三层体系、标签约定 |
| [架构模式](docs/project-rules/architecture_rules.md) | 手动 DI、ViewModel 模式、Room 配置、Web 服务、模块依赖 |
| [测试规范](docs/project-rules/testing_rules.md) | JUnit4、书源自测三阶段、测试运行命令 |
| [OpenSpec 工作流](docs/project-rules/openspec-workflow.md) | 四文档强制生成、8 步 + 3 检查点流程 |

### 架构文档 — `docs/project-flow/architecture/`

| 文档 | 内容 |
|------|------|
| [架构总览](docs/project-flow/architecture/overview.md) | 四层架构 + 数据流 + 设计模式 |
| [规则引擎](docs/project-flow/architecture/rule-engine.md) | SourceRule 状态机、五种解析、JS 环境 |
| [规则引擎算法](docs/project-flow/architecture/rule-engine-algorithms.md) | SourceRule 完整规范、RuleAnalyzer 算法、Mode 枚举 |
| [JS 环境绑定](docs/project-flow/architecture/rule-engine-js-env.md) | AnalyRule/AnalyzeUrl 绑定、ajax 跨域、@put/@get 变量 |
| [Skill 架构](docs/project-flow/architecture/skill-architecture.md) | 金字塔架构、5 阶段工作流、JVM 仿真器 |
| [AI 方法论](docs/project-flow/architecture/multi-agent-analysis-spec.md) | 五阶段流水线、单代理≤12 文件、交叉验证 |
| [Android UI](docs/project-flow/architecture/android-ui.md) | MainActivity 导航、ReadBookActivity 继承链 |
| [API 数据流](docs/project-flow/architecture/api-dataflow.md) | HTTP/WebSocket/Beacon 完整链路 |
| [App 初始化](docs/project-flow/architecture/app-init.md) | 50 步启动流程、常量系统、EventBus |
| [Base 层与 MVVM](docs/project-flow/architecture/base-layer.md) | BaseActivity/VMBaseActivity/BaseViewModel |
| [前端架构](docs/project-flow/architecture/frontend.md) | Vue3 MPA 架构、路由、组件树 |
| [前端组件](docs/project-flow/architecture/frontend-components.md) | 路由设计、页面组件、通用组件 |
| [前端状态](docs/project-flow/architecture/frontend-stores.md) | Pinia Stores + TypeScript 类型 |
| [网络层](docs/project-flow/architecture/network-layer.md) | OkHttp 拦截器链、SSL、Cookie、Cronet |

### 模块文档 — `docs/project-flow/modules/`

| 文档 | 内容 |
|------|------|
| [WebBook 搜索](docs/project-flow/modules/webbook-search.md) | 双版本并发搜索、四分类聚合去重 |
| [内容管线](docs/project-flow/modules/content-pipeline.md) | ContentProcessor 七步管线、替换规则引擎 |
| [阅读引擎](docs/project-flow/modules/reading-engine.md) | ReadBook 状态机、三章缓存、预下载 |
| [阅读排版](docs/project-flow/modules/reading-engine-pagination.md) | durChapterPos 偏移、TextChapter 数据结构 |
| [阅读媒体](docs/project-flow/modules/reading-engine-media.md) | ReadManga 漫画、AudioPlay 音频 |
| [数据层](docs/project-flow/modules/data-layer.md) | 21 实体 + 21 DAO、AutoMigration |
| [Web 服务](docs/project-flow/modules/web-service.md) | NanoHTTPD 路由、14 POST + 12 GET |
| [本地书籍](docs/project-flow/modules/local-book.md) | TXT 编码检测、EPUB 懒加载 |
| [服务层](docs/project-flow/modules/service-layer.md) | WebDAV 同步、下载缓存、TTS 朗读 |
| [配置系统](docs/project-flow/modules/config-system.md) | AppConfig / ReadBookConfig / ThemeConfig |
| [Android 服务](docs/project-flow/modules/android-services.md) | 11 个 Service、WebSocket、ExoPlayer |
| [备份恢复](docs/project-flow/modules/backup-restore.md) | 21 数据源 JSON 导出、AES 加密 |
| [远程与第三方](docs/project-flow/modules/remote-third-party.md) | RemoteBook、WebDAV、Glide、GSYVideo |
| [Model 层](docs/project-flow/modules/model-layer.md) | ReadAloud / VideoPlay / BookCover 等单例 |
| [JS 扩展函数](docs/project-flow/modules/js-extensions.md) | 30+ 可调用方法（ajax/connect/cache/file） |
| [书源管理](docs/project-flow/modules/source-management.md) | 导入/导出/校验/调试/登录全链路 |
| [RSS 子系统](docs/project-flow/modules/rss-subsystem.md) | Rss 调度、规则解析、文章流 UI |
| [工具与辅助](docs/project-flow/modules/tools-infrastructure.md) | utils 工具类、协程封装、加密 |
| [自定义库](docs/project-flow/modules/custom-libraries.md) | MOBI 解析、WebDAV 客户端、阿里云 TTS |

### 数据库文档 — `docs/project-flow/database/`

| 文档 | 内容 |
|------|------|
| [数据库总览](docs/project-flow/database/overview.md) | AppDatabase 定义、版本、迁移清单 |
| [实体与字段](docs/project-flow/database/entities.md) | BookSource + Book + 5 组规则字段详解 |
| [表结构 DDL](docs/project-flow/database/tables.md) | v89 全部 21 张表 DDL + 索引 + 约束 |

### 流程与指南 — `docs/project-flow/`

| 文档 | 内容 |
|------|------|
| [关键词索引](docs/project-flow/INDEX.md) | A-Z 150+ 条目，按关键词快速定位 |
| [任务导航](docs/project-flow/task-navigation.md) | 14 个任务导航表，按任务类型索引代码锚点 |
| [速查手册](docs/project-flow/quick-reference.md) | 构建命令、关键文件、版本锁定速查 |
| [构建打包指南](docs/project-flow/build-apk-guide.md) | 环境搭建 + 签名 + 构建 + 包名修改 |
| [Git 仓库管理](docs/project-flow/git-repo-management.md) | 远程仓库、分支策略、.gitignore、Commit 规范 |

### 功能设计文档 — `docs/specs/`

采用 [OpenSpec 工作流](docs/project-rules/openspec-workflow.md)，每个功能生成四文档（README + spec + design + tasks）。

| 目录 | 状态 | 内容 |
|------|------|------|
| [legado-skill-unified-redesign](docs/specs/legado-skill-unified-redesign/) | 进行中 | 统一 OpenSpec，合并 6 套历史 spec，9 方向 3 阶段 |
| [skill-core-capability-rebuild](docs/specs/skill-core-capability-rebuild/) | 进行中 | Skill 核心能力重建，仿真保真度 ≥95% |
| [skill-usability-optimization](docs/specs/skill-usability-optimization/) | 进行中 | Skill 可用性优化，分级工作流 + 降级策略 |
| [source-repair-loop-optimization](docs/specs/source-repair-loop-optimization/) | 进行中 | 源修复闭环优化，可观测性 + 经验闭环 |
| [legado-skill-optimization](docs/specs/legado-skill-optimization/) | 已替代 | 旧版统一 OpenSpec（27 方向 268 项） |
| [legado-skill-v2-rebuild](docs/specs/legado-skill-v2-rebuild/) | 已完成 | Legado Skill V2 重建 |
| [jvm-webview-and-test-fix](docs/specs/jvm-webview-and-test-fix/) | 已完成 | JVM 仿真 WebView + 测试修复 |
| [legado-client-enhancement](docs/specs/legado-client-enhancement/) | 已完成 | Python 客户端增强 |
| [docs-consolidation](docs/specs/docs-consolidation/) | 已完成 | 文档合并整理 |
| [tech-doc-audit-and-fix](docs/specs/tech-doc-audit-and-fix/) | 已完成 | 技术文档审计与修复 |

> 另有 10 个归档 spec 在 [docs/specs/archive/](docs/specs/archive/)

---

## 构建

### 环境要求

- JDK 17
- Android SDK（compileSdk 36, buildTools 35.0.0）
- Gradle 8.12（项目自带 Wrapper）

### 构建命令

```bash
# Debug 版本
./gradlew assembleAppDebug

# 正式版（需配置签名）
./gradlew assembleAppRelease
```

> 详见 [构建打包指南](docs/project-flow/build-apk-guide.md)

---

## API

- 提供 **Web 方式** 和 **Content Provider 方式** 两种 API，详见 [api.md](api.md)
- URL 唤起一键导入：`legado://import/{path}?src={url}`
  - path 类型：`bookSource`(书源) / `rssSource`(订阅源) / `replaceRule`(替换规则) / `httpTTS`(朗读引擎) / `theme`(主题) / `readConfig`(排版) / `dictRule`(字典规则) / `addToBookshelf`(添加到书架)

---

## 交流社区

| 平台 | 链接 |
|------|------|
| Telegram | [readsigma 频道](https://t.me/readsigma) |
| Discord | [Legado Discord](https://discord.gg/VtUfRyzRXn) |
| 微信公众号 | [legado_plus](https://mp.weixin.qq.com/s/f54f7yP9HQi6P5Wky8wE1A) |
| 帮助文档 | [语雀 Legado Wiki](https://www.yuque.com/legado/wiki) |

---

## 相关资源

- [书源规则教程](https://mgz0227.github.io/The-tutorial-of-Legado/)
- [更新日志](app/src/main/assets/updateLog.md)
- [帮助文档](app/src/main/assets/web/help/md/appHelp.md)
- [书源分享平台](https://www.yckceo.com/yuedu/shuyuans/index.html)（746+ 条书源合集）
- [订阅源分享平台](https://www.yckceo.com/yuedu/rsss/index.html)（87+ 条订阅源合集）
- [免责声明](https://gedoor.github.io/Disclaimer)

---

## 主要依赖

| 库 | 用途 |
|----|------|
| jsoup 1.16.2 | HTML 解析（**锁定版本**，jsoup#2017 破坏性变更） |
| JsoupXpath | XPath 支持 |
| json-path | JSONPath 查询 |
| rhino-android 1.8.1 | JS 脚本引擎（**锁定版本**，Android 6 兼容） |
| okhttp3 | HTTP 客户端 |
| glide | 图片加载 |
| hutool 5.8.22 | 加密工具（**锁定版本**，书源加解密依赖） |
| nanohttpd | 内置 Web 服务 |
| epublib-core | EPUB 解析 |
| LyricViewX | 朗读界面 |
| rosemoe:editor | 代码编辑器 |

---

## 致谢

感谢 [gedoor](https://github.com/gedoor) 及所有开源贡献者。

本项目基于 [Legado（阅读）](https://github.com/gedoor/legado) 开源项目，遵循原项目开源协议。

---

## License

本项目遵循原 [Legado](https://github.com/gedoor/legado) 项目的开源协议。详见 [LICENSE](LICENSE)。
