#!/usr/bin/env bash
# The local installation (docs/60-build-plan.md Section 11): Vigil, the lab of P-03 and P-04, the dev fakes and
# Mailpit on one Docker host (P-26).
#   deploy/local.sh up [--profile test|demo] [--values FILE] [--no-build] [--project NAME]
#   deploy/local.sh up --lab-only [--profile test|demo] [--project NAME]
#   deploy/local.sh down [--volumes] [--project NAME]
#   deploy/local.sh reset [--project NAME]
#   deploy/local.sh logs [--no-follow] [SERVICE...] [--project NAME]
#   deploy/local.sh e2e [SUITE] [--project NAME]
# Runs with macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

CALLER_DIR=$PWD
cd "$(dirname "${BASH_SOURCE[0]}")/.."

COMPOSE_FILE=deploy/local/compose.yaml
LAB_HOSTS=(lab-db-01 lab-api-01 lab-vas-01 lab-probe-01 lab-rpt-01)
IMAGE=vigil/demo-lab:local
PROVISION_IMAGE=vigil/lab-zabbix-provision:local
FAKES_IMAGE=vigil/dev-fakes:local
E2E_IMAGE=vigil/e2e:local
LOCAL_IMAGES=("$IMAGE" vigil/demo-faults:local "$PROVISION_IMAGE" vigil/recorder:local vigil/storm-spike:local)
FAKES=(jira-fake teams-fake claude-fake openai-fake ollama-llm-fake ollama-embed-fake)
# Every fixed container name of the compose file (other-project check).
CONTAINERS=("${LAB_HOSTS[@]}" lab-zabbix-db lab-zabbix-server lab-zabbix-web lab-zabbix-provision lab-prometheus
  lab-alertmanager demo-faults dev-recorder storm-spike vigil-db vigil-migrate vigil-app "${FAKES[@]}" mailpit
  vigil-embed e2e)
# The lab services started and waited for until healthy; up --lab-only adds the recorder (P-04).
LAB_STARTED=("${LAB_HOSTS[@]}" lab-zabbix-db lab-zabbix-server lab-zabbix-web lab-prometheus lab-alertmanager
  demo-faults)
