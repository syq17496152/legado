# 异常处理规范

> 基于 Legado 项目源码深度分析提取的项目特有异常处理约定。

---

## 自定义异常体系

所有业务异常继承 `NoStackTraceException`（覆写 `fillInStackTrace()` 返回空堆栈，避免性能开销）：

| 异常类 | 用途 | 文件 |
|--------|------|------|
| `NoStackTraceException` | 基类 | `exception/NoStackTraceException.kt` |
| `TocEmptyException` | 目录为空 | `exception/TocEmptyException.kt` |
| `ContentEmptyException` | 正文内容为空 | `exception/ContentEmptyException.kt` |
| `EmptyFileException` | 文件为空 | `exception/EmptyFileException.kt` |
| `RegexTimeoutException` | 正则执行超时 | `exception/RegexTimeoutException.kt` |
| `NoBooksDirException` | 书籍目录不存在 | `exception/NoBooksDirException.kt` |
| `InvalidBooksDirException` | 书籍目录无效 | `exception/InvalidBooksDirException.kt` |
| `ConcurrentException` | 并发限制 | `exception/ConcurrentException.kt` |
| `ActivelyCancelException` | 主动取消协程 | `help/coroutine/ActivelyCancelException.kt` |
| `WebDavException` | WebDAV 异常 | `lib/webdav/WebDavException.kt` |

**新增异常规则**：必须继承 `NoStackTraceException`，覆写 `fillInStackTrace()`。

> **注意（历史遗留，2026-09-11 补充闭环）**：`WebDavException` 直接继承 `Exception` 而非 `NoStackTraceException`，这是**已知的历史例外**，原因是它位于独立 lib 模块 `lib/webdav/`。
> ❌ **新代码禁止以此为效仿先例** —— 凡在 `app` 主模块内新增业务异常，一律继承 `NoStackTraceException` 并覆写 `fillInStackTrace()`。`lib/` 下的独立模块是否统一收编，待专项评估，在此之前不得新增同类例外。

## 四种异常捕获模式

### 模式 A：runCatching + 链式处理（网络书操作首选）

```kotlin
return kotlin.runCatching {
    analyzeUrl.getStrResponseAwait().let { ... }
}.onFailure {
    AppLog.put("执行规则失败", it)
}
```

### 模式 B：try-catch + 单字段容错（解析操作首选）

```kotlin
try {
    searchBook.kind = analyzeRule.getStringList(ruleKind)?.joinToString(",")
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    Debug.log(bookSource.bookSourceUrl, "└${e.localizedMessage}", log)
}
```

### 模式 C：Flow .catch 操作符（UI 层 Room Flow）

```kotlin
}.catch {
    AppLog.put("更新数据出错", it)
}
```

### 模式 D：Coroutine 链式 onError（ViewModel 层）

```kotlin
Coroutine.async {
    // 异步操作
}.onError {
    AppLog.put("操作失败", it)
}.onSuccess {
    // 成功处理
}
```

## 网络错误处理链

OkHttp 拦截器链自动处理：
1. `OkHttpExceptionInterceptor`：非 IOException 包装为 IOException
2. `DecompressInterceptor`：透明 gzip/deflate 解压
3. `OkhttpUncaughtExceptionHandler`：兜底 OkHttp 线程池未捕获异常

## 协程异常

- **不使用** `CoroutineExceptionHandler`
- 通过自定义 `Coroutine` 类的 `onError` 回调处理
- `ActivelyCancelException` 继承 `CancellationException`，区分主动取消
- 长时间循环操作中必须调用 `ensureActive()` 检查取消状态
