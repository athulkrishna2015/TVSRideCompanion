#!/usr/bin/env bash
# Bump versionName/versionCode, commit, tag, and push.
#
# Usage: tools/bump-version.sh [major|minor|patch]   (default: patch)
#
# Reads versionCode/versionName from app/build.gradle.kts, increments the
# requested semver part (resetting the rest), bumps versionCode by one,
# commits the change, creates annotated tag vX.Y.Z, and pushes commit + tag.
# Pushing a v* tag is what triggers the CI release job, which attaches the
# APK to a GitHub Release — so this script is the whole release flow.
#
# Requires a clean tree on master. Dry run: DRY_RUN=1 tools/bump-version.sh minor
set -euo pipefail

PART="${1:-patch}"
case "$PART" in
  major|minor|patch) ;;
  *) echo "usage: $0 [major|minor|patch]" >&2; exit 2 ;;
esac

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

BRANCH="$(git branch --show-current)"
[ "$BRANCH" = "master" ] || { echo "refusing: not on master ($BRANCH)" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "refusing: tree is dirty" >&2; git status --short >&2; exit 1; }

FILE="app/build.gradle.kts"
CODE="$(grep -oP 'versionCode = \K[0-9]+' "$FILE")"
NAME="$(grep -oP 'versionName = "\K[0-9]+\.[0-9]+\.[0-9]+' "$FILE")"
IFS=. read -r MA MI PA <<<"$NAME"

case "$PART" in
  major) MA=$((MA + 1)); MI=0; PA=0 ;;
  minor) MI=$((MI + 1)); PA=0 ;;
  patch) PA=$((PA + 1)) ;;
esac
NEW="$MA.$MI.$PA"
NEWCODE=$((CODE + 1))

sed -i "s/versionCode = $CODE/versionCode = $NEWCODE/" "$FILE"
sed -i "s/versionName = \"$NAME\"/versionName = \"$NEW\"/" "$FILE"

echo "bump: $NAME ($CODE) -> $NEW ($NEWCODE)"

if [ "${DRY_RUN:-0}" = "1" ]; then
  git diff --stat
  echo "dry run: not committing"
  exit 0
fi

git add "$FILE"
git commit -q -m "release: bump to $NEW ($NEWCODE)"
git tag -a "v$NEW" -m "v$NEW"
git push -q origin master
git push -q origin "v$NEW"
echo "pushed master + tag v$NEW — CI will publish the GitHub Release"
