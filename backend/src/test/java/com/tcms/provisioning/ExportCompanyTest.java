package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.storage.AttachmentStore;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The export, driven against a real company with a second company alongside it.
 *
 * <p>Per the async-harness lesson (handoff §14) every test class shares one container and therefore
 * one database, so this class owns the {@code exp-} slug namespace and the {@code 7c} UUID prefix
 * (pre-assigned 2026-08-22, area E of the unplanned-gaps plan). It must not assume it is alone —
 * every ground-truth count below is filtered to one of its own companies.
 *
 * <p>Four companies, because three of the arms need a fixture the others must not see:
 * <strong>alpha</strong> is complete and is what most arms export; <strong>beta</strong> is the
 * paired fixture that must never appear in alpha's archive ({@code RlsIsolationTest}'s discipline);
 * <strong>gamma</strong> owns a row naming bytes that are not on disk; <strong>delta</strong> is a
 * bare {@code company} row inserted as superuser, i.e. a company that was never provisioned.
 */
@DisplayName("export-company — a complete archive, and nothing of anybody else's")
class ExportCompanyTest extends IntegrationTestBase {

    private static final UUID TOKEN_ALPHA = UUID.fromString("7c000000-0000-0000-0000-000000000201");
    private static final UUID CHANNEL_ALPHA = UUID.fromString("7c000000-0000-0000-0000-000000000301");
    private static final UUID EVIDENCE_ALPHA = UUID.fromString("7c000000-0000-0000-0000-000000000401");
    private static final UUID EVIDENCE_BETA = UUID.fromString("7c000000-0000-0000-0000-000000000402");
    private static final UUID EVIDENCE_GAMMA = UUID.fromString("7c000000-0000-0000-0000-000000000403");
    private static final UUID COMPANY_DELTA = UUID.fromString("7c000000-0000-0000-0000-000000000004");

    private static final byte[] EVIDENCE_BYTES =
            "the screenshot behind a failed verdict\n".getBytes(StandardCharsets.UTF_8);

    private static boolean seeded;
    private static UUID alphaId;
    private static UUID betaId;
    private static UUID gammaId;
    private static String alphaEvidenceKey;
    private static String betaEvidenceKey;
    private static String gammaMissingKey;

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ExportCompanyService exportService;

    @Autowired
    private AttachmentStore attachments;

    @TempDir
    private Path outDir;

    @BeforeEach
    void seedOnce() throws SQLException {
        if (seeded) {
            return;
        }
        alphaId = provision("exp-alpha", "EXPALPHA");
        betaId = provision("exp-beta", "EXPBETA");
        gammaId = provision("exp-gamma", "EXPGAMMA");

        alphaEvidenceKey = seedEvidence(alphaId, EVIDENCE_ALPHA, true);
        betaEvidenceKey = seedEvidence(betaId, EVIDENCE_BETA, true);
        // A row whose bytes were never written: the archive must say so rather than skip it.
        gammaMissingKey = seedEvidence(gammaId, EVIDENCE_GAMMA, false);

        // The provisioned admin is INVITED, so they have no password yet — and the redaction arm
        // needs a digest that actually exists to search the archive for.
        seedPasswordHash(alphaId);
        seedApiToken(alphaId, TOKEN_ALPHA);
        seedNotificationChannel(alphaId, CHANNEL_ALPHA);
        seedBareCompany(COMPANY_DELTA);
        seeded = true;
    }

    // ---------------------------------------------------------------- the rows

    @Test
    @DisplayName("every walked table matches a direct count taken as superuser")
    void everyWalkedTableMatchesADirectCountTakenAsSuperuser() throws Exception {
        // S5, and the arm that matters most. There is no in-band ground truth for an RLS-protected
        // table — the counterfactual count is itself RLS-filtered, so with the context lost it also
        // answers zero. The superuser connection is the only thing in this suite that bypasses RLS,
        // which is what makes this the check that would catch a silently short archive.
        Map<String, byte[]> archive = export(alphaId, "alpha.zip");

        List<String> scoped;
        try (Connection connection = superuserConnection()) {
            scoped = ExportTables.classify(connection).companyScoped();

            Map<String, String> mismatches = new LinkedHashMap<>();
            for (String table : scoped) {
                long truth = count(connection, table, alphaId);
                long archived = lines(archive.get("tables/" + table + ".jsonl"));
                if (truth != archived) {
                    mismatches.put(table, "archived " + archived + ", actually " + truth);
                }
            }
            // Every mismatch, not the first: one short table and forty short tables are different
            // diagnoses, and the forty-table shape is the lost-context defect.
            assertThat(mismatches)
                    .as("Tables the export under-read. Forty of them at zero is a lost "
                            + "CompanyContext; one of them is a bug in that table's statement.")
                    .isEmpty();
        }
        assertThat(scoped)
                .as("fixture precondition: tables must have been walked, or this asserts nothing")
                .isNotEmpty();
    }

