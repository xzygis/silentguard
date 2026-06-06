<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="120" alt="SilentGuard Logo" />
</p>

<h1 align="center">SilentGuard</h1>

<p align="center">
  <strong>静默守护 — Android 短信与位置监控应用</strong>
</p>

<p align="center">
  <a href="https://github.com/xzygis/silentguard/actions/workflows/ci.yml">
    <img src="https://github.com/xzygis/silentguard/actions/workflows/ci.yml/badge.svg" alt="CI" />
  </a>
  <a href="https://github.com/xzygis/silentguard/releases/latest">
    <img src="https://img.shields.io/github/v/release/xzygis/silentguard?label=latest" alt="Latest Release" />
  </a>
  <a href="https://github.com/xzygis/silentguard/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/xzygis/silentguard" alt="License" />
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen" alt="Min SDK" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
</p>

---

SilentGuard 是一个开源的 Android 手机监控应用，可静默监控短信和 GPS 位置，并通过邮件自动转发到指定邮箱。适用于家长监护、个人设备追踪等场景。

## ✨ 功能特性

- **短信转发** — 实时监听收到的短信，自动转发到指定邮箱
- **位置上报** — 按设定间隔定时获取 GPS 坐标，邮件发送（含 Google Maps 链接）
- **开机自启** — 设备重启后自动恢复监控服务
- **加密存储** — SMTP 密码使用 EncryptedSharedPreferences (AES256-GCM) 加密
- **可靠投递** — 邮件发送失败自动重试（WorkManager + 指数退避）
- **通知降级** — 国产 ROM 短信权限被拦截时，自动通过 NotificationListenerService 捕获
- **协议兼容** — 同时支持 SSL (465) 和 STARTTLS (587) 加密协议

## 📱 截图

> 截图即将补充，欢迎贡献！

<!--
<p align="center">
  <img src="docs/screenshots/dashboard.png" width="200" />
  <img src="docs/screenshots/settings.png" width="200" />
  <img src="docs/screenshots/map.png" width="200" />
</p>
-->

## 🏗️ 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 后台 | Foreground Service |
| 定位 | Google Play Services FusedLocationProvider |
| 地图 | 高德地图 SDK |
| 邮件 | Jakarta Mail (SMTP over SSL/STARTTLS) |
| 任务调度 | WorkManager |
| 数据库 | Room |
| 持久化 | DataStore + EncryptedSharedPreferences |
| 最低版本 | Android 8.0 (API 26) |

## 📥 下载安装

