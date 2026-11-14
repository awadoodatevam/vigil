package democontrol;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The operations of the buttons (spec 6.4 items 3 and 4): the fault buttons, "Clear all faults" and
 * "Reset" (AR-B-03 with both branches, their limits and failure cases).
 */
class Operations {

  /** The answer to a press: null when the operation started, otherwise the reason. */
  record Press(boolean known, String refusal) {}

  private static final Logger LOG = LoggerFactory.getLogger(Operations.class);
  private static final DateTimeFormatter TENANT_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
  private static final Duration LAB_EVERY = Duration.ofSeconds(2);
  private static final Duration IMPORT_EVERY = Duration.ofSeconds(1);

  private final Panel panel;
  private final Lab lab;
  private final Vigil vigil;
  private final List<String> values;
  private final Clock clock;
  private final Duration limit;
  private final Duration settle;
  private final ExecutorService threads = Executors.newCachedThreadPool();

  /** The production limits are 58 s (AR-B-03) and 12 s (DD-F-10 {@code clear_settle_s}). */
  Operations(
      Panel panel,
      Lab lab,
      Vigil vigil,
      List<String> values,
      Clock clock,
      Duration limit,
      Duration settle) {
    this.panel = panel;
    this.lab = lab;
    this.vigil = vigil;
    this.values = values;
    this.clock = clock;
    this.limit = limit;
    this.settle = settle;
  }

  /** Presses a button by its key. */
  Press press(String key) {
    Button button = Button.of(key).orElse(null);
    if (button == null) {
      return new Press(false, null);
    }
    String refusal = panel.accept(button);
    if (refusal != null) {
      return new Press(true, refusal);
    }
    Instant started = clock.instant();
    LOG.info("{} started at {}", button.label, Panel.iso(started));
    switch (button) {
      case CLEAR -> threads.execute(() -> clear(panel.runStarted()));
      case RESET -> threads.execute(() -> reset(panel.runStarted()));
      default -> threads.execute(() -> inject(button, started));
    }
    return new Press(true, null);
  }

  private BigDecimal since(Instant start) {
    return LastResult.seconds(Duration.between(start, clock.instant()));
  }

  private LastResult result(
      String operation,
      Instant started,
      BigDecimal a,
      BigDecimal quiet,
      BigDecimal b,
      String error) {
    String outcome = error == null ? "completed" : "failed";
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    return new LastResult(operation, outcome, now, since(started), a, quiet, b, error);
  }

  private void inject(Button button, Instant started) {
    for (String fault : button.faults.stream().sorted().toList()) {
      try {
        lab.inject(fault);
      } catch (Failed e) {
        LastResult result = result(button.label, started, null, null, null, e.getMessage());
        panel.faultButtonFailed(result);
        log(result);
        return;
      }
    }
    LOG.info("{} ended after {} s", button.label, since(started).toPlainString());
  }

  private void clear(Instant started) {
    LabClear clear = new LabClear(started, new Stop());
    String error = null;
    try {
      clear.run();
    } catch (Failed e) {
      error = e.getMessage();
    } catch (Stopped e) {
      throw new IllegalStateException(e);
    }
    LastResult result = result(Button.CLEAR.label, started, null, clear.quiet.get(), null, error);
    finish(result, error == null || clear.cleared.get());
  }

  private interface Branch {
    void run() throws Failed, Stopped;
  }

  /** Runs a branch on its own thread: its outcome is "" or its error text. */
  private void branch(BlockingQueue<String> outcomes, Branch branch) {
    threads.execute(
        () -> {
          try {
            branch.run();
            outcomes.add("");
          } catch (Failed e) {
            outcomes.add(e.getMessage());
          } catch (Stopped e) {
            // the reset has already failed
          }
        });
  }

  private void reset(Instant started) {
    Stop stop = new Stop();
    LabClear clear = new LabClear(started, stop);
    AtomicReference<BigDecimal> branchA = new AtomicReference<>();
    AtomicReference<BigDecimal> branchB = new AtomicReference<>();
    AtomicReference<String> tenant = new AtomicReference<>();
    BlockingQueue<String> outcomes = new ArrayBlockingQueue<>(2);
    branch(
        outcomes,
        () -> {
          clear.run();
          branchA.set(since(started));
          panel.branchADone();
        });
    branch(
        outcomes,
        () -> {
          tenant.set(prepare(started, stop));
          branchB.set(since(started));
          panel.branchBDone();
        });
    String error = null;
    try {
      for (int branch = 0; branch < 2 && error == null; branch++) {
        String outcome = outcomes.take();
        error = outcome.isEmpty() ? null : outcome;
      }
      if (error == null) {
        vigil.setAlias(tenant.get(), Duration.ofSeconds(30));
      }
    } catch (Failed e) {
      error = e.getMessage();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error = "interrupted";
    }
    if (error != null) {
      stop.stop();
    }
    LastResult result =
        result(Button.RESET.label, started, branchA.get(), clear.quiet.get(), branchB.get(), error);
    finish(result, error == null || clear.cleared.get());
  }

