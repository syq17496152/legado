# Legado（开源阅读）APK 打包指南

> 从零开始，在 Windows 11 上构建 Legado APK 的完整指南，包含环境搭建、签名配置、打包命令、包名修改等。

---

## 一、环境准备

### 1.1 必需软件

| 软件 | 版本要求 | 下载地址 | 说明 |
|------|---------|---------|------|
| **JDK** | 17（必须） | https://adoptium.net/temurin/releases/?version=17 | 项目 `build.gradle` 指定 `JavaLanguageVersion.of(17)` |
| **Android SDK** | compileSdk 36 | 通过 Android Studio 或 cmdline-tools 安装 | 需安装 Android 15 (API 36) 平台 |
| **Git** | 任意 | https://git-scm.com/ | 版本号计算依赖 `git rev-list` |

### 1.2 安装 Android SDK（两种方式任选）

#### 方式 A：安装 Android Studio（推荐，改源码必选）

> **如果需要修改 Kotlin 源码，必须选此方式！** Android Studio 提供 Kotlin 智能提示、代码跳转、重构、调试等能力，cmdline-tools 无法替代。

1. 下载 Android Studio：https://developer.android.com/studio
2. 安装时勾选 Android SDK、Android Virtual Device
3. 安装完成后，打开 SDK Manager → SDK Platforms 标签，勾选 **Android 15.0 (API 36)**
4. 切换到 SDK Tools 标签，勾选安装：
   - Android SDK Build-Tools（最新版）
   - Android SDK Command-line Tools
   - Android SDK Platform-Tools
5. 打开项目：File → Open → 选择 Legado 项目根目录
6. 等待 Gradle Sync 完成（首次需下载依赖，可能数分钟）
7. Sync 完成后即可编辑代码 + 一键运行

> Android Studio 会自动创建 `local.properties` 并配置 SDK 路径，无需手动操作。

#### 方式 B：仅安装 cmdline-tools（仅适合打包，不适合改代码）

> 仅适合「不改代码只打包」的场景。如果要修改 Kotlin 源码，此方式无法提供代码补全、语法检查、跳转定义等开发能力，效率极低。

**两种方式核心能力对比：**

| 能力 | cmdline-tools | Android Studio |
|------|--------------|----------------|
| 编译打包 APK | 可以 | 可以 |
| 修改 Kotlin 代码 | 能改（记事本级别） | **能改（智能提示+跳转+重构）** |
| 代码补全/自动导入 | 无 | 有 |
| 跳转到定义/查找引用 | 无 | 有 |
| 语法错误实时提示 | 无 | 有 |
| 代码重构（重命名等） | 无 | 有 |
| 调试断点 | 无 | 有 |
| Layout XML 预览 | 无 | 有 |
| 下载大小 | ~150MB | ~1GB+ |
| 磁盘总占用 | ~300MB | ~3GB+ |
| 适合场景 | 纯打包、CI/CD、服务器 | **改源码、日常开发、调试** |

**第 1 步：下载**

访问官方页面：https://developer.android.com/studio#command-line-tools-only

滚动到底部 **"Command line tools only"** 区域，下载 **Windows** 版 ZIP（约 150MB）。

**第 2 步：解压到正确目录结构**

> 此步最容易踩坑——目录结构必须是三级嵌套，否则 sdkmanager 会报错。

```
C:\Android\Sdk\
  └── cmdline-tools\
       └── latest\
            ├── bin\
            │    ├── sdkmanager.bat
            │    └── avdmanager.bat
            ├── lib\
            └── ...
```

```powershell
# 创建 SDK 根目录
mkdir C:\Android\Sdk

# 创建三级嵌套目录
mkdir C:\Android\Sdk\cmdline-tools\latest

# 解压下载的 ZIP 后，把解压出 cmdline-tools 文件夹里的所有内容
# （bin、lib 等子目录）复制到 C:\Android\Sdk\cmdline-tools\latest\ 中
#
# ❌ 错误：内容放在 cmdline-tools\ 而不是 cmdline-tools\latest\
# ✅ 正确：内容放在 cmdline-tools\latest\
#
# 可以用 PowerShell 自动完成：
# Expand-Archive -Path "$env:USERPROFILE\Downloads\commandlinetools-win-*.zip" -DestinationPath C:\Android\Sdk\cmdline-tools\latest -Force
```

**第 3 步：设置环境变量**

```powershell
# 在 PowerShell 中执行（需要重启终端后生效）

# 设置 ANDROID_HOME
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Android\Sdk", "User")

# 设置 ANDROID_SDK_ROOT
[System.Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", "C:\Android\Sdk", "User")

# 追加 PATH（不覆盖已有 PATH）
$oldPath = [System.Environment]::GetEnvironmentVariable("PATH", "User")
$newPath = "$oldPath;C:\Android\Sdk\cmdline-tools\latest\bin;C:\Android\Sdk\platform-tools"
[System.Environment]::SetEnvironmentVariable("PATH", $newPath, "User")
```

> **重要**：设置完成后必须关闭当前终端，重新打开才能生效。

**第 4 步：接受许可证 + 安装构建所需组件**

```powershell
# 接受所有许可证（一路 y 下去）
sdkmanager --licenses

# 安装 Legado 构建所需的三个核心组件
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

三个组件的作用与大小：

| 组件 | 大小 | 作用 |
|------|------|------|
| `platforms;android-36` | ~70MB | Android 15 API 框架，compileSdk 36 必需 |
| `build-tools;36.0.0` | ~60MB | APK 打包工具（aapt2、d8、apksigner 等） |
| `platform-tools` | ~40MB | adb 等设备通信工具 |

**常用 sdkmanager 命令速查：**

```powershell
# 查看已安装组件
sdkmanager --list_installed

# 查看所有可用组件
sdkmanager --list

# 安装额外组件（按需）
sdkmanager "ndk;27.0.12077973"     # 如需 NDK
sdkmanager "cmake;3.22.1"          # 如需 CMake

# 更新所有已安装组件
sdkmanager --update

# 卸载组件
sdkmanager --uninstall "build-tools;35.0.0"
```

**国内镜像加速（可选）：**

如果直连 Google 下载慢，可以配置国内镜像。编辑 `C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat` 同目录或通过代理设置：

```powershell
# 方式 1：使用 HTTP 代理
set HTTP_PROXY=http://127.0.0.1:7890
set HTTPS_PROXY=http://127.0.0.1:7890
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"

