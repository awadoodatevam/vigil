package vigil.connectors.api;

/** The result of {@link TargetConnector#run}. */
public sealed interface TargetResult
    permits TargetResult.NotStarted, TargetResult.Finished, TargetResult.Interrupted {

  /** Nothing was sent to the target: the command certainly did not run. */
  record NotStarted(String reason) implements TargetResult {}

  /** The command ended with an exit status; the outputs as kept (DM-C-11). */
  record Finished(int exitStatus, String output, boolean outputTruncated, String errorOutput)
      implements TargetResult {}

  /** The command was sent and no exit status was received; the output received until then. */
  record Interrupted(String reason, String output, boolean outputTruncated)
      implements TargetResult {}
}
