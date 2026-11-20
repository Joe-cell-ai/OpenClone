#!/usr/bin/env bash
# Static check of a built clone. Usage:
#   tools/verify_clone.sh original.apk clone.apk com.whatsapp com.whatsapp.clone \
#       com.whatsapp.registration.app.phonenumberentry.RegisterPhone
# Heuristic only: it shows what the build changed, it does not prove the app works.
set -euo pipefail
if [[ $# -ne 5 ]]; then
  echo "Usage: $0 original.apk clone.apk old.package new.package fully.qualified.Activity" >&2
  exit 2
fi
orig=$1; clone=$2; old=$3; new=$4; component=$5
for apk in "$orig" "$clone"; do
  [[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 2; }
done
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
count() { # apk, needle, mode(x=exact line, f=contains)
  local apk=$1 needle=$2 mode=$3 total=0 d n
  mkdir -p "$work/d"; rm -f "$work"/d/*
  unzip -qo "$apk" 'classes*.dex' -d "$work/d" 2>/dev/null || { echo 0; return; }
  for d in "$work"/d/classes*.dex; do
    [[ -e "$d" ]] || continue
    if [[ "$mode" == x ]]; then
      n=$(strings -n 4 "$d" | grep -cFx -- "$needle" || true)
    else
      n=$(strings -n 4 "$d" | grep -cF -- "$needle" || true)
    fi
    total=$((total + n))
  done
  echo "$total"
}
echo "== DEX strings (heuristic; DEX encoding can hide matches)"
echo "exact '$old'        original: $(count "$orig" "$old" x)   clone: $(count "$clone" "$old" x)"
echo "exact '$new'        original: $(count "$orig" "$new" x)   clone: $(count "$clone" "$new" x)"
echo "contains '$old/'    original: $(count "$orig" "$old/" f)  clone: $(count "$clone" "$old/" f)"
echo "== Manifest declares the component in the clone?"
if command -v aapt2 >/dev/null 2>&1; then
  aapt2 dump xmltree --file AndroidManifest.xml "$clone" | grep -F -- "$component" >/dev/null \
    && echo "yes: $component" || echo "NO: $component missing from clone manifest"
elif command -v apkanalyzer >/dev/null 2>&1; then
  apkanalyzer manifest print "$clone" | grep -F -- "$component" >/dev/null \
    && echo "yes: $component" || echo "NO: $component missing from clone manifest"
else
  echo "Neither aapt2 nor apkanalyzer is installed; manifest check skipped."
fi
echo "== Not covered by the DEX rewrite: res/xml shortcuts/widgets, resources.arsc strings, native libs"
echo "== Device: adb logcat -c; reproduce; adb logcat -d | grep -E 'Permission Denial|ActivityNotFound|FATAL|cmp='"
