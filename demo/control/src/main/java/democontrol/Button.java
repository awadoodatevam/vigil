package democontrol;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The six buttons of DC-S12-02 in their order, with the faults each injects. */
enum Button {
  STORM(
      "storm",
      "Storm: SL-F-1 + SL-F-3",
      "Fills /data on the Subscriber DB server and fails the report export job.",
      List.of("SL-F-1", "SL-F-3"),
      true),
  STORM_RERUN(
      "storm-rerun",
      "Storm re-run: SL-F-1",
      "Fills /data on the Subscriber DB server again.",
      List.of("SL-F-1"),
      true),
  STORM_READ_ONLY(
      "storm-read-only",
      "Storm with read-only DB: SL-F-1 + SL-F-2",
      "Fills /data and sets the database read-only.",
      List.of("SL-F-1", "SL-F-2"),
      true),
  CUT_CLOUD_AI(
      "cut-cloud-ai",
      "Cut cloud AI: SL-F-4",
      "Blocks {product}'s calls to the Claude and OpenAI APIs.",
      List.of("SL-F-4"),
      false),
  CLEAR(
      "clear",
      "Clear all faults",
      "Ends every active fault and waits until every lab alert is resolved.",
      List.of(),
      false),
  RESET(
      "reset",
      "Reset",
      "Clears the lab and brings {product} to the starting state.",
      List.of(),
      false);

  /** The fault names of DD-F-10 ({@code faults.{id}.name}). */
  static final Map<String, String> FAULT_NAMES =
      Map.of(
          "SL-F-1", "Subscriber DB /data full",
          "SL-F-2", "Subscriber DB stays read-only",
          "SL-F-3", "Unrelated report export failure",
          "SL-F-4", "Cloud AI unreachable");

  final String key;
  final String label;
  final String description;
  final List<String> faults;
  final boolean storm;

  Button(String key, String label, String description, List<String> faults, boolean storm) {
    this.key = key;
    this.label = label;
    this.description = description;
    this.faults = faults;
    this.storm = storm;
  }

  String description(String product) {
    return description.replace("{product}", product);
  }

  static Optional<Button> of(String key) {
    return Arrays.stream(values()).filter(button -> button.key.equals(key)).findFirst();
  }
}
