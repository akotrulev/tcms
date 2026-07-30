package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The async-context harness (plan §0.2, handoff §3 lesson 14) — the fourth and last of
 * the M0 isolation deliverables, and the one covering the surfaces the synchronous
 * harness structurally cannot reach.
 *
 * <p>{@code RlsIsolationTest} proves the boundary holds on the thread that opened the
 * context. Every leak it can see is a leak on a request thread. But
 * {@link CompanyContext} is not inheritable by design, so the realistic multi-company
 * leak is elsewhere: work that <em>leaves</em> the request thread —
 * <strong>ingestion, report generation, a scheduled purge, an event handler</strong> —
 * and lands on a worker that either carries someone else's residue or carries nothing
 * and reads on regardless.
 *
 * <p>Each of the four runs here <strong>with a foreign company active on the submitting
 * thread</strong>, which is the condition under which "it inherited the caller's
 * company" and "it re-established its own" look identical from the outside.
 *
 * <h2>The two properties under test, and why both are needed</h2>
 *
 * <ol>
 *   <li><strong>Re-establish.</strong> A handler opens the company from its event
 *       envelope and sees exactly that company — not the submitter's, not both.</li>
 *   <li><strong>Fail closed.</strong> A handler that <em>forgets</em> reads
 *       <em>zero</em> rows, never the submitter's. Property 1 without property 2 is a
 *       convention; the convention is what gets forgotten.</li>
 * </ol>
 */
@DisplayName("Async company context — ingestion, reporting, scheduling, events (plan §0.2)")
@Import(AsyncCompanyContextTest.AsyncSurfaces.class)
class AsyncCompanyContextTest extends IntegrationTestBase {