# 方式 2：使用腾讯镜像（无需代理）
sdkmanager --no_https --proxy=http --proxy_host=mirrors.cloud.tencent.com --proxy_port=80 "platforms;android-36"
```

> Gradle 构建时的依赖下载也可以配置镜像，见 `settings.gradle` 中已注释的镜像仓库行。

### 1.3 配置项目 SDK 路径

在项目根目录创建 `local.properties`（此文件已在 `.gitignore` 中，不会被提交）：

```properties
sdk.dir=C:\\Android\\Sdk
# 或 Android Studio 默认路径：
# sdk.dir=C:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
```

### 1.4 验证环境

```powershell
java -version          # 应显示 openjdk version "17" 或 17.x.x
gradlew --version     # 应显示 Gradle 8.x
```

---

## 二、项目构建配置概览

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

### 构建变体说明

| 变体 | applicationId | 桌面显示名 | 用途 |
|------|--------------|----------|------|
| **appDebug** | `io.legado.miss.app.debug` | 阅读M | 开发调试，不混淆 |
| **appRelease** | `io.legado.miss.app.release` | 阅读M | 正式发布，混淆+收缩 |
| **共存包** | `io.legado.app.debug` | 阅读M | 与原版legado-E共存 |

> **注意**：debug 和 release 的 applicationId 不同（后缀不同），可以在同一设备上同时安装。

### 桌面显示名配置（app_name）

桌面显示名由 `AndroidManifest.xml` 的 `android:label="${app_name}"` 占位符决定，`app/build.gradle` 的 buildTypes 根据 applicationIdSuffix 选择不同的 strings.xml 字段：

```groovy
buildTypes {
    release {
        if (getApplicationIdSuffix() == '.releaseA') {
            manifestPlaceholders.put("app_name", "@string/app_name_a")  // 阅读M·A
        } else if (getApplicationIdSuffix() == '.releaseS') {
            manifestPlaceholders.put("app_name", "@string/app_name_s")  // LegadoPlus
        } else {
            manifestPlaceholders.put("app_name", "@string/app_name")    // 阅读M
        }
    }
    debug {
        manifestPlaceholders.put("app_name", "@string/app_name")        // 阅读M
    }
}
```

**app_name 字段在 4 个 strings.xml 中配置**（必须同步修改，否则中文系统下显示名会缺"M"）：

| 文件 | app_name | app_name_a | app_name_s | 适用语言 |
|------|---------|-----------|-----------|---------|
| `values/strings.xml` | 阅读M | 阅读M·A | LegadoPlus | 默认（英语等） |
| `values-zh/strings.xml` | 阅读M | 阅读M·A | 阅读Plus | 中文（简体） |
| `values-zh-rTW/strings.xml` | 閱讀M | 閱讀M·A | — | 中文（台湾繁体） |
| `values-zh-rHK/strings.xml` | 閲讀M | 閲讀M·A | — | 中文（香港繁体） |

> **踩坑警告**：Android 资源限定符优先级——中文系统（zh-CN）优先匹配 `values-zh/`，而非默认 `values/`。如果只改 `values/strings.xml` 的 app_name 为"阅读M"，而 `values-zh/strings.xml` 仍是"阅读"，中文系统下桌面显示名仍是"阅读"（缺 M）。**4 个文件必须同步修改**。

> **resConfigs 限制**：`app/build.gradle` 的 `resConfigs 'zh', 'zh-rHK', 'zh-rTW', 'en', ...` 限制了打包的语言资源，只有这些语言的 strings.xml 会被打包进 APK。

---

## 三、签名配置（Release 构建）

> **重要**：Release 构建必须配置签名，否则生成的 APK 未签名无法安装（报 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`）。Debug 构建使用默认 debug 签名，无需配置。

### 3.1 生成签名密钥

在项目根目录执行（生成的 `legado_release.jks` 放项目根目录）：

```powershell
keytool -genkeypair -v -keystore legado_release.jks -alias legado -keyalg RSA -keysize 2048 -validity 36500 -storepass <你的密码> -keypass <你的密码> -dname "CN=Legado, OU=Dev, O=Miss, L=CN, ST=CN, C=CN"
```

参数说明：
- `-keystore legado_release.jks`：密钥库文件名（放项目根目录）
- `-keyalg RSA -keysize 2048`：RSA 2048 位密钥
- `-validity 36500`：有效期 36500 天（约 100 年）
- `-alias legado`：密钥别名
- `-storepass <你的密码> -keypass <你的密码>`：密钥库密码和密钥密码（请替换为自己的强密码）
- `-dname`：证书持有者信息

> **密钥保管**：`legado_release.jks` 是签名密钥，丢失后无法发布同名应用的更新版本。已添加到 `.gitignore`（`*.jks` + `*.keystore` + `local.properties`），不会提交到 git。
> **不变签名铁律**：发布后不能更换签名，否则用户无法覆盖升级。证书丢失只能重新生成，但已安装用户无法升级到新签名版本，务必妥善备份证书。

### 3.2 配置签名信息（local.properties 方式，推荐）

签名配置存放在 `local.properties`（已在 `.gitignore` 中，不入 git），不存放在 `gradle.properties`（避免敏感信息误提交）。

在项目根目录 `local.properties` 文件末尾追加：

```properties
# 签名配置（不入 git，仅本地使用）
# keystore 文件放在项目根目录 legado_release.jks
RELEASE_STORE_FILE=legado_release.jks
RELEASE_STORE_PASSWORD=<你的密码>
RELEASE_KEY_ALIAS=legado
RELEASE_KEY_PASSWORD=<你的密码>
```

> **CI/CD 场景**：如需在 CI/CD 中使用，可改用 `gradle.properties` 或命令行 `-P` 参数传入（优先级：命令行 -P > gradle.properties > local.properties）。

### 3.3 签名配置原理（build.gradle 读取逻辑）

`app/build.gradle` 中的签名配置读取逻辑（支持 local.properties fallback）：

```groovy
// 签名配置读取顺序：gradle.properties / 命令行 -P → local.properties（不入 git）
// local.properties 用于本地开发，gradle.properties 用于 CI/CD
def localProps = new Properties()
def localPropsFile = rootProject.file('local.properties')
if (localPropsFile.exists()) {
    localProps.load(new FileInputStream(localPropsFile))
}
def storeFilePath = project.findProperty("RELEASE_STORE_FILE") ?: localProps.getProperty("RELEASE_STORE_FILE")
signingConfigs {
    if (storeFilePath != null) {
        myConfig {
            storeFile rootProject.file(storeFilePath)
            storePassword project.findProperty("RELEASE_STORE_PASSWORD") ?: localProps.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias project.findProperty("RELEASE_KEY_ALIAS") ?: localProps.getProperty("RELEASE_KEY_ALIAS")
            keyPassword project.findProperty("RELEASE_KEY_PASSWORD") ?: localProps.getProperty("RELEASE_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }
}
```

**buildTypes 中关联签名配置**（关键：必须用 `storeFilePath != null` 判断，不能用 `project.hasProperty`）：

```groovy
buildTypes {
    release {
        if (storeFilePath != null) {
            signingConfig signingConfigs.myConfig
        }
        // ...
    }
    debug {
        if (storeFilePath != null) {
            signingConfig signingConfigs.myConfig
        }
        // ...
    }
}
```

