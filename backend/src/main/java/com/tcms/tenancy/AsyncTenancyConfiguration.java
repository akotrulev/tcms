package com.tcms.tenancy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on asynchronous execution and scheduling, and installs
 * {@link CompanyContextTaskDecorator} on the executor behind {@code @Async} and on the
 * scheduler behind {@code @Scheduled}.
 *
 * <h2>One bean is the whole wiring, and that was measured rather than assumed</h2>
 *
 * <p>Spring Boot 3.5 applies a single {@link org.springframework.core.task.TaskDecorator}
 * bean to both builders it may use for either role — {@code SimpleAsyncTaskExecutor} /
 * {@code SimpleAsyncTaskScheduler} on virtual threads, {@code ThreadPoolTaskExecutor} /
 * {@code ThreadPoolTaskScheduler} without them. So declaring the decorator as a bean
 * covers all four, and it keeps covering them if
 * {@code spring.threads.virtual.enabled} is ever flipped — which is the configuration
 * where the guard stops being insurance and starts being load-bearing.
 * {@code PooledAsyncContextTest} boots that configuration and asserts exactly this.
 *
 * <p>This class first carried four explicit {@code *Customizer} beans setting the
 * decorator by hand. They were <strong>inert</strong>: mutating each one to stop setting
 * the decorator changed no test result, because Boot had already applied it. Code that
 * reads as a safety mechanism while doing nothing is worse than no code — it invites the
 * next reader to trust it. Deleted, and the fact recorded here so it is not "restored"
 * as a fix later.
 *
 * <p>Defining {@code applicationTaskExecutor} / {@code taskScheduler} beans outright
 * would have been the other tempting shape, and is also wrong here: it backs off Boot's
 * auto-configuration and pins the executor <em>kind</em>, silently discarding
 * {@code spring.threads.virtual.enabled}.
 *
 * @see CompanyContextTaskDecorator why the tasks need decorating at all
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class AsyncTenancyConfiguration {

    @Bean
    CompanyContextTaskDecorator companyContextTaskDecorator() {
        return new CompanyContextTaskDecorator();
    }
}
