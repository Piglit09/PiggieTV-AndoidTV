@echo off
setlocal
set DIR=%~dp0
if "%JAVA_HOME%" == "" (
  set JAVA_EXE=java
) else (
  if exist "%JAVA_HOME%\bin\java.exe" (
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
  ) else (
    set JAVA_EXE=java
  )
)

if not exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  echo Gradle wrapper JAR not found. Please run the bootstrap step once.
  exit /b 1
)

"%JAVA_EXE%" -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
