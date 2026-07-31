# Android 构建与交付

## 环境

- macOS 或 Linux
- JDK 17
- Android SDK Platform 36
- Android SDK Build-Tools 36.0.0
- 可访问 Google Maven、Maven Central 和 JitPack 的首次依赖下载环境

Android 运行时不依赖 Google Play Services。JitPack 只在构建期用于 Readium PDFium 的两项原生依赖。

## M2 可安装 APK

执行：

```sh
scripts/package_android_m2.sh
```

脚本会先运行共享层测试、Android 单元测试和 APK 构建，然后生成：

- `dist/Jerreader-Android-M2-installable.apk`
- `dist/Jerreader-Android-M2-installable.apk.sha256`

M2 包使用 Android SDK 自动生成的 debug 证书签名，可直接侧载安装，但不是商店发布包。后续 release APK/AAB 需要 Jerreader 专用签名库，签名库、密码和证书指纹不进 Git。历史 M0 脚本仅用于重现工程骨架产物。

## 设备级 Readium 回归

启动一台 API 23 或以上的 Android 设备/模拟器后执行：

```sh
./gradlew :androidApp:connectedDebugAndroidTest
```

该测试在设备内生成无版权内容的 EPUB，覆盖 M1 导入、去重、Room 持久化、删除，以及 M2 目录跳转、主题/字号和 Locator 跨会话恢复。测试同时断言源 EPUB 和 App 内 EPUB 副本的 SHA-256 和修改时间不变。

## iOS 交付边界

现有 iOS 源码、Xcode 工程和 target 目前冻结，Android M1/M2 不修改、不嵌入共享 framework、不替换任何 iOS 产物。待 Android 和共享层验证通过并获得明确批准后，再开始 iOS 接入；苹果交付物仍为未签名 `.ipa`，不交付已签名 IPA。
