package vigil.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.identity.api.IdentityImport;
import vigil.identity.api.IdentityPart;
import vigil.identity.api.IdentityPart.GrantPart;
import vigil.identity.api.IdentityPart.UserPart;
import vigil.identity.api.IdentityService;
import vigil.identity.api.NewSession;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantAliasChanged;
import vigil.tenancy.api.TenantImport;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantSettingsPart;
import vigil.testsupport.DoubleDelivery;
import vigil.testsupport.MutableClock;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionExpiryIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-16T08:00:00Z"));
  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final String HOST = "demo.vigil.test";
  private static final String PASSWORD = IdentityTypesTest.randomPassword();

  private static VigilDatabase database;
  private static VigilTestApp app;
  private static TenantService tenants;
  private static IdentityService identity;
  private static SessionSweeps sweeps;
  private static TenantId tenantA;
  private static TenantId tenantB;
  private static UUID sessionOfA;

  /** A call of {@code scheduleWithFixedDelay(Runnable, Instant, Duration)}. */
  record FixedDelayTask(Instant registeredAt, Instant startTime, Duration delay) {}

  /** The test clock. */
  static class TestClock {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  /** Records the fixed-delay tasks registered with the task scheduler (as AppStartIT does). */
  static class SweepRecordingScheduler {

    static final List<FixedDelayTask> TASKS = new CopyOnWriteArrayList<>();

    @Bean
    static BeanPostProcessor sweepRecordingSchedulerPostProcessor() {
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
                      TASKS.add(
                          new FixedDelayTask(scheduler.getClock().instant(), startTime, delay));
                    }
                    return invocation.proceed();
                  });
          return proxy.getProxy();
        }
      };
    }
  }

  @BeforeAll
  static void start() {
    database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, TestClock.class, SweepRecordingScheduler.class);
    app.awaitStarted();
    use(app.context());
    tenantA = readyTenant("tenant A");
    tenantB = readyTenant("tenant B");
  }

  private static void use(ConfigurableApplicationContext context) {
    tenants = context.getBean(TenantService.class);
    identity = context.getBean(IdentityService.class);
    sweeps = context.getBean(SessionSweeps.class);
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static TenantId readyTenant(String name) {
    TenantId tenant = tenants.createTenant(name, Actor.module("vigil-setup"));
    app.context()
        .getBean(TenantImport.class)
        .importPart(
            tenant, new TenantSettingsPart("C", null, "P", null), CLOCK.instant(), Map.of());
    app.context()
        .getBean(IdentityImport.class)
        .importPart(
            tenant,
            new IdentityPart(
                List.of(),
                List.of(new UserPart("presenter", "Demo Presenter", PASSWORD)),
                List.of(),
                List.of(new GrantPart("presenter", List.of("view")))),
            CLOCK.instant(),
            Map.of());
    tenants.markReady(tenant);
    return tenant;
  }

  private static String state(UUID session) {
    return database
        .postgres()
        .sql(
            "SELECT state || ' ' || coalesce(end_reason, '') FROM identity.login_session WHERE id = ?")
        .param(session)
        .query(String.class)
        .single()
        .trim();
  }

  private static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition met within %s", WAIT).isLessThan(deadline);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  @Test
  @Order(1)
  @DisplayName(
      "AR-R-23 the sweep of an active tenant: registered at once, every 60 s, ends idle sessions")
  void sweepOfAnActiveTenant() {
    assertThat(sweeps.scheduledTenants()).isEmpty();
    int before = SweepRecordingScheduler.TASKS.size();
    tenants.setAlias(HOST, tenantA, "installation token");
    awaitTrue(() -> sweeps.scheduledTenants().contains(tenantA));
    assertThat(sweeps.scheduledTenants()).containsExactly(tenantA);
    List<FixedDelayTask> added =
        SweepRecordingScheduler.TASKS.subList(before, SweepRecordingScheduler.TASKS.size());
    assertThat(added).hasSize(1);
    FixedDelayTask task = added.getFirst();
    assertThat(task.delay()).isEqualTo(Duration.ofSeconds(60));
    assertThat(task.startTime()).isBeforeOrEqualTo(task.registeredAt());
    assertThat(Duration.between(task.startTime(), task.registeredAt()))
        .isLessThanOrEqualTo(Duration.ofSeconds(1));

    Instant login = CLOCK.advance(Duration.ofHours(1));
    NewSession idle = identity.login(tenantA, "presenter", PASSWORD).orElseThrow();
    CLOCK.set(login.plus(Duration.ofHours(2)).plusSeconds(1));
    sweeps.sweep(tenantA);
    assertThat(state(idle.sessionId())).isEqualTo("ended expired");
  }

  @Test
  @Order(2)
  @DisplayName("AR-E-14 identity's listener matches the sweeps to the active tenants")
  void listenerMatchesSweeps() {
    TenantAliasListener listener =
        AopTestUtils.getUltimateTargetObject(app.context().getBean(TenantAliasListener.class));
    listener.on(new TenantAliasChanged(HOST, tenantA, null));
    assertThat(sweeps.scheduledTenants()).containsExactly(tenantA);
  }

  @Test
  @Order(3)
  @DisplayName("AR-Q-05 a second delivery of TenantAliasChanged gives the same result")
  void doubleDelivery() {
    sessionOfA = identity.login(tenantA, "presenter", PASSWORD).orElseThrow().sessionId();
    TenantAliasListener listener =
        AopTestUtils.getUltimateTargetObject(app.context().getBean(TenantAliasListener.class));
    TransactionTemplate tx =
        new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    List<Set<TenantId>> scheduled = new CopyOnWriteArrayList<>();
    DoubleDelivery.assertSameResult(
        database.postgres(),
        "identity",
        tx,
        () -> {
          listener.on(new TenantAliasChanged(HOST, tenantA, null));
          scheduled.add(sweeps.scheduledTenants());
        });
    assertThat(scheduled).containsExactly(Set.of(tenantA), Set.of(tenantA));
  }

  @Test
  @Order(4)
  @DisplayName("PM-R-11 the sweep runs only for active tenants: moving the host stops A's sweep")
  void onlyActiveTenants() {
    assertThat(state(sessionOfA)).isEqualTo("active");
    tenants.setAlias(HOST, tenantB, "installation token");
    awaitTrue(() -> sweeps.scheduledTenants().equals(Set.of(tenantB)));
    CLOCK.advance(Duration.ofHours(13));
    sweeps.sweep(tenantB);
    assertThat(state(sessionOfA)).isEqualTo("active");
  }

  @Test
  @Order(5)
  @DisplayName(
      "DM-R-70 a tenant that is no longer active keeps its records: its session is not ended")
  void dormantTenantUnchanged() {
    assertThat(sweeps.scheduledTenants()).containsExactly(tenantB);
    assertThat(tenants.isActive(tenantA)).isFalse();
    assertThat(state(sessionOfA)).isEqualTo("active");
  }

  @Test
  @Order(6)
  @DisplayName("AR-R-24 restart step 6 registers the sweep of the active tenant")
  void restartStep6() {
    app.close();
    app = VigilTestApp.start(database, TestClock.class);
    app.awaitStarted();
    use(app.context());
    assertThat(sweeps.scheduledTenants()).containsExactly(tenantB);
  }
}