  private void finish(LastResult result, boolean emptyRecord) {
    panel.finish(result, emptyRecord);
    log(result);
  }

  private void log(LastResult result) {
    LOG.info(
        "{} ended at {} after {} s: {}",
        result.operation(),
        Panel.iso(result.finishedAt()),
        result.seconds().toPlainString(),
        result.line());
  }

  /** Branch B: a new tenant with the seed bundle (AR-I-95 to AR-I-97); its id. */
  private String prepare(Instant started, Stop stop) throws Failed, Stopped {
    Instant deadline = started.plus(limit);
    stop.check();
    String tenantId =
        vigil.createTenant("jazz-demo " + TENANT_TIME.format(clock.instant()), left(deadline));
    stop.check();
    byte[] bundle = Bundle.build(Values.read(values));
    stop.check();
    String seedImportId = vigil.importBundle(tenantId, bundle, left(deadline));
    JsonNode answer;
    String lastError;
    while (true) {
      Instant turn = clock.instant();
      stop.check();
      lastError = null;
      answer = null;
      try {
        answer = vigil.readImport(seedImportId, left(deadline));
      } catch (Failed e) {
        lastError = e.getMessage();
      }
      String state = answer == null ? "" : answer.path("state").asString("");
      if (state.equals("succeeded") || state.equals("failed")) {
        break;
      }
      if (!clock.instant().isBefore(deadline)) {
        throw new Failed(
            lastError == null
                ? "The seed import was still running after " + limit.toSeconds() + " s."
                : "The seed import could not be read: " + lastError + ".");
      }
      stop.sleep(until(turn.plus(IMPORT_EVERY), deadline));
    }
    List<String> failed = new ArrayList<>();
    boolean allSucceeded = answer.path("state").asString("").equals("succeeded");
    for (JsonNode part : answer.path("parts")) {
      String outcome = part.path("outcome").asString("");
      allSucceeded &= outcome.equals("succeeded");
      if (outcome.equals("failed")) {
        failed.add(part.path("module").asString("") + ": " + part.path("error").asString("failed"));
      }
    }
    if (!allSucceeded) {
      throw new Failed("The seed import failed: " + String.join("; ", failed));
    }
    return tenantId;
  }

  private Duration left(Instant deadline) {
    return until(deadline, deadline);
  }

  private Duration until(Instant next, Instant deadline) {
    Instant end = next.isBefore(deadline) ? next : deadline;
    Duration wait = Duration.between(clock.instant(), end);
    return wait.isNegative() ? Duration.ZERO : wait;
  }

  /**
   * The lab clear (the whole of "Clear all faults", branch A of "Reset"): the four faults cleared,
   * the lab read every 2 s until quiet, then the settle time; all within the limit.
   */
  private final class LabClear {
    final Instant started;
    final Stop stop;
    final AtomicBoolean cleared = new AtomicBoolean();
    final AtomicReference<BigDecimal> quiet = new AtomicReference<>();

    LabClear(Instant started, Stop stop) {
      this.started = started;
      this.stop = stop;
    }

    void run() throws Failed, Stopped {
      Instant deadline = started.plus(limit);
      stop.check();
      lab.clearAll(left(deadline));
      cleared.set(true);
      Instant clearedAt = clock.instant();
      Lab.Counts counts = null;
      String lastError;
      while (true) {
        Instant turn = clock.instant();
        stop.check();
        lastError = null;
        try {
          counts = lab.read();
          panel.labRead(counts);
        } catch (Failed e) {
          lastError = e.getMessage();
        }
        if (lastError == null && counts.quiet()) {
          break;
        }
        if (!clock.instant().isBefore(deadline)) {
          throw notQuiet(lastError, counts);
        }
        stop.sleep(until(turn.plus(LAB_EVERY), deadline));
      }
      quiet.set(since(clearedAt));
      if (clock.instant().plus(settle).isAfter(deadline)) {
        stop.sleep(left(deadline));
        throw notQuiet(null, counts);
      }
      stop.sleep(settle);
    }

    private Failed notQuiet(String lastError, Lab.Counts counts) {
      return new Failed(
          lastError != null
              ? "The lab could not be read: " + lastError + "."
              : "Lab alerts were still firing after %d s: Zabbix %d, Alertmanager %d."
                  .formatted(limit.toSeconds(), counts.zabbix(), counts.alertmanager()));
    }
  }

  /** Raised in a branch that was stopped because the other branch failed. */
  static final class Stopped extends Exception {
    Stopped() {
      super(null, null, false, false);
    }
  }

  /** Stops a branch at its next call or wait. */
  static final class Stop {
    private final CountDownLatch stopped = new CountDownLatch(1);

    void stop() {
      stopped.countDown();
    }

    void check() throws Stopped {
      if (stopped.getCount() == 0) {
        throw new Stopped();
      }
    }

    void sleep(Duration duration) throws Stopped {
      try {
        if (stopped.await(duration.toNanos(), TimeUnit.NANOSECONDS)) {
          throw new Stopped();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new Stopped();
      }
    }
  }
}