前往 [Releases](https://github.com/xzygis/silentguard/releases/latest) 页面下载最新的 APK 文件，传输到 Android 手机上直接安装。

> 需要在手机设置中允许「安装未知应用」。最低支持 Android 8.0。

## 🔨 从源码构建

### 前置条件

- [Android Studio](https://developer.android.com/studio) (推荐) 或 JDK 17+
- Android SDK 34

### 快速开始

```bash
git clone https://github.com/xzygis/silentguard.git
cd silentguard
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

<details>
<summary>📖 详细构建指南</summary>

#### 方式一：Android Studio（推荐新手）

1. 下载并安装 [Android Studio](https://developer.android.com/studio)（自带 JDK 和 SDK）
2. 首次启动按向导完成 SDK 安装（确保安装 **Android SDK 34**）
3. **File → Open** → 选择 `silentguard` 文件夹 → 等待 Gradle 同步完成
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)** 生成 Debug APK
5. USB 连接手机（开启 USB 调试），点击 ▶️ 运行按钮安装

#### 方式二：命令行构建

```bash
# 设置 ANDROID_HOME 环境变量
export ANDROID_HOME=$HOME/Library/Android/sdk  # macOS
# export ANDROID_HOME=$HOME/Android/Sdk        # Linux

# 构建
./gradlew assembleDebug

# 安装到手机
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### 常见问题

| 问题 | 解决方案 |
|------|----------|
| Gradle 同步失败 | 检查网络；国内用户建议配置 Gradle 镜像 |
| `SDK location not found` | 创建 `local.properties`，写入 `sdk.dir=/你的SDK路径` |
| `JAVA_HOME is not set` | 安装 JDK 17+ 并设置 `JAVA_HOME` |

</details>

## 📖 使用方法

1. 安装并打开应用，授予短信、位置、通知等权限
2. 填写 SMTP 邮件配置（参考下方邮箱设置）
3. 填写接收邮箱地址
4. 设置位置上报间隔（分钟）
5. 点击「保存配置」→「发送测试邮件」验证
6. 开启监控开关

<details>
<summary>📧 邮箱 SMTP 配置指引</summary>

### 常见邮箱配置

| 邮箱 | SMTP 服务器 | 端口 | 备注 |
|------|-------------|------|------|
| QQ 邮箱 | smtp.qq.com | 465 | 需要使用授权码（非登录密码） |
| 163 邮箱 | smtp.163.com | 465 | 需要使用授权码 |
| Gmail | smtp.gmail.com | 465 | 需要开启"应用专用密码" |
| Outlook | smtp.office365.com | 587 | 需要开启"应用密码" |
| 飞书邮箱 | smtp.feishu.cn | 465 | 直接使用邮箱密码 |
| 阿里企业邮箱 | smtp.qiye.aliyun.com | 465 | 直接使用邮箱密码 |

### 获取授权码

#### QQ 邮箱

1. 登录 [QQ 邮箱](https://mail.qq.com) 网页版
2. **设置 → 账户** → 找到「POP3/IMAP/SMTP 服务」
3. 开启 **IMAP/SMTP 服务**（手机验证后获得授权码）

#### 163 邮箱

1. 登录 [163 邮箱](https://mail.163.com) → **设置 → POP3/SMTP/IMAP**
2. 开启 **IMAP/SMTP 服务** → 按提示设置授权码

#### Gmail

1. 前往 [Google 账户安全设置](https://myaccount.google.com/security)
2. 开启 **两步验证** → **应用专用密码** → 生成 16 位密码

### 在应用中填写

| 应用字段 | 填写内容 |
|----------|----------|
| SMTP 服务器 | 参考上表（如 `smtp.qq.com`） |
| SMTP 端口 | `465`（SSL）或 `587`（STARTTLS） |
| 发送邮箱 | 你的完整邮箱地址 |
| 邮箱授权码 | 上面获取的授权码（不是登录密码） |
| 接收邮箱 | 接收监控通知的邮箱 |

> 填完后点击「发送测试邮件」验证配置。

</details>

## 📂 项目结构

```
com.xzygis.silentguard/
├── MainActivity.kt                     # Compose 主界面
├── config/
│   └── AppConfig.kt                    # 配置管理（加密存储）
├── data/
│   ├── AppDatabase.kt                  # Room 数据库
│   ├── MonitorEvent.kt                 # 监控事件实体
│   └── MonitorEventDao.kt              # 数据访问对象
├── mail/
│   ├── MailSender.kt                   # SMTP 邮件发送（SSL/STARTTLS）
│   ├── MailWorker.kt                   # WorkManager 重试调度
│   └── EmailScheduleWorker.kt          # 定时邮件任务
├── service/
│   ├── MonitorForegroundService.kt     # 前台服务（位置监控）
│   └── SmsNotificationListenerService.kt  # 通知监听降级
├── receiver/
│   ├── SmsReceiver.kt                  # 短信广播接收
│   └── BootReceiver.kt                 # 开机自启动
└── ui/
    ├── screen/                         # 各页面（仪表盘/设置/地图/日志）
    ├── component/                      # 可复用组件
    ├── navigation/                     # 导航定义
    └── theme/                          # Material3 主题
```

## 🔒 权限说明

| 权限 | 用途 |
|------|------|
| `RECEIVE_SMS` / `READ_SMS` | 监听和读取短信 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 获取 GPS 位置 |
| `ACCESS_BACKGROUND_LOCATION` | 后台持续定位 |
| `INTERNET` | 发送邮件 |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启动 |
| `POST_NOTIFICATIONS` | Android 13+ 通知权限 |

## 🤝 贡献

欢迎贡献！请阅读 [贡献指南](CONTRIBUTING.md) 了解如何参与。

## 🔐 安全

发现安全漏洞？请参阅 [安全策略](SECURITY.md) 了解报告方式。

## ⚖️ 免责声明

本项目仅供学习和个人设备管理使用。请确保在使用时遵守当地法律法规。未经他人同意在他人设备上安装监控软件可能违反法律。作者不对任何滥用行为承担责任。

## 📄 License

本项目基于 [MIT License](LICENSE) 开源。

Copyright © 2026 [eagle](https://github.com/xzygis)
