package democontrol;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the tests move by hand. */
final class TestClock extends Clock {

  private Instant now;

  TestClock(Instant now) {
    this.now = now;
  }

  synchronized void advance(Duration duration) {
    now = now.plus(duration);
  }

  @Override
  public synchronized Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
