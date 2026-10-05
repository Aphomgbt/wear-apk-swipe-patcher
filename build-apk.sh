#!/usr/bin/env bash
#
# 不依赖 Gradle / Android Studio 直接构建 App APK。
#
# 需要 Android SDK:
#   platforms/android-36/android.jar
#   build-tools/36.0.0/{aapt2,d8}
#
# 流程:
#   aapt2 compile  -> 编译 res/
#   aapt2 link     -> base.apk (resources.arsc + 二进制清单) + R.java
#   javac          -> app + core 的 class（--release 8，对 android.jar 编译）
#   d8             -> classes.dex
#   PackageApk     -> 合并 + zipalign + 用 core 自签名 + 校验
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"
PLATFORM_VERSION="${PLATFORM_VERSION:-android-36}"

BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
AJAR="$SDK/platforms/$PLATFORM_VERSION/android.jar"
APP_ID="${APP_ID:-com.wearswipe.app}"
MIN_SDK=26
TARGET_SDK=34

for tool in "$BT/aapt2" "$BT/d8"; do
  if [ ! -x "$tool" ]; then
    echo "找不到 $tool" >&2
    echo "请先: sdkmanager --install 'platforms;$PLATFORM_VERSION' 'build-tools;$BUILD_TOOLS_VERSION'" >&2
    exit 1
  fi
done
if [ ! -f "$AJAR" ]; then
  echo "找不到 $AJAR" >&2
  exit 1
fi

BUILD="$PROJECT_DIR/build"
WORK="$BUILD/app"
APP_SRC="$PROJECT_DIR/app/src/main"
OUT_APK="$BUILD/wear-apk-swipe-patcher.apk"

echo "==> 1/6 构建 core"
bash "$PROJECT_DIR/build.sh"

rm -rf "$WORK"
mkdir -p "$WORK/compiled" "$WORK/gen" "$WORK/classes" "$WORK/dex"

echo "==> 2/6 aapt2 compile"
"$BT/aapt2" compile --dir "$APP_SRC/res" -o "$WORK/compiled/res.zip"

echo "==> 3/6 aapt2 link"
# 这份源清单是给两条构建路径共用的，所以要在这里补上只有 Gradle/AGP 才会做的那一步：
#   1) package 属性：AGP 8+ 要求包名写在 build.gradle 的 namespace 里，源清单不能有，
#      但 aapt2 link 必须有，于是生成一份注入了包名的临时副本。
#   2) ${applicationId} 占位符：这是 AGP 的 manifest placeholder 机制，aapt2 **不会**替换它。
#      漏了这步会让 ApkShareProvider 的 authority 变成字面量 "${applicationId}.apks"，
#      而代码里用的是 getPackageName()+".apks"，两者对不上 ——
#      分享 APK / 安装到本机 会在运行时抛 "Failed to find provider info"。
MANIFEST="$WORK/AndroidManifest.xml"
sed -e "s|<manifest |<manifest package=\"$APP_ID\" |" \
    -e "s|\${applicationId}|$APP_ID|g" \
    "$APP_SRC/AndroidManifest.xml" > "$MANIFEST"
if ! grep -q "package=\"$APP_ID\"" "$MANIFEST"; then
  echo "注入 package 失败" >&2
  exit 1
fi
# 防回归：源清单以后再加占位符时，这里必须让构建直接失败而不是静默打出坏包。
if grep -q '\${' "$MANIFEST"; then
  echo "清单里还有 aapt2 不会替换的占位符:" >&2
  grep -n '\${' "$MANIFEST" >&2
  exit 1
fi

"$BT/aapt2" link \
  -o "$WORK/base.apk" \
  -I "$AJAR" \
  --manifest "$MANIFEST" \
  --java "$WORK/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code 1 \
  --version-name 1.0 \
  --no-version-vectors \
  "$WORK/compiled/res.zip"

echo "==> 4/6 javac"
find "$APP_SRC/java" -name '*.java' > "$WORK/sources.txt"
find "$WORK/gen" -name '*.java' >> "$WORK/sources.txt"
javac -nowarn -encoding UTF-8 \
  -d "$WORK/classes" \
  -cp "$AJAR:$PROJECT_DIR/libs/ARSCLib-1.4.0.jar:$PROJECT_DIR/libs/apksig-8.7.0.jar:$BUILD/classes" \
  --release 8 \
  @"$WORK/sources.txt"

echo "==> 5/6 d8"
jar --create --file "$WORK/classes.jar" -C "$WORK/classes" .
"$BT/d8" \
  --min-api "$MIN_SDK" \
  --lib "$AJAR" \
  --output "$WORK/dex" \
  --release \
  "$WORK/classes.jar" \
  "$BUILD/core.jar" \
  "$PROJECT_DIR/libs/ARSCLib-1.4.0.jar" \
  "$PROJECT_DIR/libs/apksig-8.7.0.jar"

echo "==> 6/6 合并 / 对齐 / 签名"
java -Xmx1500m -cp "$PROJECT_DIR/libs/ARSCLib-1.4.0.jar:$PROJECT_DIR/libs/apksig-8.7.0.jar:$BUILD/classes:$BUILD/tools" \
  dev.PackageApk "$WORK/base.apk" "$OUT_APK" "$WORK/dex" \
  --res-jar "$PROJECT_DIR/libs/ARSCLib-1.4.0.jar"

echo
echo "完成: $OUT_APK"