LAB_HEALTHY=("${LAB_HOSTS[@]}" lab-zabbix-db demo-faults)
# The Vigil services of up (step 6).
VIGIL_STARTED=(vigil-db vigil-migrate vigil-app "${FAKES[@]}" mailpit)
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
# The secrets of Vigil's units written only by the full up (P-26 spec Section 6.3 step 3, AR-S-13): name,
# make_secrets.py kind, unit folders in order (the first that holds it gives its value to the others).
VIGIL_UNITS=(vigil-migrate vigil-db e2e)
VIGIL_SECRET_TABLE="vigil-db-superuser password24 vigil-db
vigil-db-owner password24 vigil-db vigil-migrate
vigil-db-app password24 vigil-db vigil-app
seed-password-presenter password20 vigil-app e2e
seed-password-noc-l1 password20 vigil-app e2e
seed-password-dbops-engineer password20 vigil-app e2e
seed-password-dbops-lead password20 vigil-app e2e
seed-password-admin password20 vigil-app e2e
installation-token token vigil-app
jira jira vigil-app
smtp smtp vigil-app
claude api-key vigil-app
openai api-key vigil-app
teams-team-noc-l1 teams vigil-app
teams-team-db-ops teams vigil-app
teams-team-vas-service-owners teams vigil-app
teams-team-it-apps teams vigil-app
teams-team-noc-shift-lead teams vigil-app
teams-team-noc-management teams vigil-app
teams-team-customer-care teams vigil-app
teams-user-dbops-engineer teams vigil-app
teams-user-dbops-lead teams vigil-app"
READY_URL=http://127.0.0.1:8080/actuator/health/readiness
WAIT_VIGIL=300
FAULTS_URL=http://172.31.0.1:8091/faults/clear
# Run in a one-off container of the dev fakes image on lab-net: clears SL-F-1 to SL-F-3 with the faults-token.
CLEAR_FAULTS="import json, sys, urllib.request
token = open('/run/secrets/vigil/faults-token').read()
body = json.dumps({'faults': ['SL-F-1', 'SL-F-2', 'SL-F-3']}).encode()
request = urllib.request.Request('$FAULTS_URL', data=body, method='POST',
    headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
try:
    urllib.request.urlopen(request, timeout=120).read()
except Exception as error:
    print('Clearing the faults failed:', error)
    sys.exit(1)"

if [ -x "${VIGIL_TOOLS:-/opt/vigil-tools}/bin/python" ]; then
  PY="${VIGIL_TOOLS:-/opt/vigil-tools}/bin/python"
else
  PY=python3.12
fi

usage() {
  echo "Usage: deploy/local.sh up [--profile test|demo] [--values FILE] [--no-build] [--project NAME]
       deploy/local.sh up --lab-only [--profile test|demo] [--project NAME]
       deploy/local.sh down [--volumes] [--project NAME]
       deploy/local.sh reset [--project NAME]
       deploy/local.sh logs [--no-follow] [SERVICE...] [--project NAME]
       deploy/local.sh e2e [SUITE] [--project NAME]"
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

# Prints the image names of the compose file that are pinned by digest (the third-party images), except the lines
# ending with "# optional" (vigil-embed's image, the owner's option).
third_party_images() {
  awk '$1 == "image:" && $2 ~ /@sha256:/ && $0 !~ /# optional$/ { print $2 }' "$COMPOSE_FILE"
}

# Prints the value of a flat "name: value" line of a values file, without double quotes around it.
value_of() {
  awk -v name="$1" 'index($0, name ":") == 1 {
    value = substr($0, length(name) + 2); sub(/^[ \t]+/, "", value); sub(/[ \t]+$/, "", value)
    if (value ~ /^".*"$/) { value = substr(value, 2, length(value) - 2) }
    print value; exit
  }' "$2"
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

# The secrets of Vigil's units (step 3 of up): one value per name in every folder that holds it (AR-S-13); a file
# that exists is kept and copied to the folders of that name that lack it or hold another value.
vigil_secrets() {
  local secrets=$1 work=$2 project=$3
  local name kind folders folder holder new_db=no
  for folder in "${VIGIL_UNITS[@]}"; do
    if [ ! -d "$secrets/$folder" ]; then
      mkdir -m 0555 "$secrets/$folder"
    fi
  done
  while read -r name kind folders; do
    holder=""
    for folder in $folders; do
      if [ -e "$secrets/$folder/$name" ]; then
        holder=yes
        break
      fi
    done
    case "$name" in
      vigil-db-*) if [ -z "$holder" ]; then new_db=yes; fi ;;
    esac
  done <<< "$VIGIL_SECRET_TABLE"
  if [ "$new_db" = yes ] && docker volume inspect "${project}_vigil-db-data" > /dev/null 2>&1; then
    echo "vigil-db secrets are new but the database was kept: run deploy/local.sh down --volumes --project $project"
    exit 1
  fi
  while read -r name kind folders; do
    holder=""
    for folder in $folders; do
      if [ -e "$secrets/$folder/$name" ]; then
        holder="$secrets/$folder/$name"
        break
      fi
    done
    if [ -z "$holder" ]; then
      "$PY" deploy/local/make_secrets.py "$kind" "$work/$name"
      holder="$work/$name"
    fi
    for folder in $folders; do
      if [ "$holder" != "$secrets/$folder/$name" ] && ! cmp -s "$holder" "$secrets/$folder/$name"; then
        add_secret "$secrets/$folder" "$name" "$holder"
      fi
    done
  done <<< "$VIGIL_SECRET_TABLE"
}

# Prints the state, and the last 50 log lines, of the given services.
diagnostics() {
  local service
  compose ps -a
  for service in "$@"; do
    compose logs --tail 50 "$service"
  done
}

compose() {
  if [ "${EMBED:-no}" = yes ]; then
    docker compose -f "$COMPOSE_FILE" -p "$PROJECT" --profile embed "$@"
  else
    docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "$@"
  fi
}

# The environment of every compose call: the folders of the project (BP-R-26) and the user who runs this script.
export_environment() {
  local project=$1
  local base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
  VIGIL_LOCAL_UID=$(id -u)
  VIGIL_LOCAL_GID=$(id -g)
  export VIGIL_LOCAL_UID VIGIL_LOCAL_GID VIGIL_LOCAL_SECRETS="$base/$project/secrets"
  export VIGIL_LOCAL_GENERATED="$base/$project/generated" VIGIL_LOCAL_CONFIG="$base/$project/config"
  export VIGIL_LOCAL_RECORDINGS="$base/$project/recordings" VIGIL_LOCAL_STATE="$base/$project/state"
  export VIGIL_LOCAL_E2E="$base/$project/e2e"
  PROJECT=$project
}

# Prints the key of an OpenSSH private key file as the JSON secret of AR-S-13, without a trailing newline.
key_json() {
  awk -v user="$1" 'BEGIN { printf "{\"username\":\"%s\",\"private_key\":\"", user } { printf "%s\\n", $0 } END { printf "\"}" }' "$2"
}

# Docker running and no container of another project.
check_docker() {
  local project=$1
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
}

# deploy/images.sh --test when one of its images is missing.
test_images() {
  local image missing=no
  for image in "${LOCAL_IMAGES[@]}" $(third_party_images); do
    if ! docker image inspect "$image" > /dev/null 2>&1; then
      missing=yes
    fi
  done
  if [ "$missing" = yes ]; then
    deploy/images.sh --test
  fi
}

# The lab's secrets (BP-R-26, AR-S-13) and the derived value lab_db_host_key_sha256 (AR-B-07); arguments: project,
# work folder.
lab_secrets() {
  local project=$1 work=$2
  local base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
  local secrets="$base/$project/secrets" values="$base/$project/values"
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
}

# The lab's folders and configuration (step 5 of up --lab-only); arguments: project, values file.
lab_config() {
  local project=$1 values_file=$2
  local base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
  local generated="$base/$project/generated" config="$base/$project/config" recordings="$base/$project/recordings"
  local folder
  if [ ! -d "$generated" ]; then
    mkdir -m 0700 "$generated"
  fi
  for folder in "$config" "$recordings"; do
    if [ ! -d "$folder" ]; then
      mkdir -m 0755 "$folder"
    fi
  done
  local url
  url=$(value_of webhook_base_url "$values_file")
  awk -v url="$url" '{
    out = ""; rest = $0
    while ((i = index(rest, "${webhook_base_url}")) > 0) { out = out substr(rest, 1, i - 1) url; rest = substr(rest, i + 19) }
    print out rest
  }' demo/monitoring/alertmanager/alertmanager.yml > "$config/alertmanager.yml"
  chmod 0644 "$config/alertmanager.yml"
  export VIGIL_WEBHOOK_BASE_URL="$url"
}

