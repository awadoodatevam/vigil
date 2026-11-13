#!/usr/bin/env bash
# Runs the storm driver on the local installation and then checks the logs of vigil-app and vigil-migrate for the
# installation's secrets (P-27 spec Section 6.6; SB-T-02, AR-Q-07).
#   tests/acceptance/storm/run.sh [--mode immediate|script] [--deliveries fakes|checklist] [--acts driver|pages]
#     [--project NAME]
# Runs with macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

CALLER_DIR=$PWD
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
ROOT=$PWD

COMPOSE_FILE=deploy/local/compose.yaml
IMAGE=vigil/storm-driver:local

usage() {
  echo "Usage: tests/acceptance/storm/run.sh [--mode immediate|script] [--deliveries fakes|checklist] [--acts driver|pages] [--project NAME]"
  exit 2
}

mode=immediate
deliveries=fakes
acts=driver
project=vigil-local
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode)
      if [ "$#" -lt 2 ]; then usage; fi
      case "$2" in immediate | script) mode=$2 ;; *) usage ;; esac
      shift
      ;;
    --deliveries)
      if [ "$#" -lt 2 ]; then usage; fi
      case "$2" in fakes | checklist) deliveries=$2 ;; *) usage ;; esac
      shift
      ;;
    --acts)
      if [ "$#" -lt 2 ]; then usage; fi
      case "$2" in driver | pages) acts=$2 ;; *) usage ;; esac
      shift
      ;;
    --project)
      if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
      project=$2
      shift
      ;;
    *) usage ;;
  esac
  shift
done

if [ -x "${VIGIL_TOOLS:-/opt/vigil-tools}/bin/python" ]; then
  PY="${VIGIL_TOOLS:-/opt/vigil-tools}/bin/python"
else
  PY=python3.12
fi

# Step 1: the installation and its lab profile.
base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
if [ ! -f "$base/$project/state/values-file" ]; then
  echo "Run deploy/local.sh up first."
  exit 1
fi
profile="test"
if [ -f "$base/$project/state/lab-profile" ]; then
  profile=$(cat "$base/$project/state/lab-profile")
fi

# Step 2: the image.
if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  echo "Run deploy/local.sh up first (it builds $IMAGE)."
  exit 1
fi

# Step 3: the environment of the compose call, as deploy/local.sh exports it.
VIGIL_LOCAL_UID=$(id -u)
VIGIL_LOCAL_GID=$(id -g)
export VIGIL_LOCAL_UID VIGIL_LOCAL_GID VIGIL_LOCAL_SECRETS="$base/$project/secrets"
export VIGIL_LOCAL_STATE="$base/$project/state" VIGIL_LAB_PROFILE="$profile"
results="${VIGIL_STORM_RESULTS:-$ROOT/tests/acceptance/results}"
case "$results" in
  /*) ;;
  *) results="$CALLER_DIR/$results" ;;
esac
mkdir -p "$results"
VIGIL_STORM_RESULTS="$(cd "$results" && pwd)"
export VIGIL_STORM_RESULTS

# Step 4: the driver.
driver=0
docker compose -f "$COMPOSE_FILE" -p "$project" --profile acceptance run --rm -T storm-driver \
  --mode "$mode" --deliveries "$deliveries" --acts "$acts" || driver=$?

# Step 5: AR-Q-07, the whole logs of vigil-app and vigil-migrate (which include this run).
folder="$base/$project/acceptance"
if [ ! -d "$folder" ]; then
  mkdir -m 0700 "$folder"
fi
log="$folder/vigil-$(date -u +%Y%m%dT%H%M%SZ).log"
scan=0
if (
  umask 077
  docker compose -f "$COMPOSE_FILE" -p "$project" logs --no-color --no-log-prefix vigil-app vigil-migrate > "$log"
); then
  chmod 0600 "$log"
  "$PY" tests/acceptance/log_scan.py --log "$log" --secrets-dir "$base/$project/secrets" \
    --ignore-fields username,email,expires_on || scan=$?
else
  echo "The logs of vigil-app and vigil-migrate could not be read."
  scan=1
fi
if [ "$scan" -eq 0 ]; then
  echo "AR-Q-07 passed: no secret of the installation in the log of this run."
else
  echo "AR-Q-07 failed"
fi

# Step 6.
if [ "$driver" -eq 0 ] && [ "$scan" -eq 0 ]; then
  exit 0
fi
exit 1
