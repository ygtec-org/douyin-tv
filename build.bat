@echo off
setlocal

echo ========================================
echo  抖音TV应用编译脚本
echo ========================================
echo.

echo [1/3] 清理旧的构建文件...
call gradlew clean

echo.
echo [2/3] 开始编译Release版本...
call gradlew assembleRelease

echo.
echo [3/3] 编译完成!
echo.
echo APK文件位置:
echo %cd%\app\build\outputs\apk\release\app-release.apk
echo.
echo ========================================

pause
