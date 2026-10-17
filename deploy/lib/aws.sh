# shellcheck shell=bash
# Shared functions of the Mac scripts of the demo installation (P-05 spec Section 6.1). Sourced, not executed, by
# deploy/edge.sh, new-installation.sh, build.sh, deploy.sh, status.sh, gpu.sh, models.sh and
# tests/acceptance/p05_aws_check.sh, from the repository root. Runs with macOS's bash 3.2; JSON and encoding work is
# done with python3 (standard library, Python 3.9 or later); no AWS CLI --query.

export AWS_REGION=us-east-1
export AWS_PAGER=""

# The host named in run_on_host's messages: main or GPU.
HOST_KIND=main

# Sleeps that many seconds, or not at all when VIGIL_TEST_FAST=1 (tests).
pause() {
  if [ "${VIGIL_TEST_FAST:-}" != 1 ]; then
    sleep "$1"
  fi
}

# Evaluates a Python expression over the JSON on standard input, bound to d (with the module json); prints a list one
# item per line,
# a boolean as true or false, and nothing for None.
JSON_PROGRAM='
import json
import sys

d = json.load(sys.stdin)
r = eval(sys.argv[1], {"d": d, "json": json})
if isinstance(r, (list, tuple)):
    for x in r:
        print(x)
elif isinstance(r, bool):
    print("true" if r else "false")
elif r is not None:
    print(r)
'
json() {
  python3 -c "$JSON_PROGRAM" "$1"
}

# Like json, but writes one text value exactly, without adding a newline.
json_raw() {
  python3 -c 'import json, sys; sys.stdout.write(eval(sys.argv[1], {"d": json.load(sys.stdin)}))' "$1"
}

# Prints the error code of an AWS CLI error text ("An error occurred (Code) when calling ..."), or nothing.
error_code() {
  printf '%s\n' "$1" | sed -n 's/.*An error occurred (\([A-Za-z0-9.]*\)).*/\1/p' | head -n 1
}

# Prints a new folder with mode 0700 for temporary files; the caller removes it.
temp_folder() {
  python3 -c 'import tempfile; print(tempfile.mkdtemp(prefix="vigil-"))'
}

# The identity check: sets ACCOUNT_ID, CALLER_ARN and REGISTRY, or stops with exit 1.
check_identity() {
  local out
  if ! out=$(aws sts get-caller-identity --output json 2> /dev/null); then
    echo "Sign in first: aws login --profile vigil, then export AWS_PROFILE=vigil (deploy/edge.md)."
    exit 1
  fi
  ACCOUNT_ID=$(printf '%s' "$out" | json 'd["Account"]')
  # shellcheck disable=SC2034 # used by the scripts that source this file
  CALLER_ARN=$(printf '%s' "$out" | json 'd["Arn"]')
  # shellcheck disable=SC2034 # used by the scripts that source this file
  REGISTRY="$ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com"
}

# True when the argument is a valid installation name.
valid_installation() {
  local pattern='^[a-z][a-z0-9-]{0,19}$'
  [[ $1 =~ $pattern ]]
}

# True when the argument is a valid release name.
valid_release() {
  local pattern='^[a-z0-9][a-z0-9.-]{0,39}$'
  [[ $1 =~ $pattern ]]
}

# True when the installation name is valid and has its values file.
known_installation() {
  valid_installation "$1" && [ -f "deploy/values/$1.yaml" ]
}

# Prints a value of a values file: the text after "{name}: " on the line that starts with it.
values_value() {
  awk -v prefix="$2: " 'index($0, prefix) == 1 { print substr($0, length(prefix) + 1); exit }' "$1"
}

# The deployment bucket of an installation (BP-A-21); needs ACCOUNT_ID.
bucket_name() {
  echo "vigil-$1-deploy-$ACCOUNT_ID"
}

# Prints "{instance id} {state}" of the instance with that Name tag (pending, running, stopping or stopped), or nothing.
find_instance() {
  aws ec2 describe-instances --filters "Name=tag:Name,Values=$1" \
    "Name=instance-state-name,Values=pending,running,stopping,stopped" --output json |
    json '[i["InstanceId"] + " " + i["State"]["Name"] for r in d["Reservations"] for i in r["Instances"]][:1]'
}

# Prints the state of an instance.
instance_state() {
  aws ec2 describe-instances --instance-ids "$1" --output json | json 'd["Reservations"][0]["Instances"][0]["State"]["Name"]'
}

# Prints the Systems Manager ping status of an instance (Online, ConnectionLost, Inactive), or nothing.
ping_status() {
  aws ssm describe-instance-information --filters "Key=InstanceIds,Values=$1" --output json |
    json '[i["PingStatus"] for i in d["InstanceInformationList"]][:1]'
}

