# 快速开始 - 抖音TV应用

## 🚀 快速编译

### Windows用户
双击运行 `build.bat` 文件,或在PowerShell中执行:
```powershell
.\build.bat
```

### 手动编译
```powershell
# Release版本(推荐)
.\gradlew assembleRelease

# Debug版本
.\gradlew assembleDebug
```

## 📍 APK位置
编译完成后,APK文件在:
```
app\build\outputs\apk\release\app-release.apk
```

## 📺 安装到电视

### 步骤1: 准备U盘
- 将 `app-release.apk` 复制到U盘

### 步骤2: 电视设置
- 打开电视设置
- 允许"安装未知来源应用"

### 步骤3: 安装
- U盘插入电视
- 打开文件管理器
- 找到APK并安装

### 步骤4: 使用
- 打开"抖音TV"应用
- 登录抖音账号
- 开始观看视频

## 🎮 遥控器操作

| 按键 | 功能 |
|------|------|
| 上/下 | 切换视频 |
| 左/右 | 滚动页面 |
| 确认 | 播放/暂停 |
| 返回 | 返回上一页 |

## ⚡ 核心功能

- ✅ 自动播放下一个视频
- ✅ 账号登录自动保存
- ✅ 大屏优化显示
- ✅ 遥控器完全适配

## 📞 常见问题

### Q: 编译失败?
A: 确保安装了JDK 17+,执行 `java -version` 检查

### Q: 无法安装?
A: 确保电视已允许安装未知来源应用

### Q: 视频无法播放?
A: 检查网络连接,建议使用WiFi

---

详细文档请查看 [GUIDE.md](GUIDE.md)
