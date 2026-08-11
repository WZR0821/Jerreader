# 本机构建环境

这台机器上有两个必须显式指定的东西，两个都不指定就会以看起来无关的错误失败。

```bash
# JDK 17，注意要带 Contents/Home
export JAVA_HOME="<JDK 17 路径>/Contents/Home"

# 当 xcode-select 指向 CommandLineTools 时，显式指定 Xcode。
# 不设这个，Kotlin/Native 链接会报 "An error occurred during an xcrun execution"。
export DEVELOPER_DIR="<Xcode.app 路径>/Contents/Developer"
```

Android SDK 由 `ANDROID_HOME` 或不入库的 `local.properties` 提供；JDK 不随仓库分发。

## 常用命令

```bash
./gradlew :core:testAndroidHostTest :core:iosX64Test   # 公共算法，两端各 54 例
./gradlew :androidApp:assembleDebug                    # APK
./gradlew :core:linkDebugFrameworkIosX64               # Intel Mac 模拟器用的 core framework
./gradlew :ui:linkDebugFrameworkIosSimulatorArm64      # Compose overlay（仅 arm64）
```

## 这台机器是 Intel Mac

`uname -m` 是 `x86_64`。由此产生两个约束：

1. 模拟器切片必须是 `iosX64`。`iosSimulatorArm64` 的产物在这里跑不起来，会报
   `Bad CPU type in executable`。
2. Compose Multiplatform 1.11 不再发布 `iosX64` 产物，所以 `:ui` 的 iOS 侧在这台机器上
   只能交叉编译、无法运行。`:core` 没有这个限制——阅读器所有值得测的规则都在 `core` 里。

## 一个会骗人的绿灯

`./gradlew :core:iosSimulatorArm64Test` 在这台机器上会输出 **BUILD SUCCESSFUL**，但一个用例
都没跑：KGP 发现架构不匹配后把任务的 `enabled` 设成了 false，Gradle 只在 `-i` 日志里写一行
`Skipping task ... as task onlyIf 'Task is enabled' is false`。

验收时看用例数，别只看构建结果：

```bash
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET, collections
agg = collections.defaultdict(lambda: [0, 0])
for p in glob.glob("core/build/test-results/**/*.xml", recursive=True):
    r = ET.parse(p).getroot()
    task = p.split("test-results/")[1].split("/")[0]
    agg[task][0] += int(r.get("tests", 0))
    agg[task][1] += int(r.get("failures", 0)) + int(r.get("errors", 0))
for k, (t, f) in sorted(agg.items()):
    print(f"{k}: {t} tests, {f} failed")
PY
```
