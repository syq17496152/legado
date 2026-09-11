# 架构模式规范

> 基于 Legado 项目源码深度分析提取的项目特有架构约定，AI Agent 必须遵循。

---

## 依赖注入 — 无框架，手动单例 + 全局 lazy

项目**不使用** Hilt/Dagger/Koin 等任何 DI 框架。

DI 方式为手动管理：

| 组件 | 实现方式 | 示例 |
|------|----------|------|
| 数据库 | 全局 `lazy` 单例 | `val appDb by lazy { Room.databaseBuilder(...).build() }` |
| 全局配置 | `object` 单例 | `AppConfig`, `ReadBookConfig`, `ThemeConfig` |
| 业务模型 | `object` 单例 | `ReadBook`, `WebBook`, `AudioPlay` |
| 网络客户端 | 顶层 `lazy` 属性 | `val okHttpClient by lazy { ... }` |

**规则**：新增全局组件使用 `object` 单例或顶层 `by lazy`，不引入 DI 框架。

## ViewModel 模式

- 基类：`BaseViewModel(application: Application) : AndroidViewModel(application)`
- 提供 `execute()`, `executeLazy()`, `submit()` 三个协程封装方法
- 实例化：**不使用** ViewModelProvider.Factory，子类直接创建
- Activity 基类：`VMBaseActivity<VB : ViewBinding, VM : ViewModel>`
- Fragment 基类：`VMBaseFragment<VM : ViewModel>`

## 数据库层

- Room 版本以 `AppDatabase.kt` 的 `version` 字段为准（**文档禁止硬编码快照版本号**。实证：本条原写"撰写时为 108"，2026-09-11 核实 `AppDatabase.kt` 实际已到 110，漂移 2 个版本）
- **允许主线程查询**：`allowMainThreadQueries()` — Glide 图片加载等上下文中必需，保持启用
- `room.generateKotlin=false`：生成 Java 而非 Kotlin
- Schema 导出：`app/schemas/`
- AutoMigration：v43+ 使用 AutoMigration，v1-v9 使用 `fallbackToDestructiveMigrationFrom`

## Web 服务器

- NanoHTTPD 嵌入式服务器，默认端口 1122
- 路由方式：纯 `when(uri)` 手动路由分发，无注解/反射框架
- POST 请求使用 `runBlocking` 执行（在 NanoHTTPD 工作线程中）
- 大列表优化：数据超过 3000 条使用 `okio.Pipe` 流式传输

## 事件系统 — 三种机制并存

| 机制 | 用途 | 使用场景 |
|------|------|----------|
| LiveEventBus | 跨组件/跨页面事件通信 | 书架更新、朗读状态、下载进度等 |
| MutableLiveData | ViewModel → View 单向推送 | contentLiveData, loadErrorLiveData |
| MutableStateFlow | 少量状态 | SearchModel.workingState |

**事件标签**：定义在 `constant/EventBus.kt`，共 38 个常量。

**LiveData 扩展**：
- `sendValue()`：通过主线程 Handler 设置 value（区别于 postValue 的合并行为）
- `ConflateLiveData`：合并发送，指定时间间隔内只发送最新数据

## 模块依赖

```
:app  -->  :modules:book    (规则解析引擎)
:app  -->  :modules:rhino   (JS 执行引擎)
:modules:book 和 :modules:rhino 无互相依赖
modules/web 是独立 Vue3 前端项目，非 Gradle 模块
```

## 构建特殊配置

| 配置 | 值 | 说明 |
|------|-----|------|
| `configuration-cache` | false | 启用会导致无法升级 app 版本 |
| `room.generateKotlin` | false | 生成 Java 而非 Kotlin |
| `allowMainThreadQueries` | true | Glide 图片加载等上下文中必需，保持启用 |
| Vue3 sync.js | 仅 GitHub Actions | 本地开发需手动复制构建产物 |
| `nonTransitiveRClass` | true | 非传递 R 类 |
| `coreLibraryDesugaring` | 启用 | 支持低 API 设备使用新 Java API |
