package com.tcms.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.provisioning.ExportManifest.BlobSummary;
import com.tcms.provisioning.ExportManifest.CompanyRef;
import com.tcms.provisioning.ExportManifest.ExcludedTable;
import com.tcms.provisioning.ExportManifest.MissingBlob;
import com.tcms.provisioning.ExportManifest.OrphanBlob;
import com.tcms.provisioning.ExportManifest.TableEntry;
import com.tcms.storage.AttachmentStore;
import com.tcms.storage.BlobInventory;
import com.tcms.storage.BlobRoot;
import com.tcms.storage.RawReportStore;
import com.tcms.storage.ReportStore;
import com.tcms.storage.StoredBlob;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walks one company's data into a single archive.
 *
 * <h2>The context is opened by the CALLER, and this is not a style preference</h2>
 *
 * <p>{@code CompanyAwareTransactionManager} issues
 * {@code set_config('app.current_company', …)} in {@code doBegin}, reading whatever
 * {@link CompanyContext} holds <em>at that moment</em>. A {@code CompanyContext.open()} written
 * inside this method would run after the transaction had already begun with no company — the GUC
 * stays unset for its whole life, every RLS policy evaluates {@code company_id = NULL}, and the walk
 * reads <strong>zero rows from every company-owned table without raising anything</strong>. The
 * archive opens, parses, and is empty. That is the defect this whole class is shaped around, and
 * {@code ExportCompanyTest.theExportRefusesWithNoCompanyContext} is what keeps it fixed.
 *
 * <h2>One transaction, REPEATABLE READ</h2>
 *
 * <p>Not one transaction per table, and not the default READ COMMITTED, which takes a new snapshot
 * per statement. A run finishing partway through a ~78-table walk under READ COMMITTED yields a
 * {@code step_result} whose {@code test_execution} is not in the archive — a referential break
 * discovered months later by whoever imports it. One snapshot is the reason the walk is one method.
 *
 * <h2>Six checks, because a short archive has no symptom</h2>
 *
 * <p>There is no in-band ground truth for an RLS-protected table: the counterfactual count is itself
 * an RLS-filtered query, so with the context lost it also answers zero. Hence S1 (the context is
 * open and is this company), S2 (the GUC is set at the first statement <em>and still set at the
 * last</em>, which is what a walk spanning two transactions fails), S3 (five vocabularies every
 * provisioned company has), S4 (per-table counts and digests in the manifest), and — in the suite
 * only — S5 a superuser ground-truth count and S6 the refusal outside a context.
 */
@Service
public class ExportCompanyService {

    /** The archive's own shape. A future importer refuses a version it does not know. */
    private static final int FORMAT_VERSION = 1;

    /**
     * Non-empty for every provisioned company — {@code CompanyProvisioningServiceTest}'s
     * completeness claim, reused as a liveness probe. A zero in any of the five means either the
     * company context is not open or this company was never fully provisioned, and refusing both is
     * correct: they are indistinguishable from inside the walk and both produce a short archive.
     */
    private static final List<String> SENTINEL_TABLES = List.of(
            "case_status", "classification_dimension", "company_membership",
            "key_sequence", "role_assignment");

    private final EntityManager entityManager;
    private final AttachmentStore attachments;
    private final ReportStore reports;
    private final RawReportStore rawReports;
    private final BlobInventory inventory;
    private final ObjectMapper json;
    private final Clock clock;

