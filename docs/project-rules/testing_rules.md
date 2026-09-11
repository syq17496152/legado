# 测试规范

> 基于 Legado 项目源码深度分析提取的项目测试约定。

---

## 现状

项目测试覆盖薄弱，实际测试文件以 `./gradlew test` 实际运行为准，当前已知包括：

| 文件 | 内容 |
|------|------|
| `app/src/test/java/io/legado/app/ExampleUnitTest.kt` | 自动生成占位测试 |
| `app/src/test/java/io/legado/app/JsTest.kt` | Rhino JS 引擎测试（7 个方法） |

androidTest/ 下有 6 个测试文件：ExampleInstrumentedTest.kt, AndroidJsTest.kt, HttpTest.kt, HttpTtsTest.kt, MigrationTest.kt, UpdateTest.kt

## 新增测试规则

### 单元测试

- 框架：JUnit 4
- 位置：`app/src/test/java/io/legado/app/`
- 命名：`*Test.kt`
- 重点测试对象：
  - 规则引擎（AnalyzeRule, AnalyzeByJSoup, AnalyzeByJSonPath, AnalyzeByXPath）
  - 内容处理管线（ContentProcessor）
  - 本地书解析（TextFile, EpubFile）
  - URL 模板引擎（AnalyzeUrl）

### 书源/订阅源自测

书源和订阅源必须经过自测通过后才能交付：书源/订阅源 5 阶段闭环自测（见 .trae/skills/legado-source-creator/SKILL.md）。

### 运行测试

```bash
# 推荐：指定变体（flavor 仅 app，任务名需带 App 前缀，与 assembleAppDebug 同理）
./gradlew testAppDebugUnitTest

# 跑全部变体（较慢，含 release）
./gradlew test
```

> ⚠️ 原文档只给 `./gradlew test`。按 AGENTS.md「productFlavors 仅 `app`，Gradle 任务必须带 App 前缀」的口径，单测应优先用 `testAppDebugUnitTest`（2026-09-11 补充）。

### 注意事项

- Room Schema 测试资源路径已配置：`androidTest.assets.srcDirs += files("$projectDir/schemas")`
- 项目未引入 Mock 框架，如需 Mock 请先添加依赖到 `libs.versions.toml`
- 协程测试需使用 `kotlinx-coroutines-test`
