#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

chmod +x .githooks/pre-push

if git config core.hooksPath .githooks 2>/dev/null; then
  echo "Installed git hooks: core.hooksPath=.githooks"
  echo "To skip once: SKIP_GIT_HOOKS=1 git push"
  exit 0
fi

echo "WARN: failed to set core.hooksPath; falling back to copying into .git/hooks/" >&2
mkdir -p .git/hooks
cp .githooks/pre-push .git/hooks/pre-push
chmod +x .git/hooks/pre-push

echo "Installed git hooks: .git/hooks/pre-push"
echo "To skip once: SKIP_GIT_HOOKS=1 git push"
