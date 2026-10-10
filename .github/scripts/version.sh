#!/usr/bin/env bash
# Derives versionName / versionCode from a release tag (vMAJOR.MINOR.PATCH).
#   versionName = MAJOR.MINOR.PATCH
#   versionCode = MAJOR*1000000 + MINOR*1000 + PATCH   (MINOR and PATCH below 1000)
# versionCode must stay above the upstream Olauncher code this fork started from.
# Usage: version.sh v1.0.0   -> prints name=... and code=... lines
set -euo pipefail

FLOOR=113 # upstream versionCode at fork time (v6.9.17)

tag="${1:-}"
if [[ ! "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "::error::Tag '$tag' is not vMAJOR.MINOR.PATCH (e.g. v1.0.0)" >&2
  exit 1
fi
major=$((10#${BASH_REMATCH[1]})); minor=$((10#${BASH_REMATCH[2]})); patch=$((10#${BASH_REMATCH[3]}))

if (( minor > 999 || patch > 999 || major > 2000 )); then
  echo "::error::Version parts out of range (minor/patch <= 999, major <= 2000)" >&2
  exit 1
fi
code=$((major * 1000000 + minor * 1000 + patch))
if (( code <= FLOOR )); then
  echo "::error::versionCode $code from '$tag' is not above the upstream floor $FLOOR; use v0.1.0 or newer" >&2
  exit 1
fi

echo "name=$major.$minor.$patch"
echo "code=$code"
