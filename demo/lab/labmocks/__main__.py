"""Starts one role of the mocks: python -m labmocks {db|api|vas|probe|rpt}."""

import logging
import os
import sys
import threading
import time

from . import api, common, db, probe, rpt, vas
from .common import log


def _thread(target, *args):
    """Runs target in a daemon thread; when it fails the process exits, so a failed mock stops the container."""

    def run():
        try:
            target(*args)
        except BaseException:
            log.exception("a mock thread failed")
            os._exit(1)

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    return thread


def _every_second(task):
    while True:
        task()
        time.sleep(1)


def _serve(port, handle):
    _thread(common.make_server(port, handle).serve_forever)


def _api_url(kind, index):
    return f"http://{common.API_HOST}:{api.instance_port(kind, index)}"


def run_db():
    database = db.Database("/data", common.CONTROL_DIR, common.STATUS_DIR)
    database.write_status()
    _serve(common.DB_PORT, database.handle)
    log.info("started role db, port %d", common.DB_PORT)
    _every_second(database.write_status)


def run_api():
    from . import metrics

    counters = api.ApiCounters()
    instances = [
        api.ApiInstance(kind, index, common.DB_URL, common.urllib_post, time.monotonic, counters)
        for kind, index in api.INSTANCES
    ]
    for instance in instances:
        _serve(instance.port, instance.handle)
    labels = ["api", "instance", "endpoint"]
    metrics.serve(
        common.METRICS_PORT,
        [
            metrics.counter("lab_api_requests", "API requests", labels, counters.requests.snapshot),
            metrics.counter("lab_api_errors", "API requests that failed", labels, counters.errors.snapshot),
        ],
    )
    ports = ", ".join(str(instance.port) for instance in instances)
    log.info("started role api, ports %s and metrics %d", ports, common.METRICS_PORT)
    tasks = [task for instance in instances for task in instance.stream_tasks()]
    common.run_fixed_rate(1 / api.STREAM_REQUESTS_PER_SECOND, tasks, time.monotonic, time.sleep)


def run_vas():
    from . import metrics

    gateway = vas.UssdGateway(_api_url, common.DB_URL, common.urllib_post, time.monotonic)
    dispatcher = vas.SmsDispatcher(common.DB_URL, common.urllib_post, time.monotonic, common.STATUS_DIR)
    dispatcher.write_status()
    _serve(common.VAS_PORT, gateway.handle)
    labels = ["node", "menu"]
    metrics.serve(
        common.METRICS_PORT,
        [
            metrics.counter("lab_ussd_sessions", "USSD sessions", labels, gateway.sessions.snapshot),
            metrics.counter("lab_ussd_timeouts", "USSD sessions that timed out", labels, gateway.timeouts.snapshot),
        ],
    )
    for route in vas.ROUTES:
        _thread(dispatcher.run_route, route, time.sleep)
    _thread(_every_second, dispatcher.write_status)
    _thread(common.run_fixed_rate, 1 / vas.MESSAGES_PER_SECOND, [dispatcher.receive_all], time.monotonic, time.sleep)
    log.info("started role vas, port %d and metrics %d", common.VAS_PORT, common.METRICS_PORT)
    common.run_fixed_rate(1 / vas.STREAM_SESSIONS_PER_SECOND, gateway.stream_tasks(), time.monotonic, time.sleep)


def run_probe():
    from . import metrics

    probes = probe.Probes(common.VAS_URL, common.urllib_post, time.monotonic)
    metrics.serve(
        common.METRICS_PORT,
        [
            metrics.gauge(
                "lab_probe_last_result", "Last synthetic session result", ["city", "journey"], probes.last_results
            )
        ],
    )
    log.info("started role probe, metrics %d", common.METRICS_PORT)
    common.run_fixed_rate(probe.ROUND_PERIOD_S, [probes.run_round], time.monotonic, time.sleep)


def run_rpt():
    job = rpt.ReportJob(common.CONTROL_DIR, common.STATUS_DIR, time.monotonic)
    log.info("started role rpt, no port")
    job.run_forever(time.sleep)


ROLES = {"db": run_db, "api": run_api, "vas": run_vas, "probe": run_probe, "rpt": run_rpt}


def main(argv):
    if len(argv) != 2 or argv[1] not in ROLES:
        print(f"usage: python -m labmocks {{{'|'.join(ROLES)}}}", file=sys.stderr)
        return 2
    logging.basicConfig(stream=sys.stdout, level=logging.INFO, format="%(asctime)s %(message)s")
    ROLES[argv[1]]()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
