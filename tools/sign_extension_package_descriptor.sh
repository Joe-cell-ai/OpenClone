#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 8 ]]; then
  echo "Usage: $0 <extension-id> <version> <publisher> <min-api> <max-api> <apk-file> <download-https-url> <publisher-private.pem>" >&2
  exit 2
fi
extension_id="$1"
version="$2"
publisher="$3"
min_api="$4"
max_api="$5"
apk="$6"
download_url="$7"
private_key="$8"
[[ "$extension_id" =~ ^[a-z][a-z0-9_.-]{2,63}$ ]] || { echo "Invalid extension id" >&2; exit 2; }
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must be major.minor.patch" >&2; exit 2; }
[[ "$min_api" =~ ^[0-9]+$ && "$max_api" =~ ^[0-9]+$ && "$min_api" -gt 0 && "$max_api" -ge "$min_api" ]] || { echo "Invalid API range" >&2; exit 2; }
[[ "$download_url" == https://* ]] || { echo "Package URL must use HTTPS" >&2; exit 2; }
[[ -f "$apk" ]] || { echo "APK file not found" >&2; exit 2; }
tmp_payload="$(mktemp)"
tmp_signature="$(mktemp)"
trap 'rm -f "$tmp_payload" "$tmp_signature"' EXIT
sha256="$(sha256sum "$apk" | awk '{print tolower($1)}')"
printf '%s\n' "$extension_id" "$version" "$publisher" "$min_api" "$max_api" "$sha256" > "$tmp_payload"
# The canonical descriptor is exactly six LF-separated lines, with no trailing LF.
python3 - "$tmp_payload" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
lines = p.read_bytes().splitlines()
p.write_bytes(b"\n".join(lines))
PY
openssl dgst -sha256 -sign "$private_key" -out "$tmp_signature" "$tmp_payload"
signature="$(base64 -w0 "$tmp_signature")"
python3 - "$extension_id" "$version" "$publisher" "$min_api" "$max_api" "$sha256" "$download_url" "$signature" <<'PY'
import json, sys
extension_id, version, publisher, minimum, maximum, digest, url, signature = sys.argv[1:]
print(json.dumps({
    "id": extension_id,
    "version": version,
    "publisher": publisher,
    "minimumOpenCloneApi": int(minimum),
    "maximumOpenCloneApi": int(maximum),
    "sha256": digest,
    "downloadUrl": url,
    "signature": signature,
}, separators=(",", ":")))
PY
