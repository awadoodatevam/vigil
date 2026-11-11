package vigil.connectors;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The Teams sender (AR-R-32, AR-K-19): one per installation, in memory. A fair lock is held from
 * the wait to the answer, so posts go one at a time; a post starts at least 300 ms after the
 * previous post's answer or failure, waiting the rest on the caller's thread.
 */
@Component
class TeamsSender {

  static final Duration GAP = Duration.ofMillis(300);

  /** Waits a duration on the caller's thread. */
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final ReentrantLock lock = new ReentrantLock(true);
  private final LongSupplier nanoTime;
  private final Sleeper sleeper;
  private long lastAnswerAt;
  private boolean posted;

  /** The bean: {@code System.nanoTime} and {@code Thread.sleep}. */
  TeamsSender() {
    this(System::nanoTime, Thread::sleep);
  }

  /** With a monotonic time source in nanoseconds and a sleeper (tests). */
  TeamsSender(LongSupplier nanoTime, Sleeper sleeper) {
    this.nanoTime = nanoTime;
    this.sleeper = sleeper;
  }

  /** Makes the post when its turn has come; returns its result. */
  <T> T post(Supplier<T> post) {
    lock.lock();
    try {
      if (posted) {
        long wait = lastAnswerAt + GAP.toNanos() - nanoTime.getAsLong();
        if (wait > 0) {
          try {
            sleeper.sleep(Duration.ofNanos(wait));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Teams post interrupted", e);
          }
        }
      }
      try {
        return post.get();
      } finally {
        lastAnswerAt = nanoTime.getAsLong();
        posted = true;
      }
    } finally {
      lock.unlock();
    }
  }
}