> **踩坑警告**：如果 buildTypes 用 `project.hasProperty("RELEASE_STORE_FILE")` 判断，只检查命令行 -P 参数和 gradle.properties，**不检查 local.properties**，导致只用 local.properties 配置时 Release APK 未签名。必须用 `storeFilePath != null` 判断。

### 3.4 签名方案说明

| 签名方案 | 版本要求 | 说明 |
|---------|---------|------|
| v1 (JAR signing) | Android 7.0 以下 | 基于 META-INF/MANIFEST.MF，兼容旧设备 |
| v2 (APK Signature Scheme v2) | Android 7.0+ | APK Signing Block，完整 APK 校验 |
| v3 (APK Signature Scheme v3) | Android 9.0+ | 支持密钥轮换 |
| v3.1 | Android 11+ | v3 的改良版（可选） |
| v4 (APK Signature Scheme v4) | Android 11+ | 需要 `.idsig` 文件，用于增量安装 |

> **本地构建**：enableV1Signing + enableV2Signing + enableV3Signing 设为 true 即可（v4 需要 `.idsig` 文件，本地安装可不用）。

---

## 四、构建 APK

### 4.1 Debug 构建（最简单）

```powershell
# 进入项目根目录
cd f:\myself\github\WeAgentChat\temp\legado

# 构建 Debug APK
.\gradlew assembleAppDebug

# 输出位置
# app\build\outputs\apk\app\debug\legado_miss_app_3.版本号.apk
```

Debug 构建签名说明：
- 若 `local.properties` 已配置签名信息（`RELEASE_STORE_FILE` 等），Debug 包会使用与 Release 包相同的正式签名（`signingConfigs.myConfig`），确保三包签名一致
- 若未配置签名信息，Debug 构建使用默认 debug 签名（`CN=Android Debug`）

> **🔴 三包统一签名铁律（2026-08-25）**：`local.properties` 已配置正式签名时，`app/build.gradle` 的 **debug 块也必须加载 `signingConfigs.myConfig`**（与 release 一致）。
> 历史铁证：commit `3d7671c14`（2026-08-25）误删 debug 块 `signingConfig signingConfigs.myConfig`，导致新打的测试包回落为 `CN=Android Debug` 签名，与用户手机上历史已装的正式签名测试包签名不一致，覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
> **修复/改签名/迁移 debug/release signingConfig 时，必须让 buildTypes.debug 保持与 release 相同的 `myConfig`，禁止借"避免耦合/混淆"为由删 debug 签名。**

### 4.2 Release 构建（正式发布）

```powershell
# 前提：已完成签名配置（第三章）
.\gradlew assembleAppRelease

# 输出位置
# app\build\outputs\apk\app\release\legado_miss_app_3.版本号.apk
```

**修改签名配置或 strings.xml 后必须用 --rerun-tasks 强制重新打包**：

```powershell
# Gradle 可能因缓存判断 signingConfigs 未变化而 UP-TO-DATE 跳过打包
# 修改 build.gradle 签名配置或 strings.xml 后，用 --rerun-tasks 强制重新执行所有任务
.\gradlew assembleAppRelease --rerun-tasks
```

> **踩坑警告**：修改 build.gradle 的 signingConfigs 块后，Gradle 可能仍显示 `:app:packageAppRelease UP-TO-DATE`，生成的 APK 未签名。必须用 `--rerun-tasks` 强制重新打包。

**PowerShell / Git Bash 路径转义注意**：

```powershell
# ❌ 错误：PowerShell 会把 = 后的值拆分为独立参数
.\gradlew assembleAppRelease -PRELEASE_STORE_FILE=legado_release.jks
# 报错：Task '.jks' not found

# ✅ 正确：用引号包裹 -P 参数
.\gradlew assembleAppRelease "-PRELEASE_STORE_FILE=legado_release.jks"

# ❌ 错误：Git Bash 把 Windows 反斜杠路径当转义字符
f:\path\to\adb.exe devices
# 报错：command not found

# ✅ 正确：用 cmd //c 执行 Windows 路径命令
cmd //c "f:\path\to\adb.exe devices"

# ✅ 正确：或者用正斜杠路径
/f/path/to/adb.exe devices
```

### 4.3 构建所有变体

```powershell
.\gradlew assembleDebug assembleRelease
```

### 4.4 输出 APK 命名规则

APK 文件名格式：`legado_<包名标识>_<flavor>_<version>.apk`

- 包名标识：`miss`（默认包名`io.legado.miss.app`）/ `legacy`（原版包名`io.legado.app`） / 其他自定义取最后一段
- flavor = `app`（目前仅此一个 product flavor）
- version = `3.yy.MMddHH`（如 `3.26.071900`）

示例：
- 测试包：`legado_miss_app_3.26.071900.apk`
- 正式包：`legado_miss_app_3.26.071900.apk`
- 共存包：`legado_legacy_app_3.26.071900.apk`

> **APK输出目录**：构建成功后APK会自动拷贝到`output/apk/{test|coexist|release}/`子目录，通过子目录隔离不同包类型。

### 4.5 构建后验证（重要）

Release APK 构建完成后，必须逐项验证签名、app_name、安装、启动，缺一不可。

#### 4.5.1 签名验证（apksigner verify）

```powershell
# 用 apksigner 验证 APK 签名（aapt 无法验证签名）
f:\myself\github\WeAgentChat\temp\legado\temp\android-sdk\build-tools\36.0.0\apksigner.bat verify --verbose <APK路径>
```

**期望输出**：

```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
```

> **失败排查**：如果输出 `DOES NOT VERIFY` 或 `Missing META-INF/MANIFEST.MF`，说明 APK 未签名。检查 build.gradle 的 buildTypes 是否用 `storeFilePath != null` 判断签名配置（不是 `project.hasProperty`），并用 `--rerun-tasks` 重新打包。

#### 4.5.2 桌面显示名验证（aapt dump badging）

```powershell
# 用 aapt 查询 APK 的 application-label（必须用 PowerShell + UTF-8 输出中文）
powershell -Command "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; & '<SDK路径>\build-tools\36.0.0\aapt.exe' dump badging '<APK路径>' | Select-String 'application-label'"
```

**期望输出**（中文系统需确认 zh/zh-HK/zh-TW 都带"M"）：

```
application-label:'阅读M'
application-label-zh:'阅读M'
application-label-zh-HK:'閲讀M'
application-label-zh-TW:'閱讀M'
```

> **失败排查**：如果 `application-label-zh` 是"阅读"（缺 M），说明 `values-zh/strings.xml` 的 app_name 未同步修改。4 个 strings.xml 必须同步修改（见"桌面显示名配置"小节）。

> **编码注意**：cmd 控制台用 GBK 编码，中文会显示为乱码（如"闃呰M"）。必须用 PowerShell + `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` 输出 UTF-8。

#### 4.5.3 安装验证（adb install）

```powershell
# 卸载旧版本（避免签名冲突）
adb uninstall io.legado.miss.app.release

# 安装新 APK
adb install -r <APK路径>

# 期望输出：Success
```

