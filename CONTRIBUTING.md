# 贡献指南

感谢你对 SilentGuard 的关注！我们欢迎任何形式的贡献，包括 Bug 报告、功能建议和代码提交。

## 如何报告问题

1. 在 [Issues](https://github.com/xzygis/silentguard/issues) 页面搜索是否已有相同的问题
2. 如果没有，使用对应的 Issue 模板创建新 Issue：
   - **Bug 报告**：请尽量提供详细的复现步骤、设备信息和日志
   - **功能请求**：请描述需求背景和期望的解决方案

## 开发环境搭建

### 前置要求

- **Android Studio**：Hedgehog (2023.1.1) 或更高版本
- **JDK**：17
- **Android SDK**：API 34 (Android 14)
- **Kotlin**：项目使用 Kotlin 语言开发
- **最低支持版本**：Android 8.0 (API 26)

### 开始开发

```bash
# 1. Fork 并克隆仓库
git clone https://github.com/<你的用户名>/silentguard.git
cd silentguard

# 2. 用 Android Studio 打开项目

# 3. 等待 Gradle 同步完成

# 4. 编译验证
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug
```

## 分支与提交规范

### 分支命名

所有改动必须在 feature 分支上开发，**禁止直接提交到 `main` 分支**。

分支命名格式：`feature/<简短描述>`

```
feature/add-sms-filter      # 新功能
feature/fix-email-crash      # Bug 修复
feature/refactor-database    # 重构
```

### 提交信息

请遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

```
feat: 添加短信关键词过滤功能
fix: 修复后台服务被杀死的问题
docs: 更新 README 安装说明
refactor: 重构数据库访问层
style: 调整设置页面布局
```

## Pull Request 流程

1. 从 `main` 分支创建 feature 分支：
   ```bash
   git checkout -b feature/你的功能 main
   ```
2. 在 feature 分支上进行开发和提交
3. 确保代码能正常编译（`./gradlew assembleDebug`）
4. 推送分支并创建 Pull Request：
   ```bash
   git push -u origin feature/你的功能
   ```
5. 在 PR 描述中说明变更内容，并关联相关 Issue
6. 等待代码审查，根据反馈修改

## 代码规范

### 通用规则

- 使用 **Kotlin** 语言，遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- UI 层使用 **Jetpack Compose** + **Material3**
- 使用 4 个空格缩进，不使用 Tab

### 项目结构

```
app/src/main/java/com/xzygis/silentguard/
├── config/          # 应用配置
├── data/            # 数据层（Room 数据库）
├── mail/            # 邮件发送
├── receiver/        # 广播接收器
├── service/         # 前台服务
├── ui/              # UI 层（Compose）
│   ├── component/   # 可复用组件
│   ├── navigation/  # 导航
│   ├── screen/      # 页面
│   └── theme/       # 主题
└── MainActivity.kt
```

### 注意事项

- 新增字符串资源请添加到 `res/values/strings.xml`
- 涉及权限变更请同步更新 `AndroidManifest.xml`
- 避免提交包含密钥、密码等敏感信息的代码

## 许可证

提交代码即表示你同意将你的贡献按照项目 [LICENSE](LICENSE) 中的条款进行授权。
