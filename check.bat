@echo off
setlocal

echo ========================================
echo  抖音TV - 项目完整性检查
echo ========================================
echo.

echo [检查1] 验证Java环境...
java -version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到Java环境! 请安装JDK 17或更高版本
    pause
    exit /b 1
)
echo [成功] Java环境正常

echo.
echo [检查2] 验证项目结构...
if not exist "app\src\main\AndroidManifest.xml" (
    echo [错误] 缺少AndroidManifest.xml
    pause
    exit /b 1
)
if not exist "app\src\main\java\com\douyin\tv\MainActivity.kt" (
    echo [错误] 缺少MainActivity.kt
    pause
    exit /b 1
)
echo [成功] 项目结构完整

echo.
echo [检查3] 验证Gradle配置...
if not exist "build.gradle" (
    echo [错误] 缺少build.gradle
    pause
    exit /b 1
)
if not exist "settings.gradle" (
    echo [错误] 缺少settings.gradle
    pause
    exit /b 1
)
echo [成功] Gradle配置完整

echo.
echo ========================================
echo  所有检查通过! 项目准备就绪
echo ========================================
echo.
echo 现在可以执行以下操作:
echo 1. 运行 build.bat 编译Release版本
echo 2. 运行 gradlew assembleDebug 编译Debug版本
echo.

pause
