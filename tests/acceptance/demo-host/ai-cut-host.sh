#!/usr/bin/env bash
# The main host's part of tests/acceptance/demo-host/ai-cut.sh (P-32 spec Section 6.9 item 5; SB-T-09 part one), run
# by run_on_host while SL-F-4 is active: for 10 minutes, every 30 s, from a container sharing vigil-app's network
# namespace (the deployed demo-faults image, python3), a TCP connection with TLS handshake to the Claude and OpenAI
# APIs, the Jira site, the public name and the Teams webhook host, each within 5 s, and an SMTP session to SES up to
# STARTTLS and its handshake. One line per attempt; the Teams host is read here from vigil-app's secret, passed to the
# container on its standard input and never printed.
#   ai-cut-host.sh {public host name of Vigil} {Jira host}
set -euo pipefail

VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}

demo=$1
jira=$2
image=$(docker inspect --format '{{.Config.Image}}' demo-faults)
teams=$(python3 -c '
import sys
from urllib.parse import urlsplit

with open(sys.argv[1]) as file:
    print(urlsplit(file.read().strip()).hostname or "")
' "$VIGIL_ROOT/secrets/vigil-app/teams-team-noc-l1")
if [ -z "$teams" ]; then
  echo "The secret teams-team-noc-l1 has no host."
  exit 1
fi
printf '%s\n' "$teams" | docker run --rm -i --network container:vigil-app --entrypoint /usr/bin/python3 "$image" -c '
import smtplib
import socket
import ssl
import sys
import time
from datetime import datetime, timezone

ROUNDS, EVERY, LIMIT = 20, 30, 5
SES = "email-smtp.us-east-1.amazonaws.com"
demo, jira = sys.argv[1], sys.argv[2]
teams = sys.stdin.readline().strip()
context = ssl.create_default_context()
targets = [
    ("claude", "api.anthropic.com", "api.anthropic.com:443"),
    ("openai", "api.openai.com", "api.openai.com:443"),
    ("jira", jira, jira + ":443"),
    ("public-name", demo, demo + ":443"),
    ("teams", teams, "Teams webhook host:443"),
]


def tls(host):
    start = time.monotonic()
    with socket.create_connection((host, 443), timeout=LIMIT) as raw:
        raw.settimeout(max(0.1, LIMIT - (time.monotonic() - start)))
        with context.wrap_socket(raw, server_hostname=host):
            pass


def starttls():
    start = time.monotonic()
    with smtplib.SMTP(SES, 587, timeout=LIMIT) as smtp:
        smtp.ehlo()
        smtp.sock.settimeout(max(0.1, LIMIT - (time.monotonic() - start)))
        smtp.starttls(context=context)


def attempt(number, kind, label, call, secret=False):
    start = time.monotonic()
    try:
        call()
        outcome = "connected"
    except Exception as error:
        outcome = "failed " + type(error).__name__ + ("" if secret else ": " + str(error).replace("\n", " "))
    seconds = time.monotonic() - start
    now = datetime.now(timezone.utc).strftime("%H:%M:%SZ")
    print(f"round {number} {now} {kind} {label} {seconds:.2f} s {outcome}", flush=True)


begin = time.monotonic()
for number in range(1, ROUNDS + 1):
    wait = begin + (number - 1) * EVERY - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    for kind, host, label in targets:
        attempt(number, kind, label, lambda host=host: tls(host), secret=kind == "teams")
    attempt(number, "ses", SES + ":587", starttls)
' "$demo" "$jira"
