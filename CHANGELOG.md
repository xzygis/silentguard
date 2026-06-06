# 更新日志

本项目的所有重要变更都将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.0.0] - 2026-06-06

### 新增

- 短信实时监听与邮件转发
- GPS 位置定时上报（含 Google Maps 链接）
- 开机自启动恢复监控
- SMTP 加密存储（AES256-GCM）
- 邮件发送失败自动重试（WorkManager）
- 通知监听降级方案（NotificationListenerService）
- 同时支持 SSL (465) 和 STARTTLS (587) 协议
- 自动版本号管理（Git Tag）

[Unreleased]: https://github.com/xzygis/silentguard/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/xzygis/silentguard/releases/tag/v1.0.0
