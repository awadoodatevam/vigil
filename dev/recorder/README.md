# The webhook recorder

`dev-recorder` (image `vigil/recorder:local`) stands in for `vigil-app`'s webhook operation AR-I-90 while Vigil does not run (package P-04). `deploy/local.sh up --lab-only` starts it (compose profile `lab-only`) with the network alias `demo.vigil.test` on `lab-net`, so the lab's Zabbix and Alertmanager reach it at `webhook_base_url` (`http://demo.vigil.test:8080`). P-26's full installation does not start it; `vigil-app` takes the alias.

It reads the tokens `webhook-zabbix` and `webhook-alertmanager` from `/run/secrets/vigil` (the secret folder of `vigil-app`) and writes each call to `RECORDER_OUT` (default `/recordings`, mounted from `~/.vigil-local/{project}/recordings`).

| Request | Answer |
| --- | --- |
| `POST /api/v1/webhooks/zabbix-lab`, `POST /api/v1/webhooks/alertmanager-lab` | 200 `{"entries": n}` with the source's bearer token (1 for Zabbix; the number of `alerts` for Alertmanager), otherwise 401; a body over 5 MiB gives 413. Every such call is recorded. |
| `GET /_recorder/calls?after={n}` | The recorded calls with `seq` above `n`, in order. |
| `GET /_recorder/health` | 200 `{"ok": true}` |
| Anything else | 404, not recorded |

A recorded call is `{"seq", "received_at", "source_key", "method", "path", "status", "authorized", "headers", "body"}`; the `Authorization` value is kept only as `Bearer <redacted>`, so a recording holds no secret. Files are named `{seq:06d}-{source_key}.json`; numbering continues after the files already in the folder.

Tests: `test_recorder.py`.
