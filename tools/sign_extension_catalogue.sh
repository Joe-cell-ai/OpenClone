#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <catalogue-payload.json> <publisher-private.pem> <output-envelope.json>" >&2
  exit 2
fi
payload="$1"
private_key="$2"
output="$3"
publisher_id="f33f2fb1fb231984a2234160"
tmp_signature="$(mktemp)"
trap 'rm -f "$tmp_signature"' EXIT
openssl dgst -sha256 -sign "$private_key" -out "$tmp_signature" "$payload"
payload_b64="$(base64 -w0 "$payload")"
signature_b64="$(base64 -w0 "$tmp_signature")"
python3 - "$publisher_id" "$payload_b64" "$signature_b64" "$output" <<'PY'
import json, sys
publisher_id, payload, signature, output = sys.argv[1:]
with open(output, "w", encoding="utf-8") as stream:
    json.dump({"publisherId": publisher_id, "payload": payload, "signature": signature},
              stream, separators=(",", ":"))
    stream.write("\n")
PY
