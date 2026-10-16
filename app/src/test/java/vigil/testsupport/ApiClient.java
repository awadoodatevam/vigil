package vigil.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A thin wrapper of {@link HttpClient} (HTTP/1.1) for the base address of a {@link VigilTestApp}.
 * It sends its own {@code Host} header, which needs {@code
 * -Djdk.httpclient.allowRestrictedHeaders=host} at JVM start (failsafe sets it).
 */
public final class ApiClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String baseUrl;
  private final HttpClient client;

  public ApiClient(String baseUrl) {
    this.baseUrl = baseUrl;
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(TIMEOUT)
            .build();
  }

  public ApiClient(VigilTestApp app) {
    this(app.baseUrl());
  }

  /** A new request for {@code method} and {@code path} (with its query). */
  public Request request(String method, String path) {
    return new Request(method, path);
  }

  public Request get(String path) {
    return request("GET", path);
  }

  public Request post(String path) {
    return request("POST", path);
  }

  public Request delete(String path) {
    return request("DELETE", path);
  }

  /** {@code POST /api/v1/session} on {@code host} with a JSON body of username and password. */
  public Response login(String host, String username, String password) {
    return post("/api/v1/session")
        .host(host)
        .json(
            JSON.writeValueAsString(
                new LinkedHashMap<>(Map.of("username", username, "password", password))))
        .send();
  }

  /** The answer: status, headers and body. */
  public record Response(int status, HttpHeaders headers, byte[] body) {

    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    /** The body parsed as JSON. */
    public JsonNode json() {
      return JSON.readTree(body);
    }

    public Optional<String> header(String name) {
      return headers.firstValue(name);
    }

    /** Every {@code Set-Cookie} value. */
    public List<String> setCookies() {
      return headers.allValues("set-cookie");
    }

    /** The {@code Set-Cookie} values of the cookie {@code name}. */
    public List<String> setCookies(String name) {
      return setCookies().stream().filter(value -> value.startsWith(name + "=")).toList();
    }

    /** The value of the only {@code Set-Cookie} of {@code name}. */
    public String cookieValue(String name) {
      List<String> values = setCookies(name);
      if (values.size() != 1) {
        throw new IllegalStateException(values.size() + " Set-Cookie for " + name);
      }
      String value = values.getFirst().substring(name.length() + 1);
      int end = value.indexOf(';');
      return end < 0 ? value : value.substring(0, end);
    }
  }

  /** One line of a stream: its bytes up to and including {@code \n}, and its arrival time. */
  public record Line(String text, long arrivedNanos) {}

  /** A request being built. */
  public final class Request {

    private final String method;
    private final String path;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private byte[] body;

    private Request(String method, String path) {
      this.method = method;
      this.path = path;
    }

    public Request host(String host) {
      return header("Host", host);
    }

    public Request header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    public Request cookie(String name, String value) {
      cookies.put(name, value);
      return this;
    }

    /** A JSON body with {@code Content-Type: application/json}. */
    public Request json(String json) {
      header("Content-Type", "application/json");
      return body(json.getBytes(StandardCharsets.UTF_8));
    }

    public Request body(byte[] content) {
      this.body = content;
      return this;
    }

    public Response send() {
      try {
        HttpResponse<byte[]> response =
            client.send(build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(), response.headers(), response.body());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    /** Opens a stream whose lines are read as they arrive. */
    public Stream open() {
      try {
        HttpResponse<InputStream> response =
            client.send(build(), HttpResponse.BodyHandlers.ofInputStream());
        return new Stream(response);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    private HttpRequest build() {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofByteArray(body));
      headers.forEach(builder::header);
      if (!cookies.isEmpty()) {
        StringBuilder cookie = new StringBuilder();
        cookies.forEach(
            (name, value) ->
                cookie.append(cookie.isEmpty() ? "" : "; ").append(name).append('=').append(value));
        builder.header("Cookie", cookie.toString());
      }
      return builder.build();
    }
  }

  /** An open stream; a reader thread queues each line as it arrives. */
  public static final class Stream implements AutoCloseable {

    private static final Line END = new Line(null, 0);

    private final HttpResponse<InputStream> response;
    private final BlockingQueue<Line> lines = new LinkedBlockingQueue<>();
    private volatile boolean ended;

    private Stream(HttpResponse<InputStream> response) {
      this.response = response;
      Thread.ofVirtual().name("api-client-stream").start(this::read);
    }

    public int status() {
      return response.statusCode();
    }

    public HttpHeaders headers() {
      return response.headers();
    }

    /** The next line, or empty when none arrived within {@code timeout} or the stream ended. */
    public Optional<Line> nextLine(Duration timeout) {
      try {
        Line line = lines.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (line == END) {
          lines.add(END);
          return Optional.empty();
        }
        return Optional.ofNullable(line);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    /** True once the connection has read end of stream (or failed). */
    public boolean ended() {
      return ended;
    }

    private void read() {
      try (InputStream in = response.body()) {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
          line.write(b);
          if (b == '\n') {
            lines.add(new Line(line.toString(StandardCharsets.UTF_8), System.nanoTime()));
            line.reset();
          }
        }
      } catch (IOException e) {
        // The connection closed; the stream has ended.
      } finally {
        ended = true;
        lines.add(END);
      }
    }

    @Override
    public void close() {
      try {
        response.body().close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
