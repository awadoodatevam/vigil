package vigil.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe clock in UTC whose instant tests set and advance. Tests pass a source class with a
 * method {@code @Bean @Primary Clock testClock()} returning it to {@link VigilTestApp}.
 */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> instant;

  public MutableClock(Instant start) {
    this.instant = new AtomicReference<>(start);
  }

  public void set(Instant value) {
    instant.set(value);
  }

  public Instant advance(Duration duration) {
    return instant.updateAndGet(current -> current.plus(duration));
  }

  @Override
  public Instant instant() {
    return instant.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    if (!ZoneOffset.UTC.equals(zone)) {
      throw new UnsupportedOperationException("MutableClock is UTC only");
    }
    return this;
  }
}
