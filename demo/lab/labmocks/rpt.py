"""The report export job (role rpt, DD-F-10 mock_behaviour.report_export_job)."""

import os

from .common import log, write_status

RUN_INTERVAL_S = 600
REQUEST_CHECK_S = 0.5
MODE_FILE = "report_mode"
RUN_REQUEST_FILE = "report_run_now"
STATUS_FILE = "report_export_last_result"


class ReportJob:
    def __init__(self, control_dir, status_dir, clock):
        self._mode_file = os.path.join(control_dir, MODE_FILE)
        self._run_request = os.path.join(control_dir, RUN_REQUEST_FILE)
        self._status_dir = status_dir
        self._clock = clock
        self._last_run = None
        self._mode = None

    def start(self):
        """Sets the mode to succeed and starts a run."""
        with open(self._mode_file, "w") as file:
            file.write("succeed\n")
        self.run()

    def tick(self):
        """Called every 0.5 s: starts a run when a run request exists (and removes it) or 600 s after the last run."""
        if os.path.exists(self._run_request):
            os.unlink(self._run_request)
            self.run()
        elif self._last_run is None or self._clock() - self._last_run >= RUN_INTERVAL_S:
            self.run()

    def read_mode(self):
        try:
            with open(self._mode_file) as file:
                return file.read().strip()
        except FileNotFoundError:
            return "succeed"

    def run(self):
        self._last_run = self._clock()
        mode = self.read_mode()
        if mode != self._mode:
            if self._mode is not None:
                log.info("report mode changed to %s", mode)
            self._mode = mode
        result = 0 if mode == "fail" else 1
        write_status(self._status_dir, STATUS_FILE, result)
        log.info("report run finished: result %d", result)
        return result

    def run_forever(self, sleep):
        self.start()
        while True:
            sleep(REQUEST_CHECK_S)
            self.tick()
