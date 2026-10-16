package vigil.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vigil.shared.TenantId;

/**
 * Delivers published change notices to the open live update streams of their tenant and topic
 * (PM-M23-04, AR-I-04). Producers publish after the commit of the change they announce.
 */
@Component
@Profile("!migrate")
class LiveHub implements SmartLifecycle {

  static final int QUEUE_CAPACITY = 1000;

  /** Returned by {@link Stream#next} when the stream has ended. */
  static final byte[] ENDED = new byte[0];

  private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
  private volatile boolean running;
  private volatile boolean stopped;

  /**
   * Offers the notice to every open stream of the tenant that asked for {@code topic}; never
   * blocks. A stream whose queue is full ends.
   */
  void publish(TenantId tenantId, LiveTopic topic, UUID id, Instant at) {
    if (tenantId == null || topic == null || id == null || at == null) {
      throw new IllegalArgumentException("tenant, topic, id and time are required");
    }
    byte[] notice =
        ("event: change\ndata: {\"topic\":\""
                + topic.text()
                + "\",\"id\":\""
                + id
                + "\",\"at\":\""
                + ApiTimes.format(at)
                + "\"}\n\n")
            .getBytes(StandardCharsets.UTF_8);
    for (Stream stream : streams) {
      if (stream.tenantId.equals(tenantId) && stream.topics.contains(topic)) {
        stream.offer(notice);
      }
    }
  }

  /** Registers a new stream; after {@link #stop()} the stream has ended at once. */
  Stream open(TenantId tenantId, Set<LiveTopic> topics) {
    Stream stream = new Stream(tenantId, Set.copyOf(topics));
    streams.add(stream);
    if (stopped) {
      stream.end();
    }
    return stream;
  }

  void close(Stream stream) {
    stream.end();
    streams.remove(stream);
  }

  /** The number of open streams. */
  int openStreams() {
    return streams.size();
  }

  @Override
  public void start() {
    stopped = false;
    running = true;
  }

  /** Ends every open stream. */
  @Override
  public void stop() {
    running = false;
    stopped = true;
    streams.forEach(Stream::end);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** One open stream: its tenant, its topics and its bounded queue of notices. */
  static final class Stream {

    private final TenantId tenantId;
    private final Set<LiveTopic> topics;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean ended;

    private Stream(TenantId tenantId, Set<LiveTopic> topics) {
      this.tenantId = tenantId;
      this.topics = topics;
    }

    private void offer(byte[] notice) {
      lock.lock();
      try {
        if (ended) {
          return;
        }
        if (queue.size() >= QUEUE_CAPACITY) {
          ended = true;
        } else {
          queue.add(notice);
        }
        changed.signalAll();
      } finally {
        lock.unlock();
      }
    }

    private void end() {
      lock.lock();
      try {
        ended = true;
        changed.signalAll();
      } finally {
        lock.unlock();
      }
    }

    /**
     * The next notice; {@code null} when none arrived within {@code timeoutNanos}; {@link #ENDED}
     * when the stream has ended.
     */
    byte[] next(long timeoutNanos) throws InterruptedException {
      lock.lock();
      try {
        long remaining = timeoutNanos;
        while (!ended && queue.isEmpty()) {
          if (remaining <= 0) {
            return null;
          }
          remaining = changed.awaitNanos(remaining);
        }
        return ended ? ENDED : queue.poll();
      } finally {
        lock.unlock();
      }
    }
  }
}
