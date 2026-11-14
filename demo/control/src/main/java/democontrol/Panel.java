package democontrol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * The control panel's state (DC-S12-02 to DC-S12-06, DD-F-10 {@code fault_state}): the record of
 * active faults, the storm clock, the running operation, the reachability and the last result; the
 * guards and the texts of the page. The record, the storm clock and the last result are kept in the
 * state file (AR-X-04).
 */
class Panel {

  enum Running {
    NONE,
    CLEAR,
    RESET
  }

  private final Clock clock;
  private final String product;
  private final String vigilUrl;
  private final Path stateFile;

  private final TreeMap<String, Instant> record = new TreeMap<>();
  private Instant stormStarted;
  private String stormLabel;
  private LastResult last;

  private Running running = Running.NONE;
  private Instant runStarted;
  private boolean labClear;
  private boolean vigilReady;

  private boolean labAnswered;
  private boolean vigilAnswered;
  private String labError;
  private String vigilError;
  private Lab.Counts counts = new Lab.Counts(0, 0);

  Panel(Clock clock, String product, String vigilUrl, Path stateFile) {
    this.clock = clock;
    this.product = product;
    this.vigilUrl = vigilUrl;
    this.stateFile = stateFile;
    load();
  }

  static String iso(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MILLIS).toString();
  }

  String product() {
    return product;
  }

  synchronized boolean connected() {
    return labAnswered && vigilAnswered;
  }

  synchronized boolean operationRunning() {
    return running != Running.NONE;
  }

  synchronized Instant runStarted() {
    return runStarted;
  }

  /** The guard text of DC-S12-03 that refuses the button now, or null. */
  synchronized String refusal(Button button) {
    if (running == Running.CLEAR) {
      return "Wait: Clear all faults is running.";
    }
    if (running == Running.RESET) {
      return "Wait: Reset is running.";
    }
    if (button.storm
        && (record.containsKey("SL-F-1")
            || record.containsKey("SL-F-2")
            || record.containsKey("SL-F-3"))) {
      return "Clear all faults first (active: " + String.join(", ", record.keySet()) + ").";
    }
    if (button == Button.CUT_CLOUD_AI && record.containsKey("SL-F-4")) {
      return "Already active.";
    }
    if (button == Button.CLEAR && record.isEmpty()) {
      return "No active faults.";
    }
    return null;
  }

  /**
   * Accepts the press of a button: null when it is accepted (its faults recorded, the storm clock
   * or the operation started), otherwise the reason it is refused.
   */
  synchronized String accept(Button button) {
    if (!connected()) {
      return "Connecting…";
    }
    String refusal = refusal(button);
    if (refusal != null) {
      return refusal;
    }
    Instant now = clock.instant();
    for (String fault : button.faults) {
      record.putIfAbsent(fault, now);
    }
    if (button.storm) {
      stormStarted = now;
      stormLabel = button.label;
    }
    if (button == Button.CLEAR || button == Button.RESET) {
      running = button == Button.CLEAR ? Running.CLEAR : Running.RESET;
      runStarted = now;
      labClear = false;
      vigilReady = false;
    }
    if (button == Button.RESET) {
      stormStarted = null;
      stormLabel = null;
    }
    save();
    return null;
  }

  synchronized void labRead(Lab.Counts read) {
    counts = read;
  }

  synchronized void branchADone() {
    labClear = true;
  }

  synchronized void branchBDone() {
    vigilReady = true;
  }

  /** Ends the running operation with its result; the record is emptied when asked. */
  synchronized void finish(LastResult result, boolean emptyRecord) {
    running = Running.NONE;
    runStarted = null;
    last = result;
    if (emptyRecord) {
      record.clear();
    }
    save();
  }

  /** A fault button that failed: its result becomes the last result; the record is kept. */
  synchronized void faultButtonFailed(LastResult result) {
    last = result;
    save();
  }

  /** The result of a lab reachability check: null when it succeeded. */
  synchronized void labChecked(String error, Lab.Counts read) {
    labAnswered = true;
    labError = error;
    if (read != null) {
      counts = read;
    }
  }

  /** The result of a Vigil reachability check: null when it succeeded. */
  synchronized void vigilChecked(String error) {
    vigilAnswered = true;
    vigilError = error;
  }

  /** The status line of DC-S12-05. */
  synchronized String status() {
    if (running == Running.CLEAR) {
      return "Clearing faults… "
          + elapsed()
          + " s — firing lab alerts: Zabbix "
          + counts.zabbix()
          + ", Alertmanager "
          + counts.alertmanager();
    }
    if (running == Running.RESET) {
      return "Resetting… "
          + elapsed()
          + " s — lab: "
          + (labClear ? "Clear" : "Clearing")
          + ", "
          + product
          + ": "
          + (vigilReady ? "Ready" : "Resetting");
    }
    if (labError != null) {
      return "Cannot reach the lab: " + labError + ".";
    }
    if (vigilError != null) {
      return "Cannot reach " + product + ": " + vigilError + ".";
    }
    return last == null ? "Idle" : last.line();
  }

  /** The storm clock line of DC-S12-06, or null. */
  synchronized String stormClock() {
    if (stormStarted == null) {
      return null;
    }
    long seconds = Math.max(0, Duration.between(stormStarted, clock.instant()).toSeconds());
    return "T+%d:%02d since '%s'".formatted(seconds / 60, seconds % 60, stormLabel);
  }

  private long elapsed() {
    return Math.max(0, Duration.between(runStarted, clock.instant()).toSeconds());
  }

  /** The answer of GET /api/state (spec 6.3 item 4). */
  synchronized Map<String, Object> view() {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("product", product);
    view.put("vigil_url", vigilUrl);
    view.put("connected", connected());
    List<Map<String, Object>> active = new ArrayList<>();
    record.forEach(
        (fault, since) -> {
          Map<String, Object> line = new LinkedHashMap<>();
          line.put("fault", fault);
          line.put("name", Button.FAULT_NAMES.get(fault));
          line.put("since", iso(since));
          active.add(line);
        });
    view.put("active", active);
    List<Map<String, Object>> buttons = new ArrayList<>();
    for (Button button : Button.values()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("key", button.key);
      entry.put("label", button.label);
      entry.put("description", button.description(product));
      entry.put("refused", refusal(button));
      buttons.add(entry);
    }
    view.put("buttons", buttons);
    view.put("status", status());
    view.put("storm_clock", stormClock());
    view.put("last_result", last == null ? null : last.toMap());
    return view;
  }

  private void load() {
    if (!Files.exists(stateFile)) {
      return;
    }
    JsonNode json;
    try {
      json = Http.JSON.readTree(Files.readString(stateFile));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (JsonNode line : json.path("record")) {
      record.put(line.path("fault").asString(), Instant.parse(line.path("since").asString()));
    }
    JsonNode storm = json.get("storm_clock");
    if (storm != null && storm.isObject()) {
      stormStarted = Instant.parse(storm.path("started").asString());
      stormLabel = storm.path("label").asString();
    }
    last = LastResult.of(json.get("last_result"));
  }

  private void save() {
    Map<String, Object> state = new LinkedHashMap<>();
    List<Map<String, Object>> lines = new ArrayList<>();
    record.forEach((fault, since) -> lines.add(Map.of("fault", fault, "since", iso(since))));
    state.put("record", lines);
    state.put(
        "storm_clock",
        stormStarted == null ? null : Map.of("started", iso(stormStarted), "label", stormLabel));
    state.put("last_result", last == null ? null : last.toMap());
    try {
      Path folder = stateFile.toAbsolutePath().getParent();
      Files.createDirectories(folder);
      Path temporary = Files.createTempFile(folder, ".state-", ".json");
      Files.writeString(temporary, Http.JSON.writeValueAsString(state));
      Files.move(
          temporary,
          stateFile,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
