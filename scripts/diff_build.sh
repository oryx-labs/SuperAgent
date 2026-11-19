#!/usr/bin/env bash
# Package what changed since a commit, for someone to apply by hand. Nothing leaves this machine.
#
#   1. Commits every uncommitted change locally (new files .gitignore doesn't exclude too), so the package is exactly
#      one commit. Local only: never push, fetch or any other remote operation.
#   2. Writes one file, superagent-<from>-<to>.tar.gz, holding one directory:
#        superagent-<from>-<to>/changes.tar.gz  the files added or changed between <from> and <to>, with their paths
#        superagent-<from>-<to>/apply.sh        run it from the target's root: extracts changes.tar.gz over it, then
#                                               deletes the files deleted here (it lists them and asks first)
#   3. Prints <to>: the commit the target is at once updated, to pass next time.
# On the target:  tar -xzf superagent-<...>.tar.gz -C /tmp  &&  cd <project root>  &&  bash /tmp/superagent-<...>/apply.sh
#
# Usage: scripts/diff_build.sh <commit-id> [-m <commit message>] [-o <output dir>] [-h]
#   <commit-id>  the commit the target is at (given every time; nothing is remembered)
#   -m <msg>     message for the commit of uncommitted changes [package]
#   -o <dir>     where to write the package [the current directory]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; }

FROM=""
OUT_DIR="$PWD"
MESSAGE="package"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) [[ $# -ge 2 ]] || { echo "[ERROR] -o needs a directory" >&2; exit 2; }; OUT_DIR="$2"; shift 2 ;;
    -m) [[ $# -ge 2 ]] || { echo "[ERROR] -m needs a message" >&2; exit 2; }; MESSAGE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "[ERROR] unknown option: $1" >&2; usage >&2; exit 2 ;;
    *) [[ -z "$FROM" ]] || { echo "[ERROR] only one commit id" >&2; exit 2; }; FROM="$1"; shift ;;
  esac
done
[[ -n "$FROM" ]] || { echo "[ERROR] give the commit the target is at" >&2; usage >&2; exit 2; }
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

cd "$PROJECT_ROOT"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "[ERROR] $PROJECT_ROOT is not a git repository" >&2; exit 1; }
git cat-file -e "${FROM}^{commit}" 2>/dev/null || { echo "[ERROR] $FROM is not a commit in this repository" >&2; exit 1; }
GIT_DIR="$(git rev-parse --git-dir)"
for marker in MERGE_HEAD rebase-merge rebase-apply CHERRY_PICK_HEAD; do
  [[ -e "$GIT_DIR/$marker" ]] && { echo "[ERROR] a merge / rebase / cherry-pick is in progress: finish it first" >&2; exit 1; }
done
SHORT="$(git rev-parse --short "$FROM")"

# 1. Commit what isn't committed, locally. Packages lying in the tree are never committed.
git add -A -- . ':(exclude)*.tar.gz' ':(exclude)superagent-*-apply.sh'
if ! git diff --cached --quiet; then
  git commit -q -m "$MESSAGE"
  echo "[OK] committed local changes: $(git log -1 --format='%h %s')"
fi
TO="$(git rev-parse --short HEAD)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CHANGED="$WORK/changed"   # NUL-separated paths to pack
DELETED="$WORK/deleted"   # NUL-separated paths to delete on the target
: > "$CHANGED"; : > "$DELETED"

# Packages are never part of a package, wherever they were written.
skip() { [[ "$1" == *.tar.gz || "$1" == superagent-*-apply.sh ]]; }

# Tracked files: the commit against the working tree. -z keeps any file name intact; no renames, so a rename is a
# delete plus an add.
while IFS= read -r -d '' status && IFS= read -r -d '' path; do
  skip "$path" && continue
  case "$status" in
    D) printf '%s\0' "$path" >> "$DELETED" ;;
    *) printf '%s\0' "$path" >> "$CHANGED" ;;
  esac