    ExportCompanyService(
            EntityManager entityManager,
            AttachmentStore attachments,
            ReportStore reports,
            RawReportStore rawReports,
            BlobInventory inventory,
            ObjectMapper json,
            Clock clock) {
        this.entityManager = entityManager;
        this.attachments = attachments;
        this.reports = reports;
        this.rawReports = rawReports;
        this.inventory = inventory;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Writes {@code company}'s entire dataset to {@code out}.
     *
     * <p>Written to {@code <out>.partial} and {@link StandardCopyOption#ATOMIC_MOVE}d onto
     * {@code out} as the last act, so <strong>a file at {@code out} is a file whose walk
     * finished</strong>. Every failure path deletes the partial: a half-written archive must not
     * survive under any name an operator could mistake for a good one.
     *
     * @throws IllegalStateException when no company context is open, when it names a different
     *     company, when the GUC disagrees with it, when a sentinel table is empty, or when the
     *     catalogue holds a table nothing classifies. Nothing is left at {@code out} in any of them
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportCompanyResult export(Company company, Path out) {
        UUID companyId = company.getId();

        // S1 — before anything is opened on disk, so a refusal leaves nothing behind.
        UUID active = CompanyContext.require();
        if (!active.equals(companyId)) {
            throw new IllegalStateException(
                    "The active company context is " + active + " but the export was asked for "
                            + companyId + ". Refusing: the walk would read the WRONG company's rows "
                            + "into an archive labelled with this one's slug.");
        }

        Session session = entityManager.unwrap(Session.class);
        return session.doReturningWork(connection -> run(connection, company, out));
    }

    private ExportCompanyResult run(Connection connection, Company company, Path out)
            throws SQLException {
        UUID companyId = company.getId();
        requireGucMatches(connection, companyId, "at the start of the walk");

        ExportTables tables = ExportTables.classify(connection);
        Map<String, List<String>> primaryKeys = ExportTables.primaryKeyColumns(connection);
        Map<String, Long> sentinels = countSentinels(connection, companyId);

        Path partial = out.resolveSibling(out.getFileName() + ".partial");
        Instant exportedAt = clock.instant();
        try {
            if (partial.getParent() != null) {
                Files.createDirectories(partial.getParent());
            }
            List<TableEntry> entries = new ArrayList<>();
            Map<String, Long> rowCounts = new LinkedHashMap<>();
            List<BlobRef> refs;
            List<MissingBlob> missing = new ArrayList<>();
            List<OrphanBlob> orphans;
            Map<String, BlobSummary> blobs;
            int blobCount;

            try (OutputStream file = Files.newOutputStream(partial);
                 ZipOutputStream zip = new ZipOutputStream(file)) {

                for (String table : tables.walked()) {
                    TableEntry entry = writeTable(
                            zip, connection, table, companyId, primaryKeys, exportedAt);
                    entries.add(entry);
                    rowCounts.put(table, entry.rows());
                }

                // Harvested with their own statements rather than by re-parsing every exported row.
                // Same transaction and therefore — under REPEATABLE READ — the same snapshot as the
                // dumps above, which is the property that matters: the blob set and the row set
                // describe one instant, so `attachment.size_bytes` in the archive agrees with the
                // file beside it.
                refs = harvestBlobKeys(connection, companyId);
                blobs = writeBlobs(zip, refs, missing, exportedAt);
                blobCount = blobs.values().stream().mapToInt(BlobSummary::files).sum();
                orphans = findOrphans(companyId, refs);

                // S2b — a walk that spanned two transactions has lost SET LOCAL, so this comes back
                // NULL. The single check that catches the exact defect this class is shaped around.
                requireGucMatches(connection, companyId, "at the end of the walk");

                writeEntry(zip, "README.txt", readme(company).getBytes(StandardCharsets.UTF_8),
                        exportedAt);
                ExportManifest manifest = new ExportManifest(
                        FORMAT_VERSION,
                        DateTimeFormatter.ISO_INSTANT.format(
                                exportedAt.truncatedTo(ChronoUnit.SECONDS)),
                        schemaVersion(connection),
                        new CompanyRef(companyId.toString(), company.getSlug(),
                                company.getDisplayName(), company.getStatus().name()),
                        entries,
                        excludedTables(),
                        blobs,
                        List.copyOf(missing),
                        orphans,
                        sentinels,
                        // Settled by grep, not guessed: nothing in this build writes it.
                        "no writer in this build",
                        missing.isEmpty());
                // LAST, so even a killed process cannot leave a manifest-bearing archive.
                writeEntry(zip, "manifest.json",
                        json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest),
                        exportedAt);
            }

            Files.move(partial, out,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new ExportCompanyResult(companyId, company.getSlug(), out, Files.size(out),
                    rowCounts, blobCount, missing, orphans);
        } catch (IOException failure) {
            deleteQuietly(partial);
            throw new UncheckedIOException(
                    "Could not write the export archive for '" + company.getSlug() + "'", failure);
        } catch (RuntimeException | SQLException failure) {
            deleteQuietly(partial);
            throw failure;
        }
    }

    /**
     * Reads {@code app.current_company} straight from the session.
     *
     * <p>Asked twice — first and last statement of the walk. {@code SET LOCAL} does not survive a
     * transaction boundary, so a walk that somehow spanned two comes back NULL on the second ask
     * even though every statement in between "worked".
     */
    private void requireGucMatches(Connection connection, UUID companyId, String when)
            throws SQLException {
        String actual;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT current_setting('app.current_company', true)")) {
            actual = rs.next() ? rs.getString(1) : null;
        }
        if (actual == null || actual.isBlank() || !companyId.toString().equals(actual)) {
            throw new IllegalStateException(
                    "app.current_company is '" + actual + "' " + when + ", not " + companyId
                            + ". Every row-level-security policy therefore evaluates "
                            + "company_id = NULL, which is never true — the walk would read zero "
                            + "rows from every company-owned table and produce an archive that "
                            + "opens, parses and is empty. Refusing.");
        }
    }

    private Map<String, Long> countSentinels(Connection connection, UUID companyId)
            throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : SENTINEL_TABLES) {
            long count;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM " + quoted(table) + " WHERE company_id = ?")) {
                statement.setObject(1, companyId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    count = rs.getLong(1);
                }
            }
            if (count == 0) {
                throw new IllegalStateException(
                        "The sentinel table '" + table + "' is empty for company " + companyId
                                + ". Every provisioned company has rows in all of "
                                + SENTINEL_TABLES + ", so this means one of two things and both "
                                + "produce a short archive: either the company context is not open "
                                + "(and every policed read is silently returning zero rows), or "
                                + "this company was never fully provisioned. Refusing rather than "
                                + "writing an archive that looks complete.");
            }
            counts.put(table, count);
        }
        return counts;
    }

    /**
     * One {@code tables/&lt;name&gt;.jsonl} entry, streamed.
     *
     * <p>{@code to_jsonb(t) - ?} means <strong>no per-column Java mapping</strong>, so a column a
     * later migration adds is exported the day it lands, and redaction is one bind parameter that
     * cannot be forgotten per column. JSONL rather than CSV because NULL and empty string are
     * different values and CSV cannot say so, and because four columns in this schema are JSONB.
     */
    private TableEntry writeTable(
            ZipOutputStream zip,
            Connection connection,
            String table,
            UUID companyId,
            Map<String, List<String>> primaryKeys,
            Instant exportedAt) throws SQLException, IOException {

        Set<String> dropped = ExportRedactions.droppedFrom(table);
        String order = orderBy(primaryKeys.get(table));
        String sql;
        if (ExportTables.APP_USER_TABLE.equals(table)) {
            sql = ExportTables.APP_USER_SQL;
        } else if (ExportTables.COMPANY_TABLE.equals(table)) {
            sql = "SELECT (to_jsonb(t) - ?::text[])::text AS row FROM company t WHERE t.id = ? " + order;
        } else {
            // The explicit predicate beside the policy is belt-and-braces at the one moment we most
            // want it, and it is also what makes this one statement form correct for the TARGET
            // tables that carry company_id and no policy at all.
            sql = "SELECT (to_jsonb(t) - ?::text[])::text AS row FROM " + quoted(table)
                    + " t WHERE t.company_id = ? " + order;
        }

        MessageDigest digest = sha256();
        long rows = 0;
        zip.putNextEntry(entry("tables/" + table + ".jsonl", exportedAt));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1,
                    connection.createArrayOf("text", dropped.stream().sorted().toArray()));
            statement.setObject(2, companyId);
            statement.setFetchSize(500);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    byte[] line = (rs.getString(1) + "\n").getBytes(StandardCharsets.UTF_8);
                    digest.update(line);
                    zip.write(line);
                    rows++;
                }
            }
        }
        zip.closeEntry();
        return new TableEntry(table, rows, HexFormat.of().formatHex(digest.digest()),
                dropped.stream().sorted().toList());
    }

    /**
     * The {@code ORDER BY} for a table, from its primary key.
     *
     * <p>Determinism is not decoration: two exports of an unchanged company must differ only in
     * their timestamps, because a diff is how somebody proves an export is complete.
     */
    private static String orderBy(List<String> keyColumns) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            // No primary key. Order by the projected jsonb itself: jsonb has a total btree order,
            // so this is still deterministic, which is what a diffable archive needs.
            return "ORDER BY 1";
        }
        return "ORDER BY " + String.join(", ",
                keyColumns.stream().map(column -> "t." + quoted(column)).toList());
    }

    /**
     * The three blob-bearing columns, read as keys.
     *
     * <p><strong>The rows are the authority, not the directory.</strong> Listing
     * {@code <root>/<companyId>/} and archiving whatever is there sweeps orphans left by failed
     * writes, depends on a key layout that is {@code BlobUpload}'s business and not this command's,
     * and would silently start exporting somebody else's bytes the day that layout changed.
     */
    private List<BlobRef> harvestBlobKeys(Connection connection, UUID companyId)
            throws SQLException {
        List<BlobRef> refs = new ArrayList<>();
        // attachment covers all six owner kinds (CASE, STEP, EXECUTION, STEP_RESULT, DOC_PAGE,
        // ARTIFACT) because it is the polymorphic parent — one table, one column.
        collectKeys(connection, refs, BlobRoot.EVIDENCE, "attachment", "storage_key", companyId);
        collectKeys(connection, refs, BlobRoot.REPORTS, "report_artifact", "storage_key", companyId);
        // raw_storage_key is NULLABLE (V1:2918). A null is not a missing blob, and the IS NOT NULL
        // below is what keeps it from being reported as one.
        collectKeys(connection, refs, BlobRoot.RAW_REPORTS, "ingestion_job", "raw_storage_key",
                companyId);
        return refs;
    }

    private void collectKeys(
            Connection connection,
            List<BlobRef> into,
            BlobRoot root,
            String table,
            String column,
            UUID companyId) throws SQLException {
        String sql = "SELECT t.id::text, t." + quoted(column) + " FROM " + quoted(table) + " t "
                + "WHERE t.company_id = ? AND t." + quoted(column) + " IS NOT NULL ORDER BY t.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, companyId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    into.add(new BlobRef(root, table, rs.getString(1), rs.getString(2)));
                }
            }
        }
    }

    /**
     * Streams every named blob into the archive under its storage key, verbatim.
     *
     * <p>The keys are already {@code <companyId>/<ownerId>/<uuid>} triples, so they are unique,
     * ASCII and traversal-free, and a reader maps a row to its bytes with no manifest lookup.
     * {@code attachment.filename} is client-supplied, duplicated across executions and one of the
     * classic zip traversal sinks — it stays a field in the table dump, and {@code README.txt} says
     * a reader who wants original names joins on the key.
     */
    private Map<String, BlobSummary> writeBlobs(
            ZipOutputStream zip,
            List<BlobRef> refs,
            List<MissingBlob> missing,
            Instant exportedAt) throws IOException {

        Map<BlobRoot, int[]> files = new EnumMap<>(BlobRoot.class);
        Map<BlobRoot, long[]> bytes = new EnumMap<>(BlobRoot.class);
        for (BlobRoot root : BlobRoot.values()) {
            files.put(root, new int[1]);
            bytes.put(root, new long[1]);
        }

        Set<String> written = new LinkedHashSet<>();
        for (BlobRef ref : refs) {
            if (!written.add(ref.root().label() + "/" + ref.key())) {
                continue;   // two rows may legitimately name one key; the zip may not hold it twice
            }
            Optional<StoredBlob> found = open(ref.root(), ref.key());
            if (found.isEmpty()) {
                missing.add(new MissingBlob(ref.table(), ref.rowId(), ref.key()));
                continue;
            }
            try (StoredBlob blob = found.get()) {
                zip.putNextEntry(entry("blobs/" + ref.root().label() + "/" + ref.key(), exportedAt));
                // Streamed: a company with 4 GB of evidence must not need 4 GB of heap.
                long copied = blob.stream().transferTo(zip);
                zip.closeEntry();
                files.get(ref.root())[0]++;
                bytes.get(ref.root())[0] += copied;
            }
        }

        Map<String, BlobSummary> summary = new LinkedHashMap<>();
        for (BlobRoot root : BlobRoot.values()) {
            summary.put(root.label(), new BlobSummary(files.get(root)[0], bytes.get(root)[0]));
        }
        return summary;
    }

    /** Files under this company's prefix that no exported row names. Counted, never archived. */
    private List<OrphanBlob> findOrphans(UUID companyId, List<BlobRef> refs) {
        List<OrphanBlob> orphans = new ArrayList<>();
        for (BlobRoot root : BlobRoot.values()) {
            Set<String> named = new LinkedHashSet<>(refs.stream()
                    .filter(ref -> ref.root() == root)
                    .map(BlobRef::key)
                    .toList());
            for (String key : inventory.keysUnder(root, companyId.toString())) {
                if (!named.contains(key)) {
                    orphans.add(new OrphanBlob(root.label(), key));
                }
            }
        }
        return orphans;
    }

    /**
     * Opens a key on the named root.
     *
     * <p>A switch rather than a map: {@link AttachmentStore}, {@link ReportStore} and
     * {@link RawReportStore} are deliberately three unrelated types ({@code storage/package-info})
     * so that handing evidence to the report store is a compile error, and a map able to hold all
     * three would have to erase them to {@code Object}. The switch is exhaustive over the enum, so a
     * fourth root is a compile error here too.
     */
    private Optional<StoredBlob> open(BlobRoot root, String key) {
        return switch (root) {
            case EVIDENCE -> attachments.open(key);
            case REPORTS -> reports.open(key);
            case RAW_REPORTS -> rawReports.open(key);
        };
    }

    private static List<ExcludedTable> excludedTables() {
        return ExportTables.EXCLUDED.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ExcludedTable(e.getKey(), e.getValue().name()))
                .toList();
    }

    private static String schemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success "
                             + "ORDER BY installed_rank DESC LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private String readme(Company company) {
        return """
                TCMS company export — %s (%s)

                tables/   one JSON-lines file per exported table, one row per line, produced by
                          Postgres itself with to_jsonb(). NULL and empty string are distinct, and
                          the JSONB columns nest.
                blobs/    the files those rows name, at their storage key verbatim, under a prefix
                          per storage root. An attachment's ORIGINAL FILENAME is not a path here —
                          it is client-supplied and duplicated across executions. Join
                          tables/attachment.jsonl on storage_key to recover it.
                manifest.json
                          row counts, a SHA-256 per table file, every redacted column, every table
                          left out with the reason, and "complete": whether every row's bytes were
                          found.

                Deliberately absent: password hashes, API-token digests, invitation digests,
                notification-channel secrets, and identity-provider client secrets and bind
                passwords. manifest.json lists each one.

                This is an export, not a restore format. Check manifest.schemaVersion against the
                schema you intend to read it into before believing any of it.
                """.formatted(company.getDisplayName(), company.getSlug());
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content, Instant at)
            throws IOException {
        zip.putNextEntry(entry(name, at));
        zip.write(content);
        zip.closeEntry();
    }

    /**
     * A zip entry stamped with the export instant rather than "now".
     *
     * <p>Every entry carries the same time, so two exports of an unchanged company differ only in
     * that one timestamp — and a diff is how somebody proves an export is complete.
     */
    private static ZipEntry entry(String name, Instant at) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(at.toEpochMilli());
        return entry;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /**
     * Quotes an identifier that came out of the catalogue, and refuses one that could not have.
     *
     * <p>Every name reaching this method was read from {@code pg_tables} or
     * {@code information_schema}, so the refusal is unreachable today. It is here so that stays true
     * of a caller written later, in the one place this class interpolates SQL.
     */
    private static String quoted(String identifier) {
        if (!identifier.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Refusing to interpolate '" + identifier + "' into SQL: it is not an "
                            + "identifier this schema could have produced.");
        }
        return "\"" + identifier + "\"";
    }

    private static void deleteQuietly(Path partial) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException ignored) {
            // Nothing useful to do: the caller is already failing, and a .partial left on disk is a
            // diagnosis rather than a deliverable. It must simply never become <out>.
        }
    }

    /** One row's claim on some bytes: which root, which table said so, and which row. */
    private record BlobRef(BlobRoot root, String table, String rowId, String key) {
    }
}
