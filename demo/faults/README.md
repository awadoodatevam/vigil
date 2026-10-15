# demo-faults: the fault agent

`demo-faults` injects and clears the lab faults SL-F-1, SL-F-2 and SL-F-3 (AR-U-07, AR-X-03; package P-04; SL-F-4 arrives in P-31). Image `vigil/demo-faults:local`, built by `deploy/images.sh --test`; started by `deploy/local.sh up --lab-only` on the host network as user 0, with the Docker socket and the volume `demo-faults-state`.

## Interface

`http://172.31.0.1:8091` (the host's address on `lab-net`; `FAULTS_LISTEN`), every request with `Authorization: Bearer {faults-token}` (from `/run/secrets/vigil/faults-token`), otherwise 401 `{"error":"unauthorized"}`. Operations (complete list):

| Request | Answer |
| --- | --- |
| `POST /faults/{SL-F-1, SL-F-2 or SL-F-3}/inject` | 200 `{"active": [...]}`; nothing runs when the fault is already active. 500 `{"error": "{id} inject failed (exit {code}): {output}"}` (or `(no exit after 60 s)`) when the command fails; 500 `unknown fault {id}` for any other ID. |
| `POST /faults/clear` with `{"faults": [ids]}` | Runs the clear command of each named fault in the order SL-F-1, SL-F-2, SL-F-3, also when it is not active; 200 `{"active": [...]}`. Any unknown ID: 500 and nothing runs; a body that is not `{"faults": [strings]}`: 500 `invalid body`. |
| Anything else | 404 `{"error":"not found"}` |

Requests are handled one at a time. The active faults are kept in `/var/lib/demo-faults/state.json` (`{"active": [...]}`), so a restart keeps them. Each request is logged as one line (method, path, status, duration), with the commands' output on the following lines; the token is never logged.

## Fault commands

Run inside the lab containers through the Docker Engine API (`docker exec` as root, 60 s per command):

| Fault | Container | Inject | Clear |
| --- | --- | --- | --- |
| SL-F-1 | `lab-db-01` | `scripts/sl-f-1-inject.sh`: files `txlog_000001.arc` … in `/data/txlog_archive` until 64 MiB stay free, modification times 47 h to 26 h back (DD-F-10); prints `{files} {bytes}` | `scripts/sl-f-1-clear.sh`: deletes the injected files that still exist |
| SL-F-2 | `lab-db-01` | `labctl db read-only on` | `labctl db read-only off` |
| SL-F-3 | `lab-rpt-01` | `labctl report fail` | `labctl report succeed` |

## Tests

`test_faults.py` (unit tests with a fake Docker exec) and the lab tests in `demo/monitoring/test_monitoring.py`.
