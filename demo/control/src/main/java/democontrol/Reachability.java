package democontrol;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The checks of DC-S12-05's "Cannot reach…" and DC-S12's "Connecting…" (spec 6.3 item 5): the lab
 * (Zabbix and Alertmanager readable, a TCP connection to demo-faults) and Vigil (AR-I-10 answers).
 */
@Component
class Reachability {

  private final Panel panel;
  private final Lab lab;
  private final Vigil vigil;

  Reachability(Panel panel, Lab lab, Vigil vigil) {
    this.panel = panel;
    this.lab = lab;
    this.vigil = vigil;
  }

  /** One turn of both checks, every 5 s from the start; nothing while an operation runs. */
  @Scheduled(initialDelay = 0, fixedDelay = 5000)
  synchronized void check() {
    if (panel.operationRunning()) {
      return;
    }
    String labError = null;
    Lab.Counts counts = null;
    try {
      counts = lab.read();
      lab.connectFaults();
    } catch (Failed e) {
      labError = e.getMessage();
    }
    String vigilError = null;
    try {
      vigil.branding();
    } catch (Failed e) {
      vigilError = e.getMessage();
    }
    panel.labChecked(labError, counts);
    panel.vigilChecked(vigilError);
  }
}
