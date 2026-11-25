#!/usr/bin/env bash
# The checks of the demo installation with Vigil deployed (P-32 spec Section 6.9 item 4; P-05's p05_aws_check.sh
# method): prints its parts (date, status, load balancer, main host, HTTPS answers) with their exit codes and ends with
# one line per check, PASS {check} or FAIL {check}; exits 1 when a check fails. Never prints a secret value. Run on the
# Mac while the main host runs, after deploy/deploy.sh {installation} {release}:
#   tests/acceptance/demo-host/check.sh {installation} 2>&1 | tee tests/acceptance/results/P-32-aws.txt
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: tests/acceptance/demo-host/check.sh {installation}"
  exit 2
}

if [ "$#" -ne 1 ] || ! known_installation "$1"; then
  usage
fi
I=$1
check_identity
D=$(values_value "deploy/values/$I.yaml" demo_domain)
# The curl of the TLS check: macOS's own, as P-05 (tests set VIGIL_TLS_CURL).
TLS_CURL=${VIGIL_TLS_CURL:-/usr/bin/curl}
WORK=$(temp_folder)
# shellcheck disable=SC2064 # the folder name is fixed now
trap "rm -rf '$WORK'" EXIT

# Runs a part: its heading, its output (also kept in the work folder) and its exit code.
part() {
  local name=$1 code
  shift
  echo "== $name"
  set +e
  ("$@") 2>&1 | tee "$WORK/$name.out"
  code=${PIPESTATUS[0]}
  set -e
  echo "exit $code"
}

part_date() {
  date -u
  aws --version
  echo "caller: $CALLER_ARN"
}

part_status() {
  deploy/status.sh "$I"
}

part_load_balancer() {
  local group arn
  for group in vigil-app demo-control; do
    arn=$(aws elbv2 describe-target-groups --names "$group" --output json | json 'd["TargetGroups"][0]["TargetGroupArn"]')
    aws elbv2 describe-target-health --target-group-arn "$arn" --output json |
      json '["target group '"$group"' " + t["Target"]["Id"] + ":" + str(t["Target"]["Port"]) + " " + t["TargetHealth"]["State"] for t in d["TargetHealthDescriptions"]]'
  done
}

part_main_host() {
  require_running_host "vigil-$I-main" main
  run_on_host "$HOST_ID" tests/acceptance/demo-host/check-host.sh -- "demo.$D"
}

part_https() {
  local lb_dns
  lb_dns=$(aws elbv2 describe-load-balancers --names vigil-alb --output json | json 'd["LoadBalancers"][0]["DNSName"]')
  echo "-- http-redirect"
  curl -sS -o /dev/null -D - -w 'status %{http_code}\n' "http://demo.$D/"
  echo "-- https-demo"
  curl -sS -o /dev/null -w 'status %{http_code}\n' "https://demo.$D/login"
  echo "-- actuator-404"
  curl -sS -o /dev/null -w 'status %{http_code}\n' "https://demo.$D/actuator/health"
  echo "-- https-control"
  curl -sS -w '\nstatus %{http_code}\n' "https://control.$D/"
  echo "-- unknown-host-404"
  curl -sS -k -o /dev/null -w 'status %{http_code}\n' "https://$lb_dns/"
  echo "-- tls-1.1-refused"
  set +e
  "$TLS_CURL" -sS -o /dev/null --tlsv1.0 --tls-max 1.1 "https://demo.$D/" 2>&1
  echo "exit code $?"
  set -e
}

part date part_date
part status part_status
part load-balancer part_load_balancer
part main-host part_main_host
part https part_https

# The checks (P-32 spec Section 6.9 item 4), on the parts' outputs.
python3 - "$WORK" "$D" deploy/units.yaml <<'PYTHON'
import json
import re
import sys
from pathlib import Path

work, domain, units_file = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
ONE_SHOT = {"vigil-migrate", "lab-zabbix-provision"}


def read(name):
    path = work / f"{name}.out"
    return path.read_text() if path.exists() else ""


def sections(text):
    """{name: text} of the '-- {name}' sections of a part."""
    found, name = {}, None
    for line in text.splitlines():
        if line.startswith("-- "):
            name = line[3:].strip()
            found[name] = []
        elif name is not None:
            found[name].append(line)
    return {key: "\n".join(lines) for key, lines in found.items()}


https, host, status = sections(read("https")), sections(read("main-host")), read("status")


def status_of(text):
    codes = re.findall(r"^status (\d{3})$", text, re.MULTILINE)
    return codes[-1] if codes else None


