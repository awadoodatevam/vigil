"""The control panel's Reset for deploy/local.sh reset (P-31 spec Section 6.7 item 4; AR-B-03, CTX-D-093 (10)).

Usage: control_reset.py --url URL --password-file FILE

Signs in to demo-control as operator with the password file, sends POST /api/buttons/reset, then reads /api/state
every 1 s until last_result.operation is "Reset" with finished_at later than the time the request was sent (at most
120 s). Prints the status line; exits 0 when the reset completed, 1 otherwise. The password is sent only in the
request body. No proxy of the environment is used. Standard library only.
"""

import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

USAGE = "Usage: control_reset.py --url URL --password-file FILE"
WAIT_SECONDS = 120
EVERY_SECONDS = 1
TIMEOUT_SECONDS = 30
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, url, body=None, cookie=None):
    """(status, headers, JSON body or None); raises OSError when there is no answer."""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if cookie:
        request.add_header("Cookie", cookie)
    try:
        with OPENER.open(request, timeout=TIMEOUT_SECONDS) as response:
            status, headers, raw = response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        status, headers, raw = error.code, error.headers, error.read()
    try:
        answer = json.loads(raw) if raw else None
    except ValueError:
        answer = None
    return status, headers, answer


def parse(arguments):
    if len(arguments) != 4:
        return None
    options = dict(zip(arguments[::2], arguments[1::2], strict=True))
    if set(options) != {"--url", "--password-file"}:
        return None
    return options["--url"].rstrip("/"), options["--password-file"]


def instant(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


def reset(url, password, clock=time.time, sleep=time.sleep):
    """The exit status; prints what happened."""
    status, headers, answer = call("POST", url + "/api/session", {"username": "operator", "password": password})
    if status != 204:
        print(f"Sign-in to the control panel answered HTTP {status}.")
        return 1
    cookie = (headers.get("Set-Cookie") or "").split(";", 1)[0]
    sent = clock()
    status, _headers, answer = call("POST", url + "/api/buttons/reset", {}, cookie)
    if status != 202:
        reason = answer.get("reason") if isinstance(answer, dict) else None
        print(f"Reset was refused: {reason}" if reason else f"Reset answered HTTP {status}.")
        return 1
    end = sent + WAIT_SECONDS
    while True:
        status, _headers, state = call("GET", url + "/api/state", cookie=cookie)
        if status == 200 and isinstance(state, dict):
            last = state.get("last_result")
            if isinstance(last, dict) and last.get("operation") == "Reset" and instant(last["finished_at"]) > sent:
                print(state.get("status"))
                return 0 if last.get("outcome") == "completed" else 1
        if clock() >= end:
            print(f"The reset did not finish within {WAIT_SECONDS} s.")
            return 1
        sleep(EVERY_SECONDS)


def main(arguments):
    parsed = parse(arguments)
    if parsed is None:
        print(USAGE)
        return 2
    url, password_file = parsed
    try:
        password = Path(password_file).read_text(encoding="utf-8")
    except OSError as error:
        print(f"The password file cannot be read: {error.strerror}")
        return 1
    try:
        return reset(url, password)
    except OSError as error:
        print(f"The control panel did not answer: {error}")
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
