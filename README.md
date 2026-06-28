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
├── docs/                  # 项目文档（架构、规范、功能设计）
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
