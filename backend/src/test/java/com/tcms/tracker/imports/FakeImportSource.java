package com.tcms.tracker.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import org.springframework.core.Ordered;

/**
 * A source system under the test's control — and <strong>the only way the failures this module
 * exists to handle can be driven at all</strong>.
 *
 * <h2>It lands before the importer it will be used to break</h2>
 *
 * <p><em>"Build the fault switch before the feature it proves."</em> A source that stops answering
 * halfway through an import, or refuses the credential on the second call, cannot be triggered by
 * using the product — the other end is somebody else's Jira. So this arrives in the same task as
 * the read client rather than in the task that writes the importer, and {@link #failAfter} is what
 * T4's mid-import failure test drives.
 *
 * <p>The frontend's half of the same switch is {@code tcmsFaults.importing()} in
 * {@code lib/devFaults.ts}, added in T7.
 *
 * <h2>In the test tree, unlike {@code FakeTrackerAdapter}</h2>
 *
 * <p>That one is production code behind {@code @Profile("tracker-fake")} because the SPA drives it
 * against a running instance. This one is only ever driven from Java, so it is not on the
 * production classpath at all — the same argument that profile makes, taken as far as it goes.
 *
 * <h2>It outranks the real sources, deliberately</h2>
 *
 * <p>{@link ImportSources} picks the first implementation claiming a provider, and List injection
 * respects {@link Ordered}. A fake that lost that race would leave the test dialling the public
 * internet and reading the failure as a parse error — which is exactly what happened to the tracker
 * transport suite one module over, and is why {@link ImportHttp} has a second constructor.
 */
public final class FakeImportSource implements ImportSource, Ordered {

    private final List<SourceProject> projects = new ArrayList<>();
    private final Map<String, SourceProjectSnapshot> snapshots = new LinkedHashMap<>();
    private final List<Integer> progress = new ArrayList<>();
    private final Set<String> extraStatuses = new LinkedHashSet<>();

    private int failAfter = -1;
    private int rejectingStatus;
    private int pageSize = 50;
    private int snapshotCalls;

    /**
     * Back to answering nothing — <strong>called from every {@code @BeforeEach} that uses it</strong>.
     *
     * <p>The bean lives in a Spring context that is cached across test classes, so without this a
     * project one class registered is still visible to the next, and a {@code rejecting(401)} set
     * by one method refuses every method that runs after it. The failure would name neither class.
     */
    public FakeImportSource reset() {
        projects.clear();
        snapshots.clear();
        progress.clear();
        extraStatuses.clear();
        failAfter = -1;
        rejectingStatus = 0;
        pageSize = 50;
        snapshotCalls = 0;
        return this;
    }

    /** What {@code POST /imports/source-projects} will answer, and what the cap is checked against. */
    public FakeImportSource holding(SourceProject project, SourceProjectSnapshot snapshot) {
        projects.add(project);
        snapshots.put(project.key(), snapshot);
        return this;
    }

    /**
     * Throw once this many issues have been read, as a real source going away mid-page does.
     *
     * <p>Mid-<em>read</em> and not mid-write: the point of the failure is that it lands after the
     * expensive part and before anything is stored, which is the state D-b's two passes exist to
     * make safe.
     */
    public FakeImportSource failAfter(int issues) {
        this.failAfter = issues;
        return this;
    }

    /**
     * Refuse every call — 401, 403 or a connection that never answers.
     *
     * <p>All three arrive as one {@link ImportSourceUnavailableException}, because that is what the
     * port promises and what the screen has to render; the status is carried in the message so a
     * test can tell which was asked for.
     */
    public FakeImportSource rejecting(int status) {
        this.rejectingStatus = status;
        return this;
    }

    /**
     * A status the workflow defines that no issue is in — the case {@code POST /imports} refuses
     * with a 400 while the snapshot alone would never produce it.
     */
    public FakeImportSource alsoDefining(String... statusNames) {
        extraStatuses.addAll(List.of(statusNames));
        return this;
    }

    /** How many issues one page reports, so a test can assert progress was reported per page. */
    public FakeImportSource paging(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /** The running counts handed to {@code onProgress}, in order. */
    public List<Integer> progressReports() {
        return List.copyOf(progress);
    }

    /** How many whole reads were asked for — the preview's promise is exactly one. */
    public int snapshotCalls() {
        return snapshotCalls;
    }

    @Override
    public boolean supports(ImportProvider provider) {
        return true;
    }

    @Override
    public List<SourceProject> projects(ImportConnection connection) {
        refuseIfAsked();
        return List.copyOf(projects);
    }

    /**
     * The held snapshot's status names, plus anything {@link #alsoDefining} added.
     *
     * <p>Derived from the snapshot rather than stored beside it, so a test cannot set up a source
     * whose workflow and whose issues disagree by accident. {@link #alsoDefining} is the deliberate
     * disagreement — a status the workflow defines that no issue is currently in, which is the
     * ordinary state of a real Jira and the case the mapping refusal has to cover.
     */
    @Override
    public List<String> statusNames(ImportConnection connection, String projectKey) {
        refuseIfAsked();

        SourceProjectSnapshot held = snapshots.get(projectKey);
        if (held == null) {
            throw new ImportSourceUnavailableException(
                    "That project was not found, or this account cannot read it.");
        }
        Set<String> names = new LinkedHashSet<>();
        held.statuses().forEach(status -> names.add(status.name()));
        names.addAll(extraStatuses);
        return List.copyOf(names);
    }

    @Override
    public SourceProjectSnapshot snapshot(
            ImportConnection connection, String projectKey, IntConsumer onProgress) {

        refuseIfAsked();
        snapshotCalls++;

        SourceProjectSnapshot held = snapshots.get(projectKey);
        if (held == null) {
            throw new ImportSourceUnavailableException(
                    "That project was not found, or this account cannot read it.");
        }

        int read = 0;
        for (SourceIssue ignored : held.issues()) {
            read++;
            if (failAfter >= 0 && read > failAfter) {
                throw new ImportSourceUnavailableException(
                        "The source system stopped answering after " + failAfter + " issues.");
            }
            if (read % pageSize == 0) {
                progress.add(read);
                onProgress.accept(read);
            }
        }
        progress.add(read);
        onProgress.accept(read);
        return held;
    }

    private void refuseIfAsked() {
        if (rejectingStatus != 0) {
            throw new ImportSourceUnavailableException(
                    "The source system answered " + rejectingStatus + ".");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
