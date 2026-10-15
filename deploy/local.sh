#!/usr/bin/env bash
# The local installation (docs/60-build-plan.md Section 11). So far: the lab only, with monitoring, demo-faults and
# the webhook recorder (P-03, P-04).
#   deploy/local.sh up --lab-only [--profile test|demo] [--project NAME]
#   deploy/local.sh down [--volumes] [--project NAME]
# Runs with macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

COMPOSE_FILE=deploy/local/compose.yaml
LAB_HOSTS=(lab-db-01 lab-api-01 lab-vas-01 lab-probe-01 lab-rpt-01)
IMAGE=vigil/demo-lab:local
PROVISION_IMAGE=vigil/lab-zabbix-provision:local
LOCAL_IMAGES=("$IMAGE" vigil/demo-faults:local "$PROVISION_IMAGE" vigil/recorder:local vigil/storm-spike:local)
# Every fixed container name of the compose file (other-project check).
CONTAINERS=("${LAB_HOSTS[@]}" lab-zabbix-db lab-zabbix-server lab-zabbix-web lab-zabbix-provision lab-prometheus
  lab-alertmanager demo-faults dev-recorder storm-spike)
# Started by up --lab-only (step 6) and waited for until healthy (step 7).
STARTED=("${LAB_HOSTS[@]}" lab-zabbix-db lab-zabbix-server lab-zabbix-web lab-prometheus lab-alertmanager demo-faults
  dev-recorder)
HEALTHY=("${LAB_HOSTS[@]}" lab-zabbix-db demo-faults dev-recorder)
# The monitoring and fault secrets (AR-S-13, P-04 spec Section 6.7): name, make_secret.py kind, unit folders in order.
SECRET_TABLE="webhook-zabbix token vigil-app lab-zabbix-provision
webhook-alertmanager token vigil-app lab-alertmanager
alertmanager-password password vigil-app demo-control lab-prometheus
alertmanager-web-config web-config lab-alertmanager
zabbix-admin-password password lab-zabbix-provision
zabbix-db-password password lab-zabbix-db lab-zabbix-server lab-zabbix-web
faults-token token demo-control demo-faults"
UNITS=(lab-db-01 vigil-app lab-zabbix-provision lab-alertmanager demo-control lab-prometheus lab-zabbix-db
  lab-zabbix-server lab-zabbix-web demo-faults)

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

# Adds one secret file (0444) to a unit folder (0555), making the folder writable only while it adds the file;
# an existing file of that name is replaced.
add_secret() {
  local folder=$1 name=$2 source=$3
  chmod 0755 "$folder"
  rm -f "$folder/$name"
  cp "$source" "$folder/$name"
  chmod 0444 "$folder/$name"
  chmod 0555 "$folder"
}

# Prints the image names of the compose file that are pinned by digest (the third-party images).
third_party_images() {
  awk '$1 == "image:" && $2 ~ /@sha256:/ { print $2 }' "$COMPOSE_FILE"
}

# The monitoring and fault secrets (step 3 of up --lab-only); arguments: secrets folder, work folder, project.
monitoring_secrets() {
  local secrets=$1 work=$2 project=$3
  local name kind folders folder holder make="" args="" made_password=no
  while read -r name kind folders; do
    holder=""
    for folder in $folders; do
      if [ -e "$secrets/$folder/$name" ]; then
        holder="$secrets/$folder/$name"
        break
      fi
    done
    if [ -z "$holder" ] || { [ "$name" = alertmanager-web-config ] && [ "$made_password" = yes ]; }; then
      make="$make $name"
      case "$kind" in
        web-config)
          if [ "$made_password" = no ]; then
            for folder in vigil-app demo-control lab-prometheus; do
              if [ -e "$secrets/$folder/alertmanager-password" ]; then
                cp "$secrets/$folder/alertmanager-password" "$work/alertmanager-password"
                break
              fi
            done
          fi
          args="$args web-config:/work/$name:/work/alertmanager-password"
          ;;
        *) args="$args $kind:/work/$name" ;;
      esac
      if [ "$name" = alertmanager-password ]; then
        made_password=yes
      fi
    fi
  done <<< "$SECRET_TABLE"

  case " $make " in
    *" zabbix-db-password "*)
      if docker volume inspect "${project}_lab-zabbix-db-data" > /dev/null 2>&1; then
        echo "zabbix-db-password is new but the Zabbix database was kept: run deploy/local.sh down --volumes --project $project"
        exit 1
      fi
      ;;
  esac
  if [ -n "$args" ]; then
    # shellcheck disable=SC2086 # one word per argument
    docker run --rm --user "$(id -u):$(id -g)" -v "$work":/work --entrypoint /opt/venv/bin/python \
      "$PROVISION_IMAGE" /opt/provision/make_secret.py $args
  fi

  while read -r name kind folders; do
    holder=""
    case " $make " in
      *" $name "*) holder="$work/$name" ;;
    esac
    if [ -z "$holder" ]; then
      for folder in $folders; do
        if [ -e "$secrets/$folder/$name" ]; then
          holder="$secrets/$folder/$name"
          break
        fi
      done
    fi
    for folder in $folders; do
      if [ "$holder" = "$work/$name" ] || [ ! -e "$secrets/$folder/$name" ]; then
        add_secret "$secrets/$folder" "$name" "$holder"
      fi
    done
  done <<< "$SECRET_TABLE"
}

