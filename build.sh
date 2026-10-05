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
# test 会依次跑：CoreSelfTest（算法 + 真实 APK 端到端）、PatcherCli 端到端、
# 逐条目差异对比、对齐检查，以及命令行 --save-key 的"两次输出同一把签名密钥"验证。
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$PROJECT_DIR/build"
LIBS_DIR="$PROJECT_DIR/libs"
CLASSPATH="$LIBS_DIR/ARSCLib-1.4.0.jar:$LIBS_DIR/apksig-8.7.0.jar"
BACKUP_EXT=".wskey"

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
  echo "==> 生成多 Activity 自检夹具"
  FIXTURE="$BUILD_DIR/selftest/fixture/multi-activity.apk"
  FIXTURE_ARG=""
  if bash "$PROJECT_DIR/tools/dev/make-testfixture.sh" "$FIXTURE"; then
    FIXTURE_ARG="$FIXTURE"
  else
    echo "     (跳过夹具相关用例：本机没有可用的 aapt2 / android.jar)" >&2
  fi

  echo "==> 自检"
  java -Xmx1500m -cp "$CLASSPATH:$BUILD_DIR/classes:$BUILD_DIR/tools" \
    dev.CoreSelfTest "$APK" "$BUILD_DIR/selftest" "$FIXTURE_ARG"

  echo "==> 端到端补丁"
  java -Xmx1500m -cp "$CLASSPATH:$BUILD_DIR/classes:$BUILD_DIR/tools" \
    dev.PatcherCli patch "$APK" "$BUILD_DIR/selftest/patched.apk"

  echo "==> 与原始 APK 逐条目对比"
  java -Xmx1500m -cp "$CLASSPATH:$BUILD_DIR/classes:$BUILD_DIR/tools" \
    dev.ApkDiff "$APK" "$BUILD_DIR/selftest/patched.apk"

  echo "==> 对齐检查"
  java -Xmx1500m -cp "$CLASSPATH:$BUILD_DIR/classes:$BUILD_DIR/tools" \
    dev.AlignCheck "$BUILD_DIR/selftest/patched.apk"

  echo "==> 命令行密钥复用（--save-key 第一次生成并保存，第二次复用）"
  # 这正是 README 里承诺的行为：同一把密钥 -> 两次输出的签名证书必须一模一样，
  # 新版本才能直接覆盖安装。这里用命令行路径再验一遍（core 里也有等价的自检）。
  KEY_FILE="$BUILD_DIR/selftest/cli-key$BACKUP_EXT"
  rm -f "$KEY_FILE"
  CLI_CP="$CLASSPATH:$BUILD_DIR/classes:$BUILD_DIR/tools"
  FP_FIRST="$(
    java -Xmx1500m -cp "$CLI_CP" dev.PatcherCli patch "$APK" \
      "$BUILD_DIR/selftest/key-run1.apk" --save-key "$KEY_FILE" \
      | sed -n 's/^签名指纹: \([0-9A-F:]*\).*/\1/p'
  )"
  FP_SECOND="$(
    java -Xmx1500m -cp "$CLI_CP" dev.PatcherCli patch "$APK" \
      "$BUILD_DIR/selftest/key-run2.apk" --save-key "$KEY_FILE" \
      | sed -n 's/^签名指纹: \([0-9A-F:]*\).*/\1/p'
  )"
  if [ ! -s "$KEY_FILE" ]; then
    echo "     密钥文件没有生成: $KEY_FILE" >&2
    exit 1
  fi
  if [ -z "$FP_FIRST" ] || [ "$FP_FIRST" != "$FP_SECOND" ]; then
    echo "     两次输出的签名指纹不一致: '$FP_FIRST' vs '$FP_SECOND'" >&2
    exit 1
  fi
  echo "     两次输出签名指纹一致: $FP_FIRST"
  echo "     密钥文件: $(stat -c%s "$KEY_FILE") B"
fi

echo "完成。"
