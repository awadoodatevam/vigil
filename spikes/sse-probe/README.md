# SSE probe (spike AR-SP-01, probe part)

The probe proves that live updates (`docs/50-architecture.md` AR-I-04) reach a browser in Pakistan through the load balancer of the demo installation within 1 s (AR-SP-01, AR-ASM-03), before `vigil-app` exists (P-05 spec Section 6.11). On the main host it runs as the container `sse-probe` at `vigil-app`'s addresses (`172.30.0.10` on `vigil-net`, `172.31.0.10` on `lab-net`) and published port (`10.40.1.10:8080`), so the load balancer's target group `vigil-app` reaches it as it will reach `vigil-app`. P-32 repeats the spike with `vigil-app` and its live topics, and adds the check that a lab container cannot open a connection to `vigil-app`.

## The server (`probe.py`)

Python 3.12, standard library only; HTTP/1.1, one thread per connection; streams use chunked transfer encoding, one chunk per write, as `vigil-app`'s stream does. It listens on `PROBE_ADDRESS` (default `127.0.0.1`; on the main host `172.30.0.10`) and `PROBE_PORT` (default 8080). Timings, for tests: `PROBE_PING_SECONDS` (15), `PROBE_CLOSE_SECONDS` (300), `PROBE_NOTICE_SECONDS` (3).

| Path | Answer |
| --- | --- |
| `GET /` | The test page `index.html`. |
| `GET /actuator/health/liveness`, `GET /actuator/health` | `{"status":"UP"}` (the target group's health check and `deploy/status.sh`; from outside the load balancer answers 404 for `/actuator*`). |
| `GET /probe/time` | `{"server_ms": …}`: the server's time in milliseconds since the epoch. |
| `GET /probe/info` | `{"local_address": …, "forwarded_for": …}`: the local address of the request's connection and the `X-Forwarded-For` header. |
| `GET /probe/stream?mode=notices` | A stream as AR-I-04: `retry: 1000`, `: ping` every 15 s, a notice `event: change` with `{"seq":n,"sent_ms":…}` every 3 s (99 per connection), closed by the server after 300 s. |
| `GET /probe/stream?mode=idle` | The same stream without notices. |

Any other path answers 404; a missing or other `mode` answers 400.

## The test (owner-run, P-05 spec Section 6.13 step 8)

In a browser in Pakistan, open `https://demo.vigil.solviclabs.com/`, press "Start" and keep the tab in front for about 11 minutes. The page:

1. reads `/probe/info`;
2. measures the clock offset between browser and server: 20 requests of `/probe/time`; of the one with the smallest round trip, `offset_ms = server_ms − (t_send + t_receive) / 2`;
3. opens two streams at once, `notices` and `idle`; for each notice, `delay_ms = t_receive + offset_ms − sent_ms`; the test ends when 200 notices have arrived and the `idle` stream has been reopened once (or after 15 minutes; a stream between an error and its next open gets up to 5 s more);
4. measures the clock offset again;
5. shows PASS or FAIL per criterion and a link "Download result", which saves `P-05-AR-SP-01.json`. Save it as `spikes/results/P-05-AR-SP-01.json`.

Pass criteria (all MUST hold; their keys in `criteria`):

| Key | Criterion |
| --- | --- |
| `local_address` | The probe saw the request on `172.30.0.10`: the load balancer reached `vigil-app`'s `vigil-net` address through the published port. |
| `https_origin` | The page was loaded with `https:` from `demo.vigil.solviclabs.com`. |
| `notices_200` | 200 notices arrived. |
| `max_delay_1000` | The largest `delay_ms` is at most 1,000. |
| `idle_open_295` | The `idle` stream had no error earlier than 295 s after its first open: the load balancer did not close it while it was idle for 5 minutes with heartbeats. |
| `reconnect_5000` | Every reconnection of either stream (from an `error` event to the next `open`) took at most 5,000 ms. |

## The result file

JSON with the keys (complete list): `started_at`, `finished_at` (UTC, ISO 8601); `page_url`; `user_agent`; `local_address` and `forwarded_for` (from `/probe/info`); `offset_ms_start`, `offset_ms_end`; `rtt_min_ms` (the smallest round trip of the start measurement); `notices` (`count`, `max_delay_ms`, `delays_ms`: every delay, computed with `offset_ms_start`); `idle` (`first_error_after_s`: seconds from the idle stream's first open to its first error, or null); `reconnects` (one `{stream, at_s, gap_ms}` per reconnection: the stream, the `open` event's time since "Start", the gap from the `error` event; a stream that did not reopen has `gap_ms` null and `at_s` its `error` event's time); `criteria` (one boolean per criterion); `passed`.

## Image and tests

`Dockerfile` builds the image (`ubuntu:24.04`, Ubuntu's `python3`, user 10001): `deploy/build.sh` builds it for the demo installation (`{registry}/demo/sse-probe:{release}`), `deploy/images.sh --test` as `vigil/sse-probe:local`. `test_probe.py` (run by the gate) tests the server with short timings and the local image.
