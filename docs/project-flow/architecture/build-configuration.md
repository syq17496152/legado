# Legado 构建配置与依赖体系

> 本文档提供构建配置概览，完整打包流程（环境搭建/签名配置/构建命令/包名修改/构建后验证）详见 [build-apk-guide.md](../build-apk-guide.md)。

---

## 1. 构建配置概览

| 配置项 | 值 | 来源文件 |
|--------|-----|---------|
| compileSdk | 36 | `build.gradle` (root) |
| minSdk | 23 | `app/build.gradle` |
| targetSdk | 36 | `app/build.gradle` |
| applicationId | `io.legado.miss.app` | `app/build.gradle` |
| namespace | `io.legado.app` | `app/build.gradle` |
| JDK | 17 | `app/build.gradle` (jvmToolchain) |
| Kotlin | 2.3.10 | `gradle/libs.versions.toml` |
| AGP | 8.13.2 | `gradle/libs.versions.toml` |
| OkHttp | 5.4.0 | `gradle/libs.versions.toml` |
| abiFilters | arm64-v8a, armeabi-v7a | `app/build.gradle` (ndk) |
| 版本名格式 | `3.yy.MMddHH` | `app/build.gradle` (releaseTime) |
| 版本号 | `10000 + gitCommitCount` | `app/build.gradle` |
| resConfigs | zh/zh-rHK/zh-rTW/en/es/es-rES/ja/ja-rJP/pt/pt-rBR/vi | `app/build.gradle` |

---

## 2. 构建变体

| 变体 | applicationId | 桌面显示名 | 用途 |
|------|--------------|----------|------|
| **appDebug** | `io.legado.miss.app.debug` | 阅读M | 开发调试，不混淆 |
| **appRelease** | `io.legado.miss.app.release` | 阅读M | 正式发布，混淆+收缩 |
| **共存包** | `io.legado.app.debug` | 阅读M | 与原版legado-E共存（`-PcustomAppId` 参数） |

详见 [package-naming.md](../../project-rules/package-naming.md)。

---

## 3. 版本锁定依赖

| 依赖 | 版本 | 原因 |
|------|------|------|
| jsoup | 1.16.2 | 新版有破坏性变更（jsoup#2017） |
| rhino | 1.8.1 | API 24 以下不可用的 VarHandle（desugaring 不覆盖） |
| hutool | 5.8.22 | 书源加解密依赖，不可升级 |
| commons-text | 1.13.1 | API 24 以下不可用的 Arrays.setAll（desugaring 不覆盖） |
| protobuf | 4.26.1 | 兼容性锁定 |

---

## 4. 签名配置要点

- **签名密钥**：`legado_release.jks`（项目根目录，已加入 `.gitignore`，RSA 2048 位，有效期 100 年）
- **配置文件**：`local.properties`（不入 git，含 `RELEASE_STORE_FILE`/`RELEASE_STORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD`）
- **buildTypes 判断陷阱**：必须用 `storeFilePath != null` 判断，不能用 `project.hasProperty`（后者不检查 local.properties）
- **签名方案**：v1 + v2 + v3 + v4 全启用

完整签名配置与验证流程详见 [build-apk-guide.md](../build-apk-guide.md) 第三章和 4.5 节。

---

## 5. ProGuard 配置

| 配置文件 | 作用 |
|---------|------|
| `proguard-android-optimize.txt` | Android 默认优化规则 |
| `app/proguard-rules.pro` | 项目自定义规则 |
| `app/cronet-proguard-rules.pro` | Cronet 网络库专用规则 |

> **注意**：不启用 R8 full mode，会破坏 Rhino JS 引擎/Gson/Hutool 反射调用。

---

## 6. 相关文档

| 文档 | 内容 |
|------|------|
| [build-apk-guide.md](../build-apk-guide.md) | 完整打包指南（环境搭建/签名/构建/验证/常见问题） |
| [package-naming.md](../../project-rules/package-naming.md) | 包名规范（测试包/共存包/正式包） |
| [quick-reference.md](../quick-reference.md) | 命令速查卡 |
| [ci-cd-pipeline.md](./ci-cd-pipeline.md) | GitHub Actions CI/CD 流程（原版 legado-E） |
