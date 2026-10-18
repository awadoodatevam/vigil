package vigil.connectors;

import java.time.Duration;

/** The connect timeout and the monitoring source connection test timeout (AR-TM-01). */
record ConnectorTimings(Duration connect, Duration monitoringTest) {}
