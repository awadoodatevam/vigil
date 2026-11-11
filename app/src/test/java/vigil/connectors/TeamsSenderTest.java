package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamsSenderTest {

  private static final long ANSWER_NANOS = Duration.ofMillis(40).toNanos();

  /** A post: its start and answer on the test time source. */
  private record Post(long start, long answer) {}

  private static List<Post> postFromThreeThreads() throws InterruptedException {
    AtomicLong now = new AtomicLong(1_000_000_000L);
    TeamsSender sender = new TeamsSender(now::get, duration -> now.addAndGet(duration.toNanos()));
    List<Post> posts = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger overlaps = new AtomicInteger();
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < 3; t++) {
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    for (int i = 0; i < 4; i++) {
                      sender.post(
                          () -> {
                            if (inside.incrementAndGet() > 1) {
                              overlaps.incrementAndGet();
                            }
                            long start = now.get();
                            Thread.yield();
                            long answer = now.addAndGet(ANSWER_NANOS);
                            posts.add(new Post(start, answer));
                            inside.decrementAndGet();
                            return null;
                          });
                    }
                  }));
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertThat(overlaps).hasValue(0);
    return List.copyOf(posts);
  }

  @Test
  @DisplayName("AR-K-19 posts from three threads are serialised, each 300 ms after the last answer")
  void serialised() throws InterruptedException {
    List<Post> posts = postFromThreeThreads();
    assertThat(posts).hasSize(12);
    for (int i = 1; i < posts.size(); i++) {
      assertThat(posts.get(i).start() - posts.get(i - 1).answer())
          .isGreaterThanOrEqualTo(TeamsSender.GAP.toNanos());
    }
  }

  @Test
  @DisplayName("AR-R-32 one sender per installation keeps the gap across callers, in memory")
  void oneSender() throws InterruptedException {
    List<Post> posts = postFromThreeThreads();
    for (int i = 1; i < posts.size(); i++) {
      assertThat(posts.get(i).start()).isGreaterThan(posts.get(i - 1).answer());
    }
    AtomicLong now = new AtomicLong();
    List<Duration> sleeps = new ArrayList<>();
    TeamsSender sender =
        new TeamsSender(
            now::get,
            duration -> {
              sleeps.add(duration);
              now.addAndGet(duration.toNanos());
            });
    sender.post(() -> now.addAndGet(ANSWER_NANOS));
    now.addAndGet(Duration.ofMillis(100).toNanos());
    sender.post(() -> null);
    assertThat(sleeps).containsExactly(Duration.ofMillis(200));
    now.addAndGet(Duration.ofSeconds(1).toNanos());
    sender.post(() -> null);
    assertThat(sleeps).hasSize(1);
  }
}
