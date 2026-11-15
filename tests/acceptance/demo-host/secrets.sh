#!/usr/bin/env bash
# Writes the secrets the demo-host runs need into ~/.vigil-demo/{installation}/secrets/ from Parameter Store (P-32 spec
# Section 6.9 item 1): the five seed passwords, control-password and jira; files 0444 in a folder 0555 (BP-R-26), each
# written through a temporary file. --remove deletes the folder. Prints the names, never the values. Run on the Mac:
#   tests/acceptance/demo-host/secrets.sh {installation} [--remove]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: tests/acceptance/demo-host/secrets.sh {installation} [--remove]"
  exit 2
}

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || ! known_installation "$1"; then
  usage
fi
I=$1
FOLDER="$HOME/.vigil-demo/$I/secrets"
if [ "$#" -eq 2 ]; then
  if [ "$2" != --remove ]; then
    usage
  fi
  if [ -d "$FOLDER" ]; then
    chmod u+w "$FOLDER"
    rm -rf "$FOLDER"
  fi
  echo "removed $FOLDER"
  exit 0
fi

check_identity
mkdir -p "$FOLDER"
chmod 0755 "$FOLDER"
echo "secrets in $FOLDER:"
for name in seed-password-presenter seed-password-noc-l1 seed-password-dbops-engineer seed-password-dbops-lead \
  seed-password-admin control-password jira; do
  if ! out=$(aws ssm get-parameter --name "/vigil/$I/$name" --with-decryption --output json 2>&1); then
    chmod 0555 "$FOLDER"
    case "$out" in
      *ParameterNotFound*) echo "Missing secret /vigil/$I/$name (AR-S-13)." ;;
      *) echo "$out" ;;
    esac
    exit 1
  fi
  temporary="$FOLDER/.$name.tmp"
  rm -f "$temporary"
  (umask 0077 && printf '%s' "$out" | json_raw 'd["Parameter"]["Value"]' > "$temporary")
  chmod 0444 "$temporary"
  mv -f "$temporary" "$FOLDER/$name"
  echo "  $name"
done
chmod 0555 "$FOLDER"
