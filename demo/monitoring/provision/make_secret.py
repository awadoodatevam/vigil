"""Makes secret files for deploy/local.sh (P-04 spec Section 6.2.4); run in the lab-zabbix-provision image.

Arguments: one or more {kind}:{output path}[:{input path}], handled in order. Kinds (complete list): token, password,
web-config. Each value is written to its output path with mode 0600, without a trailing newline (except the last
newline of the YAML). Prints nothing.
"""

import os
import secrets
import string
import sys

import bcrypt


def write(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as file:
        file.write(value)
    os.chmod(path, 0o600)


def make(kind, input_path):
    if kind == "token":
        return secrets.token_urlsafe(32)
    if kind == "password":
        alphabet = string.ascii_uppercase + string.ascii_lowercase + string.digits
        return "".join(secrets.choice(alphabet) for _ in range(20))
    if kind == "web-config":
        with open(input_path) as file:
            password = file.read()
        hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10)).decode()
        return f"basic_auth_users:\n  vigil: {hashed}\n"
    raise SystemExit(2)


def main(arguments):
    if not arguments:
        return 2
    for argument in arguments:
        kind, output, *rest = argument.split(":")
        write(output, make(kind, rest[0] if rest else None))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