# Waits until every given service is healthy (at most the given seconds); arguments: seconds, services.
wait_healthy() {
  local limit=$1
  shift
  local waited=0 healthy service
  while true; do
    healthy=0
    for service in "$@"; do
      if [ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$service" 2> /dev/null)" = healthy ]; then
        healthy=$((healthy + 1))
      fi
    done
    if [ "$healthy" -eq "$#" ]; then
      return 0
    fi
    if [ "$waited" -ge "$limit" ]; then
      return 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
}

# Starts the lab services, waits until they are healthy and provisions Zabbix (steps 6 to 8 of up --lab-only);
# arguments: the services to start, then -- and the services to wait for.
lab_start() {
  local started=() healthy=()
  while [ "$1" != -- ]; do
    started+=("$1")
    shift
  done
  shift
  healthy=("$@")
  if ! compose up -d "${started[@]}"; then
    diagnostics "${healthy[@]}"
    exit 1
  fi
  if ! wait_healthy 180 "${healthy[@]}"; then
    diagnostics "${healthy[@]}"
    exit 1
  fi

  # Provision: docker wait prints the exit code also when the container has already stopped.
  compose up -d lab-zabbix-provision
  local code folder
  code=$(docker wait lab-zabbix-provision)
  if [ "$code" != 0 ]; then
    docker logs lab-zabbix-provision
    exit 1
  fi
  for folder in vigil-app demo-control; do
    if ! cmp -s "$VIGIL_LOCAL_GENERATED/zabbix-api" "$VIGIL_LOCAL_SECRETS/$folder/zabbix-api"; then
      add_secret "$VIGIL_LOCAL_SECRETS/$folder" zabbix-api "$VIGIL_LOCAL_GENERATED/zabbix-api"
    fi
  done
}