# Prints the state, and the last 50 log lines, of the services that up --lab-only waits for.
diagnostics() {
  local service
  compose ps -a
  for service in "${HEALTHY[@]}"; do
    compose logs --tail 50 "$service"
  done
}

compose() {
  docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "$@"
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
    for host in "${CONTAINERS[@]}"; do
      if [ "$name" = "$host" ] && [ "$owner" != "$project" ]; then
        echo "Lab containers of project $owner exist: run deploy/local.sh down --project $owner first."
        exit 1
      fi
    done
  done < <(docker ps -a --format '{{.Names}} {{.Label "com.docker.compose.project"}}')

  local image missing=no
  for image in "${LOCAL_IMAGES[@]}" $(third_party_images); do
    if ! docker image inspect "$image" > /dev/null 2>&1; then
      missing=yes
    fi
  done
  if [ "$missing" = yes ]; then
    deploy/images.sh --test
  fi

  # Secrets (BP-R-26, AR-S-13).
  local folder
  for folder in "$base" "$base/$project" "$secrets"; do
    if [ ! -d "$folder" ]; then
      mkdir -m 0700 "$folder"
    fi
  done
  for folder in "${UNITS[@]}"; do
    if [ ! -d "$secrets/$folder" ]; then
      mkdir -m 0555 "$secrets/$folder"
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
  monitoring_secrets "$secrets" "$work" "$project"

  # Derived value lab_db_host_key_sha256 (AR-B-07), every time.
  if [ ! -d "$values" ]; then
    mkdir -m 0755 "$values"
  fi
  { cat "$host_key"; echo; } > "$work/fingerprint-key"
  chmod 0600 "$work/fingerprint-key"
  in_lab_image "$work" "ssh-keygen -l -E sha256 -f /work/fingerprint-key | cut -d ' ' -f 2 > /work/fingerprint"
  printf 'lab_db_host_key_sha256: "%s"\n' "$(cat "$work/fingerprint")" > "$values/derived.yaml"
  chmod 0644 "$values/derived.yaml"

  # Folders and configuration (step 5).
  local generated="$base/$project/generated" config="$base/$project/config" recordings="$base/$project/recordings"
  if [ ! -d "$generated" ]; then
    mkdir -m 0700 "$generated"
  fi
  for folder in "$config" "$recordings"; do
    if [ ! -d "$folder" ]; then
      mkdir -m 0755 "$folder"
    fi
  done
  local url
  url=$(sed -n 's/^webhook_base_url: *//p' deploy/values/local.yaml)
  awk -v url="$url" '{
    out = ""; rest = $0
    while ((i = index(rest, "${webhook_base_url}")) > 0) { out = out substr(rest, 1, i - 1) url; rest = substr(rest, i + 19) }
    print out rest
  }' demo/monitoring/alertmanager/alertmanager.yml > "$config/alertmanager.yml"
  chmod 0644 "$config/alertmanager.yml"

  # Start (step 6); every later compose command uses the same environment.
  VIGIL_LOCAL_UID=$(id -u)
  VIGIL_LOCAL_GID=$(id -g)
  export VIGIL_LOCAL_SECRETS="$secrets" VIGIL_LAB_PROFILE="$profile" VIGIL_LOCAL_UID VIGIL_LOCAL_GID
  export VIGIL_LOCAL_GENERATED="$generated" VIGIL_LOCAL_CONFIG="$config" VIGIL_LOCAL_RECORDINGS="$recordings"
  export VIGIL_WEBHOOK_BASE_URL="$url"
  PROJECT=$project
  if ! compose up -d "${STARTED[@]}"; then
    diagnostics
    exit 1
  fi

  # Wait (step 7).
  local waited=0 healthy service
  while true; do
    healthy=0
    for service in "${HEALTHY[@]}"; do
      if [ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$service" 2> /dev/null)" = healthy ]; then
        healthy=$((healthy + 1))
      fi
    done
    if [ "$healthy" -eq "${#HEALTHY[@]}" ]; then
      break
    fi
    if [ "$waited" -ge 180 ]; then
      diagnostics
      exit 1
    fi
    sleep 2
    waited=$((waited + 2))
  done

  # Provision (step 8): docker wait prints the exit code also when the container has already stopped.
  compose up -d lab-zabbix-provision
  local code
  code=$(docker wait lab-zabbix-provision)
  if [ "$code" != 0 ]; then
    docker logs lab-zabbix-provision
    exit 1
  fi
  for folder in vigil-app demo-control; do
    if ! cmp -s "$generated/zabbix-api" "$secrets/$folder/zabbix-api"; then
      add_secret "$secrets/$folder" zabbix-api "$generated/zabbix-api"
    fi
  done
  echo "Lab is up (profile $profile, project $project)."
}

lab_down() {
  local project=$1 volumes=$2
  local args=(--profile lab-only --profile spike down --remove-orphans)
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
