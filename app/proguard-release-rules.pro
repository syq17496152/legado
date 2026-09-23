# proguard-release-rules.pro（2026-09-15 自 proguard-rules.pro 剪切，仅 release 引用）
# 原属共用规则的 Log 移除段：双包优化后 debug 包不再共用此段——
# 调试日志铁律（用户裁决）：测试包 DebugLog/AppLog 必须完整保留供真机排障，
# 仅正式包移除 android.util.Log 全等级打印（AppLog 自有实现不受影响，正式包日志页可用）

# 移除Log类打印各个等级日志的代码，打正式包的时候可以做为禁log使用
# 记得proguard-android.txt中一定不要加-dontoptimize才起作用
# 另外的一种实现方案是通过BuildConfig.DEBUG的变量来控制
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}
