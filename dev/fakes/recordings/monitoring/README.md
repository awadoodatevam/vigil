# Recorded monitoring webhook calls

The webhook calls of one standard storm (SL-F-1 and SL-F-3, the action, then the clear) as the webhook recorder (`dev/recorder/`) kept them, saved by the storm timing spike with `--save-recordings` (package P-04). They are the payloads of the `zabbix` and `alertmanager` connector tests of P-10.

One file per call, `{n:04d}-{source_key}.json` (`n` from 1 in the order the recorder numbered the calls; `source_key` `zabbix-lab` or `alertmanager-lab`). Each file is the recorder's call object: `seq`, `received_at` (UTC, RFC 3339 with milliseconds), `source_key`, `method`, `path`, `status`, `authorized`, `headers` (the request headers; `Authorization` is `Bearer <redacted>`), `body` (the request body exactly as received: for Zabbix the JSON object of `docs/50-architecture.md` Section 12.3, for Alertmanager its webhook message of version 4, Section 12.4). No file holds a secret.
