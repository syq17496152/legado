# proguard-debug-rules.pro（2026-09-15 三包优化：测试包/共存包对齐正式包裁剪深度）
# debug proguardFiles 已换 proguard-android-optimize.txt（与 release 同款优化器，实测砍掉 14.2MB dex）
# 本文件仅保留混淆红线：历史 R8 混淆重命名导致正式包大量未知错误（用户裁决），debug 同样禁止混淆
# 调试日志铁律：BuildConfig.DEBUG=true 分支可达，DebugLog/AppLog 调用与字符串由 R8 可达性天然保留，禁止裁剪
-dontobfuscate
