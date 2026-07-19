# Legado（阅读M）

[English](English.md) | 中文

<div align="center">

<img width="100" height="100" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>

**Android 开源电子书阅读器**

自定义书源规则引擎 | CSS / JSONPath / XPath / 正则 / JS 五种解析

继承自 [gedoor/legado](https://github.com/gedoor/legado)，基于 [Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E) 版本扩展更多功能

</div>

---

## 主要功能

- **自定义书源** — 自己设置规则抓取网页数据，规则简单易懂，软件内置规则说明
- **书架管理** — 列表书架、网格书架自由切换
- **搜索与发现** — 书源规则支持搜索及发现，找书看书功能全部自定义
- **订阅源** — 订阅想看的任何内容，RSS/网页均可，支持内置视频播放器
- **替换净化** — 去除广告、替换内容，一键净化
- **高亮规则** — 正则/字面量匹配自动高亮，9种样式，支持手动划线标注
- **本地阅读** — 支持 TXT、EPUB，手动浏览 + 智能扫描
- **深度自定义** — 字体、颜色、背景、行距、段距、加粗、简繁转换等
- **多种翻页** — 覆盖、仿真、滑动、滚动等翻页模式
- **在线朗读** — TTS 语音朗读，支持自定义朗读引擎
- **Web 管理** — 内置 Web 服务，浏览器管理书架和书源
- **自动任务** — cron 定时执行 JS 脚本，自动刷新目录、通知更新
- **多主题** — 内置 13+ 主题（含护眼/暗夜系列），支持自定义
- **开源无广告** — 持续优化，完全无广告

---

## 版本亮点

基于阅读M版本，扩展了以下核心功能：

- **内置视频播放器** — 抖音风格沉浸式竖屏布局，上下滑动切换文章，多线路多集选择，WebView降级播放，自动抓取视频链接
- **高亮规则系统** — 正则/字面量匹配自动高亮，9种样式（背景色/文字色/加粗/斜体/下划线等），支持手动划线标注，内置12条预设规则
- **自动任务系统** — cron定时执行JS脚本，自动刷新书籍目录、通知新书更新
- **13+内置主题** — 护眼绿/黄/牛皮纸 + 暗夜护眼/绿/蓝/紫，支持自定义
- **订阅源性能优化** — 列表并行解析+全局规则缓存+图片解密缓存+HTTP响应缓存+DNS容错
- **书源/订阅源管理** — 标签/分组双展示模式，按类型/分组筛选，排序功能，紧凑列表/网格布局

> 完整更新记录见 [更新日志](app/src/main/assets/updateLog.md)

---

## 下载

| 版本 | 说明 |
|------|------|
| **测试包** | 开发调试，更新频繁，可覆盖安装 |
| **共存包** | 自定义包名，与原版共存 |
| **正式包** | 稳定发布，代码混淆压缩 |

> 详见 [更新日志](app/src/main/assets/updateLog.md)

---

## 构建

### 环境要求

- JDK 17
- Android SDK（compileSdk 36）
- Gradle 8.12（项目自带 Wrapper）

### 构建命令

```bash
# 测试包（默认）
build-legado.bat

# 正式包
build-legado.bat release

# 共存包（自定义包名）
build-legado.bat debug com.my.legado
```

---

## API

- 提供 **Web 方式** 和 **Content Provider 方式** 两种 API，详见 [api.md](api.md)
- URL 唤起一键导入：`legado://import/{path}?src={url}`
  - path 类型：`bookSource` / `rssSource` / `replaceRule` / `httpTTS` / `theme` / `readConfig` / `dictRule` / `addToBookshelf`

---

## 相关资源

- [书源规则教程](https://mgz0227.github.io/The-tutorial-of-Legado/)
- [更新日志](app/src/main/assets/updateLog.md)
- [帮助文档](app/src/main/assets/web/help/md/appHelp.md)
- [书源分享平台](https://www.yckceo.com/yuedu/shuyuans/index.html)（746+ 条书源合集）
- [订阅源分享平台](https://www.yckceo.com/yuedu/rsss/index.html)（87+ 条订阅源合集）
- [免责声明](https://gedoor.github.io/Disclaimer)

---

## 致谢

感谢 [gedoor](https://github.com/gedoor) 及所有开源贡献者。

感谢 [Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E) 提供的阅读Sigma版本基础。

本项目基于 [Legado（阅读）](https://github.com/gedoor/legado) 开源项目，遵循原项目开源协议。

---

## License

本项目遵循原 [Legado](https://github.com/gedoor/legado) 项目的开源协议。详见 [LICENSE](LICENSE)。
