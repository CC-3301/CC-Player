# libmpv 用 JNI 注册回调，native 方法名不可混淆。
-keep class `is`.xyz.mpv.MPVLib { *; }
