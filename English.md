# Legado (ReadSigma)

[English](English.md) | [中文](README.md)

<div align="center">

<img width="100" height="100" src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>

**Android Open Source eBook Reader**

Custom book source rule engine | CSS / JSONPath / XPath / Regex / JS parsing

Forked from [gedoor/legado](https://github.com/gedoor/legado), based on [Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E) with extended features

</div>

---

## Features

- **Custom Book Sources** — Set your own rules to capture web data, simple and easy to understand
- **Bookshelf Management** — List and grid bookshelf views
- **Search & Discovery** — Book source rules support search and discovery, fully customizable
- **RSS Sources** — Subscribe to any content, RSS/web supported, built-in video player
- **Replace & Purify** — Remove ads, replace content, one-click purification
- **Highlight Rules** — Regex/literal matching auto-highlight, 9 styles, manual annotation support
- **Local Reading** — TXT and EPUB support, manual browsing + smart scanning
- **Deep Customization** — Fonts, colors, backgrounds, line spacing, paragraph spacing, bold, simplified/traditional Chinese conversion
- **Page Turn Modes** — Cover,仿真, slide, scroll and more
- **TTS Reading** — Text-to-speech with custom engine support
- **Web Management** — Built-in web service for browser-based bookshelf and source management
- **Auto Tasks** — Cron-based scheduled JS scripts, auto-refresh TOC, update notifications
- **13+ Themes** — Eye-care (green/yellow/kraft paper) + dark themes (green/blue/purple), customizable
- **Open Source & Ad-free** — Continuously optimized, completely ad-free

---

## Version Highlights

Extended features based on ReadSigma:

- **Built-in Video Player** — TikTok-style immersive vertical layout, swipe to switch articles, multi-route/multi-episode selection, WebView fallback, auto video link extraction
- **Highlight Rule System** — Regex/literal auto-highlight, 9 styles (background/text color/bold/italic/underline etc.), manual annotation, 12 built-in presets
- **Auto Task System** — Cron-scheduled JS scripts, auto-refresh book TOC, update notifications
- **13+ Built-in Themes** — Eye-care + dark series, customizable
- **RSS Performance Optimization** — Parallel list parsing + global rule cache + image decrypt cache + HTTP response cache + DNS fault tolerance
- **Source Management** — Tag/group dual display modes, type/group filtering, sorting, compact list/grid layouts

> Full changelog: [Update Log](app/src/main/assets/updateLog.md)

---

## Download

| Version | Description |
|---------|-------------|
| **Debug** | Development & testing, frequent updates, can overwrite install |
| **Coexist** | Custom package name, coexists with original version |
| **Release** | Stable release, code obfuscation & shrinking |

---

## Build

### Requirements

- JDK 17
- Android SDK (compileSdk 36)
- Gradle 8.12 (wrapper included)

### Build Commands

```bash
# Debug (default)
build-legado.bat

# Release
build-legado.bat release

# Coexist (custom package name)
build-legado.bat debug com.my.legado
```

---

## API

- **Web** and **Content Provider** API, see [api.md](api.md)
- URL import: `legado://import/{path}?src={url}`
  - Path types: `bookSource` / `rssSource` / `replaceRule` / `httpTTS` / `theme` / `readConfig` / `dictRule` / `addToBookshelf`

---

## Resources

- [Book Source Tutorial](https://mgz0227.github.io/The-tutorial-of-Legado/)
- [Update Log](app/src/main/assets/updateLog.md)
- [Help Documentation](app/src/main/assets/web/help/md/appHelp.md)
- [Book Source Sharing Platform](https://www.yckceo.com/yuedu/shuyuans/index.html) (746+ sources)
- [RSS Source Sharing Platform](https://www.yckceo.com/yuedu/rsss/index.html) (87+ sources)
- [Disclaimer](https://gedoor.github.io/Disclaimer)

---

## Acknowledgments

Thanks to [gedoor](https://github.com/gedoor) and all open source contributors.

Thanks to [Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E) for the ReadSigma version foundation.

This project is based on [Legado](https://github.com/gedoor/legado), following the original project's open source license.

---

## License

This project follows the original [Legado](https://github.com/gedoor/legado) project's open source license. See [LICENSE](LICENSE).
