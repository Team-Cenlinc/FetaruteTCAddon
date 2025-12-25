#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

git config --unset core.hooksPath || true

if [[ -f ".git/hooks/pre-push" ]] && grep -q "FetaruteTCAddon: pre-push hook" ".git/hooks/pre-push"; then
  rm -f .git/hooks/pre-push
  echo "Removed git hook: .git/hooks/pre-push"
fi

echo "Uninstalled git hooks: core.hooksPath unset"
