@rem Gradle Wrapper launcher for KeyMapper
@rem 需要 gradle\wrapper\gradle-wrapper.jar 才能运行。
@rem
@rem 首次使用前（任选其一）：
@rem   1) 用 Android Studio 打开本工程 —— AS 会自动下载 Gradle 并生成 gradle-wrapper.jar
@rem   2) 机器已安装 Gradle 时，在工程根目录执行： gradle wrapper --gradle-version 8.2
@rem   3) 机器已安装 Gradle 时，也可直接： gradle assembleDebug （无需 wrapper）

@echo off
setlocal
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if not exist "%CLASSPATH%" (
  echo ERROR: gradle-wrapper.jar not found at %CLASSPATH%
  echo Please do one of the following:
  echo   a^) Open this project in Android Studio ^(it will generate the wrapper jar^)
  echo   b^) If Gradle is installed, run: gradle wrapper --gradle-version 8.2
  echo   c^) Or just run: gradle assembleDebug
  exit /b 1
)

if defined JAVA_HOME (
  set JAVACMD=%JAVA_HOME%\bin\java.exe
) else (
  set JAVACMD=java.exe
)

"%JAVACMD%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