> **失败排查**：
> - `INSTALL_PARSE_FAILED_NO_CERTIFICATES`：APK 未签名，见 4.5.1
> - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：签名不一致，先卸载旧版本再安装
> - `INSTALL_FAILED_NO_MATCHING_ABIS`：ABI 不匹配，APK 不含设备 CPU 架构（当前仅打包 arm64-v8a/armeabi-v7a，x86 模拟器需 ARM 兼容层如 MEmu 的 Houdini）

#### 4.5.4 启动验证（am start + dumpsys）

```powershell
# 启动 App
adb shell am start -n io.legado.miss.app.release/io.legado.app.ui.main.MainActivity

# 等待 5 秒后检查是否崩溃
adb shell dumpsys activity activities | findstr mResumedActivity

# 期望输出：mResumedActivity: ActivityRecord{... io.legado.miss.app.release/io.legado.app.ui.main.MainActivity ...}
```

> **失败排查**：如果 `mResumedActivity` 不是 `io.legado.miss.app.release/...`，说明 App 启动后崩溃。用 `adb logcat | findstr AndroidRuntime` 查看崩溃日志。

### 4.6 常见构建问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| `SDK location not found` | 缺少 `local.properties` | 创建文件并设置 `sdk.dir` |
| `Could not resolve io.legado.app:book` | 子模块未同步 | 先执行 `.\gradlew clean` |
| Cronet 下载失败 | 网络问题 | 配置代理或手动下载 Cronet JAR |
| `Execution failed for ':app:kspDebugKotlin'` | Room schema 冲突 | `.\gradlew clean` 后重新构建 |
| 编译 OOM | JVM 内存不足 | 在 `gradle.properties` 中调大 `-Xmx` |
| **卡在 3%/下载慢** | 国内网络直连 Google/Maven 慢 | **启用国内镜像**（见 4.7） |
| **Kotlin daemon AccessDeniedException** | 残留的 Kotlin daemon 临时文件 | 删除 `%LOCALAPPDATA%\kotlin\daemon` 后重试 |
| **KSP 跨盘符路径错误** | Gradle 缓存和项目不在同一盘 | 设置 `GRADLE_USER_HOME` 到项目同盘（见 4.8） |
| **transforms move 失败** | Windows 长路径限制(260字符) | 将 `GRADLE_USER_HOME` 设为极短路径如 `F:\gh` |
| **Release APK 未签名**（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`） | buildTypes 用 `project.hasProperty` 判断签名配置，不读 local.properties | buildTypes 改用 `storeFilePath != null` 判断（见 3.3） |
| **修改 signingConfigs 后 APK 仍未签名**（`UP-TO-DATE`） | Gradle 缓存判断未变化跳过打包 | 用 `--rerun-tasks` 强制重新打包（见 4.2） |
| **PowerShell `-P` 参数被拆分**（`Task '.jks' not found`） | PowerShell 把 `=值` 拆分为独立参数 | 用引号包裹：`"-PRELEASE_STORE_FILE=legado_release.jks"` |
| **Git Bash 执行 Windows 路径命令失败**（`command not found`） | Git Bash 把反斜杠当转义字符 | 用 `cmd //c "命令"` 或正斜杠路径（见 4.2） |
| **中文系统桌面显示名缺"M"** | values-zh 的 app_name 未同步修改 | 4 个 strings.xml 必须同步修改（见"桌面显示名配置"） |
| **aapt 输出中文乱码**（`闃呰M`） | cmd 控制台用 GBK 编码 | 用 PowerShell + `[Console]::OutputEncoding = UTF8` |
| **历史 spec 文档命令/包名过时**（`io.legado.missapp`/`assembleRelease`） | docs/specs/ 下历史 spec 文档创建时包名为 `io.legado.missapp`（无点），后续重命名为 `io.legado.miss.app`（有点）；部分 spec 文档用 `assembleRelease`/`assembleDebug` 缺 App 前缀 | **以本文档为准**：包名 `io.legado.miss.app`、任务名 `assembleAppDebug`/`assembleAppRelease`。查阅 docs/specs/ 时注意历史值 |

### 4.6.1 历史 spec 文档过时内容警告

> ⚠️ **AI 打包时注意**：`docs/specs/` 目录下的历史 spec 文档（特别是 `build-workflow-optimization/`、`apk-size-optimization/`、`p0-bugfix-round1/`、`rss-unified-search/`、`source-layout-redesign/` 等）中可能存在以下过时内容：
>
> 1. **包名过时**：使用 `io.legado.missapp`（无点），实际应为 `io.legado.miss.app`（有点）
> 2. **Gradle 任务名缺 App 前缀**：使用 `assembleDebug`/`assembleRelease`，实际应为 `assembleAppDebug`/`assembleAppRelease`（本项目 productFlavors 仅 `app` 一个，任务名首字母大写敏感）
> 3. **签名配置过时**：部分 spec 文档描述用 `project.hasProperty` 判断签名，实际应用 `storeFilePath != null` 判断（见 3.3）
>
> **打包时一律以本指南（build-apk-guide.md）为准**，spec 文档仅作历史参考。

### 4.7 国内 Gradle 镜像加速（重要！）

> 国内直连 Google Maven / Maven Central 极慢，首次构建会卡住。**必须启用国内镜像**。

项目 `settings.gradle` 已内置镜像配置，默认被注释掉了。打开 `settings.gradle`，找到以下内容并**取消注释**：

**pluginManagement.repositories 部分：**

```groovy
// 注释掉原来的镜像注释行，改为：
maven { url 'https://maven.aliyun.com/repository/google' }
maven { url 'https://maven.aliyun.com/repository/public' }
maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
```

**dependencyResolutionManagement.repositories 部分：**

```groovy
maven { url 'https://maven.aliyun.com/repository/google' }
maven { url 'https://maven.aliyun.com/repository/public' }
maven { url 'https://repo.huaweicloud.com/repository/maven/' }
```

> 镜像放在原始仓库后面作为备用源。如果原始仓库能连上就用原始的，连不上自动走镜像。

**Gradle 本体下载加速**（首次运行 gradlew 时下载 gradle-8.14.4-bin.zip）：

编辑 `gradle/wrapper/gradle-wrapper.properties`，将 `distributionUrl` 改为腾讯镜像：

```properties
# 原始地址
# distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.4-bin.zip

# 腾讯镜像加速
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.4-bin.zip
```

构建完成后可改回原始地址。

### 4.8 GRADLE_USER_HOME 配置（解决跨盘符和长路径问题）

