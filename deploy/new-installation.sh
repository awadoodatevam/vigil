#!/usr/bin/env bash
# Generates the secrets of an installation that AR-S-13 marks "new-installation" into Parameter Store
# /vigil/{installation}/ (P-05 spec Section 6.6). An existing parameter is never read or changed; no value is printed
# or passed on a command line. Run on the Mac: deploy/new-installation.sh {installation}.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: deploy/new-installation.sh {installation}"
  exit 2
}

if [ "$#" -ne 1 ] || ! known_installation "$1"; then
  usage
fi
I=$1
check_identity

# The 21 secrets, in the order of AR-S-13, with their kind: token, 20 or 24 (password length), key.
SECRETS="webhook-zabbix token
webhook-alertmanager token
zabbix-admin-password 20
zabbix-db-password 20
alertmanager-password 20
lab-db-01-diag key
lab-db-01-action key
lab-db-01-diag-pub key
lab-db-01-action-pub key
lab-db-01-host-key key
seed-password-presenter 20
seed-password-noc-l1 20
seed-password-dbops-engineer 20
seed-password-dbops-lead 20
seed-password-admin 20
installation-token token
control-password 20
faults-token token
vigil-db-superuser 24
vigil-db-owner 24
vigil-db-app 24"

existing=$(aws ssm describe-parameters --parameter-filters "Key=Path,Option=OneLevel,Values=/vigil/$I" --output json |
  json '[p["Name"].rsplit("/", 1)[-1] for p in d["Parameters"]]')
has() {
  printf '%s\n' "$existing" | grep -qx "$1"
}

for k in diag action; do
  private="lab-db-01-$k"
  public="lab-db-01-$k-pub"
  if has "$private" && ! has "$public"; then
    echo "Secret pair incomplete: /vigil/$I/$private exists without /vigil/$I/$public."
    exit 1
  fi
  if has "$public" && ! has "$private"; then
    echo "Secret pair incomplete: /vigil/$I/$public exists without /vigil/$I/$private."
    exit 1
  fi
done

WORK=$(temp_folder)
# shellcheck disable=SC2064 # the folder name is fixed now
trap "rm -rf '$WORK'" EXIT

# Prints the content of an OpenSSH private key file as the JSON secret of AR-S-13, without a trailing newline
# (key_json of deploy/local.sh).
key_json() {
  awk -v user="$1" 'BEGIN { printf "{\"username\":\"%s\",\"private_key\":\"", user } { printf "%s\\n", $0 } END { printf "\"}" }' "$2"
}

# Tokens and passwords: one file per missing secret, without a newline.
kinds=""
while read -r name kind; do
  if ! has "$name"; then
    case "$kind" in
      token | 20 | 24) kinds="$kinds $kind:$name" ;;
    esac
  fi
done <<< "$SECRETS"
# shellcheck disable=SC2086 # one word per secret
python3 - "$WORK" $kinds <<'PYTHON'
import os
import secrets
import string
import sys

alphabet = string.ascii_uppercase + string.ascii_lowercase + string.digits
for argument in sys.argv[2:]:
    kind, name = argument.split(":")
    if kind == "token":
        value = secrets.token_urlsafe(32)
    else:
        value = "".join(secrets.choice(alphabet) for _ in range(int(kind)))
    descriptor = os.open(os.path.join(sys.argv[1], name), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as file:
        file.write(value)
PYTHON

# Key pairs, made only when both parameters are missing, and the host key.
for k in diag action; do
  if ! has "lab-db-01-$k"; then
    ssh-keygen -q -t ed25519 -N '' -C "vigil-$k" -f "$WORK/$k"
    key_json "vigil-$k" "$WORK/$k" > "$WORK/lab-db-01-$k"
    tr -d '\n' < "$WORK/$k.pub" > "$WORK/lab-db-01-$k-pub"
  fi
done
if ! has lab-db-01-host-key; then
  ssh-keygen -q -t ed25519 -N '' -C lab-db-01 -f "$WORK/host"
  printf '%s' "$(cat "$WORK/host")" > "$WORK/lab-db-01-host-key"
fi

while read -r name kind; do
  if has "$name"; then
    echo "$name: kept"
  else
    aws ssm put-parameter --name "/vigil/$I/$name" --type SecureString --tier Standard --value "file://$WORK/$name" \
      --output json > /dev/null
    echo "$name: created"
  fi
done <<< "$SECRETS"
