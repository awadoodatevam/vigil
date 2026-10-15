"""The synthetic probes (role probe, DD-F-10 mock_behaviour.synthetic_probes)."""

import threading

from .common import Outcomes, call

# DD-F-03 LA-08, in its order.
CITIES = (
    "karachi",
    "lahore",
    "islamabad",
    "rawalpindi",
    "faisalabad",
    "multan",
    "peshawar",
    "quetta",
    "hyderabad",
    "sialkot",
)
JOURNEY_MENUS = {
    "balance-check": "balance",
    "bundle-list": "data-bundles",
    "bundle-purchase": "voice-bundles",
    "bundle-status": "bundle-status",
    "sms-confirmation": "sms-bundles",
}
ROUND_PERIOD_S = 10
SESSION_DEADLINE_S = 3.0
LABELS = tuple((city, journey) for city in CITIES for journey in JOURNEY_MENUS)


def node_of(city):
    """ussd-gw-a for the 1st, 3rd, 5th, 7th and 9th city, ussd-gw-b for the others."""
    return "ussd-gw-a" if CITIES.index(city) % 2 == 0 else "ussd-gw-b"


class Probes:
    def __init__(self, vas_url, http, clock):
        self._vas_url = vas_url
        self._http = http
        self._clock = clock
        self._last = dict.fromkeys(LABELS, 1)
        self._lock = threading.Lock()
        self._outcomes = Outcomes("probe of city and journey")

    def last_results(self):
        with self._lock:
            return dict(self._last)

    def probe(self, city, journey):
        body = {"node": node_of(city), "menu": JOURNEY_MENUS[journey]}
        _, answer, error = call(self._http, self._clock, f"{self._vas_url}/session", body, SESSION_DEADLINE_S)
        if error is None and answer.get("result") != "ok":
            error = f"result {answer.get('result')}"
        with self._lock:
            self._last[(city, journey)] = 1 if error is None else 0
        self._outcomes.record((city, journey), error)

    def run_round(self):
        """Starts the 50 sessions together, one thread each, and waits for them."""
        threads = [threading.Thread(target=self.probe, args=label, daemon=True) for label in LABELS]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
