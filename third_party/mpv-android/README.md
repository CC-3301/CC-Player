# mpv-android 产物来源

应用源码与预编译库取自 [mpv-android](https://github.com/mpv-android/mpv-android)（MIT，见 `LICENSE`）。

## 二进制

- 来源：release tag `2026-09-17` 的 `app-default-arm64-v8a-release.apk` 中 `lib/arm64-v8a/` 下的全部 `.so`
- 位置：`app/src/main/jniLibs/arm64-v8a/`
- ABI：仅 arm64-v8a
- libmpv 版本：`mpv v0.41.0`（libass 静态链入 libmpv.so）

## 源码

- `app/src/main/java/is/xyz/mpv/MPVLib.kt`：官方 `MPVLib.kt` 原样复制，JNI 方法名必须与 `libplayer.so` 的符号一致
- `app/src/main/java/com/cc3301/ccplayer/ui/MpvSurfaceView.kt`：按官方 `BaseMPVView.kt` 的 Surface 挂载顺序改写；播放画面由 `MPVLib.attachSurface` 交给 libmpv 渲染，即官方 `MPVView` 的渲染职责
