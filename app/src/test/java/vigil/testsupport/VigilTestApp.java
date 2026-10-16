package vigil.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import vigil.VigilApplication;

/**
 * Starts vigil-app in the test JVM on its own thread, on one database. Gives the port as soon as
 * the web server is up (before the restart procedure has finished) and the application context once
 * it exists. Every {@code *IT} that starts vigil-app uses this on its own fresh database.
 */
public final class VigilTestApp implements AutoCloseable {

  private static final Duration TIMEOUT = Duration.ofSeconds(120);
  private static final AtomicInteger COUNTER = new AtomicInteger();

  private final CompletableFuture<Integer> port = new CompletableFuture<>();
  private final CompletableFuture<ConfigurableApplicationContext> context =
      new CompletableFuture<>();
  private final CompletableFuture<ConfigurableApplicationContext> started =
      new CompletableFuture<>();

  private VigilTestApp(VigilDatabase database, List<String> extraArgs, Class<?>... configurations) {
    List<Class<?>> sources = new ArrayList<>();
    sources.add(VigilApplication.class);
    sources.addAll(List.of(configurations));
    SpringApplication application = new SpringApplication(sources.toArray(Class<?>[]::new));
    application.setRegisterShutdownHook(false);
    application.addListeners(
        event -> {
          if (event instanceof ApplicationPreparedEvent prepared) {
            context.complete(prepared.getApplicationContext());
          } else if (event instanceof WebServerInitializedEvent initialized) {
            port.complete(initialized.getWebServer().getPort());
          }
        });
    List<String> arguments =
        new ArrayList<>(
            List.of(
                "--server.port=0",
                "--VIGIL_SECRETS_DIR=" + database.appSecretsFolder(),
                "--VIGIL_DB_HOST=" + database.host(),
                "--VIGIL_DB_PORT=" + database.port(),
                "--VIGIL_DB_NAME=" + database.name()));
    arguments.addAll(extraArgs);
    String[] args = arguments.toArray(String[]::new);
    Thread thread =
        new Thread(
            () -> {
              try {
                started.complete(application.run(args));
              } catch (Throwable failure) {
                port.completeExceptionally(failure);
                context.completeExceptionally(failure);
                started.completeExceptionally(failure);
              }
            },
            "vigil-test-app-" + COUNTER.incrementAndGet());
    thread.start();
  }

  /** Starts vigil-app on {@code database} with extra configuration classes. */
  public static VigilTestApp start(VigilDatabase database, Class<?>... configurations) {
    return start(database, List.of(), configurations);
  }

  /**
   * Starts vigil-app on {@code database} with extra command-line arguments after the existing ones
   * and extra configuration classes.
   */
  public static VigilTestApp start(
      VigilDatabase database, List<String> extraArgs, Class<?>... configurations) {
    return new VigilTestApp(database, extraArgs, configurations);
  }

  /** The HTTP port, as soon as the web server is up. */
  public int port() {
    return await(port);
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + port();
  }

  /** The application context, once it exists. */
  public ConfigurableApplicationContext context() {
    return await(context);
  }

  /** Waits until the start, including the restart procedure, has finished; throws if it failed. */
  public ConfigurableApplicationContext awaitStarted() {
    return await(started);
  }

  @Override
  public void close() {
    if (context.isDone() && !context.isCompletedExceptionally()) {
      context.join().close();
    }
  }

  private static <T> T await(CompletableFuture<T> future) {
    try {
      return future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw new IllegalStateException("vigil-app did not start", e.getCause());
    } catch (TimeoutException e) {
      throw new IllegalStateException("vigil-app did not start within " + TIMEOUT, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