# Sets HOST_ID to the instance with that Name tag, which MUST exist and be running; KIND is main or GPU.
require_running_host() {
  local name=$1 kind=$2 found state
  found=$(find_instance "$name")
  if [ -z "$found" ]; then
    if [ "$kind" = GPU ]; then
      echo "The GPU host does not exist yet (deploy/edge.sh, BP-OT-09)."
    else
      echo "The main host does not exist yet (deploy/edge.sh)."
    fi
    exit 1
  fi
  # shellcheck disable=SC2034 # used by the scripts that source this file
  HOST_ID=${found%% *}
  state=${found#* }
  if [ "$state" != running ]; then
    echo "The $kind host is $state. Start it first (deploy/edge.md, or deploy/gpu.sh start for the GPU host)."
    exit 1
  fi
}

# Writes the parameters of an AWS-RunShellScript command to a file. Arguments: the output file, the script file,
# the other files, --, the script's arguments. Each file goes inline as base64 (at most 16 KiB).
# shellcheck disable=SC2016 # the shell variables are expanded on the host
RUN_PROGRAM='
import base64
import json
import os
import secrets
import shlex
import sys

out, script, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
split = rest.index("--") if "--" in rest else len(rest)
files, arguments = [script] + rest[:split], rest[split + 1 :]
folder = "$R/run/" + secrets.token_hex(8)
lines = [
    "export AWS_REGION=us-east-1",
    "export PATH=\"$PATH:/snap/bin\"",
    "R=\"${VIGIL_ROOT:-/srv/vigil}\"",
    "mkdir -p \"$R/run\" || exit 1",
    "D=\"" + folder + "\"",
    "mkdir -m 0700 \"$D\" || exit 1",
]
for path in files:
    with open(path, "rb") as file:
        data = file.read()
    if len(data) > 16384:
        print(path + " is larger than 16 KiB.")
        sys.exit(1)
    encoded = base64.b64encode(data).decode()
    target = "\"$D/" + os.path.basename(path) + "\""
    lines.append("printf %s " + encoded + " | base64 -d > " + target + " || { rm -rf \"$D\"; exit 1; }")
command = "bash \"$D/" + os.path.basename(script) + "\""
for argument in arguments:
    command += " " + shlex.quote(argument)
lines += ["cd \"$D\"", command, "code=$?", "cd /", "rm -rf \"$D\"", "exit $code"]
with open(out, "w") as file:
    json.dump({"commands": lines, "executionTimeout": ["3600"]}, file)
'

# run_on_host {instance id} {script file} [{file} ...] -- [{argument} ...]: runs a repository script on the host as
# root through Systems Manager, prints its standard output and error and returns its exit code (HOST_KIND names the
# host in messages).
run_on_host() {
  local instance=$1 work params attempt=0 command_id out status=unknown code
  shift
  work=$(temp_folder)
  params="$work/parameters.json"
  if ! python3 -c "$RUN_PROGRAM" "$params" "$@"; then
    rm -rf "$work"
    exit 1
  fi
  while [ "$(ping_status "$instance")" != Online ]; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
      rm -rf "$work"
      echo "The $HOST_KIND host is not reachable through Systems Manager."
      exit 1
    fi
    pause 5
  done
  if ! out=$(aws ssm send-command --instance-ids "$instance" --document-name AWS-RunShellScript \
    --parameters "file://$params" --output json); then
    rm -rf "$work"
    return 1
  fi
  rm -rf "$work"
  command_id=$(printf '%s' "$out" | json 'd["Command"]["CommandId"]')
  attempt=0
  while [ "$attempt" -lt 730 ]; do
    attempt=$((attempt + 1))
    pause 5
    if ! out=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" --output json 2>&1); then
      if [ "$(error_code "$out")" = InvocationDoesNotExist ]; then
        continue
      fi
      echo "$out" >&2
      return 1
    fi
    status=$(printf '%s' "$out" | json 'd["Status"]')
    case "$status" in
      Pending | InProgress | Delayed | Cancelling) status=unknown ;;
      *) break ;;
    esac
  done
  if [ "$status" = unknown ]; then
    echo "The command on the $HOST_KIND host ended with status unknown."
    return 1
  fi
  printf '%s' "$out" | json_raw 'd.get("StandardOutputContent") or ""'
  printf '%s' "$out" | json_raw 'd.get("StandardErrorContent") or ""' >&2
  if [ "$status" != Success ] && [ "$status" != Failed ]; then
    echo "The command on the $HOST_KIND host ended with status $status."
    return 1
  fi
  code=$(printf '%s' "$out" | json 'd.get("ResponseCode", 1)')
  if [ "$status" = Failed ] && { [ "$code" -le 0 ] || [ "$code" -gt 255 ]; }; then
    code=1
  fi
  return "$code"
}
