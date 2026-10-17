# The demo installation on AWS: operator steps

How the operator creates and runs the demo installation on AWS from the Mac (`docs/50-architecture.md` Section 17, `docs/60-build-plan.md` Section 10.3, P-05 spec Sections 6.4 and 6.13). Every script runs from the repository root and acts only in `us-east-1`.

## Costs

The running costs of `docs/50-architecture.md` Section 17.1 start with the main host, which `deploy/edge.sh` creates in its step 11 (about 234 USD a month in total when the main host runs always; the load balancer, the public addresses and the storage cost also while the main host is stopped). The GPU host costs only while it runs: `deploy/edge.sh` stops it after its bootstrap, and `deploy/gpu.sh start` and `deploy/gpu.sh stop` start and stop it.

## Prerequisites

1. Xcode Command Line Tools, for `python3`: `xcode-select --install`.
2. AWS CLI 2.32 or later from AWS's macOS installer (`AWSCLIV2.pkg`); check with `aws --version`.
3. Sign in with the administrator user of BP-OT-03, confirming its MFA in the browser, with region `us-east-1`:

   ```bash
   aws login --profile vigil
   export AWS_PROFILE=vigil
   ```

   Every script first runs `aws sts get-caller-identity`; without a valid sign-in it prints "Sign in first: aws login --profile vigil, then export AWS_PROFILE=vigil (deploy/edge.md)." and stops.
4. Docker Desktop running (`deploy/build.sh` uses Docker with `buildx`).

## BP-OT-09: the G instance quota

The GPU host is a `g6.xlarge` (4 vCPU). In the AWS console, open Service Quotas, "Amazon Elastic Compute Cloud (Amazon EC2)", region `us-east-1`, and find "Running On-Demand G and VT instances". Its value MUST be at least 4. When it is lower, choose "Request increase at account level", enter 4 (or more) and submit; AWS answers by email, usually within a few days. Until the increase is granted, `deploy/edge.sh` creates everything else and exits 3.

## Running `deploy/edge.sh demo`

```bash
deploy/edge.sh demo
```

It creates what is missing and sets what can drift, printing one line per resource (`{kind} {name}: created`, `set`, `exists`, or for a target `skipped ({state})`), then a summary: the four name servers, the load balancer's DNS name, the bucket, the push role's ARN and the hosts' states. It can run any number of times. Exit codes:

| Exit code | Meaning |
| --- | --- |
| 0 | Done: every resource exists. |
| 3 | Everything exists except the GPU host, which EC2 refused for the quota (`VcpuLimitExceeded`, see BP-OT-09 above) or for capacity (`InsufficientInstanceCapacity`); run `deploy/edge.sh demo` again later. |
| 4 | The delegation of the subdomain is not visible yet; the four name servers are printed. Add them at Cloudflare (next section) and run `deploy/edge.sh demo` again. The certificate, the HTTPS listener, the address records and the GPU host follow on that run. |

Any other error prints its message and exits 1; exit 2 means a wrong argument or a values file without `demo_domain`.

## The delegation at Cloudflare

In Cloudflare's dashboard, open the zone `solviclabs.com`, "DNS", "Records", and add four records, one per name server that `deploy/edge.sh` printed:

| Type | Name | Content |
| --- | --- | --- |
| `NS` | `vigil` | the first printed name server |
| `NS` | `vigil` | the second |
| `NS` | `vigil` | the third |
| `NS` | `vigil` | the fourth |

Then check until the four names appear (usually within minutes):

```bash
dig +short NS vigil.solviclabs.com @1.1.1.1
```

and run `deploy/edge.sh demo` again.

## The GitHub repository variable `VIGIL_PUSH_ROLE_ARN`

`release.yml` pushes images with the role `vigil-demo-github-push`. In the GitHub repository, open "Settings", "Secrets and variables", "Actions", "Variables", choose "New repository variable", name it `VIGIL_PUSH_ROLE_ARN` and paste the ARN of the line `push role: {ARN}` that `deploy/edge.sh demo` printed.

## SES production access (BP-OT-08)

SES starts in sandbox mode. In the SES console (region `us-east-1`), open "Account dashboard" and choose "Request production access": mail type "Transactional", website `https://demo.vigil.solviclabs.com`, and as use case: notifications to nine internal mailboxes, under 1,000 messages a month (AR-H-08). The request is made in P-07; until it is granted, each of the nine mailboxes is verified there.

## Starting and stopping the main host until P-32

Until P-32 the main host runs only for the owner-run steps that need it (CTX-D-041); stop it after each such step and start it before the next. Its instance ID is in the line `main host: {instance id} {state}` that `deploy/edge.sh demo` and `deploy/status.sh demo` print.

```bash
aws ec2 start-instances --instance-ids {instance id}
aws ec2 wait instance-running --instance-ids {instance id}

aws ec2 stop-instances --instance-ids {instance id}
aws ec2 wait instance-stopped --instance-ids {instance id}
```

From P-32 the main host runs always (AR-H-01).

## The order of the first deployment

1. The prerequisites above; BP-OT-09 (request the quota when it is lower than 4).
2. `deploy/edge.sh demo`: exits 4 with the four name servers (expected on the first run).
3. The four `NS` records at Cloudflare; wait until `dig +short NS vigil.solviclabs.com @1.1.1.1` shows them.
4. With the main host running (start it when you stopped it): `deploy/edge.sh demo` → exit 0, or 3 while BP-OT-09 is pending, with the lines `target vigil-app/vigil-demo-main: created` (or `exists`) and `target demo-control/vigil-demo-main: created` (or `exists`), not `skipped`. The main host stays running until step 9.
5. Set the repository variable `VIGIL_PUSH_ROLE_ARN`.
6. `deploy/new-installation.sh demo`: creates the 21 secrets in Parameter Store (it prints names, never values).
7. `deploy/build.sh p05-1`: builds and pushes the SSE probe image from the Mac (CTX-D-041).
8. `deploy/deploy.sh demo p05-1`: stops at the models check with "No digest recorded for embeddinggemma"; then `deploy/models.sh update embeddinggemma`; then `deploy/deploy.sh demo p05-1` again → `deployment p05-1 done`.
9. AR-SP-01: in a browser in Pakistan open `https://demo.vigil.solviclabs.com/`, press "Start", keep the tab in front for about 11 minutes, download the result and save it as `spikes/results/P-05-AR-SP-01.json` (`spikes/sse-probe/README.md`).
10. `tests/acceptance/p05_aws_check.sh demo` (when BP-OT-09 was granted after step 4, run step 4 again first, so the GPU host is created there).
11. Stop the main host (previous section).
12. Commit `deploy/models.lock`, `spikes/results/P-05-AR-SP-01.json` and `tests/acceptance/results/P-05-aws.txt` to the branch and push.

Other scripts: `deploy/status.sh demo` (hosts, containers, health, models), `deploy/gpu.sh start|stop|status` (the GPU host, from P-06), `deploy/models.sh update {model}` (a new model digest in `deploy/models.lock`, which you commit).
