# wear-apk-swipe-patcher

> ### ⚠️ AI 生成声明
>
> **本项目的全部代码、脚本与文档均由 AI 编写，没有一行是人工逐字敲出来的。**
> 详细的参与方式与边界见文末「[作者与 AI 声明](#作者与-ai-声明)」。
>
> 使用前请自行阅读「[已知局限](#已知局限)」——尤其是**重新签名会改变应用签名**、
> 以及**真机验证尚未完成**这两点。

把任意 APK 的「右滑返回」关掉，并在**手机上**重新签名输出一个可直接安装的 APK。
全程没有 PC 参与：不需要 aapt2 / zipalign / apksigner / apktool 等外部可执行文件。

## 它到底做了什么

纯资源层修改，**不碰 dex、不碰 so、不碰任何 res 文件**：

```xml
<!-- 1. 在应用自己的资源包里新增一个主题，继承原主题 -->
<style name="WearNoSwipeUnityTheme" parent="@style/UnityThemeSelector">
    <item name="android:windowSwipeToDismiss">false</item>
</style>

<!-- 2. 让启动 Activity 使用这个主题 -->
<activity android:name="..."
          android:theme="@style/WearNoSwipeUnityTheme" />
```

二进制层面只改两个文件：`AndroidManifest.xml`（二进制 XML）与 `resources.arsc`。

## 为什么能跑在手机上

| 需要的能力 | 用的方案 | 是否纯 Java |
|---|---|---|
| 读写 `resources.arsc`、二进制 XML | ARSCLib 1.4.0 | 是（class 全部 major 52） |
| 解析框架属性（如 `android:windowSwipeToDismiss`） | ARSCLib 自带 android-23…36 framework APK，离线解析 | 是 |
| zipalign | `com.reandroid.archive.ZipAlign.alignApk(File,File)` | 是 |
| APK 签名（V1/V2/V3） | Google apksig 8.7.0 | 是 |
| 生成签名证书 | 本项目 `core.crypto` 手写 DER 自签名证书 | 是 |

`android:windowSwipeToDismiss` 的框架属性 ID 是 **`0x010103f3`**
（与相邻的 `banner=0x010103f2`、`isGame=0x010103f4` 一致，已用框架资源包实测确认）。

## 快速开始

### 环境要求

| 用途 | 需要 |
|---|---|
| 只编译 / 测试 core（`./build.sh`） | **JDK 17+**（`javac --release 8`）。没有任何网络依赖，两个第三方 jar 已随仓库提供 |
| 构建 App APK —— 手工路径（`./build-apk.sh`） | 上面的 JDK，外加 Android SDK 的 `build-tools`（`aapt2`、`d8`）与 `platforms/android-36/android.jar` |
| 构建 App APK —— Gradle 路径（`./gradlew assembleDebug`） | Android SDK + 能访问 Google Maven（首次会下载 Gradle 9.8.0 与 AGP 9.4.1） |

Android SDK 位置通过 `local.properties` 里的 `sdk.dir` 或环境变量 `ANDROID_HOME` 指定
（`local.properties` 是**本机私有文件，已 gitignore**）。

```bash
./build.sh                 # 编译 core -> build/core.jar
./build.sh test app.apk    # 编译 + 64 项自检 + 端到端补丁 + 差异对比 + 对齐检查
```

### 命令行单独使用

```bash
CP="libs/ARSCLib-1.4.0.jar:libs/apksig-8.7.0.jar:build/classes"

java -cp "$CP" dev.PatcherCli inspect app.apk
java -cp "$CP" dev.PatcherCli patch   app.apk out.apk
java -cp "$CP" dev.PatcherCli patch   app.apk out.apk --parent UnityThemeSelector
java -cp "$CP" dev.PatcherCli patch   app.apk out.apk --scheme v1     # 只签 V1，不写 v2 签名块
java -cp "$CP" dev.PatcherCli patch   app.apk out.apk --scheme v2     # 只签 V2，不新增 META-INF
java -cp "$CP" dev.PatcherCli verify  out.apk
java -cp "$CP" dev.PatcherCli verify  out.apk --scheme v2 --min-sdk 24
```

`patch` 可用选项：

| 选项 | 作用 |
|---|---|
| `--style NAME` | 补丁主题名，默认 `WearNoSwipeUnityTheme` |
| `--parent STYLE` | 强制父主题（如 `UnityThemeSelector`、`@android:style/Theme`） |
| `--all-activities` | 给清单里所有 Activity 都打补丁（默认只改启动 Activity） |
| `--application` | 也给 `<application>` 打补丁 |
| `--no-auto-parent` | 关闭父主题自动探测 |
| `--no-align` / `--no-verify` | 跳过 zipalign / 签名后校验 |
| `--scheme v1\|v2\|v1+v2` | 输出签名方案，默认 `v1+v2`（见下） |
| `--keystore … --storepass … --alias … --keypass …` | 用自带密钥库签名；不给则运行时生成自签名证书 |

`verify` 可用选项：`--min-sdk N`（默认 21）、`--scheme v1|v2|v1+v2`（默认 `v1+v2`）。

### 输出签名方案（`--scheme` / 界面单选框）

**在打补丁之前**就要定下来：输出 APK 写不写 V2 签名。

| 方案 | 写入 | 不写入 | 适用 |
|---|---|---|---|
| `v1+v2`（默认） | `META-INF/*.SF` 等 + APK Signing Block | —— | 兼容面最广，含 Android 7.0 以下 |
| `v2` | 仅 APK Signing Block | 不新增任何 `META-INF` 条目 | 只求产物更接近现代 APK；**需要 Android 7.0 (API 24)+** |
| `v1` | 仅 `META-INF/*.SF` 等 | 不写 v2 签名块 | 排查对 v2 校验行为异常的旧 ROM |

实现要点：

- `PatchOptions.SigningScheme` 是唯一的真源，`ApkSignerTool.sign/verify` 都多了一个
  接收它的重载（旧的三参重载保留，等价于 `V1_AND_V2`，行为完全不变）。
- **校验起点跟着方案走**：只签 V2 时 apksig 必须按 `minSdk = max(minSdk, 24)` 校验，
  否则会因为「缺少 V1 签名」把一份合法产物判成失败。`VerifyResult#getMinCheckedPlatformVersion()`
  会把实际用的版本暴露出来。
- V3 属于 v2 那一族，所以只在「启用 V2 且 minSdk ≥ 28」时才挂上；只签 V1 时不会单独出现 V3。
- 选了 `v2` 而输入 APK 的 `minSdkVersion < 24` 时，补丁报告里会**自动加一条警告**，
  说明产物装不到 Android 7.0 以下。
- 自检里有独立见证：除了 apksig，还会直接数输出里 `META-INF/*.SF|*.RSA|*.DSA|*.EC`
  的条目数，确认「该写 V1 就真写了、该跳过就真没写」。

## 父主题是怎么选的

补丁主题必须继承**原启动 Activity 本来会用的那个主题**，否则会改变应用外观。
优先级：

1. 用户 `--parent` 强制指定的主题；
2. 启动 Activity 上已有的 `android:theme`；
3. `<application android:theme>`；
4. 应用内可识别的常见基础主题（`UnityThemeSelector`、`AppTheme`、`AppBaseTheme`、
   `Theme.AppCompat.*` …）；
5. 框架默认 `@android:style/Theme` —— 也就是"清单没声明主题"时框架真正的取值。

## Android 手机端 App

App 在**手机上**运行：SAF 选一个 APK → 本机打完补丁 → 保存/分享成品，再装到手表。
全程不联网。

### 构建

```bash
# 方式一：不依赖 Gradle / Android Studio（已实测通过）
./build-apk.sh
#   -> build/wear-apk-swipe-patcher.apk

# 方式二：Gradle + AGP（需要能访问 Google Maven）
./gradlew assembleDebug
#   -> app/build/outputs/apk/debug/app-debug.apk
```

`build-apk.sh` 的流水线：`aapt2 compile` → `aapt2 link` → `javac`（对 android.jar，`--release 8`）
→ `d8` → 合并 dex → zipalign → 用本项目 core 自签名 → 校验。
**最后三步用的就是本项目的 core**，等于顺带把 core 的打包链路在真实 Android 产物上又验了一遍。

### 关键设计取舍

| 决定 | 原因 |
|---|---|
| **零 AndroidX / Jetpack / Compose 依赖** | 只用 framework API（`Theme.Material` + 原生 View）。构建不受第三方仓库可用性影响，APK 仅 ~1 MB，兼容面更广 |
| **前台服务 `dataSync` + WakeLock** | 几十 MB 的 APK 打包+重签要数十秒到数分钟，切走后台会被冻结 |
| **minSdk = 26（而不是 21）** | ARSCLib 引用 `java.util.function/*`（138 个类，含 `ApkModule`/`TableBlock`）、`MethodHandles`（177 个类，含 `ZipAligner`）；apksig 的 `ApkVerifier` 引用 `java.util.stream`/`Optional`。这些是 API 24–26 才有的 JDK 类，不做 core library desugaring 就不可能在 21 上跑 |
| **清单里不写 `package` 属性** | AGP 8+ 要求包名写在 `namespace`；`build-apk.sh` 用 sed 注入一份临时副本给 `aapt2 link`。同一份清单两条构建路径都能用 |
| **自带 `ApkShareProvider`（40 行 ContentProvider）** | 不引入 AndroidX 就没有 `FileProvider`。自己写一个，并严格限制只能访问应用自己的输出目录 |
| **core 通过 `sourceSets` 直接编进 app** | 不建 Gradle 子项目，避免"Gradle 版"和"build.sh 版"两份实现不同步 |
| **手工构建时用 `--res-jar` 带入 ARSCLib 的框架资源** | `build-apk.sh` 不走 AGP，不会自动打包 jar 资源；而 ARSCLib 靠 `getResourceAsStream("/frameworks/android/android-XX.apk")` 解析框架属性。AGP 路径会自动包含，手工路径必须显式指定 |

### App 界面

单屏，四步：

1. **选择要处理的 APK** —— SAF 选文件，立刻显示分析结果（包名/版本/minSdk/启动 Activity/当前主题/是否已打过补丁）
2. **选项** —— 是否同时改 `<application>`、是否改所有 Activity、手动指定父主题（留空自动探测）、
   **输出签名方案**（V1 + V2 / 仅 V2 / 仅 V1，默认 V1 + V2）
3. **开始打补丁** —— 进度条 + 实时日志，前台通知同步显示
4. **结果** —— 完整补丁报告 + 签名校验结果 + 「保存到…」/「分享给其它应用」/「安装到本机」

选完文件就会警告：成品签名与原始不同，装到手表前需先卸载原版。

## 模块结构

```
libs/    ARSCLib-1.4.0.jar, apksig-8.7.0.jar
core/    纯 JVM 补丁逻辑（Android 端直接复用，零 Android 依赖）
  com/wearswipe/core/
    WearSwipePatcher  端到端编排：补丁 → 打包 → 对齐 → 签名 → 校验
    ResourcePatcher   资源层补丁本体（清单 + resources.arsc）
    ApkInspector      只读分析：这 APK 用的什么主题？打过补丁没？能不能打？
    ManifestUtil      清单/资源表只读工具
    ApkSignerTool     apksig 封装（按 minSdk 自动选 V1/V2/V3）
    SigningConfig     签名配置（自签名 / 密钥库两种来源）
    PatchOptions      参数
    PatchReport       结果快照
    Progress          进度回调
    crypto/           手写 DER + 运行时自签名 X.509 证书
app/     Android 手机端 App（零 AndroidX）
  com/wearswipe/app/
    MainActivity      SAF 选文件 / 选项 / 进度 / 结果
    PatchService      前台服务，跑补丁
    PatchSession      进程内共享状态 + 监听
    OutputStore       成品 APK 的落地目录与命名
    ApkShareProvider  极简 ContentProvider（替代 AndroidX FileProvider）
tools/dev/  桌面调试工具
    PackageApk        aapt2 base.apk + d8 dex -> 合并/对齐/签名（给 build-apk.sh 用）
    PatcherCli        patch / inspect / verify 命令行
    CoreSelfTest      64 项自检（不依赖 JUnit）
    ApkDiff           按解压后内容 SHA-256 逐条目对比两个 APK
    AlignCheck        手工解析 zip，检查 STORED 条目对齐
    Probe             资源表 / 清单结构探查
build.sh      构建 core + 调试工具（可选跑完整测试）
build-apk.sh  构建 App APK（aapt2 + d8 + core 签名，不依赖 Gradle）
```

## 已验证的结论（Happy Glass 1.0.6.1，39 MB Unity 游戏）

对 `happy-glass 1.0.6.1.apk`（39 MB Unity 游戏，minSdkVersion 16）实测：

| 指标 | 结果 |
|---|---|
| 补丁耗时 | **7.3 秒**（含 V1+V2 签名） |
| 输出体积 | 39,374,503 B（比原始仅大 **2,096 B**） |
| 补丁样式资源 ID | `0x7f07018f`（与 apktool 手工版**完全一致**） |
| 内容未变条目 | 1333 条中 **1328 条逐字节相同** |
| 非签名内容变化 | **仅 2 条**：`AndroidManifest.xml`、`resources.arsc` |
| `classes.dex` CRC32 | `510684f0` —— 与原始**完全相同** |
| zipalign | 374 个 STORED 条目**全部 4 字节对齐** |
| 签名校验 | apksig `verified=true`，V1 + V2 |
| 第三方交叉验证 | apktool 2.12.1 能正常解码，XML 语义与手工版一致 |

与 apktool 手工版的对比（用同为 39 MB 的输入）：

| | 本项目 | apktool 手工版 |
|---|---|---|
| 非签名内容变化条目 | **2** | 458（含 `classes.dex`、`classes2.dex`、全部 `res/`） |
| STORED 条目未对齐 | **0** | 273 |
| `classes.dex` | **未改动** | 被改动（少了 48,904 B） |

### App 本身的验证

`build-apk.sh` 产出 `build/wear-apk-swipe-patcher.apk`（3,471,033 B，
sha256 `7fafe7c7638ad61b5962fe651b32f9333cc2c9639a08042172075072a0b8f008`），
用 Android SDK 官方工具核对：

| 检查 | 工具 | 结果 |
|---|---|---|
| 签名 | `apksigner verify --verbose` | `Verifies`；V2 通过；强制 `--min-sdk-version 21` 时 V1 也通过（minSdk 26 下 apksig 默认不检查 V1） |
| 清单 | `aapt2 dump badging` | 包名 `com.wearswipe.app`、minSdk 26、targetSdk 34、5 个权限、标签「禁用右滑返回」、启动 Activity 正确 |
| 对齐 | `dev.AlignCheck` | STORED 条目 0 个未对齐 |
| dex 完整性 | 类名扫描 | app / core / ARSCLib / apksig 的关键类全部在 dex 中 |
| **框架资源** | `unzip -l` | **14 个 `frameworks/android/android-23..36.apk` + `arsclib.properties` 都在** |
| 依赖安全 | 高危包扫描 | `java/nio/file`、`java/awt`、`javax/swing`、`javax/imageio`、`sun/`、`com/sun/` 全部 **0 引用** |
| Gradle 路径 | `gradle --no-daemon assembleDebug` | `BUILD SUCCESSFUL in 5m 49s`，产出 APK 同样通过 apksigner 校验 |

**踩到的坑**（已修）：`PackageApk` 一开始只把 `.class` 转成 dex 打进 APK，漏掉了 jar 里的运行时资源。
ARSCLib 是用 `AndroidFrameworks.class.getResourceAsStream("/frameworks/android/android-XX.apk")`
按版本加载框架包的 —— Android 的 `PathClassLoader` 会从 APK zip 里读这些条目，缺了它们，
任何需要框架资源解析的 APK（启动 Activity 与 application 都没显式主题、又没命中自动候选时）
都会在设备上抛 `IOException: No resource found for version: N`。现在用 `--res-jar` 显式带入。

**踩到的坑 2**（已修）：源清单里 `android:authorities="${applicationId}.apks"` 用的是 AGP 的
manifest placeholder 机制，**`aapt2` 不会替换它**。于是手工构建出来的 APK 里 authority 是
字面量 `"${applicationId}.apks"`，而 `OutputStore.uriFor()` 里用的是
`getPackageName() + ".apks"` —— 两者对不上，「保存/分享 APK」和「安装到本机」会在运行时抛
`Failed to find provider info`。现在 `build-apk.sh` 的 sed 会一并替换占位符，并加了
「清单里仍有 `${...}` 就让构建直接失败」的防回归检查。两条路径现在都产出
`com.wearswipe.app.apks`，与代码侧一致。

**注意**：本机 `apksigner` 能验、`aapt2` 能解析、dex 与框架资源都完整，但**没有在真机上装过、跑过**。

## 已知局限

- **只覆盖 system/activity 级别的右滑返回**。Compose 的 `SwipeDismissBox`、
  View 里 `ViewDragHelper`、游戏自绘手势**不在覆盖范围内** —— 那些是应用自己画的，
  资源层改不动。
- Wear OS 各版本对 `windowSwipeToDismiss` 的行为不完全一致，**需要真机验证**。
- 重新签名后应用签名变了，**必须先卸载原版再安装**（除非原本就是同一密钥）。
  依赖原签名的功能（Google 登录、Firebase 校验、应用内购）可能失效。
- 二次打包会丢失原始 zip 条目的时间戳（apksig 归一到固定时间），对运行无影响。
- 当前不处理 split APK / APK bundle，只接受单个 APK。
- 选了「仅 V2」签名后产物没有 V1 签名，**装不到 Android 7.0 以下**（补丁报告里会有对应警告）。
  这也是为什么默认是 V1 + V2。

## 未完成

- **真机测试** —— 这是唯一还没做的关键验证：
  - App 装到手机上跑通一次完整流程
  - 打出来的成品装到手表上，确认右滑返回真的被关掉
- Wear OS 3/4 对 `windowSwipeToDismiss` 的支持不一致，需要按手表系统版本分别确认

## 作者与 AI 声明

### 结论：本项目由 AI 编写

**仓库里的全部内容——Java 源码、Shell 构建脚本、Gradle 配置、项目文档——都是 AI 生成的。**
没有一行是人工逐字敲出来的。

| 角色 | 承担者 | 具体做了什么 |
|---|---|---|
| 编写者 | **AI 编程代理（Cline）** | 全部源码、脚本、构建流程、测试、本文档，以及实际执行的编译/测试/排查 |
| 需求方与验收方 | 人类用户 | 提出目标与约束、决定设计取舍、审阅每一处改动、纠正 AI 的错误方向 |

人类的作用是**方向、约束与验收**，不是输入代码。因此：

- 项目里出现的注释、命名风格、设计理由说明，都是 AI 自己写的；
- 「已验证的结论」一节里的数字，是 AI 实际跑命令量出来的，复现命令都在文档里；
- 但**架构决定**（例如「零 AndroidX」「core 不走 Gradle 子项目」「minSdk 定 26」）
  是在人类用户明确认可后定下来的，不是 AI 自作主张。

### 这对使用者意味着什么

AI 生成的代码**可以正常编译、测试通过并在真实产物上验证**，但请把下面几点当作前提：

- **没有经过大规模人工代码审计。** 安全边界（签名密钥处理、`ContentProvider` 的路径限制、
  文件写入范围）虽然写了注释说明设计意图，但**请自行复核**再用于不可信的输入。
- **真机验证仍未完成**（见「未完成」）。所有结论都来自本机的命令行工具与真实 APK 产物，
  没有一台真实 Wear 设备跑过完整流程。
- **不要用它处理来路不明或你无权修改的 APK。**
- 发现 AI 写错的地方很正常——请开 issue 指出，不必客气。

### 一个具体的例子

本次「签名方案可选」功能的开发过程能说明 AI 的**真实工作方式**，包括它犯的错：

1. AI 把「是否做 V2 签名」实现成三选一（`V1+V2` / `仅 V2` / `仅 V1`）而非二选一复选框，
   并在 README 里主动说明了这个偏离及理由；
2. AI 第一次编译时漏了一处 API（`isVerifiedUsingV2Scheme` 写错），**自检直接报错**，
   它自己读编译输出修正后重跑；
3. AI 踩到一个非显然的坑并自行定位：**只签 V2 的产物如果仍按 `minSdk = 21` 校验，
   apksig 会因为「缺少 V1 签名」把合法产物判为失败**，于是让校验起点跟着方案走
   （`max(minSdk, 24)`）；
4. 除了信任 apksig 的结论，AI 还加了一个**独立见证**——直接数输出里
   `META-INF/*.SF|*.RSA|*.DSA|*.EC` 的条目数，用来证明「该写 V1 就真写了、该跳过就真没写」；
5. 以上每一步都在提交历史与本文档里可查。

## 许可证

本项目采用 **MIT 许可证**，见 [LICENSE](LICENSE)。

第三方组件（`libs/` 下的两个 jar）各自遵循其原始许可证，**不受本项目 MIT 许可影响**，
详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：

| 组件 | 版本 | 许可证 |
|---|---|---|
| [ARSCLib](https://github.com/REAndroid/ARSCLib) | 1.4.0 | Apache-2.0 |
| [apksig](https://android.googlesource.com/platform/tools/apksig)（Google） | 8.7.0 | Apache-2.0 |

两个 jar 随仓库提供（共约 5.5 MB），是为了让 `./build.sh` **不联网也能编译**。

## 免责声明

这是一个**修改他人 APK 的工具**。请仅对你有权修改的应用使用，并自行承担后果：
重新签名会导致应用更新链路、Google 登录、Firebase 校验、应用内购等依赖原签名的功能失效。
本项目按「原样」提供，不附带任何担保。

