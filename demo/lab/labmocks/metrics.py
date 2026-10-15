"""Prometheus metrics of the mocks on port 9200; the only module that imports prometheus_client."""

from prometheus_client import start_http_server
from prometheus_client.core import CounterMetricFamily, GaugeMetricFamily
from prometheus_client.registry import CollectorRegistry


class _Collector:
    def __init__(self, families):
        self._families = families

    def collect(self):
        for family in self._families:
            yield family()


def counter(name, documentation, label_names, snapshot):
    """A counter family read at each scrape from snapshot() -> {label values: count}; name without _total."""

    def family():
        metric = CounterMetricFamily(name, documentation, labels=label_names)
        for labels, value in snapshot().items():
            metric.add_metric(list(labels), value)
        return metric

    return family


def gauge(name, documentation, label_names, snapshot):
    def family():
        metric = GaugeMetricFamily(name, documentation, labels=label_names)
        for labels, value in snapshot().items():
            metric.add_metric(list(labels), value)
        return metric

    return family


def serve(port, families):
    registry = CollectorRegistry(auto_describe=False)
    registry.register(_Collector(families))
    start_http_server(port, registry=registry)