如果项目在 F 盘，Gradle 默认将缓存放在 `C:\Users\用户名\.gradle\`，会导致两个问题：
1. **KSP 跨盘符错误**：`this and base files have different roots`
2. **长路径限制**：Windows 260 字符限制导致 transforms move 失败

**解决方法**：设置 `GRADLE_USER_HOME` 到项目同盘的短路径。

**方式一：在构建脚本中设置**（见 10.4 一键构建脚本）

**方式二：系统环境变量**

```powershell
# 设置系统环境变量（永久生效）
[System.Environment]::SetEnvironmentVariable("GRADLE_USER_HOME", "F:\gh", "User")
```

> 路径越短越好：`F:\gh` 比 `F:\myself\...\temp\gradle-home` 好得多，避免嵌套路径过长。

### 4.9 一键构建脚本（build-legado.bat）

项目根目录已内置 `build-legado.bat`，自动处理环境变量、清理缓存、构建。

**使用方法：**

| 操作 | 命令 | 最终包名 | APK位置 |
|------|------|---------|---------|
| 构建共存包（日常推荐） | `build-legado.bat debug io.legado.app` | `io.legado.app.debug` | `output/apk/coexist/` |
| 构建测试包 | `build-legado.bat` | `io.legado.miss.app.debug` | `output/apk/test/` |
| 构建正式包 | `build-legado.bat release` | `io.legado.miss.app.release` | `output/apk/release/` |
| 清理构建缓存 | `build-legado.bat clean` | — | — |

**自定义包名说明：**

第二个参数为自定义 applicationId，通过 Gradle 项目属性 `-PcustomAppId` 传入。

- 不传第二个参数 → 使用默认包名 `io.legado.miss.app`
- 传入第二个参数 → 使用自定义包名，如 `io.legado.app`（共存包）

自定义包名后，APK 可以和原版 Legado 同时安装在同一设备上（包名不同=不同应用）。

> **原理**：`app/build.gradle` 中已改为：
> ```groovy
> applicationId project.hasProperty("customAppId") ? project.property("customAppId") : "io.legado.miss.app"
> ```
> 不传 `-PcustomAppId` 时使用默认包名 `io.legado.miss.app`，debug后缀`.debug`，release后缀`.release`。

**脚本自动做的事：**

1. 检查 JDK 17 和 Android SDK 是否存在
2. 设置 `GRADLE_USER_HOME=F:\gh`（短路径，避免跨盘符和长路径问题）
3. 清理 Kotlin daemon 残留缓存（解决 `AccessDeniedException`）
4. 清理旧的 Gradle transforms 缓存
5. 停止残留的 Gradle daemon
6. 使用 `--no-daemon` 构建（避免守护进程文件锁）
7. 如有自定义包名，通过 `-PcustomAppId=xxx` 传入 Gradle
8. 构建成功后将APK拷贝到`output/apk/{test|coexist|release}/`子目录
9. 列出 APK 文件路径和包名

> **注意**：可在**系统 CMD** 或 **Trae CN 终端 PowerShell** 中运行。沙盒限制已解除（需用户开通沙箱外权限，铁证：2026-07-29 三包在 Trae CN PowerShell 构建成功）。若遇 transforms move 失败，见 [10.4 已修复的构建问题](#104-已修复的构建问题) 和 [常见问题排查](#常见问题排查)。

### 4.10 打包后清理构建进程（强制门禁）

> **背景（2026-09-03 local-build-speedup 基线实测更新）**：打包前清场（删 Kotlin daemon 缓存 + `--stop` + `--no-daemon`）会导致 Kotlin 增量编译快照每次丢失、`compileAppDebugKotlin` 准全量重编——实测增量打包 7m33s 的根因。daemon 复用是提速核心，内存安全改由三重保险保障：
> ① jvmargs Xmx 限幅（debug 3g 命令行注入 / release 4g 走 properties，防 R8 OOM）
> ② `org.gradle.daemon.idletimeout=600000` 空闲 10 分钟自退（连带回收 Kotlin daemon）
> ③ `daemon-stop` 手动清场入口
> 另实测：打包过程内存峰值 93.5% 中 java 仅占 7.6GB（24%），模拟器/IDE/系统缓存 ~22GB（69%）才是驻留大头。

**daemon 管理方式（按打包入口）**：

| 打包入口 | daemon 行为 |
|---------|---------|
| `build-legado.bat`（debug） | 复用 daemon + 降堆 3g 注入；失败自动清场；成功后等 10min 自退 |
| `build-legado.bat`（release） | 复用 daemon + 走 properties 4g（R8 防回归）；失败自动清场 |
| `ai_tests/scripts/quick_build_install.py` | 编译成功保留 daemon，失败自动清场 |
| 直接 `gradlew assembleApp*` / IDE | 建议构建后 `build-legado.bat daemon-stop` 清场（或等 10min 自退） |

手动清场命令（`build-legado.bat daemon-stop` 等价于）：
```powershell
# 1. 停 Gradle daemon（连带停由其拉起的 Kotlin daemon）
.\gradlew --stop

# 2. 强杀本项目残留 Kotlin daemon（按 marker 路径含 in-legado 过滤，避免误杀他项目）
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*KotlinCompileDaemon*' -and $_.CommandLine -like '*in-legado*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
```

配套内存约束（`gradle.properties`）：
- `org.gradle.jvmargs` → `-Xmx4g`（release R8 上限；debug 由 bat 命令行注入降为 3g）
- `kotlin.daemon.jvmargs=-Xmx4g`（debug 由 bat 注入降为 3g）
- `org.gradle.configuration-cache=true`（版本号已 ValueSource 化，缓存安全）
- `org.gradle.parallel=true`（3 模块并行）
- `org.gradle.daemon.idletimeout=600000`（空闲 10 分钟自退）

> ⚠️ CC 陷阱（ValueSource 实测）：ValueSource 内不可用 `ExecOperations.exec`（CC 上下文中静默失败，versionCode 回退 1），需用 `ProcessBuilder` + 显式 `workingDir`；Groovy 脚本嵌套类必须显式 import。debug 打包命令行 `-Dorg.gradle.jvmargs` 会**整体替换** properties 参数串，必须完整复制仅改 Xmx。
> ✅ 已落地：`build-legado.bat` daemon 复用 + `daemon-stop` 入口 + `quick_build_install.py` 失败才清场 + 版本号 ValueSource 化。设计文档：`docs/specs/local-build-speedup/`。

---

## 五、Cronet 网络库

Legado 使用 Chromium Cronet 作为网络加速库。Cronet 相关文件位于 `app/cronetlib/`。

### 5.1 已有文件

- `app/cronetlib/cronet_api.jar`
- `app/cronetlib/cronet_shared_java.jar`
- `app/src/main/assets/cronet.json`（SO 文件 MD5 映射）

### 5.2 更新 Cronet 版本

```powershell
# 1. 修改 gradle.properties 中的 CronetVersion
# CronetVersion=500.0.1

# 2. 执行下载任务
.\gradlew app:downloadCronet
```

此任务会下载对应版本的 JAR 和各架构 SO 文件，并更新 `cronet.json`。

> **2026-08-19 迁移**：Cronet 已切换为 `cronet-bundled` Maven 构件（`gradle.properties` 中 `CronetVersion=500.0.1`），替换本地 `cronetlib/` jars 并移除 APK 内置 SO 手工打包，上述 `downloadCronet` 本地下载流程仅作历史参考。

---

## 六、Vue3 Web 模块构建（可选）

Legado 内嵌 Vue3 前端（`modules/web/`），用于 Web 书源编辑功能。

```powershell
# 进入 web 模块目录
cd modules\web