    @Test
    @DisplayName("company B is absent from every line of the archive")
    void companyBIsAbsentFromEveryLine() throws Exception {
        // Over the BYTES, not over one table: the interesting leak is a column nobody thought to
        // check, so the assertion has to be one no future column can slip past.
        Map<String, byte[]> archive = export(alphaId, "alpha-isolation.zip");

        List<String> needles = List.of(betaId.toString(), "exp-beta", "EXPBETA", betaEvidenceKey);
        Map<String, String> leaks = new LinkedHashMap<>();
        archive.forEach((entry, content) -> {
            String text = new String(content, StandardCharsets.UTF_8);
            needles.stream().filter(text::contains)
                    .forEach(needle -> leaks.put(entry, needle));
        });

        assertThat(leaks).as("Archive entries carrying another company's data").isEmpty();
    }

    @Test
    @DisplayName("no redacted value appears anywhere in the archive")
    void noRedactedValueAppearsAnywhereInTheArchive() throws Exception {
        // Value-based, not key-based: a redaction that renamed the JSON key and kept the value
        // would pass a key-based assertion and lose nothing an attacker cares about.
        Map<String, byte[]> archive = export(alphaId, "alpha-redaction.zip");

        List<String> secrets = new ArrayList<>();
        try (Connection connection = superuserConnection()) {
            secrets.add(scalar(connection,
                    "SELECT u.password_hash FROM app_user u "
                            + "JOIN company_membership m ON m.user_id = u.id WHERE m.company_id = ?",
                    alphaId));
            secrets.add(scalar(connection,
                    "SELECT token_hash FROM api_token WHERE id = ?", TOKEN_ALPHA));
            secrets.add(scalar(connection,
                    "SELECT token_hash FROM membership_invitation WHERE company_id = ?", alphaId));
            secrets.add(scalar(connection,
                    "SELECT encode(secret, 'hex') FROM notification_channel WHERE id = ?",
                    CHANNEL_ALPHA));
        }
        assertThat(secrets)
                .as("fixture precondition: the four secrets must exist, or this asserts nothing")
                .doesNotContainNull()
                .allSatisfy(secret -> assertThat(secret).isNotBlank());

        Map<String, String> leaks = new LinkedHashMap<>();
        archive.forEach((entry, content) -> {
            String text = new String(content, StandardCharsets.UTF_8);
            secrets.stream().filter(text::contains).forEach(secret -> leaks.put(entry, secret));
        });
        assertThat(leaks).as("Archive entries carrying a credential or a credential digest").isEmpty();
    }

    @Test
    @DisplayName("the secret hint survives and the secret does not")
    void theSecretHintSurvivesAndTheSecretDoesNot() throws Exception {
        // Both halves of the notification_channel decision on one channel, so a later "redact the
        // whole table" simplification reddens instead of quietly losing the hint — which is the
        // only part of the credential that was ever designed to leave that table.
        Map<String, byte[]> archive = export(alphaId, "alpha-hint.zip");
        String channels = text(archive, "tables/notification_channel.jsonl");

        assertThat(channels).contains("\"secret_hint\"").contains("xoxb-…9f2");
        // `"secret_hint"` contains `"secret` but not `"secret"` — the closing quote is what
        // makes this precise rather than a check that would fail on the hint too.
        assertThat(channels).doesNotContain("\"secret\"");
    }

    @Test
    @DisplayName("the manifest names every redaction, every exclusion and the Flyway head")
    void theManifestDescribesTheArchive() throws Exception {
        // S4. Written LAST, so a killed process cannot leave a manifest-bearing archive — which is
        // what makes its presence the thing a reader may trust.
        Map<String, byte[]> archive = export(alphaId, "alpha-manifest.zip");
        String manifest = text(archive, "manifest.json");

        assertThat(manifest).contains("\"formatVersion\" : 1");
        assertThat(manifest).contains("\"complete\" : true");
        assertThat(manifest).contains("\"slug\" : \"exp-alpha\"");
        assertThat(manifest).contains("\"token_hash\"");            // api_token's redaction, named
        assertThat(manifest).contains("\"password_hash\"");         // app_user's, named
        assertThat(manifest).contains("CREDENTIAL_ONLY");           // an exclusion reason
        assertThat(manifest).contains("\"logoBlob\" : \"no writer in this build\"");
        assertThat(manifest)
                .as("the Flyway head is the one fact a future importer must check first")
                .contains("\"schemaVersion\"");
        // The sentinels are recorded, not merely checked: an operator reading a months-old archive
        // can see that the vocabularies were there when it was taken.
        assertThat(manifest).contains("\"company_membership\"").contains("\"key_sequence\"");
    }

