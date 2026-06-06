---
description: Git 工作流规则，适用于所有代码提交和分支操作
alwaysApply: true
---

# Git 工作流规则

## 分支策略

- `main` 分支为受保护分支，**禁止直接提交或推送**
- 所有改动必须在 `feature/*` 分支上开发，通过 PR 合并到 `main`
- 分支命名规范：`feature/<简短描述>`，如 `feature/add-sms-filter`

## 提交流程

1. 从 `main` 创建 feature 分支：`git checkout -b feature/xxx main`
2. 在 feature 分支上开发和提交
3. 推送 feature 分支：`git push -u origin feature/xxx`
4. 在 GitHub 创建 Pull Request，目标分支为 `main`
5. PR 合并后，GitHub Actions 自动构建 APK 并发布 Release

## 禁止操作

- 禁止 `git push origin main`
- 禁止 `git commit` 时处于 `main` 分支
- 禁止 `git push --force` 到任何分支

## 编译验证

修改代码后，使用以下命令验证编译：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug
```
