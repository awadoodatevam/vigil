"""Prints the Alertmanager web configuration for the password read on standard input (AR-S-13 secret
alertmanager-web-config; P-05 spec Section 6.7), exactly as the web-config kind of
demo/monitoring/provision/make_secret.py writes it. Run by deploy/secrets.sh on the main host (python3-bcrypt).
"""

import sys

import bcrypt


def web_config(password):
    hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10)).decode()
    return f"basic_auth_users:\n  vigil: {hashed}\n"


def main():
    sys.stdout.write(web_config(sys.stdin.read()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
