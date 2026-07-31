# Android 构建与交付

## 环境

- macOS 或 Linux
- JDK 17
- Android SDK Platform 36
- Android SDK Build-Tools 36.0.0
- 可访问 Google Maven、Maven Central 和 JitPack 的首次依赖下载环境

Android 运行时不依赖 Google Play Services。JitPack 只在构建期用于 Readium PDFium 的两项原生依赖。

## M0 可安装 APK

执行：

```sh
scripts/package_android_m0.sh
```

脚本会先运行共享层测试、Android 单元测试和 APK 构建，然后生成：

- `dist/Jerreader-Android-M0-installable.apk`
- `dist/Jerreader-Android-M0-installable.apk.sha256`

M0 包使用 Android SDK 自动生成的 debug 证书签名，可直接侧载安装，但不是商店发布包。后续 release APK/AAB 需要 Jerreader 专用签名库，签名库、密码和证书指纹不进 Git。

## 设备级 Readium 回归

启动一台 API 23 或以上的 Android 设备/模拟器后执行：

```sh
./gradlew :androidApp:connectedDebugAndroidTest
```

该测试在设备内生成无版权内容的最小 EPUB，覆盖 Readium 解析器打开/关闭，以及 `ReaderActivity` 挂载 EPUB Navigator 和销毁的完整生命周期。两条路径都会断言输入 EPUB 的 SHA-256 和修改时间不变。

## iOS 交付边界

现有 iOS 源码、Xcode 工程和 target 目前冻结，Android M0 不修改、不嵌入共享 framework、不替换任何 iOS 产物。待 Android 和共享层验证通过并获得明确批准后，再开始 iOS 接入；苹果交付物仍为未签名 `.ipa`，不交付已签名 IPA。
