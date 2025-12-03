# 抖音TV应用 - 编译和安装指南

## 📱 项目简介

这是一个专为Android TV设计的抖音客户端应用,通过WebView嵌入抖音网页,实现在智能电视上浏览和播放抖音视频。

## ✨ 核心功能

### 已实现功能
- ✅ **完整网页嵌入**: 嵌入抖音官方网页 https://www.douyin.com/
- ✅ **电视遥控器支持**: 完整适配电视遥控器操作
- ✅ **自动播放下一个**: 视频播放完毕自动跳转到下一个视频
- ✅ **账号登录持久化**: Cookie自动保存,支持持久化登录
- ✅ **大屏优化**: 针对电视大屏优化显示效果
- ✅ **流畅播放**: 硬件加速,视频播放流畅

### 遥控器操作说明
| 按键 | 功能 |
|------|------|
| **方向键上** | 切换到上一个视频 |
| **方向键下** | 切换到下一个视频 |
| **方向键左** | 向左滚动 |
| **方向键右** | 向右滚动 |
| **确认键/OK** | 播放/暂停当前视频 |
| **返回键** | 返回上一页 |
| **菜单键** | 显示抖音菜单 |

## 🔧 编译环境要求

### 必需环境
- **Java JDK**: 17 或更高版本
- **Android SDK**: 已安装 Android SDK (推荐使用 Android Studio)
- **Gradle**: 8.0+ (项目已包含 Gradle Wrapper,无需单独安装)

### 检查环境
```powershell
# 检查 Java 版本
java -version

# 检查环境变量
echo $env:JAVA_HOME
echo $env:ANDROID_HOME
```

## 📦 编译步骤

### 方法1: 使用编译脚本 (推荐)
```powershell
# 在项目根目录执行
.\build.bat
```

### 方法2: 使用 Gradle 命令
```powershell
# 编译 Release 版本
.\gradlew assembleRelease

# 编译 Debug 版本
.\gradlew assembleDebug

# 清理构建
.\gradlew clean
```

### 编译输出位置
- **Release APK**: `app\build\outputs\apk\release\app-release.apk`
- **Debug APK**: `app\build\outputs\apk\debug\app-debug.apk`

## 📲 安装步骤

### 在Android TV上安装

#### 方法1: 使用U盘安装
1. 将编译好的 APK 文件拷贝到 U盘
2. U盘插入电视的 USB 接口
3. 打开电视的文件管理器
4. 找到 APK 文件并点击安装
5. 允许"安装未知来源应用"

#### 方法2: 使用ADB安装
```powershell
# 连接电视(确保电视和电脑在同一局域网)
adb connect 电视IP地址:5555

# 安装APK
adb install app\build\outputs\apk\release\app-release.apk
```

### 首次使用
1. 打开"抖音TV"应用
2. 等待页面加载
3. 使用遥控器登录抖音账号
4. 开始浏览和观看视频

## 🎯 使用技巧

### 账号登录
- 首次打开需要登录抖音账号
- 登录信息会自动保存(Cookie持久化)
- 下次打开无需重新登录

### 视频观看
- 视频会自动播放
- 播放完毕后自动切换到下一个视频
- 使用遥控器确认键可暂停/播放

### 网络要求
- 建议连接 WiFi 网络
- 需要稳定的网络连接以获得最佳体验

## 🛠️ 技术架构

### 技术栈
- **开发语言**: Kotlin
- **UI框架**: Android WebView
- **电视适配**: AndroidX Leanback
- **最低支持**: Android 5.0 (API 21)
- **目标版本**: Android 14 (API 34)

### 核心组件
- `MainActivity.kt`: 主Activity,包含WebView和遥控器逻辑
- `WebViewClient`: 处理页面加载和URL拦截
- `WebChromeClient`: 处理进度和JS对话框
- `JavascriptInterface`: JS与Android交互桥梁

### 关键特性实现

#### 1. 自动播放下一个视频
通过注入JavaScript代码监听video标签的ended事件,当视频播放结束时自动触发切换。

#### 2. Cookie持久化
使用CookieManager自动保存和恢复Cookie,实现登录状态持久化。

#### 3. 电视遥控器适配
重写onKeyDown方法,将遥控器按键映射到相应的网页操作。

#### 4. 大屏优化
- 设置User Agent为PC版
- 注入CSS优化显示效果
- 启用硬件加速

## ⚠️ 注意事项

### 兼容性说明
- 本应用依赖抖音网页版,如抖音网页更新可能影响部分功能
- 部分电视设备可能需要ROOT权限才能安装第三方应用
- 建议在Android 7.0+系统上使用以获得最佳体验

### 已知限制
- 部分交互功能可能不如原生应用流畅
- 网页加载速度取决于网络状况
- 某些特殊功能可能无法使用

### 隐私安全
- 应用仅嵌入抖音官方网页
- 不收集或上传任何用户数据
- 登录凭证仅保存在本地设备

## 🔍 故障排查

### 编译失败
```powershell
# 清理并重新编译
.\gradlew clean
.\gradlew assembleRelease
```

### 无法连接网络
- 检查AndroidManifest.xml中的网络权限
- 确保电视已连接网络

### 视频无法播放
- 检查网络连接
- 清除应用数据后重新登录

### 无法安装APK
- 确保电视已允许"安装未知来源应用"
- 检查APK文件是否完整

## 📝 版本信息

- **应用版本**: 1.0
- **版本号**: 1
- **包名**: com.douyin.tv
- **最小SDK**: 21 (Android 5.0)
- **目标SDK**: 34 (Android 14)

## 📄 项目结构

```
douyin-tv/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/douyin/tv/
│   │       │   └── MainActivity.kt
│   │       └── res/
│   │           ├── drawable/
│   │           ├── mipmap/
│   │           └── values/
│   ├── build.gradle
│   └── proguard-rules.pro
├── gradle/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
└── build.bat
```

## 🤝 贡献

如果您发现问题或有改进建议,欢迎提交Issue或Pull Request。

## 📜 免责声明

本项目仅供学习交流使用,不得用于商业用途。抖音及相关商标归北京字节跳动科技有限公司所有。