    // ---------------------------------------------------------------- the refusals

    @Test
    @DisplayName("the export refuses with no company context, and leaves no file")
    void theExportRefusesWithNoCompanyContext() {
        // S6, and the regression guard for the one defect the whole area is shaped around: a walk
        // outside the context returns zero rows from every RLS-protected table and produces an
        // archive that opens fine and is empty. Moving CompanyContext.open into the service is what
        // this arm exists to redden.
        Company alpha = companies.findById(alphaId).orElseThrow();
        Path out = outDir.resolve("never-written.zip");

        try (var ignored = CompanyContext.clear()) {
            assertThatThrownBy(() -> exportService.export(alpha, out))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No company context is active");
        }

        assertThat(out).doesNotExist();
        assertThat(out.resolveSibling("never-written.zip.partial")).doesNotExist();
    }

    @Test
    @DisplayName("exporting one company under another's context refuses")
    void theExportRefusesWhenTheContextNamesAnotherCompany() {
        // S1's second half. A context that is open but WRONG is the failure a require() alone does
        // not catch: the walk would read beta's rows into an archive labelled alpha.
        Company alpha = companies.findById(alphaId).orElseThrow();
        Path out = outDir.resolve("wrong-context.zip");

        try (var ignored = CompanyContext.open(betaId)) {
            assertThatThrownBy(() -> exportService.export(alpha, out))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("was asked for");
        }
        assertThat(out).doesNotExist();
    }

    @Test
    @DisplayName("a company with no seeded vocabularies aborts rather than exporting")
    void aCompanyWithNoSeededVocabulariesAbortsRatherThanExporting() {
        // S3 from the other side. A half-provisioned company and a lost context look identical from
        // inside the walk — both read zero from every policed table — and refusing both is correct.
        Company delta = companies.findById(COMPANY_DELTA).orElseThrow();
        Path out = outDir.resolve("delta.zip");

        try (var ignored = CompanyContext.open(COMPANY_DELTA)) {
            assertThatThrownBy(() -> exportService.export(delta, out))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sentinel table");
        }
        assertThat(out).doesNotExist();
        assertThat(out.resolveSibling("delta.zip.partial")).doesNotExist();
    }

    @Test
    @DisplayName("every sentinel table is checked, not just the first one")
    void everySentinelTableIsNamedInTheRefusal() {
        // ⚠ Deliberately per-table rather than "it aborted": a refusal that only ever names
        // company_membership would pass an is-it-loud assertion while four of the five sentinels
        // were decorative. This is CE-M3's answer in the mutation queue.
        Company delta = companies.findById(COMPANY_DELTA).orElseThrow();
        try (var ignored = CompanyContext.open(COMPANY_DELTA)) {
            assertThatThrownBy(() ->
                    exportService.export(delta, outDir.resolve("delta-sentinels.zip")))
                    .hasMessageContainingAll("case_status", "classification_dimension",
                            "company_membership", "key_sequence", "role_assignment");
        }
    }

    // ---------------------------------------------------------------- the bytes

    @Test
    @DisplayName("a seeded evidence file's bytes are in the archive")
    void aSeededEvidenceFilesBytesAreInTheArchive() throws Exception {
        // Equality, not existence and not size: only equality catches a stream copied from the
        // wrong key, which is the failure a three-port storage module exists to make unlikely and
        // an export walking three roots makes possible again.
        Map<String, byte[]> archive = export(alphaId, "alpha-blobs.zip");

        assertThat(archive).containsKey("blobs/evidence/" + alphaEvidenceKey);
        assertThat(archive.get("blobs/evidence/" + alphaEvidenceKey)).isEqualTo(EVIDENCE_BYTES);

        String manifest = text(archive, "manifest.json");
        assertThat(manifest).contains("\"evidence\"");
        assertThat(manifest).contains("\"missingBlobs\" : [ ]");
    }

