# issues-found（real-device-bugfix-0911 批次F，2026-09-11）

> 真机/模拟器验证过程发现的问题与待复测项登记。正向优化原则：只登记事实与复测路径，不做规避式"修复"。

## 待复测项（需真机/后续验证）

| # | 项 | 来源 | 状态 | 复测路径 |
|---|-----|------|------|---------|
| 1 | 视频嗅探 `window.__videoUrls__` GSON 解析失败×2 + sniff UNKNOWN×56 | 用户日志分析 | 疑站点改版非本地 bug | 真机对最新书源复测嗅探链 |
| 2 | BufferSpeed onLoadCompleted SLOW×245 | 用户日志分析 | 疑站点/网络带宽 | HostAccessStrategy 上线后对比统计 |
| 3 | MPEG4Writer UBSan mul-overflow SIGABRT（Android16/MIUI 平台库缺陷） | logcat 铁证 09-10 09:51 | 应用侧已规避（PTS 钳制+TsFallback 清理+ts 先行落库），平台缺陷无法根治 | 真机下载 HLS 长视频观察是否再 abort |
| 4 | MIUI SettingTrigger/TurboSchedMonitor 框架日志（52 条） | logcat 分析 | **非本仓代码无法治理** | 测试日志提取脚本 tag 黑名单过滤 |
| 5 | 视频嗅探 403×15/404×7（站点侧资源缺失/防盗链） | appLog 分析 | 站点侧问题 | 不修 |
| 6 | Cronet 降级-恢复机制观察结论 | 设计登记后续 | 观察项 | 真机导出日志统计降级/恢复次数与访问成功率 |

## 登记后续（架构预留，非本批实施）

1. 高亮跨段对话支持（首版保留 \n 排除防吞段误标；根治=分块扫描+配对栈，引擎级改造）
2. 高亮"长度上限"规则级配置评估结论输出（超 400 字对话当前优雅不命中）
3. Cronet per-host 迁移（HostAccessStrategy 阶段2：certErrorCache/degradedForSession/lastFailedHostHint 收编，≈重写规模）
4. AI 角色绑定链 characterId 透传（依赖段落坐标系验证）
5. 脚本/HTTP 声源进逐段 multiRole 合成链（当前门禁降级默认音+面板提示）

## 环境限制（模拟器 TTS 联调）

- **MEmu 精简 ROM 无 `texttospeech` 系统服务**（dumpsys Can't find service）→ 系统引擎音色枚举/朗读推进的模拟器功能级验证受限（l2_verify_tts_engine/read 的 UI 深层步骤 verdict=manual）
- CloneTTS v0.7.0 APK（287MB）已确认官方渠道，模拟器无 TTS 服务注册前提不成立 → **TTS 功能级联调移交用户真机**（L3，覆盖 6.5 清单④⑤⑩⑪⑫）；CloneTTS APK 已下载至 output/CloneTTS-V0.7.0.apk 供真机侧使用
- 模拟器书架空 + 无本地书 → 朗读推进脚本 STEP1 manual（历史已知环境状态）
- MEmu WebView 分项 0 字节时清理弹窗不显示（CacheActivity filter bytes>0 既有设计），真机有数据即见