def http_redirect():
    text = https.get("http-redirect", "")
    location = re.search(r"^location:\s*(\S+)", text, re.MULTILINE | re.IGNORECASE)
    return status_of(text) == "301" and bool(location) and location.group(1).startswith(f"https://demo.{domain}")


def https_control():
    text = https.get("https-control", "")
    return status_of(text) == "200" and "<title>Demo control panel</title>" in text


def tls():
    text = https.get("tls-1.1-refused", "")
    return "exit code 35" in text and "alert protocol version" in text.lower()


def targets():
    lines = [line for line in read("load-balancer").splitlines() if line.startswith("target group ")]
    groups = {line.split()[2] for line in lines}
    return groups == {"vigil-app", "demo-control"} and all(line.endswith(" healthy") for line in lines)


def published():
    ports = {}
    for line in host.get("published-ports", "").splitlines()[:-1]:
        name, _, mapped = line.partition("\t")
        for item in mapped.split(","):
            if "->" in item:
                ports.setdefault(name.strip(), []).append(item.strip().split("->")[0])
    return ports == {"vigil-app": ["10.40.1.10:8080"], "demo-control": ["10.40.1.10:8090"]}


def docker_user():
    return any(
        "-s 172.31.0.0/24" in line and "-d 172.30.0.10/32" in line and "--ctstate NEW" in line and "-j DROP" in line
        for line in host.get("docker-user", "").splitlines()
    )


def units_with_secrets():
    names, current = [], None
    for line in Path(units_file).read_text().splitlines():
        match = re.match(r"^  ([a-z0-9-]+):\s*$", line)
        if match:
            current = match.group(1)
        elif current and re.match(r"^      - \S", line):
            if current not in names:
                names.append(current)
    return [name for name in names if name not in ONE_SHOT]


def secrets_readable():
    lines = set(status.splitlines())
    expected = units_with_secrets()
    return bool(expected) and all(f"{unit}: secrets readable" in lines for unit in expected)


def correlation():
    for line in status.splitlines():
        if line.startswith("vigil-app health: "):
            try:
                health = json.loads(line[len("vigil-app health: ") :])
            except ValueError:
                return False
            return ((health.get("components") or {}).get("correlation") or {}).get("status") == "UP"
    return False


def clock():
    chrony, zone = host.get("chrony", ""), host.get("timedatectl", "")
    reference = [line for line in chrony.splitlines() if "Reference ID" in line]
    time_zone = [line for line in zone.splitlines() if "Time zone" in line]
    return bool(reference) and "169.254.169.123" in reference[0] and bool(time_zone) and "UTC" in time_zone[0]


def release():
    env = dict(line.split("=", 1) for line in host.get("env", "").splitlines() if "=" in line)
    deployed = host.get("deployed", "")
    named = re.search(r'^release: "([^"]+)"$', deployed, re.MULTILINE)
    services = [s for s in host.get("services", "").splitlines()[:-1] if s and s not in ONE_SHOT]
    listed = set(re.findall(r"^  ([a-z0-9-]+): ", deployed, re.MULTILINE))
    return (
        bool(named)
        and named.group(1) == env.get("VIGIL_RELEASE")
        and env.get("COMPOSE_PROFILES") == "vigil"
        and {"vigil-app", "demo-control"} <= set(services)
        and set(services) <= listed
    )


def public_name():
    """200, or 404 unknown_host: Vigil answered through the public name before any tenant alias exists (AR-I-02)."""
    return re.search(r"^lab-public-name (200|404 unknown_host)$", host.get("lab-public-name", ""), re.M) is not None


CHECKS = [
    ("http-redirect", http_redirect),
    ("https-demo", lambda: status_of(https.get("https-demo", "")) == "200"),
    ("actuator-404", lambda: status_of(https.get("actuator-404", "")) == "404"),
    ("https-control", https_control),
    ("unknown-host-404", lambda: status_of(https.get("unknown-host-404", "")) == "404"),
    ("tls-1.1-refused", tls),
    ("targets-healthy", targets),
    ("published-ports", published),
    ("DOCKER-USER", docker_user),
    ("lab-to-app-blocked", lambda: "lab-to-app blocked after " in host.get("lab-to-app", "")),
    ("lab-public-name", public_name),
    ("secrets-readable", secrets_readable),
    ("correlation", correlation),
    ("clock", clock),
    ("release", release),
]
failed = 0
for name, check in CHECKS:
    try:
        passed = bool(check())
    except Exception:  # noqa: BLE001 - an output the check cannot read fails it
        passed = False
    print(f"{'PASS' if passed else 'FAIL'} {name}")
    failed |= not passed
sys.exit(1 if failed else 0)
PYTHON