# 安装依赖
npm install

# 开发模式
npm run dev

# 生产构建（含类型检查 + 同步到 assets）
npm run build
```

> **注意**：`npm run build` 会执行 `sync.js`，该脚本仅在 GitHub Actions 中自动执行。本地构建需手动确认 `dist/` 内容已复制到 `app/src/main/assets/web/`。

---

## 七、修改包名（applicationId）

### 7.1 什么是包名？

包名（applicationId）是 Android 系统中唯一标识应用的字符串，决定了应用在设备上的身份。修改包名后，新包名和原包名被视为两个不同的应用，可以同时安装。

### 7.2 当前包名结构

| 构建类型 | applicationId | 桌面显示名 | 说明 |
|---------|--------------|----------|------|
| Debug（默认） | `io.legado.miss.app.debug` | 阅读M | 开发调试，默认包名 |
| Release（默认） | `io.legado.miss.app.release` | 阅读M | 正式发布，默认包名 |
| 共存包（自定义） | `io.legado.app.debug` | 阅读M | 与原版legado-E共存 |

> **注意**：debug 和 release 的 applicationId 不同（后缀不同），可以在同一设备上同时安装。详见 [包名规范](../project-rules/package-naming.md)。

### 7.3 修改方法

#### 方法一：通过 customAppId 属性传包名（最简单，推荐）

本项目 `applicationId` 由 `customAppId` 构建属性动态决定（`app/build.gradle` L61-66），不传参时默认 `io.legado.miss.app`：

```groovy
// app/build.gradle 第 61-66 行
defaultConfig {
    // 1. 传 -PcustomAppId=xxx → 共存包（用户自定义包名）
    // 2. 不传参数 + 构建类型 debug → 测试包（io.legado.miss.app.debug）
    // 3. 不传参数 + 构建类型 release → 正式包（io.legado.miss.app.release）
    applicationId project.hasProperty("customAppId") ? project.property("customAppId") : "io.legado.miss.app"
}
```

修改包名无需编辑 gradle 文件，构建时传参即可：

```powershell
# 共存包示例（与原版 legado 共存）
.\gradlew assembleAppDebug -PcustomAppId=io.legado.app.debug
# 或一键脚本（内部等价传参）
build-legado.bat debug io.legado.app
```

包名后缀由 `buildTypes` 的 `applicationIdSuffix` 追加（`app/build.gradle` L123/L144：release 加 `.release`、debug 加 `.debug`），最终包名 = `customAppId`（或默认值）+ 构建类型后缀。

> **注意**：此方法只改 applicationId，不改 Kotlin 源码包结构（namespace）。applicationId 和 namespace 是独立的——applicationId 决定应用在设备上的标识，namespace 决定 R 类和 BuildConfig 的包路径。两者可以不同。

#### 方法二：彻底改包名（包含源码包路径）

如果要完全改变应用身份（包括源码包路径），需要更多修改：

**步骤 1：修改 namespace**

```groovy
// app/build.gradle 第 28 行
android {
    namespace = 'com.yourname.legado'  // 改为你的包名
}
```

**步骤 2：移动源码目录**

```powershell
# 将 Java/Kotlin 源码从 io/legado/app 移到 新包路径
# app/src/main/java/io/legado/app/ → app/src/main/java/com/yourname/legado/
# app/src/debug/java/io/legado/app/ → app/src/debug/java/com/yourname/legado/
```

**步骤 3：批量替换包名引用**

在所有 `.kt` 文件中：
- `package io.legado.app` → `package com.yourname.legado`
- `import io.legado.app` → `import com.yourname.legado`

在 `AndroidManifest.xml` 中：
- `android:name=".App"` 中的相对引用无需修改（相对路径自动跟随 namespace）
- 绝对引用如 `io.legado.app.xxx` 需手动替换

**步骤 4：更新 Firebase 配置**

修改 `app/google-services.json`，将所有 `package_name` 改为新包名：

```json
{
  "client_info": {
    "android_client_info": {
      "package_name": "com.yourname.legado.debug"
    }
  }
}
```

> 如果不需要 Firebase（崩溃统计、性能统计），可以移除 `google-services.json` 和 `app/build.gradle` 中的 `alias libs.plugins.google.services` 插件。

**步骤 5：更新 ProGuard 规则**

检查 `app/proguard-rules.pro` 中是否有硬编码的 `io.legado.app` 包名引用（如 `-keep class io.legado.app.**`），需要同步修改。

**步骤 6：同步修改子模块**

子模块 `modules/book/` 和 `modules/rhino/` 也有自己的 `build.gradle`，如果 `namespace` 引用了 `io.legado`，需同步修改。

### 7.4 修改包名后的注意事项

| 注意事项 | 说明 |
|---------|------|
| **数据迁移** | 包名变更后，新应用无法读取旧应用的数据，需手动备份恢复 |
| **WebDAV 同步** | Legado 支持 WebDAV 备份恢复，可用于数据迁移 |
| **书源兼容** | 书源 JSON 不依赖包名，可直接导入 |
| **签名一致性** | 使用同一签名密钥可实现覆盖安装（如果只改 applicationIdSuffix） |
| **Firebase** | 新包名需在 Firebase Console 中重新注册应用 |
| **已有用户** | 如果是公开发布，新包名无法覆盖旧包名的已安装应用 |

---

## 八、GitHub Actions 自动构建

> ⚠️ **本 fork 未实际启用 GitHub Actions 自动构建**：`.github/workflows/` 下的 5 个 workflow 均为上游 legado-E 遗产，push 触发已被注释（仅保留手动 workflow_dispatch 且实际不使用）。打包请走 `build-legado.bat` 或 gradlew 命令，本章仅作参考。

上游 legado-E 曾配置 CI/CD（`.github/workflows/release.yml`），支持自动构建和发布。

### 8.1 触发方式

手动触发（workflow_dispatch）：在 GitHub 仓库 Actions 页面点击 "Run workflow"。

### 8.2 前置配置

需在仓库 Settings → Secrets 中设置：

| Secret | 说明 |
|--------|------|
| `RELEASE_KEY_STORE` | JKS 签名文件的 base64 编码：`base64 -i legado_release.jks` |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥密码 |
| `RELEASE_STORE_PASSWORD` | 密钥库密码 |
| `ACTIONS_TOKEN` | 用于推送 APK 到 release 分支的 GitHub Token |

### 8.3 产出物

- `legado_miss_app_<version>.apk`：标准 Release 版（miss=默认包名）
- `legado_legacy_app_<version>.apk`：共存 Debug 版（legacy=原版包名）

---

## 九、快速开始（5 分钟上手）

### 9.1 Debug 构建快速流程（无需签名）

```powershell
# 1. 确认 JDK 17
java -version

