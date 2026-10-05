#!/usr/bin/env bash
#
# 生成自检夹具 APK：一个只含清单 + resources.arsc 的最小 APK，
# 里面有 4 个 Activity、4 种不同的主题来源，外加一个与本工具默认补丁样式同名的样式。
#
# 用来验证两件不能只靠"看代码"确认的事：
#   1) "给所有 Activity 打补丁"时，每个 Activity 拿到的是"继承各自原主题"的补丁样式；
#   2) 原 APK 自己定义了同名样式时，不会把它 clear() 掉。
#
# 需要 Android SDK 的 aapt2 + android.jar；没有就返回非 0（自检会跳过这部分）。
#
# 用法: tools/dev/make-testfixture.sh <输出.apk>
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE_DIR="$PROJECT_DIR/tools/testfixture"
OUT="${1:-$PROJECT_DIR/build/selftest/fixture/multi-activity.apk}"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-}"
PLATFORM_VERSION="${PLATFORM_VERSION:-}"

if [ -z "$BUILD_TOOLS_VERSION" ]; then
  BUILD_TOOLS_VERSION="$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "$PLATFORM_VERSION" ]; then
  PLATFORM_VERSION="$(ls "$SDK/platforms" 2>/dev/null | sort -V | tail -1 || true)"
fi

AAPT2="$SDK/build-tools/$BUILD_TOOLS_VERSION/aapt2"
AJAR="$SDK/platforms/$PLATFORM_VERSION/android.jar"

if [ ! -x "$AAPT2" ] || [ ! -f "$AJAR" ]; then
  echo "找不到 aapt2 / android.jar（SDK=$SDK），跳过夹具生成" >&2
  exit 3
fi

WORK="$(dirname "$OUT")/work"
rm -rf "$WORK"
mkdir -p "$WORK"

"$AAPT2" compile --dir "$FIXTURE_DIR/res" -o "$WORK/res.zip" > /dev/null

mkdir -p "$(dirname "$OUT")"
"$AAPT2" link \
  -o "$OUT" \
  -I "$AJAR" \
  --manifest "$FIXTURE_DIR/AndroidManifest.xml" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name 1.0 \
  "$WORK/res.zip" > /dev/null

rm -rf "$WORK"
echo "夹具 APK: $OUT ($(stat -c%s "$OUT") B)"
