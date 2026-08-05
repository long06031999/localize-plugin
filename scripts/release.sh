#!/usr/bin/env bash
#
# Cuts a release: bumps pluginVersion, commits, tags.
#
#   ./scripts/release.sh 1.0.2
#
# The bump is committed BEFORE the tag is created, so the tag points at a commit whose
# gradle.properties states that very version. Tagging first and bumping afterwards leaves every
# released commit claiming the previous version, which is what CI now refuses to build.
#
# Nothing is pushed — the push is what triggers publishing, so it stays an explicit step.

set -euo pipefail

version="${1:-}"
if [[ -z "$version" ]]; then
  echo "usage: $0 <version>        e.g. $0 1.0.2" >&2
  exit 1
fi

# Marketplace requires x.y.z; a hyphen suffix routes to the eap channel instead of stable.
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "Not a valid version: '$version' (expected 1.0.2 or 1.0.2-beta.1)" >&2
  exit 1
fi

die() { echo "✗ $1" >&2; exit 1; }

branch=$(git rev-parse --abbrev-ref HEAD)
[[ "$branch" == "main" ]] || die "On '$branch'. Release from main."

git diff --quiet || die "Working tree has unstaged changes. Commit or stash first."
git diff --cached --quiet || die "There are staged changes. Commit or reset first."

if git rev-parse -q --verify "refs/tags/v${version}" >/dev/null; then
  die "Tag v${version} already exists locally."
fi

git fetch origin --quiet
if git rev-parse -q --verify "refs/tags/v${version}" >/dev/null 2>&1; then
  die "Tag v${version} already exists on the remote."
fi
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] \
  || die "main and origin/main differ. Pull or push first."

current=$(grep -E '^pluginVersion=' gradle.properties | cut -d= -f2)
[[ "$current" != "$version" ]] || die "gradle.properties already says ${version}."

# Marketplace rejects a version it has already published, so catch it before tagging.
published=$(curl -sS --max-time 15 \
  "https://plugins.jetbrains.com/api/plugins/33331/updates?size=50" 2>/dev/null \
  | grep -o "\"version\":\"[^\"]*\"" | cut -d'"' -f4 || true)
if [[ -n "$published" ]] && grep -qxF "$version" <<<"$published"; then
  die "Version ${version} is already on the Marketplace. Pick a higher one."
fi

echo "→ ${current} → ${version}"
perl -i -pe "s/^pluginVersion=.*/pluginVersion=${version}/" gradle.properties
git add gradle.properties
git commit -q -m "Release v${version}"
git tag -a "v${version}" -m "v${version}"

cat <<EOF

✓ Committed the bump and tagged v${version}.

Push to publish (GitHub Release + JetBrains Marketplace):

    git push origin main && git push origin v${version}

To undo before pushing:

    git tag -d v${version} && git reset --hard HEAD~1
EOF
