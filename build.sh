#!/usr/bin/env bash
#
# 构建 wear-apk-swipe-patcher 的纯 JVM core（无 Gradle，无外部可执行文件依赖）。
#
# 产物:
#   build/classes  编译出的 .class
#   build/core.jar 可被 Android app 模块直接引用的 jar
#
# 用法:
#   ./build.sh              只编译 core
#   ./build.sh test <apk>   编译并跑自检（需要一个真实 APK）
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$PROJECT_DIR/build"
LIBS_DIR="$PROJECT_DIR/libs"
CLASSPATH="$LIBS_DIR/ARSCLib-1.4.0.jar:$LIBS_DIR/apksig-8.7.0.jar"

RELEASE_TARGET="${JAVA_RELEASE:-8}"

if [ ! -f "$LIBS_DIR/ARSCLib-1.4.0.jar" ]; then
  echo "缺少 $LIBS_DIR/ARSCLib-1.4.0.jar" >&2
  exit 1
fi
if [ ! -f "$LIBS_DIR/apksig-8.7.0.jar" ]; then
  echo "缺少 $LIBS_DIR/apksig-8.7.0.jar" >&2
  exit 1
fi

rm -rf "$BUILD_DIR/classes"
mkdir -p "$BUILD_DIR/classes"

find "$PROJECT_DIR/core/src/main/java" -name '*.java' > "$BUILD_DIR/sources.txt"

echo "==> javac (release $RELEASE_TARGET)"
javac -nowarn -encoding UTF-8 \
  -d "$BUILD_DIR/classes" \
  -cp "$CLASSPATH" \
  --release "$RELEASE_TARGET" \
  @"$BUILD_DIR/sources.txt"

echo "==> jar"
jar --create --file "$BUILD_DIR/core.jar" -C "$BUILD_DIR/classes" .
echo "     $BUILD_DIR/core.jar"

echo "==> 编译调试工具"
rm -rf "$BUILD_DIR/tools"
mkdir -p "$BUILD_DIR/tools"
javac -nowarn -encoding UTF-8 \
  -d "$BUILD_DIR/tools" \
  -cp "$CLASSPATH:$BUILD_DIR/classes" \
  --release "$RELEASE_TARGET" \
  "$PROJECT_DIR"/tools/dev/*.java
echo "     $BUILD_DIR/tools"

if [ "${1:-}" = "test" ]; then
  APK="${2:-}"
  if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "用法: ./build.sh test <原始.apk>" >&2
    exit 2
  fi
  echo "==> 自检"
  java -Xmx1500m -cp "$CLASSPATH:$BUILD_DIR/classes:$BUILD_DIR/tools" \
    dev.CoreSelfTest "$APK" "$BUILD_DIR/selftest"

  echo "==> 端到端补丁"
  java -Xmx1500m -cp "$CLASSPATH:$BUILD_DIR/classes:$BUILD_DIR/tools" \
    dev.PatcherCli patch "$APK" "$BUILD_DIR/selftest/patched.apk"

  echo "==> 与原始 APK 逐条目对比"
  java -Xmx1500m -cp "$CLASSPATH:$BUILD_DIR/classes:$BUILD_DIR/tools" \
    dev.ApkDiff "$APK" "$BUILD_DIR/selftest/patched.apk"

  echo "==> 对齐检查"
  java -Xmx1500m -cp "$CLASSPATH:$BUILD_DIR/classes:$BUILD_DIR/tools" \
    dev.AlignCheck "$BUILD_DIR/selftest/patched.apk"
fi

echo "完成。"
