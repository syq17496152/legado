# proguard-debug-rules.pro（2026-09-15 发布省流：测试包/共存包对齐正式包打包模式）
# 目标：debug 也开 R8 裁剪（minify+shrinkResources）缩小体积，但保留类名/方法名原名（堆栈可读、无需 mapping）
# -dontobfuscate：禁用混淆重命名（用户裁决"不要混淆"）
# -dontoptimize：禁用 R8 优化器内联（debug 重视与源码行为/堆栈一致性，仅做 tree-shaking 裁剪）
-dontobfuscate
-dontoptimize
