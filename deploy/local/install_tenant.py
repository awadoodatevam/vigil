"""Creates, imports and aliases a tenant of a local installation (P-26 spec Section 6.3 step 7; AR-I-95 to AR-I-98).

Usage: install_tenant.py --values FILE --values FILE --state DIR --token-file FILE

Builds the seed bundle from docs/31-demo-data/ with bundle.py's function into {state}/bundle.zip, creates the tenant
"local {UTC time}", imports the bundle, reads the import every 2 s until it is not running (at most 300 s) and writes
the answer to {state}/seed-import.json, then points the alias demo.{demo_domain} at the tenant and writes the answer to
{state}/tenant.json. The installation token is read from its file and sent only in the Authorization header.
Standard library only.
"""

import json
import secrets
import sys
import time
import urllib.error
import urllib.request
from datetime import UTC, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import bundle  # noqa: E402

USAGE = "Usage: install_tenant.py --values FILE [--values FILE ...] --state DIR --token-file FILE"
DATA = Path(__file__).resolve().parent.parent.parent / "docs" / "31-demo-data"
BASE_URL = "http://127.0.0.1:8080"
POLL_SECONDS = 2
IMPORT_LIMIT_SECONDS = 300
TIMEOUT_SECONDS = 30
# The installation is on this machine: no proxy of the environment is used.
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Failed(Exception):
    """A step that failed; the message is printed."""


class Api:
    def __init__(self, base, token):
        self.base = base
        self.token = token

    def call(self, operation, method, path, expected, body=None, content_type="application/json"):
        data = None
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Authorization", f"Bearer {self.token}")
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", content_type)
        try:
            with OPENER.open(request, timeout=TIMEOUT_SECONDS) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        except OSError as error:
            raise Failed(f"{operation}: {error}") from None
        if status != expected:
            raise Failed(f"{operation} answered {status}: {raw.decode('utf-8', errors='replace')[:500]}")
        try:
            return json.loads(raw)
        except ValueError:
            raise Failed(f"{operation} answered {status} without JSON.") from None


def multipart(field, filename, content):
    boundary = "vigil-" + secrets.token_hex(16)
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        "Content-Type: application/zip\r\n\r\n"
    ).encode()
    return head + content + f"\r\n--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def install(value_paths, state, token, base=BASE_URL, clock=time.monotonic, sleep=time.sleep):
    values = bundle.read_values(value_paths)
    content = bundle.build(DATA, value_paths)
    state.mkdir(parents=True, exist_ok=True)
    (state / "bundle.zip").write_bytes(content)
    api = Api(base, token)
    name = "local " + datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    tenant_id = api.call("Create tenant (AR-I-95)", "POST", "/api/v1/installation/tenants", 200, {"name": name})[
        "tenant_id"
    ]
    body, content_type = multipart("bundle", "bundle.zip", content)
    seed_import_id = api.call(
        "Seed import (AR-I-96)",
        "POST",
        f"/api/v1/installation/tenants/{tenant_id}/seed-imports",
        202,
        body,
        content_type,
    )["seed_import_id"]
    started = clock()
    while True:
        answer = api.call(
            "Seed import state (AR-I-97)", "GET", f"/api/v1/installation/seed-imports/{seed_import_id}", 200
        )
        if answer.get("state") != "running":
            break
        if clock() - started >= IMPORT_LIMIT_SECONDS:
            raise Failed(f"The seed import did not finish within {IMPORT_LIMIT_SECONDS} s.")
        sleep(POLL_SECONDS)
    write_json(state / "seed-import.json", answer)
    failed = [part for part in answer.get("parts", []) if part.get("outcome") != "succeeded"]
    if answer.get("state") != "succeeded" or failed:
        lines = [f"The seed import ended {answer.get('state')}."]
        lines += [f"{part.get('module')}: {part.get('error')}" for part in failed if part.get("error")]
        raise Failed("\n".join(lines))
    host = f"demo.{values['demo_domain']}"
    alias = api.call(
        "Set alias (AR-I-98)", "PUT", f"/api/v1/installation/aliases/{host}", 200, {"tenant_id": tenant_id}
    )
    write_json(state / "tenant.json", {"name": name, **alias})
    return alias


def parse(arguments):
    values, state, token_file = [], None, None
    index = 0
    while index < len(arguments):
        if index + 1 >= len(arguments):
            return None
        flag, value = arguments[index], arguments[index + 1]
        if flag == "--values":
            values.append(value)
        elif flag == "--state" and state is None:
            state = value
        elif flag == "--token-file" and token_file is None:
            token_file = value
        else:
            return None
        index += 2
    if not values or state is None or token_file is None:
        return None
    return values, Path(state), Path(token_file)


def main(arguments):
    parsed = parse(arguments)
    if parsed is None:
        print(USAGE)
        return 2
    values, state, token_file = parsed
    try:
        token = token_file.read_text(encoding="utf-8")
        alias = install(values, state, token, base=BASE_URL)
    except (Failed, bundle.BundleError) as error:
        print(error)
        return 1
    print(f"Tenant {alias['tenant_id']} is ready at {alias['host_name']}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
