# Intent 与深度链接体系

## 1. 概述

Legado 的 Intent 体系由以下核心组件构成：

| 组件 | 职责 | 源文件 |
|------|------|--------|
| Deep Link 注册 | AndroidManifest 声明 scheme/host/pathPattern | AndroidManifest.xml |
| URL Scheme 处理 | legado:// 和 yuedu:// 协议解析与分发 | OnLineImportActivity.kt |
| 文件关联 | mimeType + pathPattern 匹配打开书籍/配置 | FileAssociationActivity.kt |
| 分享接收 | ACTION_SEND / ACTION_PROCESS_TEXT 文本处理 | SharedReceiverActivity.kt |
| Launcher 入口 | 8 个 LAUNCHER Activity（1默认+7备选图标） | WelcomeActivity.kt |
| 自定义 Action | Service 间通信的 IntentAction 常量 | IntentAction.kt |
| 大对象传递 | 静态 Map 中转，避免 TransactionTooLargeException | IntentData.kt |
| 快捷方式 | 桌面动态快捷方式（书架/上次阅读/朗读） | ShortCuts.kt（位于 api/） |
| Intent 辅助 | 浏览器跳转、TTS 设置、安装未知应用 | IntentHelp.kt |
| ContentProvider API | 跨应用数据交换（书源/书籍增删改查） | ReaderProvider.kt |

架构数据流：

```
外部触发                          Legado 内部处理
---------                        ---------------
legado://import/bookSource?src=URL --> OnLineImportActivity --> ImportBookSourceDialog
yuedu://import/bookSource?src=URL  --> OnLineImportActivity --> (同上)
打开 .txt/.epub 文件              --> FileAssociationActivity --> importBook / importJson
分享文本到 Legado                 --> SharedReceiverActivity --> SearchActivity / MainActivity
浏览器打开 URL                    --> IntentHelp.getBrowserIntent()
JS 脚本 openUrl("legado://...")  --> JsExtensions.openUrl() --> OnLineImportActivity
其他应用 ContentProvider 调用      --> ReaderProvider --> BookController / BookSourceController
```
