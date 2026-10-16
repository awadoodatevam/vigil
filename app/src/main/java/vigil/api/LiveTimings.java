package vigil.api;

import java.time.Duration;

/** The heartbeat interval and the open time of a live update stream (AR-TM-04). */
record LiveTimings(Duration pingInterval, Duration maxOpen) {}
