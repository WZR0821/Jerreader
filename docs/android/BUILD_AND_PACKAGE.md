# Android 构建与交付

## 环境

- macOS 或 Linux
- JDK 17
- Android SDK Platform 36
- Android SDK Build-Tools 36.0.0
- 可访问 Google Maven、Maven Central 和 JitPack 的首次依赖下载环境

Android 运行时不依赖 Google Play Services。JitPack 只在构建期用于 Readium PDFium 的两项原生依赖。

## 当前功能对齐版可安装 APK

执行：

```sh
scripts/package_android_parity.sh
```

脚本会先清理 iCloud 在 build 目录里留下的重复文件，再运行共享层测试、Android 单元测试、lint 和 APK 构建，然后生成：

- `dist/Android/Jerreader-Android-1.0.2-parity.apk`
- `dist/Android/Jerreader-Android-1.0.2-parity.apk.sha256`

该包使用 Android SDK 自动生成的 debug 证书签名，可直接侧载，但不是商店发布包。

## 历史 M4 点按查词阶段可安装 APK

执行：

```sh
scripts/package_android_m4_tap.sh
```

脚本会运行共享层 Android Host 测试、Android 单元测试、lint 和 APK 构建，然后生成：

- `dist/Android/Jerreader-Android-M4-TapLookup-installable.apk`
- `dist/Android/Jerreader-Android-M4-TapLookup-installable.apk.sha256`

设备/模拟器级的 Readium 点按回归需按下文单独运行，因为打包脚本不能假设构建机已连接 Android 设备。

## 历史 M2 APK

执行：

```sh
scripts/package_android_m2.sh
```

脚本会先运行共享层测试、Android 单元测试和 APK 构建，然后生成：

- `dist/Android/Jerreader-Android-M2-installable.apk`
- `dist/Android/Jerreader-Android-M2-installable.apk.sha256`

M2 包使用 Android SDK 自动生成的 debug 证书签名，可直接侧载安装，但不是商店发布包。后续 release APK/AAB 需要 Jerreader 专用签名库，签名库、密码和证书指纹不进 Git。历史 M0 脚本仅用于重现工程骨架产物。

## 设备级 Readium 回归

启动一台 API 23 或以上的 Android 设备/模拟器后执行：

```sh
./gradlew :androidApp:connectedDebugAndroidTest
```

该测试在设备内生成无版权内容的 EPUB，覆盖 M1 导入、去重、Room 持久化、删除，M2 目录跳转、主题/字号和 Locator 跨会话恢复，以及 M4 英语/日语真实点按命中、基本形和中文释义卡。测试同时断言源 EPUB 和 App 内 EPUB 副本的 SHA-256 和修改时间不变。真实词典适配器使用注入的 Stub 响应测试，不访问外网。

## iOS 交付边界

现有 iOS 源码、Xcode 工程和 target 目前冻结。本阶段不修改、不嵌入共享 framework、不替换任何 iOS 产物。待 Android 和共享层验证通过并获得明确批准后，再开始 iOS 接入；苹果交付物仍为未签名 `.ipa`，不交付已签名 IPA。
