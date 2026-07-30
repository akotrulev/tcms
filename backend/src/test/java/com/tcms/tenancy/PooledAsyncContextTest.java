package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The same guard, in the configuration where it stops being insurance.
 *
 * <p>{@code AsyncCompanyContextTest} runs against this application's real settings, where
 * {@code spring.threads.virtual.enabled=true} means Boot builds
 * {@code SimpleAsyncTaskExecutor}/{@code SimpleAsyncTaskScheduler} and every task gets a
 * fresh, never-reused virtual thread. A thread that is never reused cannot carry another
 * company's residue, so under that configuration
 * {@link CompanyContextTaskDecorator} is protecting against nothing.
 *
 * <p>Turning virtual threads off — one line of YAML, plausibly changed by someone
 * measuring a scheduler under load and not thinking about tenancy — swaps in pooled
 * executors, and pooled threads <em>are</em> reused. This boots exactly that
 * configuration and asserts the decorator survives the switch.
 *
 * <p>It costs one extra application context. That is the price of the claim in
 * {@code AsyncTenancyConfiguration}'s comment being tested rather than asserted, and the
 * claim is the reason the decorator exists at all.
 */
@DisplayName("Async company context — virtual threads OFF, where threads are pooled")
@TestPropertySource(properties = "spring.threads.virtual.enabled=false")
class PooledAsyncContextTest extends IntegrationTestBase {

    @Autowired
    private AsyncTaskExecutor applicationTaskExecutor;

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private CompanyContextTaskDecorator decorator;

    @Test
    @DisplayName("Boot builds POOLED executors, so this configuration is the one that can leak")
    void executorsArePooledWithoutVirtualThreads() {
        // Asserted rather than assumed: if a future Boot version stopped switching kinds on
        // this property, the assertion below would be testing the virtual-thread wiring a
        // second time and quietly proving nothing.
        assertThat(applicationTaskExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
    }

    @Test
    @DisplayName("the guard follows the switch onto both pooled executors")
    void pooledExecutorsCarryTheDecorator() {
        assertThat(ReflectionTestUtils.getField(applicationTaskExecutor, "taskDecorator"))
                .as("the pooled @Async executor must still decorate its tasks")
                .isSameAs(decorator);
        assertThat(ReflectionTestUtils.getField(taskScheduler, "taskDecorator"))
                .as("the pooled scheduler must still decorate its tasks")
                .isSameAs(decorator);
    }
}
