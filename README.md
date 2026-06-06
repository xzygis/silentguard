# SilentGuard

一个 Android 手机监控应用，可静默监控短信和 GPS 位置，并通过邮件自动转发到指定邮箱。

## 功能

- **短信转发** — 实时监听收到的短信，自动转发到指定邮箱
- **位置上报** — 按设定间隔定时获取 GPS 坐标，邮件发送（含 Google Maps 链接）
- **开机自启** — 设备重启后自动恢复监控服务
- **加密存储** — SMTP 密码使用 EncryptedSharedPreferences (AES256-GCM) 加密
- **可靠投递** — 邮件发送失败自动重试（WorkManager + 指数退避）

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 后台 | Foreground Service |
| 定位 | Google Play Services FusedLocationProvider |
| 邮件 | Jakarta Mail (SMTP over SSL) |
| 任务调度 | WorkManager |
| 持久化 | DataStore + EncryptedSharedPreferences |
| 最低版本 | Android 8.0 (API 26) |

## 项目结构

```
com.xzygis.silentguard/
├── MainActivity.kt              # Compose 配置界面
├── config/
│   └── AppConfig.kt             # 配置管理（加密存储）
├── mail/
│   ├── MailSender.kt            # SMTP 邮件发送
│   └── MailWorker.kt            # WorkManager 重试调度
├── service/
│   └── MonitorForegroundService.kt  # 前台服务（位置监控）
└── receiver/
    ├── SmsReceiver.kt           # 短信广播接收
    └── BootReceiver.kt          # 开机自启动
```

## 构建

### 前置条件

- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 8+
- Android SDK 34

### 步骤

```bash
git clone https://github.com/xzygis/silentguard.git
cd silentguard
```

用 Android Studio 打开项目，等待 Gradle 同步完成后，连接设备或启动模拟器运行。

## 使用方法

1. 安装并打开应用，授予短信、位置、通知等权限
2. 填写 SMTP 邮件配置（服务器、端口、发送邮箱、授权码）
3. 填写接收邮箱地址
4. 设置位置上报间隔（分钟）
5. 点击「保存配置」，可先点「发送测试邮件」验证
6. 开启监控开关

## 权限说明

| 权限 | 用途 |
|------|------|
| `RECEIVE_SMS` / `READ_SMS` | 监听和读取短信 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 获取 GPS 位置 |
| `ACCESS_BACKGROUND_LOCATION` | 后台持续定位 |
| `INTERNET` | 发送邮件 |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启动 |
| `POST_NOTIFICATIONS` | Android 13+ 通知权限 |

## 免责声明

本项目仅供学习和个人设备管理使用。请确保在使用时遵守当地法律法规。未经他人同意在他人设备上安装监控软件可能违反法律。作者不对任何滥用行为承担责任。

## License

[MIT](LICENSE)