done < <(git diff -z --name-status --no-renames "$FROM" HEAD --)

n_changed=$(tr -cd '\0' < "$CHANGED" | wc -c | tr -d ' ')
n_deleted=$(tr -cd '\0' < "$DELETED" | wc -c | tr -d ' ')
if [[ "$n_changed" == 0 && "$n_deleted" == 0 ]]; then
  echo "[OK] nothing changed since $SHORT (the target is at $TO already)"
  exit 0
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
NAME="superagent-$SHORT-$TO"
BUNDLE="$WORK/$NAME"
mkdir -p "$BUNDLE"

# COPYFILE_DISABLE / --no-xattrs: no macOS ._ files or extended headers that GNU tar on Linux would warn about.
if [[ "$n_changed" != 0 ]]; then
  COPYFILE_DISABLE=1 tar --no-xattrs -czf "$BUNDLE/changes.tar.gz" -C "$PROJECT_ROOT" --null -T "$CHANGED"
else
  COPYFILE_DISABLE=1 tar --no-xattrs -czf "$BUNDLE/changes.tar.gz" -T /dev/null
fi

# The apply script carries the deleted paths itself, each quoted with %q so any name reaches rm intact.
{
  cat <<EOF
#!/usr/bin/env bash
# Applies $NAME: the changes from $SHORT to $TO, made $STAMP. Run it from the root of the copy to update:
#   bash path/to/$NAME/apply.sh [-y]
# It extracts changes.tar.gz (next to this script) over the current directory, then deletes the files listed below.
# -y skips the question.
set -euo pipefail
PKG="\$(dirname "\${BASH_SOURCE[0]}")/changes.tar.gz"
YES=""
for arg in "\$@"; do
  case "\$arg" in
    -y) YES=1 ;;
    *) PKG="\$arg" ;;
  esac
done
[[ -f "\$PKG" ]] || { echo "[ERROR] package not found: \$PKG" >&2; exit 1; }
[[ -f pom.xml ]] || { echo "[ERROR] run this from the project root (no pom.xml in \$(pwd))" >&2; exit 1; }
DELETED=(
EOF
  while IFS= read -r -d '' path; do
    printf '  %q\n' "$path"
  done < "$DELETED"
  cat <<'EOF'
)
echo "Target: $(pwd)"
echo "Update or add $(tar -tzf "$PKG" | grep -vc '/$') file(s):"
tar -tzf "$PKG" | grep -v '/$' | sed 's/^/  + /'
if [[ ${#DELETED[@]} -gt 0 ]]; then
  echo "Delete ${#DELETED[@]} file(s):"
  printf '  - %s\n' "${DELETED[@]}"
fi
if [[ -z "$YES" ]]; then
  read -r -p "Apply? [y/N] " answer
  [[ "$answer" == y || "$answer" == Y ]] || { echo "Nothing changed."; exit 0; }
fi
tar -xzf "$PKG"
for path in "${DELETED[@]}"; do
  case "$path" in
    /*|..|../*|*/../*|*/..) echo "[SKIP] unsafe path: $path" >&2; continue ;;
  esac
  rm -f -- "$path"
done
echo "[OK] applied"
EOF
} > "$BUNDLE/apply.sh"
chmod +x "$BUNDLE/apply.sh"

COPYFILE_DISABLE=1 tar --no-xattrs -czf "$OUT_DIR/$NAME.tar.gz" -C "$WORK" "$NAME"

echo "[OK] $SHORT..$TO: $n_changed added or changed, $n_deleted deleted"
echo "     $OUT_DIR/$NAME.tar.gz ($(du -h "$OUT_DIR/$NAME.tar.gz" | cut -f1))"
echo "     on the target: tar -xzf $NAME.tar.gz -C /tmp, then from the project root: bash /tmp/$NAME/apply.sh"
echo "     once applied the target is at $TO: next time run  scripts/diff_build.sh $TO"
