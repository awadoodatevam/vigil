"""The API instances (role api, DD-F-10 mock_behaviour.api_instances)."""

from .common import BAD_REQUEST, NOT_FOUND, Counts, Outcomes, call, parse_body

# DD-F-03 LA-05 and LA-06: the endpoints of each API kind.
ENDPOINTS = {
    "balance": ("/v1/balance", "/v1/balance/main", "/v1/balance/bundles", "/v1/balance/history"),
    "bundle": ("/v1/bundles/offers", "/v1/bundles/purchase", "/v1/bundles/active", "/v1/bundles/renew"),
}
BASE_PORT = {"balance": 8100, "bundle": 8200}
INSTANCE_COUNT = 4
WRITE_DEADLINE_S = 1.0
STREAM_REQUESTS_PER_SECOND = 1  # per endpoint of each instance


def instance_name(api, index):
    return f"api-{api}-{index}"


def instance_port(api, index):
    return BASE_PORT[api] + index


INSTANCES = tuple((api, index) for api in ("balance", "bundle") for index in range(1, INSTANCE_COUNT + 1))
LABELS = tuple((api, instance_name(api, index), endpoint) for api, index in INSTANCES for endpoint in ENDPOINTS[api])


def writes_of(api, endpoint):
    """The schemas one request writes, in order (P-03 spec Section 6.2)."""
    if api == "balance":
        return ("balance_ledger", "subscriber_profile")
    if endpoint == "/v1/bundles/offers":
        return ("bundle_orders", "bundle_catalog")
    return ("bundle_orders",)


class ApiCounters:
    """lab_api_requests_total and lab_api_errors_total per (api, instance, endpoint)."""

    def __init__(self):
        self.requests = Counts(LABELS)
        self.errors = Counts(LABELS)


class ApiInstance:
    def __init__(self, api, index, db_url, http, clock, counters, outcomes=None):
        self.api = api
        self.name = instance_name(api, index)
        self.port = instance_port(api, index)
        self._db_url = db_url
        self._http = http
        self._clock = clock
        self._counters = counters
        self._outcomes = outcomes or Outcomes("request of instance and endpoint")

    def request(self, endpoint):
        """Makes the writes of one request, each with its 1 s deadline; the request fails when one of them fails.

        Every write of the request is made, so that each schema keeps receiving writes while writes fail
        (DD-F-10 every_writer_rate; P-03 report, deviation 1).
        """
        error = None
        for schema in writes_of(self.api, endpoint):
            _, _, write_error = call(
                self._http, self._clock, f"{self._db_url}/write", {"schema": schema}, WRITE_DEADLINE_S
            )
            if error is None:
                error = write_error
        key = (self.api, self.name, endpoint)
        self._counters.requests.add(key)
        if error is not None:
            self._counters.errors.add(key)
        self._outcomes.record((self.name, endpoint), error)
        if error is not None:
            return 502, {"ok": False, "error": error}
        return 200, {"ok": True}

    def handle(self, path, raw):
        if path not in ENDPOINTS[self.api]:
            return NOT_FOUND
        try:
            body = parse_body(raw)
        except ValueError:
            return BAD_REQUEST
        if body not in (None, {}):
            return BAD_REQUEST
        return self.request(path)

    def stream_tasks(self):
        """One task per endpoint; the role starts each STREAM_REQUESTS_PER_SECOND times a second."""
        return [lambda endpoint=endpoint: self.request(endpoint) for endpoint in ENDPOINTS[self.api]]
