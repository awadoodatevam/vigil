package democontrol;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The checks of DC-S12-05's "Cannot reach…" and DC-S12's "Connecting…" (spec 6.3 item 5): the lab
 * (Zabbix and Alertmanager readable, a TCP connection to demo-faults) and Vigil (AR-I-10 answers).
 */
class Reachability {

  private static final Logger LOG = LoggerFactory.getLogger(Reachability.class);

  private final Panel panel;
  private final Lab lab;
  private final Vigil vigil;
  private String labError;
  private String vigilError;

  Reachability(Panel panel, Lab lab, Vigil vigil) {
    this.panel = panel;
    this.lab = lab;
    this.vigil = vigil;
  }

  /** One turn of both checks; nothing while an operation runs. */
  synchronized void check() {
    if (panel.operationRunning()) {
      return;
    }
    String labNow = null;
    Lab.Counts counts = null;
    try {
      counts = lab.read();
      lab.connectFaults();
    } catch (Failed e) {
      labNow = e.getMessage();
    }
    String vigilNow = null;
    try {
      vigil.branding();
    } catch (Failed e) {
      vigilNow = e.getMessage();
    }
    panel.labChecked(labNow, counts);
    panel.vigilChecked(vigilNow);
    if (!Objects.equals(labNow, labError)) {
      LOG.info("Lab check: {}", labNow == null ? "reachable" : labNow);
    }
    if (!Objects.equals(vigilNow, vigilError)) {
      LOG.info("Vigil check: {}", vigilNow == null ? "reachable" : vigilNow);
    }
    labError = labNow;
    vigilError = vigilNow;
  }
}