# 2. 设置 SDK 路径
echo "sdk.dir=C:\\Users\\<你>\\AppData\\Local\\Android\\Sdk" > local.properties

# 3. 构建 Debug APK（无需签名配置，使用默认包名io.legado.miss.app）
.\gradlew assembleAppDebug --no-daemon

# 4. 找到 APK（文件名含包名标识miss）
ls app\build\outputs\apk\app\debug\legado_miss_app_*.apk

# 5. 安装到设备
adb install app\build\outputs\apk\app\debug\legado_miss_app_*.apk
```

> 也可直接双击 `build-legado.bat`，脚本自动处理环境变量和缓存。详见 [4.9 一键构建脚本](#49-一键构建脚本build-legadobat)。

### 9.2 Release 构建快速流程（需签名配置）

```powershell
# 1. 生成签名密钥（仅首次，放项目根目录，已 gitignore 不入 git）
keytool -genkeypair -v -keystore legado_release.jks -alias legado -keyalg RSA -keysize 2048 -validity 36500 -storepass <你的密码> -keypass <你的密码> -dname "CN=Legado, OU=Dev, O=Miss, L=CN, ST=CN, C=CN"

# 2. 在 local.properties 追加签名配置（不入 git）
# RELEASE_STORE_FILE=legado_release.jks
# RELEASE_STORE_PASSWORD=<你的密码>
# RELEASE_KEY_ALIAS=legado
# RELEASE_KEY_PASSWORD=<你的密码>

# 3. 构建 Release APK
.\gradlew assembleAppRelease

# 4. 验证签名（必须显示 v1/v2/v3 全部 true）
<SDK路径>\build-tools\36.0.0\apksigner.bat verify --verbose app\build\outputs\apk\app\release\legado_miss_app_*.apk

# 5. 验证桌面显示名（必须显示 application-label-zh:'阅读M'）
powershell -Command "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; & '<SDK路径>\build-tools\36.0.0\aapt.exe' dump badging app\build\outputs\apk\app\release\legado_miss_app_*.apk | Select-String application-label"

# 6. 卸载旧版本 + 安装新 APK
adb uninstall io.legado.miss.app.release
adb install -r app\build\outputs\apk\app\release\legado_miss_app_*.apk

# 7. 启动验证（必须显示 mResumedActivity 是 io.legado.miss.app.release）
adb shell am start -n io.legado.miss.app.release/io.legado.app.ui.main.MainActivity
adb shell dumpsys activity activities | findstr mResumedActivity
```

> **修改 build.gradle 签名配置或 strings.xml 后**，必须用 `.\gradlew assembleAppRelease --rerun-tasks` 强制重新打包，否则 Gradle 可能因缓存跳过打包导致 APK 未签名。

---

## 十、已验证环境说明（本项目实测）

> 以下环境已于 2026-06-28 实测验证，SDK 已安装到位。

### 10.1 当前环境配置

| 项目 | 路径/值 |
|------|--------|
| **项目目录** | `F:\myself\github\WeAgentChat\temp\legado` |
| **JDK 17** | `C:\Program Files\AdoptOpenJDK\jdk-17.0.0.20-hotspot` |
| **Android SDK** | `C:\Android\Sdk` |
| **local.properties** | `sdk.dir=C:\\Android\\Sdk` |
| **GRADLE_USER_HOME** | `F:\gh`（短路径，解决跨盘符和长路径问题） |
| **platforms** | android-36 (Android 15) |
| **build-tools** | 36.0.0 + 35.0.0（Gradle 自动补装） |
| **platform-tools** | adb.exe 等 |
| **国内镜像** | 阿里云+华为云已启用（见 4.7） |

### 10.2 在 PowerShell 中手动构建（系统终端或 Trae CN 终端均可）

> **沙盒限制已解除**：用户开通沙箱外权限后，可在 Trae CN 终端 PowerShell 直接构建（铁证：2026-07-29 三包在 Trae CN PowerShell 构建成功）。若遇 transforms move 失败，见 [10.4 已修复的构建问题](#104-已修复的构建问题)。

```powershell
# 1. 打开 PowerShell（系统终端 Win+R→powershell，或 Trae CN 内置终端均可）

# 2. 设置环境变量
$env:JAVA_HOME = "C:\Program Files\AdoptOpenJDK\jdk-17.0.0.20-hotspot"
$env:ANDROID_HOME = "C:\Android\Sdk"
$env:GRADLE_USER_HOME = "F:\gh"

# 3. 进入项目目录
cd F:\myself\github\WeAgentChat\temp\legado

# 4. 构建 Debug APK（测试包）
.\gradlew assembleAppDebug --no-daemon

# 5. 构建 Release APK（正式包，需签名配置）
.\gradlew assembleAppRelease --no-daemon

# 6. 构建共存包（自定义包名，⚠️ 必须用参数数组方式传递，见下方陷阱）
$args = @("assembleAppDebug","--no-daemon","-PcustomAppId=io.legado.app")
& .\gradlew.bat @args

# 7. 构建成功后，APK 位于
# 测试包：app\build\outputs\apk\app\debug\legado_miss_app_3.xx.xxxxxx.apk
# 正式包：app\build\outputs\apk\app\release\legado_miss_app_3.xx.xxxxxx.apk
# 共存包：app\build\outputs\apk\app\debug\legado_legacy_app_3.xx.xxxxxx.apk
```

> **PowerShell 参数传递陷阱**：直接传 `.\gradlew.bat assembleAppDebug -PcustomAppId=io.legado.app` 会被 PowerShell 截断为 `-PcustomAppId=io`（点号被解释为分隔符），导致 `Task '.legado.app' not found` 错误。**必须用参数数组方式**：`$args=@("assembleAppDebug","--no-daemon","-PcustomAppId=io.legado.app"); & .\gradlew.bat @args`（铁证：2026-07-29 共存包首次构建失败）。

### 10.3 使用一键构建脚本（推荐）

直接在系统 CMD 中双击 `build-legado.bat` 即可，脚本自动设置所有环境变量。详见 4.9 节。

### 10.4 已修复的构建问题

| 问题 | 原因 | 修复 |
|------|------|------|
| `For input string: ""` | `git rev-list HEAD --count` 返回空（无 Git 提交历史） | `app/build.gradle` 第 24 行改为 try-catch，默认 gitCommits=1 |
| Gradle transforms move 失败 | 多重因素：①杀毒软件锁定文件 ②沙盒限制 rename（已解除） ③transforms 缓存损坏 | 见下方[常见问题排查](#常见问题排查)章节 |
| KSP 跨盘符路径错误 | Gradle 缓存(C:) 和项目(F:) 在不同盘 | 设置 `GRADLE_USER_HOME=F:\gh` |
| 长路径 transforms 失败 | Windows 260 字符限制 | `GRADLE_USER_HOME` 用极短路径 `F:\gh` |
| Kotlin daemon AccessDeniedException | 残留临时文件锁 | 构建前删除 `%LOCALAPPDATA%\kotlin\daemon` |
| 首次构建卡在 3% | 国内网络直连 Google/Maven 慢 | 启用阿里云/华为云镜像（见 4.7） |
| PowerShell `-PcustomAppId=io.legado.app` 被截断 | PowerShell 把点号解释为分隔符，参数被拆分 | 用参数数组：`$args=@("assembleAppDebug","--no-daemon","-PcustomAppId=io.legado.app"); & .\gradlew.bat @args` |

---

## 十一、常见问题排查（2026-07-29 打包实战经验整理）

> **来源**：2026-07-29 APK 三包构建（测试包+正式包+共存包）实战中遇到的问题与解决方案。

### 11.1 Gradle transforms move 失败

**症状**：
```
Could not move temporary workspace (F:\gh\caches\8.14.4\transforms\xxx-yyy) to immutable location (F:\gh\caches\8.14.4\transforms\xxx)
```

**根因（多重因素）**：
1. **杀毒软件锁定文件**：360 安全卫士/Windows Defender 实时扫描锁定 transforms 临时 workspace 文件，导致 rename 时 AccessDeniedException
2. **沙盒限制 rename**：Trae CN 沙盒环境限制目录 rename 操作（**已解除**：用户开通沙箱外权限后可在 Trae CN 终端构建）
3. **transforms 缓存损坏**：缓存被中断后残留不完整文件，后续构建无法 rename

**解决方案（按优先级）**：
```powershell
# 1. 杀所有 java 进程（避免文件锁）
Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force

