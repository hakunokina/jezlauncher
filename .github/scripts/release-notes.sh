#!/usr/bin/env bash
# Writes release notes for a tag to stdout. Source order:
#   1. annotated tag message (needs GH_TOKEN + GITHUB_REPOSITORY for the API lookup)
#   2. matching "## 1.2.3" / "## [1.2.3]" / "## v1.2.3" section of CHANGELOG.md
#   3. a one-line fallback
# Usage: release-notes.sh v1.2.3 [CHANGELOG.md]
set -euo pipefail

tag="$1"; changelog="${2:-CHANGELOG.md}"
ver="${tag#v}"
notes=""

if [[ -n "${GH_TOKEN:-}" && -n "${GITHUB_REPOSITORY:-}" ]]; then
  # Annotated tag: refs/tags/<tag> points at a tag object; lightweight tags point at a commit.
  read -r otype osha < <(gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$tag" --jq '.object.type + " " + .object.sha' 2>/dev/null || echo "none -")
  if [[ "$otype" == "tag" ]]; then
    notes="$(gh api "repos/$GITHUB_REPOSITORY/git/tags/$osha" --jq '.message' | sed '/-----BEGIN PGP SIGNATURE-----/,$d')"
  fi
fi

if [[ -z "${notes//[[:space:]]/}" && -f "$changelog" ]]; then
  notes="$(awk -v v="$ver" '
    /^## / {
      if (on) exit
      h=$0; gsub(/^##[ \t]*\[?v?/, "", h); sub(/[\]\[ \t].*$/, "", h)
      if (h == v) { on=1; next }
    }
    on { print }
  ' "$changelog")"
fi

if [[ -z "${notes//[[:space:]]/}" ]]; then
  notes="JezLauncher $ver"
fi

printf '%s\n' "$notes"
