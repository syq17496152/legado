# 更新日志
欢迎关注公众号[阅读Plus]即时了解软件更新资讯  
<img src="https://open.weixin.qq.com/qr/code?username=legado_plus" width="200">

## cronet版本: 128.0.6613.40

**2026/07/05**
- 依赖升级性能优化：Kotlin协程Channel性能提升9.8倍、5组AndroidX组件升级
- 最低系统版本提升至Android 6.0（原4.4），解锁AndroidX生态新版本
- WebView滚动流畅度优化：启用硬件加速渲染层
- OkHttp升级至5.4.0：消除4处内部API依赖，提升HTTP安全性
- 全面修复Room数据库主线程访问崩溃（20+处调用点迁移至IO线程）
- 排除Compose运行时传递依赖，DEX体积减少21%（约15MB）
- 清理9处已废弃的版本兼容代码，提升代码可维护性
- 核心质量优化：修复内存泄漏、线程安全、数据库ANR等重大问题
- 修复 IntentData 内存泄漏：改用 ConcurrentHashMap + TTL 自动清理 + Activity 作用域关联
- 修复 ReadBook.callBack 泄漏：改用 WeakReference 防止 Activity 无法回收
- 修复 WebViewPool 泄漏：destroy 失败重试机制、统一 ReentrantLock 锁策略
- 消除数据库主线程查询：移除 allowMainThreadQueries()，所有 DAO 调用迁移至 IO 线程
- 线程安全优化：ReadBook/RecyclerAdapter/WebViewPool 改用并发集合+ReentrantLock 替代 @Synchronized
- 修复 LifecycleHelp ConcurrentModificationException：遍历中删除改为 removeAll
- 修复 Cronet TODO() 崩溃：替换为 UnsupportedOperationException
- 统一错误日志：27处 e.printStackTrace() 替换为 AppLog.put
- 补充空 catch 块注释说明
- 新增核心模块单元测试：规则解析器（CSS/JSONPath/Regex）12 用例、DAO 5 用例
- 修复 MigrationTest：填充完整迁移链数组

**2026/07/04**
- 修复新主题未显示的问题：升级版本号触发主题合并逻辑，确保已有用户也能看到新增主题
- 新增8个内置主题：绿意、莫兰迪、海洋、薰衣草、琥珀（日间），暗夜绿、暗夜蓝、暗夜紫（夜间）
- 同步阅读Sigma最新提交：修复WebDAV同步循环提前退出、预设布局切换后翻页动画不刷新
- 更新内置订阅源：源仓库CSS选择器适配、导入源域名迁移
- 搜索线程默认值调整为32、预下载默认值调整为2
- UI/UX 系统性优化：修复暗色模式下文字不可见、WCAG 对比度不达标等11项P0问题
- 新增 Design Token 体系：圆角(4级)、排版(12级)、间距(6级)、阴影(6级)
- 补全暗色模式颜色覆盖：highlight/error/success/lightBlue
- 统一圆角规范：Card/Dialog 12dp、Popup 8dp，新增 shape_corner 四级 Drawable
- 修正图标体系：viewport/dp尺寸归一化、fillColor 统一为 tint 着色
- 触控目标合规化：26+ 处操作图标/按钮从 24~36dp 提升至 48dp
- BottomNav 高度 50→56dp、FAB elevation 2→6dp、封面加载添加 crossfade 过渡
- 搜索 Activity 添加 windowSoftInputMode 适配键盘
- 自定义 Toast 适配 Android 11+ 禁令

**2026/03/07**
- 优化代码，修复问题

**2026/03/03**
- 优化代码，修复问题
- 视频悬浮窗播放时进行系统媒体播放通知
- 净化规则使用js时支持调用java.log
- 代码编辑器搜索替换内容支持$符号
- 优化书架滚动位置记忆
- 增加搜索结果排序时对书籍分类信息进行判断
- 增加自动检查app更新功能

**2026/02/16**
- 优化代码，修复问题
- 让小说朗读走系统媒体播放通道
- 更新内置字典规则
- 新增java.refreshBookToc函数
- java.reLoginView函数增加deltaUp参数
- 新增@webjs:规则类型
- 文件类书源支持下载链接type指定文件后缀
- 提升购买按钮权限

**2026/01/31**
- 优化代码，修复一些问题
- 正文增加锁定反向横屏

**2026/01/28**
- 新增java.reLoginView()函数，刷新登录界面
- 书源发现支持更多丰富的按钮类型
- 新增java.refreshExplore()函数
- java.open函数支持打开登录界面
- 书源简介支持html标签包裹，显示html样式
- 书籍简介和字典支持gif动态图和svg图data链接
- 书籍简介和字典支持button按钮
- 支持源控制图片显示尺寸
- 书籍简介支持markdown语法编写
- 新增java.showBrowser函数，能进行半屏显示段评
- 支持图片链接click键，不推荐继续使用旧方式
- 支持双击响应段评图
- 新增chapter.update()函数
- 新增java.showPhoto函数
- 新增java.refreshContent()函数
- 支持订阅源启动页html用js返回空
- 提升webview函数获取js结果速度
- 其余优化与修复

**2026/01/13**
- 软件自定义背景图支持.9.png格式
- 背景图导入支持直接输入图片在线链接
- 主题分享支持在线背景图链接
- 背景图支持跟随主题切换
- 主题设置支持透明操作栏，提升图片背景视觉效果
- 支持分组封面自定义图片恢复默认
- 登录UI的select类型支持action键
- 提升内置浏览器打开速度（例：订阅源、段评 打开速度大概快100毫秒左右）
- 支持正文下划线设为虚线类型
- cache.get函数新增onlyDisk参数
- tts源支持jslib规则
- tts源登录界面新增java.clearTtsCache()函数
- 支持导出单个tts源
- 编辑tts源、字典规则、TXT目录规则时误触空白区域会提示保存
- 新增正文边缘点击阈值设置，防止曲面屏误触
- 实现订阅源的登录检查规则
- 在链接访问出错时，也能执行一次登录检查规则
- StrResponse对象支持callTime()获取响应时间
- 并发访问函数支持skipRateLimit参数，绕过源并发率限制
- 视频播放器支持记录函数调用时的播放进度
- 其余细节优化与bug修复


## **必读**
来源于fork仓库 [Luoyacheng/legado](https://github.com/Luoyacheng/legado)　  
[查看实时详细日志](https://gitee.com/lyc486/legado/commits/main)　 

【温馨提醒】 *更新前一定要做好备份，以免数据丢失！*  
* 阅读只是一个转码工具，不提供内容，第一次安装app，需要自己手动导入书源。
* 正文出现缺字漏字、内容缺失、排版错乱等情况，有可能是净化规则或简繁转换出现问题。
----

* [2025年日志](https://github.com/Luoyacheng/legado/blob/record2025/app/src/main/assets/updateLog.md)　
* [2023年日志](https://github.com/gedoor/legado/blob/record2023/app/src/main/assets/updateLog.md)　
* [2022年日志](https://github.com/gedoor/legado/blob/record2022/app/src/main/assets/updateLog.md)　
* [2021年日志](https://github.com/gedoor/legado/blob/record2021/app/src/main/assets/updateLog.md)　
