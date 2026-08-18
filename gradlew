#!/bin/sh
#
# Gradle Wrapper launcher for KeyMapper
# 需要 gradle/wrapper/gradle-wrapper.jar 才能运行。
#
# 首次使用前（任选其一）：
#   1) 用 Android Studio 打开本工程 —— AS 会自动下载 Gradle 并生成 gradle-wrapper.jar
#   2) 机器已安装 Gradle 时，在工程根目录执行： gradle wrapper --gradle-version 8.2
#   3) 机器已安装 Gradle 时，也可直接： gradle assembleDebug （无需 wrapper）
#

# 定位工程根目录
APP_HOME="$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$CLASSPATH" ]; then
  echo "ERROR: gradle-wrapper.jar not found at $CLASSPATH" >&2
  echo "请二选一：" >&2
  echo "  a) 用 Android Studio 打开本工程（会自动生成 wrapper jar）" >&2
  echo "  b) 已装 Gradle 则运行: gradle wrapper --gradle-version 8.2" >&2
  echo "  c) 或直接使用: gradle assembleDebug" >&2
  exit 1
fi

# 选择 Java 命令
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="\"$JAVA_HOME/bin/java\""
  JAVACMD=$JAVA_HOME/bin/java
else
  JAVACMD=java
fi

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
