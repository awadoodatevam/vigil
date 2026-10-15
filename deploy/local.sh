#!/usr/bin/env bash
# The local installation (docs/60-build-plan.md Section 11). This package: the lab only.
#   deploy/local.sh up --lab-only [--profile test|demo] [--project NAME]
#   deploy/local.sh down [--volumes] [--project NAME]
# Runs with macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

COMPOSE_FILE=deploy/local/compose.yaml
LAB_HOSTS=(lab-db-01 lab-api-01 lab-vas-01 lab-probe-01 lab-rpt-01)
IMAGE=vigil/demo-lab:local

usage() {
  echo "Usage: deploy/local.sh up --lab-only [--profile test|demo] [--project NAME] | deploy/local.sh down [--volumes] [--project NAME]"
  exit 2
}

# Generates the missing key pairs and writes the host key fingerprint, with ssh-keygen in the lab image as root.
# Arguments: the work folder and the ssh-keygen commands; the files come back owned by the user who runs this script.
in_lab_image() {
  local work=$1 commands=$2
  docker run --rm --entrypoint sh -v "$work":/work "$IMAGE" \
    -c "$commands && chown -R $(id -u):$(id -g) /work"
}

# Adds one secret file (0444) to a unit folder (0555), making the folder writable only while it adds the file.
add_secret() {
  local folder=$1 name=$2 source=$3
  chmod 0755 "$folder"
  cp "$source" "$folder/$name"
  chmod 0444 "$folder/$name"
  chmod 0555 "$folder"
}

# Prints the content of an OpenSSH private key file as the JSON secret of AR-S-13, without a trailing newline.
key_json() {
  awk -v user="$1" 'BEGIN { printf "{\"username\":\"%s\",\"private_key\":\"", user } { printf "%s\\n", $0 } END { printf "\"}" }' "$2"
}

lab_up() {
  local project=$1 profile=$2
  local base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
  local secrets="$base/$project/secrets" values="$base/$project/values"

  if ! docker info > /dev/null 2>&1; then
    echo "Docker is not running."
    exit 1
  fi
  local name owner host
  while read -r name owner; do
    for host in "${LAB_HOSTS[@]}"; do
      if [ "$name" = "$host" ] && [ "$owner" != "$project" ]; then
        echo "Lab containers of project $owner exist: run deploy/local.sh down --project $owner first."
        exit 1
      fi
    done
  done < <(docker ps -a --filter name=^lab- --format '{{.Names}} {{.Label "com.docker.compose.project"}}')

  if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
    deploy/images.sh --test
  fi

  # Secrets (BP-R-26, AR-S-13).
  local folder
  for folder in "$base" "$base/$project" "$secrets"; do
    if [ ! -d "$folder" ]; then
      mkdir -m 0700 "$folder"
    fi
  done
  for folder in "$secrets/lab-db-01" "$secrets/vigil-app"; do
    if [ ! -d "$folder" ]; then
      mkdir -m 0555 "$folder"
    fi
  done
  local k private public generate=""
  for k in diag action; do
    private="$secrets/vigil-app/lab-db-01-$k"
    public="$secrets/lab-db-01/lab-db-01-$k-pub"
    if [ ! -e "$private" ] && [ ! -e "$public" ]; then
      generate="$generate $k"
    elif [ ! -e "$private" ]; then
      echo "Secret pair incomplete: delete $public or restore $private."
      exit 1
    elif [ ! -e "$public" ]; then
      echo "Secret pair incomplete: delete $private or restore $public."
      exit 1
    fi
  done

  local work
  work=$(mktemp -d)
  chmod 0700 "$work"
  # shellcheck disable=SC2064 # the folder name is fixed now
  trap "rm -rf '$work'" EXIT

  local commands=true
  for k in $generate; do
    commands+=" && ssh-keygen -q -t ed25519 -N '' -C vigil-$k -f /work/$k"
  done
  local host_key="$secrets/lab-db-01/lab-db-01-host-key"
  if [ ! -e "$host_key" ]; then
    commands+=" && ssh-keygen -q -t ed25519 -N '' -C lab-db-01 -f /work/host"
  fi
  if [ "$commands" != true ]; then
    in_lab_image "$work" "$commands"
  fi
  for k in $generate; do
    key_json "vigil-$k" "$work/$k" > "$work/$k.json"
    add_secret "$secrets/vigil-app" "lab-db-01-$k" "$work/$k.json"
    tr -d '\n' < "$work/$k.pub" > "$work/$k.pub-line"
    add_secret "$secrets/lab-db-01" "lab-db-01-$k-pub" "$work/$k.pub-line"
  done
  if [ ! -e "$host_key" ]; then
    printf '%s' "$(cat "$work/host")" > "$work/host-line"
    add_secret "$secrets/lab-db-01" lab-db-01-host-key "$work/host-line"
  fi

  # Derived value lab_db_host_key_sha256 (AR-B-07), every time.
  if [ ! -d "$values" ]; then
    mkdir -m 0755 "$values"
  fi
  { cat "$host_key"; echo; } > "$work/fingerprint-key"
  chmod 0600 "$work/fingerprint-key"
  in_lab_image "$work" "ssh-keygen -l -E sha256 -f /work/fingerprint-key | cut -d ' ' -f 2 > /work/fingerprint"
  printf 'lab_db_host_key_sha256: "%s"\n' "$(cat "$work/fingerprint")" > "$values/derived.yaml"
  chmod 0644 "$values/derived.yaml"

  VIGIL_LOCAL_SECRETS="$secrets" VIGIL_LAB_PROFILE="$profile" \
    docker compose -f "$COMPOSE_FILE" -p "$project" up -d "${LAB_HOSTS[@]}"

  local waited=0 healthy
  while true; do
    healthy=0
    for host in "${LAB_HOSTS[@]}"; do
      if [ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$host" 2> /dev/null)" = healthy ]; then
        healthy=$((healthy + 1))
      fi
    done
    if [ "$healthy" -eq "${#LAB_HOSTS[@]}" ]; then
      break
    fi
    if [ "$waited" -ge 180 ]; then
      docker compose -f "$COMPOSE_FILE" -p "$project" ps
      for host in "${LAB_HOSTS[@]}"; do
        docker compose -f "$COMPOSE_FILE" -p "$project" logs --tail 50 "$host"
      done
      exit 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  echo "Lab is up (profile $profile, project $project)."
}

lab_down() {
  local project=$1 volumes=$2
  local args=(down)
  if [ "$volumes" = yes ]; then
    args+=(--volumes)
  fi
  docker compose -f "$COMPOSE_FILE" -p "$project" "${args[@]}"
}

command=${1:-}
if [ "$#" -gt 0 ]; then
  shift
fi
project=vigil-local
profile=""
lab_only=no
volumes=no
case "$command" in
  up)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --lab-only) lab_only=yes ;;
        --profile)
          if [ "$#" -lt 2 ]; then usage; fi
          case "$2" in test | demo) profile=$2 ;; *) usage ;; esac
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
    if [ "$lab_only" != yes ]; then
      echo "The full local installation arrives in package P-26."
      exit 2
    fi
    if [ -z "$profile" ]; then
      profile=$(awk -F': *' '$1 == "lab_profile" { print $2 }' deploy/values/local.yaml)
    fi
    lab_up "$project" "$profile"
    ;;
  down)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --volumes) volumes=yes ;;
        --project)
          if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
          project=$2
          shift
          ;;
        *) usage ;;
      esac
      shift
    done
    lab_down "$project" "$volumes"
    ;;
  *) usage ;;
esac
