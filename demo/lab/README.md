# demo-lab: the lab hosts

The image `vigil/demo-lab:local` (built by `deploy/images.sh --test`) runs the five lab hosts of DD-F-10 `lab_hosts`, each container started with its role in `LAB_ROLE` by `deploy/local/compose.yaml` (AR-U-08, AR-X-01). Package P-03 built it; this file lists the interfaces that P-04 (monitoring and `demo-faults`) and the fault scripts use (P-03 spec Section 6.4).

| Host | Processes |
| --- | --- |
| `lab-db-01` | XFS `/data` loop-mounted from `/var/lib/lab-data/data-{profile}.xfs`; `sshd` (port 22, users `vigil-diag` and `vigil-action`); `prometheus-node-exporter` (9100); Zabbix agent 2 (10050); the subscriber database (7000) |
| `lab-api-01` | API instances `api-balance-1` to `-4` (ports 8101 to 8104) and `api-bundle-1` to `-4` (8201 to 8204); metrics 9200 |
| `lab-vas-01` | Zabbix agent 2 (10050); USSD gateway nodes `ussd-gw-a` and `ussd-gw-b` (sessions on 7100); SMS queue and dispatcher, 24 routes; metrics 9200 |
| `lab-probe-01` | Synthetic probes, 10 cities by 5 journeys every 10 s; metrics 9200 |
| `lab-rpt-01` | Zabbix agent 2 (10050); report export job, every 600 s |

No port is published; everything is reached on `lab-net` (172.31.0.0/24). A host is ready when `/run/lab/ready` exists (the compose health check).

## Interfaces

| Interface | Where | Content |
| --- | --- | --- |
| Database status files | `lab-db-01:/var/lib/lab/status/` | `db_writes_{schema}` and `db_write_errors_{schema}` for the 6 schemas `subscriber_profile`, `balance_ledger`, `bundle_catalog`, `bundle_orders`, `ussd_sessions`, `sms_delivery`: the counts since the mock started, one decimal integer and a newline, written at start and once per second. Every status file is written to a temporary file in the same folder and renamed (a reader never sees a partial file); files 0644. |
| SMS queue status files | `lab-vas-01:/var/lib/lab/status/` | `sms_queue_depth_{route}` for `route-01` to `route-24`: the current depth, same format, written at start and once per second. |
| Report export status file | `lab-rpt-01:/var/lib/lab/status/` | `report_export_last_result`: `1` (success) or `0` (failure) and a newline, written by each run. |
| Read-only flag (SL-F-2) | `lab-db-01` | `labctl db read-only on` creates `/var/lib/lab/control/db_read_only`; `labctl db read-only off` removes it; `labctl db read-only` prints `on` or `off`. The database checks it at every write: while it exists every write fails with `database is read-only`, also when `/data` has space. |
| Report job mode (SL-F-3) | `lab-rpt-01` | `labctl report fail` and `labctl report succeed` write the mode to `/var/lib/lab/control/report_mode` and create `/var/lib/lab/control/report_run_now` (a run starts within 1 s, also when the mode did not change); `labctl report` prints the mode (`succeed` when the file is missing). |
| `labctl` | every host | Bash (`demo/lab/labctl.sh`, installed as `/usr/local/bin/labctl`). It works under `${LABCTL_ROOT:-/var/lib/lab}` (`LABCTL_ROOT` is set only by tests) and decides its host from `LAB_ROLE`: `db …` only on `lab-db-01`, `report …` only on `lab-rpt-01`. Any other command, or a command for another host, prints a usage line and exits 2. Called by `docker compose exec {service} labctl …` or `docker exec {container} labctl …` (P-04's `demo-faults`, AR-X-03). |
| Metrics | `lab-api-01:9200/metrics`, `lab-vas-01:9200/metrics`, `lab-probe-01:9200/metrics` | Prometheus text format from `prometheus_client`. `lab-api-01`: counters `lab_api_requests_total` and `lab_api_errors_total` with labels `api` (`balance`, `bundle`), `instance` (`api-balance-1` …) and `endpoint` (`/v1/balance` …). `lab-vas-01`: counters `lab_ussd_sessions_total` and `lab_ussd_timeouts_total` with labels `node` and `menu`. `lab-probe-01`: gauge `lab_probe_last_result` with labels `city` and `journey` (1 success, 0 failure). Every series exists from the start (counters at 0, the gauge at 1). The label `instance` is part of the metric; P-04's scrape configuration keeps it (`honor_labels: true`). |
| Node exporter | `lab-db-01:9100/metrics` | Ubuntu's `prometheus-node-exporter` with its default collectors; `node_filesystem_avail_bytes` and `node_filesystem_size_bytes` with `mountpoint="/data"` are present. |
| Zabbix agent 2 | `lab-db-01`, `lab-vas-01`, `lab-rpt-01`, port 10050 | Passive checks from `lab-net` (`Server=172.31.0.0/24,127.0.0.1`, `Hostname` = the host name); `vfs.fs.size[/data,free]` (on `lab-db-01`) and `vfs.file.contents[{status file}]` give the values above. The entrypoint writes `/etc/zabbix/zabbix_agent2.conf` at every start (P-04 MAY change it in `demo/lab/entrypoint.sh`). |

## What the mocks do

The behaviour is DD-F-10 `mock_behaviour` as made exact by P-03 spec Section 6.2: a database write fails while the read-only flag exists or `/data` has less than 256 MiB available; each API instance makes one request per second on each endpoint, each node of the USSD gateway one session per second on each menu, and each SMS route receives one message per second; while writes fail, API requests fail, sessions time out, SMS queues grow and probes report 0, and all recover when writes work again. The database never writes to `/data`; only the faults and the registered action change it.

## Users and `/data` on `lab-db-01`

`vigil-diag` (UID 2001) and `vigil-action` (UID 2002, also in group `vigil-diag`) log in only with their own keys, which come with the host key from the secret files in `/run/secrets/vigil` at every start. `/data`, `/data/db` and `/data/export` are `root:vigil-diag` 0750; `/data/txlog_archive` is `vigil-action:vigil-diag` 0750. So `vigil-diag` reads everything and writes nothing, and `vigil-action` can create and delete files only in `/data/txlog_archive`.

## Monitoring and faults (P-04)

Zabbix, Prometheus and Alertmanager monitor these hosts (`demo/monitoring/README.md`), and `demo-faults` injects and clears SL-F-1 to SL-F-3 through `docker exec` and `labctl` (`demo/faults/README.md`). The agent configuration accepts passive checks from `172.31.0.0/24` and from `127.0.0.1` (the ready check's own connects). The lab hosts start in dependency order: `lab-api-01` after `lab-db-01`, `lab-vas-01` after both, `lab-probe-01` after `lab-vas-01` (each `service_healthy`), so no mock calls a service that is not yet listening. The API mocks make every write of a request (a request fails when any write fails), and the USSD gateway writes `ussd_sessions` also after a failed API call (CTX-D-035).

## Tests

`test_labmocks.py` (unit tests, no container) and `test_lab.py` (marker `lab`, starts the lab with `deploy/local.sh` as project `vigil-labtest`; `VIGIL_LAB_PROFILE` chooses the profile).
