#!/usr/bin/env bash

set -u

BASE_BRANCH="${1:-main}"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERROR: 当前目录不是 Git 仓库。" >&2
  exit 1
}

cd "$REPO_ROOT" || exit 1

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xbb-ai-check.XXXXXX")" || exit 1
trap 'rm -rf "$TMP_DIR"' EXIT

echo "AI coordination preflight"
echo "repository: $REPO_ROOT"
echo "base: $BASE_BRANCH"
echo "current: $(git branch --show-current)"
echo

echo "Tracked working-tree changes:"
TRACKED_STATUS="$(git status --short --untracked-files=no)"
if [ -n "$TRACKED_STATUS" ]; then
  printf '%s\n' "$TRACKED_STATUS"
else
  echo "(none)"
fi
echo

echo "Local untracked files (preserve unless explicitly owned):"
UNTRACKED_STATUS="$(git status --short --untracked-files=all | sed -n 's/^?? /?? /p')"
if [ -n "$UNTRACKED_STATUS" ]; then
  printf '%s\n' "$UNTRACKED_STATUS"
else
  echo "(none)"
fi
echo

echo "Registered worktrees:"
git worktree list
echo

BRANCHES=()
while IFS= read -r branch; do
  [ -n "$branch" ] && BRANCHES+=("$branch")
done < <(git worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')

UNIQUE_BRANCHES=()
for branch in "${BRANCHES[@]}"; do
  seen=0
  for existing in "${UNIQUE_BRANCHES[@]:-}"; do
    [ "$existing" = "$branch" ] && seen=1
  done
  [ "$seen" -eq 0 ] && UNIQUE_BRANCHES+=("$branch")
done

ACTIVE_BRANCHES=()
for branch in "${UNIQUE_BRANCHES[@]:-}"; do
  [ "$branch" = "$BASE_BRANCH" ] && continue
  safe_name="${branch//\//_}"
  diff_file="$TMP_DIR/$safe_name.files"
  git diff --name-only "$BASE_BRANCH...$branch" 2>/dev/null | sort -u > "$diff_file"
  ACTIVE_BRANCHES+=("$branch:$diff_file")
  count="$(wc -l < "$diff_file" | tr -d ' ')"
  echo "Branch $branch changes $count file(s) relative to $BASE_BRANCH."
  sed -n '1,80p' "$diff_file"
  if [ "$count" -gt 80 ]; then
    echo "... output truncated ..."
  fi
  echo

  migration_count="$(sed -n '/^backend\/migrations_alembic\/versions\//p' "$diff_file" | wc -l | tr -d ' ')"
  if [ "$migration_count" -gt 0 ]; then
    echo "MIGRATION OWNER CANDIDATE: $branch changes $migration_count Alembic migration file(s)."
    echo
  fi
done

CONFLICTS=0
MIGRATION_BRANCHES=0

for entry in "${ACTIVE_BRANCHES[@]:-}"; do
  file="${entry#*:}"
  if sed -n '/^backend\/migrations_alembic\/versions\//p' "$file" | grep -q .; then
    MIGRATION_BRANCHES=$((MIGRATION_BRANCHES + 1))
  fi
done

i=0
while [ "$i" -lt "${#ACTIVE_BRANCHES[@]}" ]; do
  left="${ACTIVE_BRANCHES[$i]}"
  left_branch="${left%%:*}"
  left_file="${left#*:}"
  j=$((i + 1))
  while [ "$j" -lt "${#ACTIVE_BRANCHES[@]}" ]; do
    right="${ACTIVE_BRANCHES[$j]}"
    right_branch="${right%%:*}"
    right_file="${right#*:}"
    overlap_file="$TMP_DIR/overlap-$i-$j"
    comm -12 "$left_file" "$right_file" > "$overlap_file"
    if [ -s "$overlap_file" ]; then
      CONFLICTS=$((CONFLICTS + 1))
      echo "OVERLAP: $left_branch <-> $right_branch"
      sed -n '1,120p' "$overlap_file"
      echo
    fi
    j=$((j + 1))
  done
  i=$((i + 1))
done

echo "Summary:"
echo "- active non-base worktree branches: ${#ACTIVE_BRANCHES[@]}"
echo "- branch pairs with overlapping files: $CONFLICTS"
echo "- branches changing Alembic migrations: $MIGRATION_BRANCHES"

if [ "$MIGRATION_BRANCHES" -gt 1 ]; then
  echo "HIGH RISK: 多个活动分支同时修改迁移；必须串行并确认唯一 migration_owner。" >&2
  exit 2
fi

if [ "$CONFLICTS" -gt 0 ]; then
  echo "WARNING: 存在文件重叠；后开始的任务应暂停重叠模块并更新交接文件。" >&2
  exit 2
fi

if [ -n "$TRACKED_STATUS" ]; then
  echo "WARNING: 当前工作树有已跟踪修改，开始新任务前必须确认归属。" >&2
  exit 2
fi

echo "OK: 未发现需要阻断的已跟踪修改、分支重叠或迁移并发。"