new_work_folder() {
  WORK=$(mktemp -d)
  chmod 0700 "$WORK"
  # shellcheck disable=SC2064 # the folder name is fixed now
  trap "rm -rf '$WORK'" EXIT
}

lab_up() {
  local project=$1 profile=$2
  check_docker "$project"
  test_images
  new_work_folder
  lab_secrets "$project" "$WORK"
  export_environment "$project"
  export VIGIL_LAB_PROFILE="$profile"
  lab_config "$project" deploy/values/local.yaml
  lab_start "${LAB_STARTED[@]}" dev-recorder -- "${LAB_HEALTHY[@]}" dev-recorder
  echo "Lab is up (profile $profile, project $project)."
}

# Prints the reason, the last 50 log lines of the failing service, and exits 1.
fail() {
  echo "$1"
  compose logs --tail 50 "$2" || true
  exit 1
}

# Waits for step 6 of up: the fakes healthy, vigil-migrate exited 0 and vigil-app ready, at most WAIT_VIGIL seconds.
wait_vigil() {
  local waited=0 migrated state code service
  while true; do
    migrated=no
    state=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' vigil-migrate 2> /dev/null || true)
    case "$state" in
      "exited 0") migrated=yes ;;
      exited*) fail "vigil-migrate failed." vigil-migrate ;;
    esac
    if [ "$migrated" = yes ] && wait_healthy 0 "${FAKES[@]}"; then
      code=$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' "$READY_URL" || true)
      if [ "$code" = 200 ]; then
        return 0
      fi
    fi
    if [ "$waited" -ge "$WAIT_VIGIL" ]; then
      if [ "$migrated" != yes ]; then
        fail "vigil-migrate did not finish within $WAIT_VIGIL s." vigil-migrate
      fi
      for service in "${FAKES[@]}"; do
        if ! wait_healthy 0 "$service"; then
          fail "$service is not healthy after $WAIT_VIGIL s." "$service"
        fi
      done
      fail "vigil-app is not ready after $WAIT_VIGIL s." vigil-app
    fi
    sleep 2
    waited=$((waited + 2))
  done
}

# Step 7 of up and the second step of reset: a new tenant with the seed bundle, and the alias.
install_tenant() {
  local values_file=$1
  local base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
  local state="$base/$PROJECT/state"
  if ! "$PY" deploy/local/install_tenant.py --values "$values_file" --values "$base/$PROJECT/values/derived.yaml" \
    --state "$state" --token-file "$base/$PROJECT/secrets/vigil-app/installation-token"; then
    fail "The tenant could not be installed." vigil-app
  fi
}

