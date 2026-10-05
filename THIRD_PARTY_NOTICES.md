# 第三方组件声明

本项目自身以 **MIT** 许可证发布（见 [LICENSE](LICENSE)）。

`libs/` 目录下**随仓库提供**了两个第三方二进制 jar，用于让 `./build.sh` 在不联网的情况下
也能编译。它们**不是**本项目的作品，各自遵循其原始许可证，**不受本项目 MIT 许可证影响**。

---

## 1. ARSCLib 1.4.0

- 文件：`libs/ARSCLib-1.4.0.jar`
- 上游：<https://github.com/REAndroid/ARSCLib>
- 作者：REAndroid
- 许可证：**Apache License 2.0**
- 许可证全文：<https://www.apache.org/licenses/LICENSE-2.0>
- 用途：读写 `resources.arsc` 与二进制 XML；离线解析框架属性；zipalign
  （`com.reandroid.archive.ZipAlign`）
- 被引用位置：`core/src/main/java/com/wearswipe/core/{ResourcePatcher,ManifestUtil,ApkInspector}.java`
- 备注：jar 内**没有**内嵌 `LICENSE` 文件，许可证以上游仓库声明为准（Apache-2.0）。
  jar 内自带 `frameworks/android/android-23…36.apk` 框架资源包，
  是离线解析 `android:windowSwipeToDismiss` 等框架属性所必需的，
  打包成 APK 时会被一并带入（见 `build-apk.sh` 的 `--res-jar`）。

## 2. apksig 8.7.0（Android Open Source Project）

- 文件：`libs/apksig-8.7.0.jar`
- 上游：<https://android.googlesource.com/platform/tools/apksig>
- 版权：Copyright (C) The Android Open Source Project
- 许可证：**Apache License 2.0**
- 许可证全文：<https://www.apache.org/licenses/LICENSE-2.0>
- 用途：APK 的 V1 / V2 / V3 签名与校验
- 被引用位置：`core/src/main/java/com/wearswipe/core/ApkSignerTool.java`
- 备注：jar 内自带 `LICENSE`（Apache-2.0）文件，未做改动。

---

## 关于 Apache-2.0 合规

Apache-2.0 允许以二进制形式再分发，要求保留版权与许可声明。本项目通过本文件履行该义务，
且两个 jar **均保持原样未被修改**（可用上游发布包比对校验和）。

```
# 校验随仓库提供的 jar 与上游发布包一致
sha256sum libs/ARSCLib-1.4.0.jar libs/apksig-8.7.0.jar
```

本仓库中这两个文件的 SHA-256（CI 每次构建都会校验）：

```
2000fab9f436a199db2bca9ceae9fd8225f23fa2e1ffc9ee2c0b508169731a82  libs/ARSCLib-1.4.0.jar
c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89  libs/apksig-8.7.0.jar
```

## 商标

Android、Google、Wear OS 是 Google LLC 的商标。本文件中对这些名称的引用仅为说明兼容性，
不表示 Google 对本项目的任何认可或背书。
