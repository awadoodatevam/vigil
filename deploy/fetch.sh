#!/usr/bin/env bash
# Copies one file of the main host to the Mac through Systems Manager, in pieces of deploy/main/host-read.sh, and
# checks it by its SHA-256 (P-32 spec Section 6.4 item 6; CTX-D-095 (3)). Only files under /srv/vigil/spike/results,
# /srv/vigil/logs and /srv/vigil/deploy. Run on the Mac: deploy/fetch.sh {installation} {host path} {local path}.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: deploy/fetch.sh {installation} {host path} {local path}"
  exit 2
}

if [ "$#" -ne 3 ] || ! known_installation "$1"; then
  usage
fi
I=$1
HOST_PATH=$2
LOCAL_PATH=$3
case "$HOST_PATH" in
  */../* | */..) HOST_PATH=refused ;;
esac
case "$HOST_PATH" in
  /srv/vigil/spike/results/?* | /srv/vigil/logs/?* | /srv/vigil/deploy/?*) ;;
  *)
    echo "Only files under /srv/vigil/spike/results, /srv/vigil/logs and /srv/vigil/deploy can be fetched."
    exit 1
    ;;
esac
check_identity
require_running_host "vigil-$I-main" main

WORK=$(temp_folder)
# shellcheck disable=SC2064 # the folder name is fixed now
trap "rm -rf '$WORK'" EXIT
offset=0
piece=0
while :; do
  piece=$((piece + 1))
  if ! run_on_host "$HOST_ID" deploy/main/host-read.sh -- "$HOST_PATH" "$offset" > "$WORK/piece-$piece"; then
    cat "$WORK/piece-$piece"
    exit 1
  fi
  # Prints the piece's length, then "end" or "more".
  read -r length state < <(python3 -c '
import sys

lines = open(sys.argv[1]).read().split("\n")
lines = [line for line in lines if line and not line.startswith("sha256 ")]
end = bool(lines) and lines[-1] == "END"
body = lines[:-1] if end else lines
print(sum(len(line) for line in body), "end" if end else "more")
' "$WORK/piece-$piece")
  if [ "$state" = end ]; then
    break
  fi
  if [ "$length" -eq 0 ]; then
    echo "The main host sent an empty piece of $HOST_PATH."
    exit 1
  fi
  offset=$((offset + length))
done

target_dir=$(dirname "$LOCAL_PATH")
mkdir -p "$target_dir"
python3 - "$WORK" "$piece" "$LOCAL_PATH" <<'PYTHON'
import base64
import gzip
import hashlib
import os
import sys
import tempfile

work, count, target = sys.argv[1], int(sys.argv[2]), sys.argv[3]
expected, text = None, []
for number in range(1, count + 1):
    with open(os.path.join(work, f"piece-{number}")) as piece:
        for line in piece.read().split("\n"):
            if line.startswith("sha256 "):
                expected = line.split(" ", 1)[1]
            elif line and line != "END":
                text.append(line)
data = gzip.decompress(base64.b64decode("".join(text)))
found = hashlib.sha256(data).hexdigest()
if found != expected:
    print(f"The SHA-256 of the fetched file is {found}, the main host's is {expected}.")
    sys.exit(1)
handle, temporary = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(target)), prefix=".fetch-")
with os.fdopen(handle, "wb") as out:
    out.write(data)
os.chmod(temporary, 0o644)
os.replace(temporary, target)
print(f"fetched {sys.argv[3]} ({len(data)} bytes, sha256 {found})")
PYTHON