    @Test
    @DisplayName("a row naming bytes that are gone is reported rather than skipped")
    void aRowNamingBytesThatAreGoneIsReportedRatherThanSkipped() throws Exception {
        // The archive is still the best available copy; what must not happen is a clean exit. Both
        // halves are asserted — the manifest a reader unzips, and the result the exit code is
        // derived from — because a script may read either one.
        Path out = outDir.resolve("gamma.zip");
        ExportCompanyResult result;
        Company gamma = companies.findById(gammaId).orElseThrow();
        try (var ignored = CompanyContext.open(gammaId)) {
            result = exportService.export(gamma, out);
        }

        assertThat(out).exists();
        assertThat(result.wasComplete())
                .as("a missing blob is exit 3, not exit 0 — an offboarding script must be able to "
                        + "tell a complete copy from the best available one")
                .isFalse();
        assertThat(result.missing()).singleElement().satisfies(missing -> {
            assertThat(missing.table()).isEqualTo("attachment");
            assertThat(missing.id()).isEqualTo(EVIDENCE_GAMMA.toString());
            assertThat(missing.storageKey()).isEqualTo(gammaMissingKey);
        });

        String manifest = text(read(out), "manifest.json");
        assertThat(manifest).contains("\"complete\" : false");
        assertThat(manifest).contains(EVIDENCE_GAMMA.toString());
    }

    @Test
    @DisplayName("company B's bytes are not in the archive, by entry name")
    void companyBsBytesAreNotInTheArchive() throws Exception {
        // On the ENTRY NAMES, deliberately. Beta's key is under beta's uuid prefix, so a harvest
        // rewritten as a filesystem sweep of <root>/<companyId>/ would still get this right by
        // accident — the names are what fails when the rows stop being the authority.
        Map<String, byte[]> archive = export(alphaId, "alpha-foreign-bytes.zip");

        assertThat(archive.keySet())
                .noneMatch(entry -> entry.contains(betaId.toString()))
                .contains("blobs/evidence/" + alphaEvidenceKey);
    }

