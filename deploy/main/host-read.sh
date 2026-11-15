#!/usr/bin/env bash
# The main host's part of deploy/fetch.sh (P-32 spec Section 6.4 item 6), run by run_on_host: prints one piece of a
# file, at most 18,000 base64 characters of the file's gzip from the offset (Systems Manager returns at most 24,000
# characters of output), then END when it was the last piece; with offset 0 first the line "sha256 {hex}" of the file.
#   host-read.sh {path} {offset}
set -euo pipefail

VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}

path=$1
offset=$2
case "$path" in
  */../* | */..) path=refused ;;
esac
case "$path" in
  /srv/vigil/spike/results/?* | /srv/vigil/logs/?* | /srv/vigil/deploy/?*) ;;
  *)
    echo "Only files under /srv/vigil/spike/results, /srv/vigil/logs and /srv/vigil/deploy can be fetched."
    exit 1
    ;;
esac
file="$VIGIL_ROOT${path#/srv/vigil}"
if [ ! -f "$file" ]; then
  echo "No file $path on the main host."
  exit 1
fi
python3 - "$file" "$offset" <<'PYTHON'
import base64
import gzip
import hashlib
import sys

PIECE = 18000
with open(sys.argv[1], "rb") as source:
    data = source.read()
offset = int(sys.argv[2])
text = base64.b64encode(gzip.compress(data, mtime=0)).decode()
if offset == 0:
    print("sha256", hashlib.sha256(data).hexdigest())
print(text[offset : offset + PIECE])
if offset + PIECE >= len(text):
    print("END")
PYTHON
