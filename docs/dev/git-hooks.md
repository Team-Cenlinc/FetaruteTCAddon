# Git Hooks

本仓库提供版本化的 Git hooks（位于 `.githooks/`），用于在 `git push` 前自动执行 `./gradlew clean check`，防止未通过检查的提交被推送。

## 安装

在仓库根目录执行：

```bash
./scripts/install-githooks.sh
```

该脚本会设置本地仓库配置 `core.hooksPath=.githooks`（仅影响当前仓库，不会影响全局）。
若 `core.hooksPath` 写入失败，会自动回退为复制到 `.git/hooks/pre-push`。

## 卸载

```bash
./scripts/uninstall-githooks.sh
```

## 跳过

临时跳过一次：

```bash
SKIP_GIT_HOOKS=1 git push
```

也可以使用：

```bash
FTA_SKIP_PRE_PUSH=1 git push
```
