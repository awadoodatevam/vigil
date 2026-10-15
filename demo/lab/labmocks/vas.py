"""The USSD gateway and the SMS queue with its dispatcher (role vas, DD-F-10 mock_behaviour.ussd_gateway, sms_queue)."""

import collections
import threading

from .api import INSTANCE_COUNT
from .common import BAD_REQUEST, NOT_FOUND, Counts, Outcomes, call, parse_body, write_status

NODES = ("ussd-gw-a", "ussd-gw-b")
# DD-F-03 LA-07 menus and the API kind and endpoint each calls (P-03 spec Section 6.2).
MENUS = {
    "balance": ("balance", "/v1/balance"),
    "data-bundles": ("bundle", "/v1/bundles/offers"),
    "voice-bundles": ("bundle", "/v1/bundles/purchase"),
    "sms-bundles": ("bundle", "/v1/bundles/purchase"),
    "my-bundles": ("bundle", "/v1/bundles/active"),
    "bundle-status": ("bundle", "/v1/bundles/active"),
}
SESSION_DEADLINE_S = 2.0
API_DEADLINE_S = 1.5
STREAM_SESSIONS_PER_SECOND = 1  # per menu of each node
SESSION_LABELS = tuple((node, menu) for node in NODES for menu in MENUS)

ROUTES = tuple(f"route-{number:02d}" for number in range(1, 25))
MESSAGES_PER_SECOND = 1  # per route
DELIVERY_DEADLINE_S = 1.0
MIN_DELIVERY_INTERVAL_S = 0.05  # at most 20 deliveries per second per route
RETRY_INTERVAL_S = 1.0


class UssdGateway:
    def __init__(self, api_url, db_url, http, clock):
        """api_url(api kind, instance index) gives the base address of one API instance."""
        self._api_url = api_url
        self._db_url = db_url
        self._http = http
        self._clock = clock
        self._next_instance = {(node, api): 0 for node in NODES for api in ("balance", "bundle")}
        self._lock = threading.Lock()
        self.sessions = Counts(SESSION_LABELS)
        self.timeouts = Counts(SESSION_LABELS)
        self._outcomes = Outcomes("session of node and menu")

    def choose_instance(self, node, api):
        """The next API instance (1 to 4) of that kind for that node, in round-robin order."""
        with self._lock:
            position = self._next_instance[(node, api)]
            self._next_instance[(node, api)] = (position + 1) % INSTANCE_COUNT
        return position + 1

    def session(self, node, menu):
        """Runs one session; True when the API call and the ussd_sessions write both answered 200 within 2 s."""
        start = self._clock()
        deadline = start + SESSION_DEADLINE_S
        api, endpoint = MENUS[menu]
        url = self._api_url(api, self.choose_instance(node, api)) + endpoint
        _, _, error = call(self._http, self._clock, url, {}, min(API_DEADLINE_S, deadline - self._clock()))
        _, _, write_error = call(
            self._http, self._clock, f"{self._db_url}/write", {"schema": "ussd_sessions"}, deadline - self._clock()
        )
        error = error or write_error
        if error is None and self._clock() > deadline:
            error = "timeout"
        self.sessions.add((node, menu))
        if error is not None:
            self.timeouts.add((node, menu))
        self._outcomes.record((node, menu), error)
        return error is None

    def handle(self, path, raw):
        if path != "/session":
            return NOT_FOUND
        try:
            body = parse_body(raw)
        except ValueError:
            return BAD_REQUEST
        if body is None or set(body) != {"node", "menu"} or body["node"] not in NODES or body["menu"] not in MENUS:
            return BAD_REQUEST
        ok = self.session(body["node"], body["menu"])
        return 200, {"result": "ok" if ok else "timeout"}

    def stream_tasks(self):
        """One task per node and menu; the role starts each STREAM_SESSIONS_PER_SECOND times a second."""
        return [lambda node=node, menu=menu: self.session(node, menu) for node, menu in SESSION_LABELS]


class SmsDispatcher:
    """The in-memory queue of each route and its dispatcher, which records every delivery in sms_delivery."""

    def __init__(self, db_url, http, clock, status_dir):
        self._db_url = db_url
        self._http = http
        self._clock = clock
        self._status_dir = status_dir
        self._queues = {route: collections.deque() for route in ROUTES}
        self._arrived = {route: threading.Event() for route in ROUTES}
        self._lock = threading.Lock()
        self._outcomes = Outcomes("delivery of route")

    def receive(self, route):
        with self._lock:
            self._queues[route].append(self._clock())
        self._arrived[route].set()

    def receive_all(self):
        for route in ROUTES:
            self.receive(route)

    def depth(self, route):
        with self._lock:
            return len(self._queues[route])

    def step(self, route):
        """Delivers the oldest message of route. Returns the seconds until the next step, or None when the queue is
        empty: after a delivery the next starts at once but at most 20 a second; after a failure the same write is
        tried again 1 s after the failed attempt started."""
        if self.depth(route) == 0:
            return None
        start = self._clock()
        _, _, error = call(
            self._http, self._clock, f"{self._db_url}/write", {"schema": "sms_delivery"}, DELIVERY_DEADLINE_S
        )
        self._outcomes.record(route, error)
        if error is not None:
            return max(0.0, start + RETRY_INTERVAL_S - self._clock())
        with self._lock:
            self._queues[route].popleft()
        return max(0.0, start + MIN_DELIVERY_INTERVAL_S - self._clock())

    def run_route(self, route, sleep):
        arrived = self._arrived[route]
        while True:
            arrived.clear()
            delay = self.step(route)
            if delay is None:
                arrived.wait(1.0)
            elif delay > 0:
                sleep(delay)

    def write_status(self):
        for route in ROUTES:
            write_status(self._status_dir, f"sms_queue_depth_{route}", self.depth(route))