# 2. 清理 transforms 缓存（F盘和C盘都要清）
Remove-Item -Path "F:\gh\caches\8.14.4\transforms" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -Path "$env:USERPROFILE\.gradle\caches\8.14.4\transforms" -Recurse -Force -ErrorAction SilentlyContinue

# 3. 清理 Kotlin daemon 缓存
Remove-Item -Path "$env:LOCALAPPDATA\kotlin\daemon" -Recurse -Force -ErrorAction SilentlyContinue

# 4. 将 F:\gh 和项目目录添加到杀毒软件白名单（长期方案）

# 5. 重新构建
$env:JAVA_HOME = "C:\Program Files\AdoptOpenJDK\jdk-17.0.0.20-hotspot"
$env:ANDROID_HOME = "C:\Android\Sdk"
$env:GRADLE_USER_HOME = "F:\gh"
& .\gradlew.bat assembleAppDebug --no-daemon
```

**铁证**：2026-07-28 打包失败 2 小时（沙盒限制+杀毒软件锁定），2026-07-29 用户开通沙箱外权限+清除 F:\gh 缓存后三包构建全部成功。

### 11.2 PowerShell 参数传递陷阱（共存包构建）

**症状**：
```
Task '.legado.app' not found in root project 'legado' and its subprojects.
```
且日志显示 `Base applicationId: io`（被截断为 io，丢失 .legado.app）。

**根因**：PowerShell 调用原生命令（gradlew.bat）时，把 `-PcustomAppId=io.legado.app` 中的点号解释为分隔符，参数被拆分为 `-PcustomAppId=io` + `.legado.app`（后者被当成任务名）。

**解决方案**：用参数数组方式传递（PowerShell splatting）：
```powershell
# ❌ 错误：直接传递，参数被截断
& .\gradlew.bat assembleAppDebug --no-daemon -PcustomAppId=io.legado.app

# ✅ 正确：用参数数组
$args = @("assembleAppDebug","--no-daemon","-PcustomAppId=io.legado.app")
& .\gradlew.bat @args
```

**铁证**：2026-07-29 共存包首次构建失败（45秒后 Task not found），改用参数数组后 5m37s 构建成功。

### 11.3 沙盒限制解除说明

**历史铁律（已失效）**：
> "不能在 Trae CN 内置终端中构建！Gradle 的 transforms 缓存需要目录 move/rename 操作，被沙盒环境阻止。必须用系统原生终端。"

**当前状态**：用户开通沙箱外权限后，沙盒限制已解除，可在 Trae CN 终端 PowerShell 直接构建。

**验证铁证**：2026-07-29 三包在 Trae CN 终端 PowerShell 构建成功：
- 测试包：21m31s（assembleAppDebug）
- 正式包：19m34s（assembleAppRelease，含 R8 混淆）
- 共存包：5m37s（assembleAppDebug -PcustomAppId=io.legado.app）

**注意**：若用户未开通沙箱外权限，仍需在系统原生 PowerShell/CMD 中构建。开通方法见 Trae CN 设置。

### 11.4 Gradle 版本降级方案（废弃）

**背景**：2026-07-28 曾尝试将 Gradle 从 8.14.4 降级到 8.11.1 以规避 transforms 原子移动 bug。

**废弃原因**：
1. Gradle 8.11.1 下载不完整（F:\gh 中只有 .part 文件，无完整 zip）
2. 降级方案治标不治本，根因是沙盒限制+杀毒软件锁定，不是 Gradle 版本问题
3. 恢复到 8.14.4 + 清理 transforms 缓存 + 沙盒权限解除后，构建正常

**当前配置**：`gradle-wrapper.properties` 中 `distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.4-bin.zip`

### 11.5 APK 文件名规则（build.gradle 自动生成）

**文件名格式**：`{name}_{appIdSuffix}_{flavor}_{versionName}.apk`

| 包类型 | applicationId | appIdSuffix | 文件名示例 |
|--------|--------------|-------------|-----------|
| 测试包 | io.legado.miss.app.debug | miss | legado_miss_app_3.26.072908.apk |
| 正式包 | io.legado.miss.app.release | miss | legado_miss_app_3.26.072909.apk |
| 共存包 | io.legado.app.debug | legacy | legado_legacy_app_3.26.072909.apk |

> **注意**：共存包的 appIdSuffix 是 `legacy`（不是 `coexist`），由 `app/build.gradle` 中 `appId.startsWith("io.legado.app")` 判断决定。测试包和正式包的 appIdSuffix 都是 `miss`（因为 applicationId 都以 `io.legado.miss.app` 开头）。

> **输出目录隔离**：测试包和共存包都输出到 `app/build/outputs/apk/app/debug/`，后构建的会覆盖先构建的。`build-legado.bat` 会在构建完成后立即拷贝到 `output/apk/{test|coexist|release}/`，通过子目录隔离。

---

## 十二、参考链接

| 资源 | 地址 |
|------|------|
| Legado 源码 | https://github.com/gedoor/legado |
| Android Studio | https://developer.android.com/studio |
| JDK 17 (Adoptium) | https://adoptium.net/temurin/releases/?version=17 |
| Android SDK cmdline-tools | https://developer.android.com/studio#command-line-tools-only |
| keytool 文档 | https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html |
| Gradle Wrapper | 项目内置 `gradlew` / `gradlew.bat`，无需单独安装 |
