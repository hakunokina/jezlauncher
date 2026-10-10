#!/usr/bin/env bash
# Fails unless the APK is signed (v2+), is not debuggable and carries the expected package and version.
# Prints the signing certificate SHA-256 so it can be compared with the expected release key.
# Usage: verify-apk.sh <apk> <versionName> <versionCode> <applicationId>
set -euo pipefail

apk="$1"; want_name="$2"; want_code="$3"; want_pkg="$4"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
bt="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"
ext=""; [[ "${OS:-}" == "Windows_NT" ]] && ext=".bat"

apksigner="$bt/apksigner$ext"
[[ -x "$apksigner" || -f "$apksigner" ]] || { echo "::error::apksigner not found in $bt" >&2; exit 1; }

"$apksigner" verify --verbose --print-certs "$apk" | grep -E "^(Verifies|Verified using|Signer #1 certificate SHA-256)" || true
"$apksigner" verify "$apk" >/dev/null

badging="$("$bt/aapt2" dump badging "$apk")"
grep -q "versionCode='$want_code' versionName='$want_name'" <<<"$badging" \
  || { echo "::error::$apk version mismatch (want $want_name / $want_code)" >&2; grep -m1 "^package:" <<<"$badging" >&2; exit 1; }
grep -q "^package: name='$want_pkg' " <<<"$badging" \
  || { echo "::error::$apk package mismatch (want $want_pkg)" >&2; grep -m1 "^package:" <<<"$badging" >&2; exit 1; }
! grep -q "application-debuggable" <<<"$badging" \
  || { echo "::error::$apk is debuggable" >&2; exit 1; }
echo "OK $apk $want_pkg $want_name ($want_code)"