    @Test
    @DisplayName("the archive is deterministic: two runs produce the same entries and digests")
    void twoExportsOfAnUnchangedCompanyAgree() throws Exception {
        // Diffability is what lets somebody PROVE an export is complete, so entry order and the
        // per-table digests are asserted rather than hoped for.
        Map<String, byte[]> first = export(alphaId, "alpha-1.zip");
        Map<String, byte[]> second = export(alphaId, "alpha-2.zip");

        assertThat(second.keySet()).containsExactlyElementsOf(first.keySet());
        first.forEach((entry, content) -> {
            if (!"manifest.json".equals(entry)) {
                assertThat(second.get(entry)).as(entry).isEqualTo(content);
            }
        });
        // The manifest differs only in exportedAt, so the table digests inside it must agree.
        assertThat(digestsIn(text(second, "manifest.json")))
                .isEqualTo(digestsIn(text(first, "manifest.json")));
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, byte[]> export(UUID companyId, String fileName) throws IOException {
        Company company = companies.findById(companyId).orElseThrow();
        Path out = outDir.resolve(fileName);
        try (var ignored = CompanyContext.open(companyId)) {
            // OUTSIDE the transactional method, which is the whole of this area's decision 1.
            exportService.export(company, out);
        }
        return read(out);
    }

    private static Map<String, byte[]> read(Path archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                zip.transferTo(content);
                entries.put(entry.getName(), content.toByteArray());
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> archive, String entry) {
        byte[] content = archive.get(entry);
        assertThat(content).as("archive entry %s", entry).isNotNull();
        return new String(content, StandardCharsets.UTF_8);
    }

    private static long lines(byte[] content) {
        assertThat(content).isNotNull();
        if (content.length == 0) {
            return 0;
        }
        return new String(content, StandardCharsets.UTF_8).lines().count();
    }

    private static List<String> digestsIn(String manifest) {
        return manifest.lines()
                .filter(line -> line.contains("\"sha256\""))
                .map(String::trim)
                .toList();
    }

    private static long count(Connection connection, String table, UUID companyId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM \"" + table + "\" WHERE company_id = ?")) {
            statement.setObject(1, companyId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String scalar(Connection connection, String sql, UUID parameter)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private UUID provision(String slug, String projectKey) {
        String displayName = slug.toUpperCase(java.util.Locale.ROOT) + " Ltd";
        return provisioning.provision(ProvisionCompanyRequest.withFirstProject(
                        slug, displayName, "ops@" + slug + ".test", "Ops Person",
                        projectKey, displayName + " Product"))
                .companyId();
    }

    /**
     * An attachment row and, optionally, its bytes.
     *
     * <p>Seeded through {@link AttachmentStore} and a direct insert rather than through
     * {@code EvidenceService}, because that path needs a plan, a run, a case, a version and an
     * execution before it will accept a byte — and what this class is testing is the export's
     * harvest, not evidence upload. The key is built the way {@code BlobUpload.key} builds one
     * ({@code companyId/ownerId/uuid}), which is the property the archive's entry names rest on.
     */
    private String seedEvidence(UUID companyId, UUID attachmentId, boolean writeBytes)
            throws SQLException {
        UUID ownerId = UUID.randomUUID();
        String key = companyId + "/" + ownerId + "/" + UUID.randomUUID();
        if (writeBytes) {
            attachments.put(key, EVIDENCE_BYTES);
        }
        try (Connection connection = appConnection(companyId);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO attachment
                       (id, company_id, owner_type, owner_id, storage_key, filename,
                        content_type, size_bytes, created_at)
                     VALUES (?, ?, 'EXECUTION', ?, ?, 'screenshot.png', 'image/png', ?, now())
                     """)) {
            statement.setObject(1, attachmentId);
            statement.setObject(2, companyId);
            statement.setObject(3, ownerId);
            statement.setString(4, key);
            statement.setLong(5, EVIDENCE_BYTES.length);
            statement.executeUpdate();
        }
        return key;
    }

    /** Gives the invited admin a password digest, so there is a real secret to look for. */
    private void seedPasswordHash(UUID companyId) throws SQLException {
        try (Connection connection = superuserConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE app_user SET password_hash = ?
                      WHERE id IN (SELECT user_id FROM company_membership WHERE company_id = ?)
                     """)) {
            statement.setString(1, "$2a$10$7cexportfixturedigestnotarealbcrypthashvalue0");
            statement.setObject(2, companyId);
            statement.executeUpdate();
        }
    }

    private void seedApiToken(UUID companyId, UUID tokenId) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            UUID ownerId = firstMember(connection, companyId);
            UUID roleId = anyRole(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO api_token
                      (id, company_id, name, token_hash, token_prefix, owner_id, role_id, created_at)
                    VALUES (?, ?, 'CI', ?, 'tcms_7c00', ?, ?, now())
                    """)) {
                statement.setObject(1, tokenId);
                statement.setObject(2, companyId);
                // A digest-shaped value, distinctive enough that finding it in the archive is
                // unambiguous — which is what the redaction arm searches for.
                statement.setString(3, HexFormat.of().formatHex(
                        ("7c-token-digest-" + tokenId).getBytes(StandardCharsets.UTF_8)));
                statement.setObject(4, ownerId);
                statement.setObject(5, roleId);
                statement.executeUpdate();
            }
        }
    }

    private void seedNotificationChannel(UUID companyId, UUID channelId) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            UUID projectId = firstProject(connection, companyId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO notification_channel
                      (id, company_id, project_id, kind, name, enabled, config, secret,
                       secret_hint, created_at, updated_at)
                    VALUES (?, ?, ?, 'SLACK', 'Releases', true, '{}'::jsonb, ?, 'xoxb-…9f2', ?, ?)
                    """)) {
                statement.setObject(1, channelId);
                statement.setObject(2, companyId);
                statement.setObject(3, projectId);
                statement.setBytes(4,
                        "7c-webhook-ciphertext".getBytes(StandardCharsets.UTF_8));
                statement.setObject(5, Instant.now().atOffset(java.time.ZoneOffset.UTC));
                statement.setObject(6, Instant.now().atOffset(java.time.ZoneOffset.UTC));
                statement.executeUpdate();
            }
        }
    }

    /** A {@code company} row and nothing else — a company that was never provisioned. */
    private void seedBareCompany(UUID companyId) throws SQLException {
        try (Connection connection = superuserConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO company (id, slug, display_name, status, created_at, lock_version)
                     VALUES (?, 'exp-delta', 'EXP DELTA Ltd', 'ACTIVE', now(), 0)
                     ON CONFLICT (id) DO NOTHING
                     """)) {
            statement.setObject(1, companyId);
            statement.executeUpdate();
        }
    }

    private static UUID firstMember(Connection connection, UUID companyId) throws SQLException {
        return uuid(connection,
                "SELECT user_id FROM company_membership WHERE company_id = ? ORDER BY user_id LIMIT 1",
                companyId);
    }

    private static UUID firstProject(Connection connection, UUID companyId) throws SQLException {
        return uuid(connection,
                "SELECT id FROM project WHERE company_id = ? ORDER BY id LIMIT 1", companyId);
    }

    private static UUID anyRole(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id FROM role ORDER BY id LIMIT 1")) {
            assertThat(rs.next()).as("the built-in roles must be seeded").isTrue();
            return rs.getObject(1, UUID.class);
        }
    }

    private static UUID uuid(Connection connection, String sql, UUID parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("fixture precondition for %s", sql).isTrue();
                return rs.getObject(1, UUID.class);
            }
        }
    }
}
