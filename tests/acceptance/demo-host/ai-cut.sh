#!/usr/bin/env bash
# SB-T-09 part one on the demo installation (P-32 spec Section 6.9 item 5, CTX-D-095 (8)): with SL-F-4 active, for 10
# minutes every 30 s, from vigil-app's network namespace, Claude and OpenAI are unreachable within 5 s while Jira, the
# Teams webhook host, SES (STARTTLS) and the public name connect (ai-cut-host.sh on the main host). Refuses unless the
# control panel lists SL-F-4 as active. Ends with PASS SB-T-09 part one, or one FAIL line per failed kind; exit 0 or 1.
# The operator presses "Cut cloud AI: SL-F-4" before and "Clear all faults" after. Run on the Mac:
#   tests/acceptance/demo-host/ai-cut.sh {installation} 2>&1 | tee tests/acceptance/results/P-32-ai-cut.txt
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: tests/acceptance/demo-host/ai-cut.sh {installation}"
  exit 2
}

if [ "$#" -ne 1 ] || ! known_installation "$1"; then
  usage
fi
I=$1
D=$(values_value "deploy/values/$I.yaml" demo_domain)
JIRA=$(values_value "deploy/values/$I.yaml" endpoint.jira)
JIRA_HOST=$(python3 -c 'import sys; from urllib.parse import urlsplit; print(urlsplit(sys.argv[1]).hostname)' "$JIRA")
CONTROL=${VIGIL_CONTROL_URL:-https://control.$D}
PASSWORD_FILE="$HOME/.vigil-demo/$I/secrets/control-password"
if [ ! -f "$PASSWORD_FILE" ]; then
  echo "Run tests/acceptance/demo-host/secrets.sh $I first."
  exit 1
fi

# The control panel's active faults, as the operator (the password is sent only in the sign-in's body).
if ! active=$(python3 - "$CONTROL" "$PASSWORD_FILE" <<'PYTHON'
import json
import sys
import urllib.error
import urllib.request

url, password_file = sys.argv[1].rstrip("/"), sys.argv[2]
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, path, body=None, cookie=None):
    request = urllib.request.Request(url + path, method=method, data=None if body is None else json.dumps(body).encode())
    request.add_header("Content-Type", "application/json")
    if cookie:
        request.add_header("Cookie", cookie)
    try:
        with opener.open(request, timeout=30) as answer:
            return answer.status, answer.headers, answer.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()


with open(password_file) as file:
    password = file.read()
status, headers, _ = call("POST", "/api/session", {"username": "operator", "password": password})
if status != 204:
    print(f"Sign-in to the control panel answered HTTP {status}.")
    sys.exit(1)
cookie = (headers.get("Set-Cookie") or "").split(";", 1)[0]
status, _, body = call("GET", "/api/state", cookie=cookie)
if status != 200:
    print(f"The control panel's state answered HTTP {status}.")
    sys.exit(1)
print(" ".join(a.get("fault", "") for a in json.loads(body).get("active") or []))
PYTHON
); then
  echo "$active"
  exit 1
fi
if ! grep -qw SL-F-4 <<< "$active"; then
  echo "SL-F-4 is not active: press \"Cut cloud AI: SL-F-4\" in the control panel first."
  exit 1
fi
echo "control panel: active faults $active"

check_identity
require_running_host "vigil-$I-main" main
WORK=$(temp_folder)
# shellcheck disable=SC2064 # the folder name is fixed now
trap "rm -rf '$WORK'" EXIT
code=0
run_on_host "$HOST_ID" tests/acceptance/demo-host/ai-cut-host.sh -- "demo.$D" "$JIRA_HOST" > "$WORK/log" || code=$?
cat "$WORK/log"
if [ "$code" -ne 0 ]; then
  echo "FAIL SB-T-09 part one: the host part ended with exit code $code"
  exit 1
fi

# The verdicts: 20 rounds; claude and openai failed in every round, the others connected in every round (each attempt
# has 5 s, so a failure or a connection is within 5 s).
python3 - "$WORK/log" <<'PYTHON'
import re
import sys

LINE = re.compile(r"^round (\d+) \S+ (\S+) .+? (\d+\.\d\d) s (connected|failed\b.*)$")
BLOCKED, REACHABLE = ("claude", "openai"), ("jira", "public-name", "teams", "ses")
attempts = {}
for line in open(sys.argv[1]).read().splitlines():
    match = LINE.match(line)
    if match:
        number, kind, seconds, outcome = match.groups()
        attempts.setdefault(kind, {})[int(number)] = (float(seconds), outcome)
failed = []
for kind in BLOCKED + REACHABLE:
    rounds = attempts.get(kind, {})
    complete = sorted(rounds) == list(range(1, 21))
    if kind in BLOCKED:
        good = all(outcome.startswith("failed") for _, outcome in rounds.values())
    else:
        good = all(outcome == "connected" for _, outcome in rounds.values())
    if not (complete and good):
        failed.append(kind)
for kind in failed:
    print(f"FAIL SB-T-09 {kind}")
if not failed:
    print("PASS SB-T-09 part one")
sys.exit(1 if failed else 0)
PYTHON
