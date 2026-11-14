# demo-faults: the fault agent

`demo-faults` injects and clears the lab faults SL-F-1 to SL-F-4 (AR-U-07, AR-X-03; packages P-04 and P-31). Image `vigil/demo-faults:local`, built by `deploy/images.sh --test`; started by `deploy/local.sh up` (and `up --lab-only`) on the host network as user 0 with the capabilities `NET_ADMIN` and `NET_RAW`, the Docker socket and the volume `demo-faults-state`.

## Interface

`http://172.31.0.1:8091` (the host's address on `lab-net`; `FAULTS_LISTEN`), every request with `Authorization: Bearer {faults-token}` (from `/run/secrets/vigil/faults-token`), otherwise 401 `{"error":"unauthorized"}`. Operations (complete list):

| Request | Answer |
| --- | --- |
| `POST /faults/{SL-F-1, SL-F-2, SL-F-3 or SL-F-4}/inject` | 200 `{"active": [...]}`; nothing runs when the fault is already active. 500 `{"error": "{id} inject failed (exit {code}): {output}"}` (or `(no exit after 60 s)`) when the command fails; 500 `unknown fault {id}` for any other ID. |
| `POST /faults/clear` with `{"faults": [ids]}` | Runs the clear command of each named fault in the order SL-F-1, SL-F-2, SL-F-3, SL-F-4, also when it is not active (clearing an inactive SL-F-4 does nothing); 200 `{"active": [...]}`. Any unknown ID: 500 and nothing runs; a body that is not `{"faults": [strings]}`: 500 `invalid body`. |
| Anything else | 404 `{"error":"not found"}` |

Requests are handled one at a time (SL-F-4's 30 s loop takes the same lock). The active faults are kept in `/var/lib/demo-faults/state.json` (`{"active": [...]}`, with `"sl_f_4_addresses": [...]` while SL-F-4 is active), so a restart keeps them and resumes SL-F-4. Each request is logged as one line (method, path, status, duration), with the commands' output on the following lines; the token is never logged.

## Fault commands

Run inside the lab containers through the Docker Engine API (`docker exec` as root, 60 s per command):

| Fault | Container | Inject | Clear |
| --- | --- | --- | --- |
| SL-F-1 | `lab-db-01` | `scripts/sl-f-1-inject.sh`: files `txlog_000001.arc` … in `/data/txlog_archive` until 64 MiB stay free, modification times 47 h to 26 h back (DD-F-10); prints `{files} {bytes}` | `scripts/sl-f-1-clear.sh`: deletes the injected files that still exist |
| SL-F-2 | `lab-db-01` | `labctl db read-only on` | `labctl db read-only off` |
| SL-F-3 | `lab-rpt-01` | `labctl report fail` | `labctl report succeed` |

## SL-F-4: cloud AI unreachable

Runs on the host network itself (not through `docker exec`), each command with the 60 s deadline. The `iptables` program is `iptables-nft` when `iptables-nft -S DOCKER-USER` succeeds, else `iptables-legacy` when its `-S DOCKER-USER` succeeds, else the inject fails with `SL-F-4 inject failed: the DOCKER-USER chain was not found`.

- Inject: `api.anthropic.com` and `api.openai.com` resolved to their IPv4 addresses (a name that does not resolve adds nothing and is logged); for each source `172.30.0.10` and `172.31.0.10` (`vigil-app`) and each address, the rule `-s {source}/32 -d {address}/32 -p tcp -m tcp --dport 443 -j REJECT --reject-with tcp-reset` inserted at the top of `DOCKER-USER` unless `-C` finds it, then `conntrack -D -p tcp -s {source} -d {address} --dport 443` (exit 1 with "0 flow entries have been deleted." is success). Every 30 s while active, both names are resolved again and new addresses are blocked in the same way.
- Clear: the loop stops; every stored address's rules are removed with `-D` while `-C` finds them.
- In the local installation the rules are added on the Docker host but change nothing, because `vigil-app` calls the AI fakes there (`docs/60-build-plan.md` Section 11.1); the real effect is checked on the main host (P-32).

## Tests

`test_faults.py` (unit tests with a fake Docker exec, a fake host command runner and a fake resolver) and the lab tests in `demo/monitoring/test_monitoring.py`.
