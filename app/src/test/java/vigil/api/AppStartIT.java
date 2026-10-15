package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.web.servlet.client.RestTestClient;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class AppStartIT {

  private static final String STARTING_BODY = "{\"error\":{\"code\":\"starting\"}}";
  private static final Pattern STEP_LINE =
      Pattern.compile("\"message\":\"restart step (\\d) done\"");

  private static VigilTestApp app;
  private static RestTestClient client;

  @BeforeAll
  static void start() {
    app =
        VigilTestApp.start(
            VigilDatabase.fresh(), BlockingResubmission.class, RecordingScheduler.class);
    client = RestTestClient.bindToServer().baseUrl(app.baseUrl()).build();
  }

  @AfterAll
  static void stop() {
    BlockingResubmission.RELEASE.countDown();
    app.close();
  }

  @Test
  @Order(1)
  @DisplayName("AR-R-24 503 starting until the restart procedure has run steps 1 to 7 in order")
  void readyGateAndRestartProcedure(CapturedOutput output) throws InterruptedException {
    assertThat(BlockingResubmission.ENTERED.await(120, TimeUnit.SECONDS))
        .as("step 3 reached")
        .isTrue();
    for (String path : List.of("/incidents", "/actuator/health")) {
      client
          .get()
          .uri(path)
          .exchange()
          .expectStatus()
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
          .expectHeader()
          .contentType(MediaType.APPLICATION_JSON)
          .expectBody(String.class)
          .isEqualTo(STARTING_BODY);
    }
    client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();

    BlockingResubmission.RELEASE.countDown();
    app.awaitStarted();
    awaitStatus("/actuator/health", HttpStatus.OK);

    List<Integer> steps =
        output
            .getOut()
            .lines()
            .map(STEP_LINE::matcher)
            .filter(Matcher::find)
            .map(matcher -> Integer.valueOf(matcher.group(1)))
            .toList();
    assertThat(steps).containsExactly(1, 2, 3, 4, 5, 6, 7);
  }

  @Test
  @Order(2)
  @DisplayName("AR-T-04 the connection pool has 20 connections")
  void connectionPool() {
    assertThat(app.context().getBean(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(20);
  }

  @Test
  @Order(3)
  @DisplayName("AR-R-28 the task scheduler has 4 threads named vigil-scheduler-")
  void taskScheduler() throws Exception {
    ThreadPoolTaskScheduler scheduler = app.context().getBean(ThreadPoolTaskScheduler.class);
    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
    CompletableFuture<String> threadName = new CompletableFuture<>();
    scheduler.schedule(() -> threadName.complete(Thread.currentThread().getName()), Instant.now());
    assertThat(threadName.get(10, TimeUnit.SECONDS)).startsWith("vigil-scheduler-");
  }

  @Test
  @Order(4)
  @DisplayName("AR-R-23 step 6 registers the per-installation event resubmission task")
  void perInstallationTask() {
    assertThat(RecordingScheduler.FIXED_DELAY_TASKS).hasSize(1);
    FixedDelayTask task = RecordingScheduler.FIXED_DELAY_TASKS.getFirst();
    assertThat(Duration.between(task.registeredAt(), task.startTime()))
        .isBetween(Duration.ofSeconds(59), Duration.ofSeconds(61));
    task.task().run();
    assertThat(BlockingResubmission.OLDER_THAN).containsExactly(Duration.ofSeconds(60));
  }

  @Test
  @Order(5)
  @DisplayName("AR-TM-08 the event resubmission runs with a fixed delay of 60 s")
  void resubmissionInterval() {
    assertThat(RecordingScheduler.FIXED_DELAY_TASKS)
        .singleElement()
        .extracting(FixedDelayTask::delay)
        .isEqualTo(Duration.ofSeconds(60));
  }

  private static void awaitStatus(String path, HttpStatus status) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (true) {
      HttpStatus actual =
          HttpStatus.valueOf(
              client.get().uri(path).exchange().returnResult(String.class).getStatus().value());
      if (actual == status || System.nanoTime() > deadline) {
        assertThat(actual).isEqualTo(status);
        return;
      }
      Thread.sleep(200);
    }
  }

  /** A call of {@code scheduleWithFixedDelay(Runnable, Instant, Duration)}. */
  record FixedDelayTask(Runnable task, Instant registeredAt, Instant startTime, Duration delay) {}

  /**
   * Replaces {@link IncompleteEventPublications} by a wrapper whose full resubmission (step 3)
   * blocks until released and that records the age of the per-installation task's calls.
   */
  static class BlockingResubmission {

    static final CountDownLatch ENTERED = new CountDownLatch(1);
    static final CountDownLatch RELEASE = new CountDownLatch(1);
    static final List<Duration> OLDER_THAN = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    IncompleteEventPublications blockingIncompleteEventPublications(ListableBeanFactory beans) {
      return new IncompleteEventPublications() {

        @Override
        public void resubmitIncompletePublications(Predicate<EventPublication> filter) {
          ENTERED.countDown();
          try {
            RELEASE.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          delegate().resubmitIncompletePublications(filter);
        }

        @Override
        public void resubmitIncompletePublicationsOlderThan(Duration duration) {
          OLDER_THAN.add(duration);
          delegate().resubmitIncompletePublicationsOlderThan(duration);
        }

        @Override
        public void resubmitIncompletePublications(ResubmissionOptions options) {
          delegate().resubmitIncompletePublications(options);
        }

        private IncompleteEventPublications delegate() {
          Map<String, IncompleteEventPublications> all =
              beans.getBeansOfType(IncompleteEventPublications.class);
          return all.entrySet().stream()
              .filter(entry -> !entry.getKey().equals("blockingIncompleteEventPublications"))
              .map(Map.Entry::getValue)
              .findFirst()
              .orElseThrow();
        }
      };
    }
  }

  /** Records the fixed-delay tasks registered with the task scheduler. */
  static class RecordingScheduler {

    static final List<FixedDelayTask> FIXED_DELAY_TASKS = new CopyOnWriteArrayList<>();

    @Bean
    static BeanPostProcessor recordingSchedulerPostProcessor() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (!(bean instanceof ThreadPoolTaskScheduler scheduler)) {
            return bean;
          }
          ProxyFactory proxy = new ProxyFactory(scheduler);
          proxy.setProxyTargetClass(true);
          proxy.addAdvice(
              (MethodInterceptor)
                  invocation -> {
                    Object[] args = invocation.getArguments();
                    if (invocation.getMethod().getName().equals("scheduleWithFixedDelay")
                        && args.length == 3
                        && args[1] instanceof Instant startTime
                        && args[2] instanceof Duration delay) {
                      FIXED_DELAY_TASKS.add(
                          new FixedDelayTask(
                              (Runnable) args[0],
                              scheduler.getClock().instant(),
                              startTime,
                              delay));
                    }
                    return invocation.proceed();
                  });
          return proxy.getProxy();
        }
      };
    }
  }
}