full_up() {
  local project=$1 profile=$2 values_file=$3 build=$4
  local base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"

  # Step 1: checks.
  check_docker "$project"
  if [ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' dev-recorder 2> /dev/null || true)" = "$project" ]; then
    docker compose -f "$COMPOSE_FILE" -p "$project" --profile lab-only rm -sf dev-recorder
  fi

  # Step 2: build.
  if [ "$build" = yes ]; then
    ./mvnw -B -q -pl app -DskipTests package
    deploy/images.sh --local
  fi
  test_images

  # Step 3: secrets.
  new_work_folder
  lab_secrets "$project" "$WORK"
  vigil_secrets "$base/$project/secrets" "$WORK" "$project"

  # Step 4: values.
  local folder
  for folder in "$base/$project/state" "$base/$project/e2e"; do
    if [ ! -d "$folder" ]; then
      mkdir -m 0755 "$folder"
    fi
  done
  printf '%s\n' "$values_file" > "$base/$project/state/values-file"
  printf '%s\n' "$profile" > "$base/$project/state/lab-profile"
  EMBED=no
  if [ "$(value_of endpoint.local_embedding "$values_file")" = "http://vigil-embed:11434" ]; then
    EMBED=yes
  fi

  # Step 5: lab.
  export_environment "$project"
  export VIGIL_LAB_PROFILE="$profile"
  lab_config "$project" "$values_file"
  lab_start "${LAB_STARTED[@]}" -- "${LAB_HEALTHY[@]}"

  # Step 6: Vigil.
  if [ "$EMBED" = yes ]; then
    if ! compose up -d "${VIGIL_STARTED[@]}" vigil-embed; then
      diagnostics "${VIGIL_STARTED[@]}" vigil-embed
      exit 1
    fi
  elif ! compose up -d "${VIGIL_STARTED[@]}"; then
    diagnostics "${VIGIL_STARTED[@]}"
    exit 1
  fi
  wait_vigil
  if [ "$EMBED" = yes ]; then
    if ! wait_healthy "$WAIT_VIGIL" vigil-embed; then
      fail "vigil-embed is not healthy after $WAIT_VIGIL s." vigil-embed
    fi
    if ! compose exec -T vigil-embed ollama pull embeddinggemma; then
      fail "The model embeddinggemma could not be pulled." vigil-embed
    fi
  fi

  # Steps 7 and 8.
  install_tenant "$values_file"
  echo "Vigil: http://demo.vigil.test:8080 (user presenter; password in $base/$project/secrets/vigil-app/seed-password-presenter)"
}

lab_down() {
  local project=$1 volumes=$2
  local args=(--profile lab-only --profile spike --profile embed --profile e2e --profile acceptance down
    --remove-orphans)
  if [ "$volumes" = yes ]; then
    args+=(--volumes)
  fi
  docker compose -f "$COMPOSE_FILE" -p "$project" "${args[@]}"
}

reset() {
  local project=$1
  local base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
  if [ ! -f "$base/$project/state/values-file" ]; then
    echo "Run deploy/local.sh up first."
    exit 1
  fi
  local values_file
  values_file=$(cat "$base/$project/state/values-file")
  export_environment "$project"
  if ! docker run --rm --network "${project}_lab-net" --user "$(id -u):$(id -g)" \
    -v "$base/$project/secrets/demo-control:/run/secrets/vigil:ro" --entrypoint /usr/bin/python3 "$FAKES_IMAGE" \
    -c "$CLEAR_FAULTS"; then
    fail "The faults could not be cleared." demo-faults
  fi
  install_tenant "$values_file"
}

logs() {
  local follow=$1
  shift
  if [ "$follow" = yes ]; then
    docker compose -f "$COMPOSE_FILE" -p "$PROJECT" --profile lab-only --profile spike --profile embed --profile e2e \
      --profile acceptance logs --tail 200 --follow "$@"
  else
    docker compose -f "$COMPOSE_FILE" -p "$PROJECT" --profile lab-only --profile spike --profile embed --profile e2e \
      --profile acceptance logs --tail 200 "$@"
  fi
}

# Runs Playwright in the e2e container: {pw} of the P-26 spec Section 6.3 with the given arguments.
playwright() {
  docker compose -f "$COMPOSE_FILE" -p "$PROJECT" --profile e2e run --rm e2e \
    /e2e/node_modules/.bin/playwright test -c /e2e/tests/playwright.config.ts "$@"
}

e2e() {
  local suite=$1
  local base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
  if ! docker image inspect "$E2E_IMAGE" > /dev/null 2>&1; then
    deploy/images.sh --local
  fi
  export_environment "$PROJECT"
  if [ ! -d "$base/$PROJECT/e2e" ]; then
    mkdir -p "$base/$PROJECT/e2e"
  fi
  local status=0 pattern=@ai-down listing count
  if [ -n "$suite" ]; then
    playwright --grep-invert @ai-down --grep "@$suite" || status=1
    pattern="(?=.*@ai-down)(?=.*@$suite)"
  else
    playwright --grep-invert @ai-down || status=1
  fi
  listing=$(playwright --list --grep "$pattern" 2>&1 || true)
  count=$(printf '%s\n' "$listing" | sed -n 's/^Total: \([0-9][0-9]*\) test.*/\1/p' | head -n 1)
  if [ -n "$count" ] && [ "$count" -ge 1 ]; then
    docker compose -f "$COMPOSE_FILE" -p "$PROJECT" stop claude-fake openai-fake
    playwright --grep "$pattern" || status=1
    docker compose -f "$COMPOSE_FILE" -p "$PROJECT" start claude-fake openai-fake || status=1
  fi
  return "$status"
}

command=${1:-}
if [ "$#" -gt 0 ]; then
  shift
fi
project=vigil-local
profile=""
lab_only=no
volumes=no
build=yes
follow=yes
values_file=""
suite=""
services=()
case "$command" in
  up)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --lab-only) lab_only=yes ;;
        --no-build) build=no ;;
        --profile)
          if [ "$#" -lt 2 ]; then usage; fi
          case "$2" in test | demo) profile=$2 ;; *) usage ;; esac
          shift
          ;;
        --values)
          if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
          values_file=$2
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
    if [ "$lab_only" = yes ]; then
      if [ -n "$values_file" ] || [ "$build" = no ]; then
        usage
      fi
      if [ -z "$profile" ]; then
        profile=$(value_of lab_profile deploy/values/local.yaml)
      fi
      lab_up "$project" "$profile"
    else
      values_file=${values_file:-$PWD/deploy/values/local.yaml}
      case "$values_file" in
        /*) ;;
        *) values_file="$CALLER_DIR/$values_file" ;;
      esac
      if [ ! -f "$values_file" ]; then
        echo "Values file $values_file does not exist."
        exit 1
      fi
      values_file="$(cd "$(dirname "$values_file")" && pwd)/$(basename "$values_file")"
      if [ -z "$profile" ]; then
        profile=$(value_of lab_profile "$values_file")
      fi
      full_up "$project" "$profile" "$values_file" "$build"
    fi
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
  reset)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --project)
          if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
          project=$2
          shift
          ;;
        *) usage ;;
      esac
      shift
    done
    PROJECT=$project
    reset "$project"
    ;;
  logs)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --no-follow) follow=no ;;
        --project)
          if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
          project=$2
          shift
          ;;
        -*) usage ;;
        *) services+=("$1") ;;
      esac
      shift
    done
    PROJECT=$project
    if [ "${#services[@]}" -gt 0 ]; then
      logs "$follow" "${services[@]}"
    else
      logs "$follow"
    fi
    ;;
  e2e)
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --project)
          if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
          project=$2
          shift
          ;;
        -*) usage ;;
        *)
          if [ -n "$suite" ]; then usage; fi
          suite=$1
          ;;
      esac
      shift
    done
    PROJECT=$project
    e2e "$suite"
    ;;
  *) usage ;;
esac
