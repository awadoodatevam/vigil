"""Makes one secret file of a local installation (P-26 spec Section 6.3; AR-S-04, AR-S-13).

Usage: make_secrets.py {kind} {file}. Kinds (complete list): password24, password20, token, jira, smtp, api-key,
teams. The value is written to the file with mode 0600 and no trailing newline; nothing is printed, so no value is on
a command line or in a log. Standard library only.
"""

import json
import os
import secrets
import string
import sys

USAGE = "Usage: make_secrets.py password24|password20|token|jira|smtp|api-key|teams {file}"
ALPHABET = string.ascii_lowercase + string.ascii_uppercase + string.digits


def password(length):
    return "".join(secrets.choice(ALPHABET) for _ in range(length))


def token():
    """32 random bytes, base64url."""
    return secrets.token_urlsafe(32)


def value(kind, path):
    if kind == "password24":
        return password(24)
    if kind == "password20":
        return password(20)
    if kind in ("token", "api-key"):
        return token()
    if kind == "jira":
        return json.dumps({"email": "vigil@vigil.test", "api_token": token(), "expires_on": "2099-12-31"})
    if kind == "smtp":
        return json.dumps({"username": "vigil", "password": password(20)})
    if kind == "teams":
        return f"http://teams.fakes:8000/{os.path.basename(path)}"
    return None


def write(path, text):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as file:
        file.write(text)
    os.chmod(path, 0o600)


def main(arguments):
    if len(arguments) != 2:
        print(USAGE)
        return 2
    text = value(arguments[0], arguments[1])
    if text is None:
        print(USAGE)
        return 2
    write(arguments[1], text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