    // A namespace of this class's own, per the convention the schema tests use. The
    // container is shared across the whole run and every class seeds into it, so two
    // classes sharing a company id share a fixture: this test first ran against
    // PermissionEvaluatorTest's 3333…/4444… companies and saw its projects too.
    private static final UUID ACME = UUID.fromString("a55c0000-0000-4000-8000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("a55c0000-0000-4000-8000-000000000002");

    /** Long enough that a slow container never flakes; short enough that a hang is a failure. */
    private static final int TIMEOUT_SECONDS = 30;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private IngestionPipeline ingestion;

    @Autowired
    private ReportGenerator reports;

    @Autowired
    private RetentionPurge purge;

    @Autowired
    private ProjectArchivedListener archivedListener;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private CompanyContextTaskDecorator decorator;

    /** Boot's {@code @Async} executor, by bean name. */
    @Autowired
    private org.springframework.core.task.AsyncTaskExecutor applicationTaskExecutor;

    @BeforeEach
    void seedTwoCompanies() {
        // Instance-level rows: no company context, by design.
        tx.executeWithoutResult(status -> {
            if (companies.findById(ACME).isEmpty()) {
                companies.save(Company.provision(ACME, "async-acme", "Async Acme", Instant.now()));
            }
            if (companies.findById(GLOBEX).isEmpty()) {
                companies.save(Company.provision(GLOBEX, "async-globex", "Async Globex", Instant.now()));
            }
        });
        seedProject(ACME, "async-acme-report");
        seedProject(GLOBEX, "async-globex-report");
        archivedListener.reset();
    }

    private void seedProject(UUID companyId, String marker) {
        try (var ignored = CompanyContext.open(companyId)) {
            tx.executeWithoutResult(status -> {
                if (projects.findByKey("ASYNC").isEmpty()) {
                    // Same key and same name in both companies — identical-looking data is
                    // what turns a leak into a visible failure rather than a lucky pass.
                    projects.save(Project.create(
                            UUID.randomUUID(), companyId, "ASYNC", "Async Product", marker, Instant.now()));
                }
            });
        }
    }

    // ---------------------------------------------------------------- surface 1: ingestion

    @Test
    @DisplayName("ingestion re-establishes from the event envelope, not from the submitting thread")
    void ingestionReadsItsOwnCompany() throws Exception {
        Observation observed;
        // Submitted while GLOBEX is active — the hostile condition. If the worker
        // inherited anything, it would inherit Globex.
        try (var ignored = CompanyContext.open(GLOBEX)) {
            observed = ingestion.ingest(new ResultsIngested(ACME)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(observed.ambientCompanyOnEntry())
                .as("a worker thread must start with NO company — inheriting would make a leak look correct")
                .isNull();
        assertThat(observed.visible())
                .as("ingestion for Acme must see Acme's data and only Acme's")
                .containsExactly("async-acme-report");
    }

    // -------------------------------------------------------- surface 2: report generation

    @Test
    @DisplayName("report generation that FORGETS to open a context reads zero rows, not the submitter's")
    void forgetfulReportGenerationFailsClosed() throws Exception {
        // The whole point of the design. A report job is the classic place to forget,
        // because it reads and never writes, so nothing complains. With the GUC unset
        // the RLS policies yield nothing (RlsIsolationTest trap 2) — but only if the
        // worker thread genuinely has no company, which is this decorator's job.
        List<String> visible;
        try (var ignored = CompanyContext.open(GLOBEX)) {
            visible = reports.generateForgettingContext().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(visible)
                .as("a context-less async read must see NOTHING — never the submitting thread's company")
                .isEmpty();
    }

    @Test
    @DisplayName("report generation for one company cannot see the other, and leaves the caller's context intact")
    void reportGenerationIsScopedToItsArgument() throws Exception {
        List<String> visible;
        try (var ignored = CompanyContext.open(GLOBEX)) {
            visible = reports.generateFor(ACME).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(CompanyContext.current())
                    .as("dispatching async work must not disturb the caller's own company")
                    .contains(GLOBEX);
        }
        assertThat(visible).containsExactly("async-acme-report");
    }

    // ---------------------------------------------------------- surface 3: a scheduled purge

    @Test
    @DisplayName("a scheduled sweep over both companies keeps them apart, run from a foreign context")
    void scheduledPurgeSweepsEachCompanySeparately() throws Exception {
        // A scheduled job has no request and no envelope: it iterates companies and opens
        // each in turn. The failure mode is the loop leaking company N into iteration N+1,
        // which a single-company assertion cannot see — so both are asserted, in order.
        CompletableFuture<List<Observation>> swept = new CompletableFuture<>();

        try (var ignored = CompanyContext.open(GLOBEX)) {
            taskScheduler.schedule(
                    () -> {
                        try {
                            swept.complete(purge.sweep(List.of(ACME, GLOBEX)));
                        } catch (RuntimeException e) {
                            swept.completeExceptionally(e);
                        }
                    },
                    Instant.now());
        }

        List<Observation> observations = swept.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).ambientCompanyOnEntry())
                .as("the scheduler thread must not have inherited the company that scheduled the task")
                .isNull();
        assertThat(observations.get(0).visible()).containsExactly("async-acme-report");
        assertThat(observations.get(1).ambientCompanyOnEntry())
                .as("iteration 1's scope must have closed before iteration 2 opened its own")
                .isNull();
        assertThat(observations.get(1).visible())
                .as("the second iteration must not carry the first's company")
                .containsExactly("async-globex-report");
    }

    // ------------------------------------------------------------ surface 4: an event handler

    @Test
    @DisplayName("an @Async after-commit event handler re-establishes from the envelope")
    void asyncEventHandlerReadsTheEventsCompany() throws Exception {
        // AFTER_COMMIT is the phase that matters: the publishing transaction — and with it
        // the GUC and the Hibernate filter — is already gone by the time the handler runs.
        // The handler therefore has nothing to inherit even in principle, which is exactly
        // why the envelope has to carry the company.
        try (var ignored = CompanyContext.open(ACME)) {
            tx.executeWithoutResult(status -> events.publishEvent(new ProjectArchived(ACME)));
        }

        Observation observed = archivedListener.awaitObservation(TIMEOUT_SECONDS);

        assertThat(observed.ambientCompanyOnEntry())
                .as("the handler runs on a worker thread, after commit, with no company of its own")
                .isNull();
        assertThat(observed.visible()).containsExactly("async-acme-report");
    }

    // ------------------------------------------------------- the mechanism the four rest on

    @Test
    @DisplayName("a pooled thread cannot carry one task's company into the next")
    void pooledThreadCarriesNoResidue() throws Exception {
        // The decorator's entire reason to exist, tested where it is actually reachable.
        // The application's own executors currently run on virtual threads, which are never
        // reused — so this uses a deliberately pooled, single-threaded executor to guarantee
        // the second task lands on the first task's thread. Flipping
        // spring.threads.virtual.enabled to false would make the production executors behave
        // exactly like this one, which is why the guard is not optional.
        ThreadPoolTaskExecutor pool = singleThreadPool();
        try {
            AtomicReference<Thread> firstThread = new AtomicReference<>();
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            AtomicReference<UUID> residue = new AtomicReference<>();

            // Task 1 opens a company and LEAKS it — a handler that forgot to close, or
            // threw past its close. Not a strawman: it is one missing try-with-resources.
            pool.submit(() -> {
                firstThread.set(Thread.currentThread());
                CompanyContext.open(GLOBEX);
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            pool.submit(() -> {
                secondThread.set(Thread.currentThread());
                residue.set(CompanyContext.currentOrNull());
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(secondThread.get())
                    .as("fixture precondition: both tasks must share a thread, or nothing is proven")
                    .isSameAs(firstThread.get());
            assertThat(residue.get())
                    .as("the next task on a recycled thread must inherit NOTHING")
                    .isNull();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("running a task inline restores the submitting thread's own company")
    void inlineExecutionRestoresTheCallersCompany() {
        // A rejection policy of CallerRuns, a SyncTaskExecutor in a test, or a
        // TaskDecorator applied on an inline path all run the task on the CALLER's thread.
        // Clearing without restoring would then silently strip the caller's company and the
        // next statement in the request would read zero rows.
        try (var ignored = CompanyContext.open(ACME)) {
            decorator.decorate(() -> assertThat(CompanyContext.currentOrNull()).isNull()).run();

            assertThat(CompanyContext.current())
                    .as("the caller's company must survive running a decorated task inline")
                    .contains(ACME);
        }
    }

    @Test
    @DisplayName("the guard is installed on the application's own executor and scheduler")
    void applicationExecutorsCarryTheDecorator() {
        // The four surfaces above would pass on virtual threads even with no decorator at
        // all, because a virtual thread is never reused. This asserts the wiring itself, so
        // switching spring.threads.virtual.enabled off cannot quietly remove the guard.
        assertThat(ReflectionTestUtils.getField(applicationTaskExecutor, "taskDecorator"))
                .as("the @Async executor must decorate its tasks")
                .isSameAs(decorator);
        assertThat(ReflectionTestUtils.getField(taskScheduler, "taskDecorator"))
                .as("the scheduler must decorate its tasks")
                .isSameAs(decorator);
    }

    private static ThreadPoolTaskExecutor singleThreadPool() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(10);
        pool.setTaskDecorator(new CompanyContextTaskDecorator());
        pool.initialize();
        return pool;
    }

    // ------------------------------------------------------------------------- test surfaces

    /** What a worker thread saw: what it inherited, and what it could then read. */
    record Observation(UUID ambientCompanyOnEntry, List<String> visible) {
    }

    record ResultsIngested(UUID companyId) implements CompanyScopedEvent {
    }

    record ProjectArchived(UUID companyId) implements CompanyScopedEvent {
    }

    /**
     * The four async surfaces named in the M0 deliverable, as the thinnest beans that can
     * carry the behaviour. They live in the test because the modules that will own them
     * ({@code execution}, {@code reporting}, {@code work}) do not exist yet — the property
     * being pinned is the tenancy plumbing underneath them, which does.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class AsyncSurfaces {

        @Bean
        IngestionPipeline ingestionPipeline(ProjectRepository projects, TransactionTemplate tx) {
            return new IngestionPipeline(projects, tx);
        }

        @Bean
        ReportGenerator reportGenerator(ProjectRepository projects, TransactionTemplate tx) {
            return new ReportGenerator(projects, tx);
        }

        @Bean
        RetentionPurge retentionPurge(ProjectRepository projects, TransactionTemplate tx) {
            return new RetentionPurge(projects, tx);
        }

        @Bean
        ProjectArchivedListener projectArchivedListener(ProjectRepository projects, TransactionTemplate tx) {
            return new ProjectArchivedListener(projects, tx);
        }
    }

    /** Reads what the active company can see — the observable a leak would change. */
    abstract static class CompanyScopedWork {

        private final ProjectRepository projects;
        private final TransactionTemplate tx;

        CompanyScopedWork(ProjectRepository projects, TransactionTemplate tx) {
            this.projects = projects;
            this.tx = tx;
        }

        List<String> visibleDescriptions() {
            return tx.execute(status ->
                    projects.findAllByOrderByNameAsc().stream().map(Project::getDescription).toList());
        }
    }

    /** Surface 1 — result ingestion, driven by an envelope. */
    static class IngestionPipeline extends CompanyScopedWork {

        IngestionPipeline(ProjectRepository projects, TransactionTemplate tx) {
            super(projects, tx);
        }

        @Async
        CompletableFuture<Observation> ingest(ResultsIngested event) {
            UUID inherited = CompanyContext.currentOrNull();
            try (var ignored = CompanyContext.openFor(event)) {
                return CompletableFuture.completedFuture(new Observation(inherited, visibleDescriptions()));
            }
        }
    }

    /** Surface 2 — report generation, in both a correct and a forgetful variant. */
    static class ReportGenerator extends CompanyScopedWork {

        ReportGenerator(ProjectRepository projects, TransactionTemplate tx) {
            super(projects, tx);
        }

        @Async
        CompletableFuture<List<String>> generateFor(UUID companyId) {
            try (var ignored = CompanyContext.open(companyId)) {
                return CompletableFuture.completedFuture(visibleDescriptions());
            }
        }

        /** Deliberately opens no context. Must read nothing rather than someone's data. */
        @Async
        CompletableFuture<List<String>> generateForgettingContext() {
            return CompletableFuture.completedFuture(visibleDescriptions());
        }
    }

    /** Surface 3 — a scheduled retention sweep across every company in turn. */
    static class RetentionPurge extends CompanyScopedWork {

        RetentionPurge(ProjectRepository projects, TransactionTemplate tx) {
            super(projects, tx);
        }

        List<Observation> sweep(List<UUID> companyIds) {
            List<Observation> observations = new ArrayList<>();
            for (UUID companyId : companyIds) {
                UUID inherited = CompanyContext.currentOrNull();
                try (var ignored = CompanyContext.open(companyId)) {
                    observations.add(new Observation(inherited, visibleDescriptions()));
                }
            }
            return observations;
        }
    }

    /** Surface 4 — an after-commit, asynchronous domain-event handler. */
    static class ProjectArchivedListener extends CompanyScopedWork {

        private final AtomicReference<Observation> observation = new AtomicReference<>();
        private volatile CountDownLatch handled = new CountDownLatch(1);

        ProjectArchivedListener(ProjectRepository projects, TransactionTemplate tx) {
            super(projects, tx);
        }

        void reset() {
            observation.set(null);
            handled = new CountDownLatch(1);
        }

        @Async
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(ProjectArchived event) {
            UUID inherited = CompanyContext.currentOrNull();
            try (var ignored = CompanyContext.openFor(event)) {
                observation.set(new Observation(inherited, visibleDescriptions()));
            } finally {
                handled.countDown();
            }
        }

        Observation awaitObservation(int timeoutSeconds) throws InterruptedException {
            if (!handled.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the async event handler never ran");
            }
            return observation.get();
        }
    }
}
