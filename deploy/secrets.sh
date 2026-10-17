#!/usr/bin/env bash
# Writes the secret files of the units of the deployed compose file from Parameter Store (AR-S-04, AR-S-13; P-05 spec
# Section 6.7). Runs on the main host as root, from {VIGIL_ROOT}/deploy/; never prints a secret value.
#   deploy/secrets.sh {installation}              the secrets of deploy/units.yaml (zabbix-api is left unchanged)
#   deploy/secrets.sh {installation} --generated  copies the generated zabbix-api and writes values/derived.yaml
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}
DEPLOY="$VIGIL_ROOT/deploy"
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

usage() {
  echo "Usage: deploy/secrets.sh {installation} [--generated]"
  exit 2
}

pattern='^[a-z][a-z0-9-]{0,19}$'
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || ! [[ $1 =~ $pattern ]]; then
  usage
fi
I=$1
generated=no
if [ "$#" -eq 2 ]; then
  if [ "$2" != --generated ]; then
    usage
  fi
  generated=yes
fi

for file in units.yaml compose.yaml .env; do
  if [ ! -f "$DEPLOY/$file" ]; then
    echo "$file missing in $DEPLOY."
    exit 1
  fi
done

if [ "$(id -u)" -eq 0 ]; then
  root=yes
else
  root=no
  echo "Not root: secret files keep the current user as owner."
fi

# Gives a file or folder to a user (only as root).
own() {
  if [ "$root" = yes ]; then
    chown "$2" "$1"
  fi
}

# The served units, one line each in the order of units.yaml: "{unit} {user} {secret} ...".
services=$(docker compose -p vigil -f "$DEPLOY/compose.yaml" --env-file "$DEPLOY/.env" config --services)
served=$(SERVICES="$services" python3 - "$DEPLOY/units.yaml" <<'PYTHON'
import os
import sys

import yaml

services = set(os.environ["SERVICES"].split())
with open(sys.argv[1]) as file:
    units = yaml.safe_load(file)["units"]
for name, unit in units.items():
    if name in services:
        print(" ".join([name, str(unit["user"])] + unit["secrets"]))
PYTHON
)

# Sets PARAMETER_JSON to the decrypted parameter, or stops when it is missing.
fetch() {
  if ! PARAMETER_JSON=$(aws ssm get-parameter --name "/vigil/$I/$1" --with-decryption --output json 2>&1); then
    case "$PARAMETER_JSON" in
      *ParameterNotFound*) echo "Missing secret /vigil/$I/$1 (AR-S-13)." ;;
      *) echo "$PARAMETER_JSON" ;;
    esac
    exit 1
  fi
}

# Writes the value of the fetched parameter to standard output, as it is.
value() {
  printf '%s' "$PARAMETER_JSON" | python3 -c 'import json, sys; sys.stdout.write(json.load(sys.stdin)["Parameter"]["Value"])'
}

# Writes standard input to {folder}/{name} (mode 0400, owner the user) through a temporary file and a rename.
put_file() {
  local folder=$1 name=$2 user=$3 temporary="$1/.$2.tmp"
  rm -f "$temporary"
  (umask 0377 && cat > "$temporary")
  chmod 0400 "$temporary"
  own "$temporary" "$user"
  mv -f "$temporary" "$folder/$name"
}

# Creates a unit's folder when missing and opens it for writing (0700).
open_folder() {
  mkdir -p "$1"
  chmod 0700 "$1"
  own "$1" "$2"
}

if printf '%s\n' "$served" | grep -q '^lab-zabbix-provision '; then
  mkdir -p "$VIGIL_ROOT/generated"
  own "$VIGIL_ROOT/generated" 10001
  chmod 0700 "$VIGIL_ROOT/generated"
fi

if [ "$generated" = no ]; then
  while read -r unit user secrets; do
    if [ -z "$unit" ]; then
      continue
    fi
    folder="$VIGIL_ROOT/secrets/$unit"
    open_folder "$folder" "$user"
    for path in "$folder"/* "$folder"/.[!.]*; do
      if [ ! -e "$path" ]; then
        continue
      fi
      case " $secrets zabbix-api " in
        *" ${path##*/} "*) ;;
        *) rm -rf "$path" ;;
      esac
    done
    count=0
    for name in $secrets; do
      case "$name" in
        zabbix-api) continue ;;
        alertmanager-web-config)
          fetch alertmanager-password
          value | python3 "$HERE/web_config.py" | put_file "$folder" "$name" "$user"
          ;;
        *)
          fetch "$name"
          value | put_file "$folder" "$name" "$user"
          ;;
      esac
      count=$((count + 1))
    done
    chmod 0500 "$folder"
    echo "$unit: $count secrets"
  done <<< "$served"
  exit 0
fi

# --generated: the zabbix-api token of lab-zabbix-provision (AR-S-04) and the derived values (AR-B-07).
readers=$(printf '%s\n' "$served" | awk '{ for (i = 3; i <= NF; i++) if ($i == "zabbix-api") print $1 " " $2 }')
if [ -n "$readers" ]; then
  if [ -f "$VIGIL_ROOT/generated/zabbix-api" ]; then
    while read -r unit user; do
      folder="$VIGIL_ROOT/secrets/$unit"
      open_folder "$folder" "$user"
      put_file "$folder" zabbix-api "$user" < "$VIGIL_ROOT/generated/zabbix-api"
      chmod 0500 "$folder"
      echo "$unit: zabbix-api copied"
    done <<< "$readers"
  else
    echo "No generated zabbix-api yet; left unchanged."
  fi
fi

fetch lab-db-01-host-key
key_folder=$(mktemp -d)
# shellcheck disable=SC2064 # the folder name is fixed now
trap "rm -rf '$key_folder'" EXIT
(umask 0177 && { value; echo; } > "$key_folder/host-key")
chmod 0600 "$key_folder/host-key"
fingerprint=$(ssh-keygen -l -E sha256 -f "$key_folder/host-key" | cut -d ' ' -f 2)
rm -rf "$key_folder"
if [ ! -d "$VIGIL_ROOT/values" ]; then
  mkdir -m 0755 "$VIGIL_ROOT/values"
fi
printf 'lab_db_host_key_sha256: "%s"\n' "$fingerprint" > "$VIGIL_ROOT/values/.derived.yaml.tmp"
chmod 0644 "$VIGIL_ROOT/values/.derived.yaml.tmp"
mv -f "$VIGIL_ROOT/values/.derived.yaml.tmp" "$VIGIL_ROOT/values/derived.yaml"
echo "derived.yaml written"
