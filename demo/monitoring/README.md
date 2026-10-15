# demo-monitoring: Zabbix, Prometheus and Alertmanager of the lab

The real monitoring of the lab (AR-U-09, AR-U-10, AR-X-02; package P-04). It watches the five lab hosts of `demo/lab/` exactly as DD-F-10 `monitoring_configuration` says and sends its webhooks, in the message formats of `docs/50-architecture.md` Sections 12.3 and 12.4, to `{webhook_base_url}/api/v1/webhooks/zabbix-lab` and `…/alertmanager-lab`. In a local installation `webhook_base_url` is `http://demo.vigil.test:8080` (`deploy/values/local.yaml`): the webhook recorder (`dev/recorder/`) holds that name on `lab-net` while Vigil does not run; from P-26 `vigil-app` does.

| Unit | Image | What it does |
| --- | --- | --- |
| `lab-zabbix-db` | `postgres:16.15` (pinned by digest) | Zabbix's database, on the volume `lab-zabbix-db-data`. |
| `lab-zabbix-server` | `zabbix/zabbix-server-pgsql:alpine-7.0.31` | Polls Zabbix agent 2 on `lab-db-01`, `lab-vas-01`, `lab-rpt-01` (passive checks, port 10050) and runs the webhook media type. |
| `lab-zabbix-web` | `zabbix/zabbix-web-nginx-pgsql:alpine-7.0.31` | Web interface and API on `lab-zabbix-web:8080` (`/api_jsonrpc.php`). |
| `lab-zabbix-provision` | `vigil/lab-zabbix-provision:local` (`provision/`) | One-shot: configures Zabbix through its API at each `up`. |
| `lab-prometheus` | `prom/prometheus:v3.15.0` | Scrapes the node exporter of `lab-db-01` and the mocks' metrics every 5 s and evaluates `prometheus/lab-rules.yml`. |
| `lab-alertmanager` | `prom/alertmanager:v0.34.1` | Routes the five catalogue alerts to the webhook, one alert per group. |

No port is published: everything is reached on `lab-net`.

## Files

| File | Content |
| --- | --- |
| `provision/provision.py` | `lab-zabbix-provision` (P-04 spec 6.2.1). Inputs: `ZABBIX_URL` (default `http://lab-zabbix-web:8080`), `WEBHOOK_BASE_URL` (required), `/run/secrets/vigil/webhook-zabbix`, `/run/secrets/vigil/zabbix-admin-password`; output `/srv/vigil/generated/zabbix-api`. It waits for the API (120 s, then 60 s for the login), logs in as `Admin` (on the first run it changes the default password `zabbix` to the secret), creates or updates the host group `Lab hosts`, the hosts, the 32 items and 32 triggers, the media type `Vigil webhook` (its script holds the webhook address and token and sends `User-Agent: zabbix-webhook`), the user group `Vigil` (read on `Lab hosts`), the user `vigil` and the action `Vigil lab webhook`, then keeps the API token of `vigil` when the token file is still valid, or replaces it. It prints one line per step and never a secret. Exit 0 on success, 1 with the error text otherwise. |
| `provision/make_secret.py` | Used only by `deploy/local.sh` to make secrets (`token`, `password`, `web-config` with the bcrypt hash of `alertmanager-password`). |
| `prometheus/prometheus.yml`, `prometheus/lab-rules.yml` | Scrape jobs `lab-db-node`, `lab-api` (`honor_labels: true`), `lab-vas`, `lab-probe`; the 5 rules of LA-04 to LA-08 with `for: 10s`, the label `component` and the `summary` of DC-A-17; alerts go to `lab-alertmanager:9093` with basic authentication. |
| `alertmanager/alertmanager.yml` | A template: `deploy/local.sh` replaces `${webhook_base_url}` and writes `~/.vigil-local/{project}/config/alertmanager.yml`. One route for the five catalogue alert names with `group_by: ['...']`, `group_wait: 5s`, `group_interval: 10s`, `repeat_interval: 12h`; the receiver `vigil` sends with `send_resolved: true`, `max_alerts: 0` and the bearer token file `webhook-alertmanager`. Alertmanager's web interface needs basic authentication for user `vigil` (`--web.config.file`, the secret `alertmanager-web-config`). |

Items and triggers (DD-F-03): LA-01 `vfs.fs.size[/data,free]` on `lab-db-01` (`<1G`); LA-02 `vfs.file.contents[/var/lib/lab/status/db_write_errors_{schema}]` for the 6 schemas (count now above the count 20 s ago, `last(…,#1:now-20s)`); LA-03 `…/sms_queue_depth_{route}` on `lab-vas-01` for the 24 routes (above 20 and above the depth 20 s ago); LA-09 `…/report_export_last_result` on `lab-rpt-01` (`=0`). Every item is a passive agent item of type "Numeric (unsigned)" polled every 10 s; the status-file items have one "Trim" step for space, tab, CR and LF (written `\s\t\r\n`, Zabbix's escape sequences). Every trigger has the tags `component` and `vigil=lab`.

## Interfaces for later packages

- P-10: the messages that the recorder kept for one storm are under `dev/fakes/recordings/monitoring/`; they are the payloads of the `zabbix` and `alertmanager` connector tests.
- P-26: `vigil-app` takes the network alias `demo.vigil.test`; the recorder is not started with the full installation.
- P-31: `demo-control` reads Zabbix (`problem.get` with tag `vigil=lab`, as user `vigil` with the token `zabbix-api`) and Alertmanager (`/api/v2/alerts` with user `vigil` and `alertmanager-password`) as the storm timing spike's quiet check does, with the secrets of `secrets/demo-control/`.
- P-32: `lab-zabbix-provision` runs unchanged on the demo installation with `user: 10001`, the demo `webhook_base_url` and `/srv/vigil/generated`.

## Tests

`provision/test_provision.py` (unit test against a fake Zabbix API) and `test_monitoring.py` (marker `lab`: starts the lab as project `vigil-labtest`, checks the provisioned configuration, the secrets, `demo-faults`, and runs one storm with SL-F-1, SL-F-2 and SL-F-3 with `spikes/storm-timing/run.sh --storms 0 --extra read-only`).
