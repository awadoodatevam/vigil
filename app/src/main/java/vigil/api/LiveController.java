package vigil.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

/**
 * The live update stream (AR-I-16, AR-I-04, AR-T-10): Server-Sent Events written by the request's
 * own virtual thread (AR-R-33), with a heartbeat and an end after the open time (AR-TM-04).
 */
@RestController
@Profile("!migrate")
class LiveController {

  private static final Logger LOG = LoggerFactory.getLogger(LiveController.class);
  private static final byte[] RETRY = "retry: 1000\n\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PING = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

  private final LiveHub hub;
  private final LiveTimings timings;

  LiveController(LiveHub hub, LiveTimings timings) {
    this.hub = hub;
    this.timings = timings;
  }

  @GetMapping("/api/v1/live")
  @LoggedIn
  void live(
      ApiCaller caller,
      @RequestParam(name = "topics", required = false) String topics,
      HttpServletResponse response) {
    Set<LiveTopic> parsed = parseTopics(topics);
    LiveHub.Stream stream = hub.open(caller.tenantId(), parsed);
    try {
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType("text/event-stream;charset=UTF-8");
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
      OutputStream out = response.getOutputStream();
      out.write(RETRY);
      out.flush();
      long ping = timings.pingInterval().toNanos();
      long opened = System.nanoTime();
      long end = opened + timings.maxOpen().toNanos();
      long nextPing = opened + ping;
      while (true) {
        long now = System.nanoTime();
        if (now - end >= 0) {
          return;
        }
        if (now - nextPing >= 0) {
          out.write(PING);
          out.flush();
          nextPing += ping;
          continue;
        }
        byte[] notice = stream.next(Math.min(nextPing - now, end - now));
        if (notice == LiveHub.ENDED) {
          return;
        }
        if (notice != null) {
          out.write(notice);
          out.flush();
        }
      }
    } catch (IOException e) {
      LOG.debug("live stream client went away", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      hub.close(stream);
    }
  }

  /** Splits at every comma, keeping empty items; every item MUST parse (AR-I-04). */
  private static Set<LiveTopic> parseTopics(String topics) {
    if (topics == null || topics.isEmpty()) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    Set<LiveTopic> parsed = new LinkedHashSet<>();
    for (String item : topics.split(",", -1)) {
      try {
        parsed.add(LiveTopic.parse(item));
      } catch (IllegalArgumentException e) {
        throw new VigilError(Code.BAD_REQUEST);
      }
    }
    return parsed;
  }
}
