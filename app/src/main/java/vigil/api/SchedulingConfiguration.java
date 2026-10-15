package vigil.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The task scheduler of AR-R-28 (4 threads) and the executor of {@code @Async} listeners (virtual
 * threads), so listeners never run on the scheduler's threads (AR-R-14).
 */
@Configuration
@Profile("!migrate")
class SchedulingConfiguration {

  @Bean
  ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("vigil-scheduler-");
    return scheduler;
  }

  @Bean(name = "taskExecutor")
  SimpleAsyncTaskExecutor taskExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("vigil-async-");
    executor.setVirtualThreads(true);
    return executor;
  }
}
