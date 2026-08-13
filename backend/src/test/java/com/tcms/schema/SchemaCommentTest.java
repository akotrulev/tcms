package com.tcms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <strong>Every schema identifier a comment names must be one this schema still has.</strong>
 *
 * <p>This schema's comments carry the <em>why</em> — {@code V1} says so about itself, and most of
 * the arguments in it exist nowhere else. They are also the one part of the DDL that no statement
 * validates, no entity mapping contradicts and no test reads: {@code hibernate.ddl-auto: validate}
 * compares columns and types, {@code SchemaConventionTest} compares policies and indexes, and both
 * are blind to a sentence. So a comment describing a structure a later migration dropped is
 * <em>the</em> stale claim this repository cannot detect by running anything — until this class.
 *
 * <h2>What it found, and how long it had been there</h2>
 *
 * <p>Two, out of 195 comment statements across 49 migrations:
 *
 * <ul>
 *   <li><strong>{@code suite_condition}</strong> described the resolution order as "walking
 *       {@code suite.path} upward". {@code V33} (§98) dropped {@code suite.path} and
 *       {@code suite.parent_id} — a suite is flat — so for fifteen sections the table's comment
 *       described a mechanism nobody could perform, in the words {@link
 *       com.tcms.repository.CaseConditions}'s own header <em>quotes</em> as the statement of the
 *       rule. {@code V33} rewrote {@code suite}'s comment and did not look one table over.</li>
 *   <li><strong>{@code role_assignment}</strong> named {@code project.company} twice. The sentences
 *       are true and the identifier is not a column — the shorthand for "the company the project
 *       belongs to", which is {@code project.company_id}.</li>
 * </ul>
 *
 * <p>Both are repaired by {@code V49}, which is comment-only. That the second is a shorthand rather
 * than a stale claim is exactly why it is repaired rather than exempted: <strong>this check cannot
 * tell the two apart, and neither can a reader who greps for the identifier to find out what reads
 * it.</strong> §162's lesson is that an ignore list naming the wrong mechanism reads as coverage.
 *
 * <h2>Two rules, because there are two ways to name something</h2>
 *
 * <ol>
 *   <li><strong>Qualified</strong> — {@code table.column}, resolved against that table's live
 *       columns. Precise: it can say <em>which</em> table the reference is wrong about, and it is
 *       the form a stale column reference almost always takes.</li>
 *   <li><strong>Bare</strong> — any {@code snake_case} token, resolved against every name this
 *       database has: relations (tables, indexes, sequences, views), columns of any of them,
 *       constraints, triggers, types, and functions <em>in any namespace</em> so that
 *       {@code to_tsvector} and {@code current_setting} resolve as what they are rather than as
 *       exemptions. This is the rule that would catch a comment naming a dropped <em>table</em>,
 *       which the qualified rule cannot see.</li>
 * </ol>
 *
 * <p>The bare rule reports nothing today and that is the expected result for a population rule: it
 * is coverage for the next migration, and the count it scans is pinned so it cannot go quiet by
 * scanning nothing (§154's shape — a corpus that silently empties passes forever).
 */
@DisplayName("Schema comments — every identifier named is one the schema still has")
class SchemaCommentTest extends IntegrationTestBase {

    /**
     * Tokens that look like a schema identifier and deliberately are not. <strong>One, and it is
     * argued rather than listed.</strong>
     *
     * <p>It started at three. {@code current_company} (the RLS GUC) and {@code links_changed} (the
     * outbox event type §167 added) came off it when {@link #isPartOfAQualifiedName} arrived: both
     * are written dotted — {@code app.current_company}, {@code documentation.links_changed} — so
     * they are names in a namespace this database does not own, and <em>asking which namespace</em>
     * is a better answer than exempting the word. An exemption list is where a check goes to stop
     * working; the entries worth keeping are the ones no rule can decide.
     *
     * <p>{@link #everyExemptionStillExemptsSomething} deletes an entry the moment no comment
     * contains it, because a one-directional ignore list cannot report a member that stopped
     * needing to be on it (§162).
     */
    private static final Map<String, String> NOT_A_SCHEMA_IDENTIFIER = Map.of(
            // A value inside a JSON example on `test_plan.exit_criteria`:
            // {"metric":"pass_rate","op":">=","value":95}. It is data the product stores, and the
            // one token here that no namespace question can settle — it has no namespace at all.
            "pass_rate", "a metric name inside the exit-criteria JSON example, not an identifier");

    /** {@code word.word}, both halves lowercase — the way this schema writes a column reference. */
    private static final Pattern QUALIFIED = Pattern.compile("\\b([a-z][a-z0-9_]*)\\.([a-z][a-z0-9_]*)\\b");

    /** A {@code snake_case} token: at least one underscore, so ordinary English is not a candidate. */
    private static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

    private static Connection catalogConnection;

    /** Every comment in the {@code public} schema: {@code label -> text}. */
    private static Map<String, String> comments;

    /** {@code table -> its live column names}, for the qualified rule. */
    private static Map<String, Set<String>> columnsByTable;

    /** Every name this database declares, for the bare rule. */
    private static Set<String> allIdentifiers;

    /**
     * Read on the first test rather than in {@code @BeforeAll}, and that is not a style choice.
     * <strong>At {@code @BeforeAll} time this schema is empty</strong> — the Spring context, and
     * therefore Flyway, is created when the test instance is post-processed, which is after the
     * class-level callback. The first version of this class read the catalog there and found 5286
     * descriptions (all of them {@code pg_catalog}'s own) and zero tables in {@code public}: every
     * rule failed on its corpus pin, which is the only reason the mistake was visible at all. A
     * rule whose corpus is empty and whose pin is missing passes.
     */
    @BeforeEach
    void readCatalog() throws SQLException {
        if (comments != null) {
            return;
        }
        catalogConnection = superuserConnection();
        comments = loadComments();
        columnsByTable = loadColumns();
        allIdentifiers = loadIdentifiers();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (catalogConnection != null) {
            catalogConnection.close();
        }
    }

    @Test
    @DisplayName("every table.column a comment names is a live column of that table")
    void everyQualifiedReferenceNamesALiveColumn() {
        Map<String, Set<String>> offenders = new LinkedHashMap<>();
        int resolved = 0;

        for (Map.Entry<String, String> comment : comments.entrySet()) {
            Matcher matcher = QUALIFIED.matcher(comment.getValue());
            while (matcher.find()) {
                String table = matcher.group(1);
                String column = matcher.group(2);
                Set<String> columns = columnsByTable.get(table);
                if (columns == null) {
                    continue; // not a table of ours: `plan §6f`, `docs/specs`, a Java call
                }
                if (columns.contains(column)) {
                    resolved++;
                } else {
                    offenders.computeIfAbsent(comment.getKey(), key -> new TreeSet<>())
                            .add(table + "." + column);
                }
            }
        }

        assertThat(offenders)
                .as("""
                        Comments naming a column the table does not have. A comment is the only \
                        place several of this schema's decisions are written down, and it is the \
                        one part of the DDL nothing else validates — `ddl-auto: validate` compares \
                        columns, not sentences. Either the reference is stale (the column was \
                        dropped: fix the comment in a new migration, COMMENT ON is replace-only) \
                        or it is a shorthand for a column that does have a name (spell the name — \
                        this check cannot tell the two apart, and neither can a reader who greps \
                        for the identifier).""")
                .isEmpty();

        // Pinned so the rule cannot pass by resolving nothing — if the extraction breaks, or the
        // corpus is read from the wrong catalog, this is what says so.
        assertThat(resolved)
                .as("qualified references that resolved; the rule is asleep if this reaches zero")
                .isGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("every snake_case token a comment names is a name this database declares")
    void everySchemaIdentifierNamesALiveObject() {
        Map<String, Set<String>> offenders = new LinkedHashMap<>();
        int scanned = 0;

        for (Map.Entry<String, String> comment : comments.entrySet()) {
            String text = comment.getValue();
            Matcher matcher = SNAKE_CASE.matcher(text);
            while (matcher.find()) {
                if (isPartOfAQualifiedName(text, matcher.start(), matcher.end())) {
                    continue;
                }
                String token = matcher.group();
                scanned++;
                if (allIdentifiers.contains(token) || NOT_A_SCHEMA_IDENTIFIER.containsKey(token)) {
                    continue;
                }
                offenders.computeIfAbsent(comment.getKey(), key -> new TreeSet<>()).add(token);
            }
        }

        assertThat(offenders)
                .as("""
                        Comments naming something that looks like a schema identifier and is not \
                        one — a dropped table, a renamed index, a constraint that never landed. \
                        This is the rule the qualified one cannot stand in for: a bare `suite_case` \
                        has no table to check it against. If the token genuinely is not an \
                        identifier, add it to NOT_A_SCHEMA_IDENTIFIER with what it IS; one entry \
                        is the whole list today and it is a sentence.""")
                .isEmpty();

        // 118 measured at V49. A floor rather than the number, because every migration that
        // comments a new table moves it — but far enough below zero's neighbourhood that a broken
        // extraction, or the dotted skip swallowing everything, is a failure rather than a pass.
        assertThat(scanned)
                .as("snake_case tokens scanned; the rule is asleep if this reaches zero")
                .isGreaterThanOrEqualTo(100);
    }

    /**
     * Whether a token is one half of a dotted name, in which case the other rule owns it.
     *
     * <p><strong>The division of labour, and the one thing it does not cover.</strong> A dotted
     * name whose left half is a table is judged by {@link #everyQualifiedReferenceNamesALiveColumn}
     * — checking the right half here as a bare identifier would report {@code page_key} against
     * every other table's columns, which is not the question. A dotted name whose left half is
     * <em>not</em> a table names something in a namespace this database does not own:
     * {@code documentation.links_changed} is the outbox event type §167 added,
     * {@code app.current_company} is the RLS GUC, {@code plan §6f} and {@code docs/specs} are
     * documents. Neither rule can check those, and inventing a catalog of event names to check the
     * first against would be a second copy of a list the emitter owns.
     *
     * <p>So the hole is precise and worth stating: <strong>a reference to a dropped TABLE written
     * in dotted form is invisible to both rules</strong> — {@code gone_table.col} has no live left
     * half, and skipping it here is what stops the check demanding that every module name in every
     * sentence be a table. Written bare, a dropped table is caught. That is the trade, and it is
     * the same one the qualified rule makes when it ignores an unknown left half.
     */
    private static boolean isPartOfAQualifiedName(String text, int start, int end) {
        boolean dotBefore = start > 0 && text.charAt(start - 1) == '.';
        // A FULL STOP IS NOT A QUALIFIER. The first version of this asked only whether the next
        // character was a dot, and `§190 SC-2` — a sentence ending "…through suite_parent_link." —
        // was skipped as though it named a column of a table called `suite_parent_link`. A
        // sentence-final identifier is the most ordinary way a comment names a table, so the rule
        // was silent on exactly the population it exists for. The dot must be followed by the
        // start of another identifier for the pair to be a qualified name.
        boolean dotAfter = end + 1 < text.length()
                && text.charAt(end) == '.'
                && (Character.isLetterOrDigit(text.charAt(end + 1)) || text.charAt(end + 1) == '_');
        return dotBefore || dotAfter;
    }

    @Test
    @DisplayName("every NOT_A_SCHEMA_IDENTIFIER entry still excuses a token some comment contains")
    void everyExemptionStillExemptsSomething() {
        // The same skip the rule itself applies: an exemption for a token that only ever appears
        // as half of a dotted name excuses nothing, because the rule never reaches it.
        Set<String> present = new LinkedHashSet<>();
        for (String text : comments.values()) {
            Matcher matcher = SNAKE_CASE.matcher(text);
            while (matcher.find()) {
                if (!isPartOfAQualifiedName(text, matcher.start(), matcher.end())) {
                    present.add(matcher.group());
                }
            }
        }

        List<String> dead = new ArrayList<>(NOT_A_SCHEMA_IDENTIFIER.keySet());
        dead.removeAll(present);

        assertThat(dead)
                .as("""
                        Exemptions that excuse nothing: no comment contains the token any more. \
                        Delete the entry. A one-directional ignore list cannot report a member \
                        that stopped needing to be on it, which is how an exemption outlives the \
                        thing it excused and reads as coverage (§162).""")
                .isEmpty();

        // The other direction: an exemption for a token the catalog declares would be silently
        // redundant, and would go on excusing the name after the object it names is dropped.
        List<String> shadowed = NOT_A_SCHEMA_IDENTIFIER.keySet().stream()
                .filter(allIdentifiers::contains)
                .toList();
        assertThat(shadowed)
                .as("""
                        Exemptions for tokens this database actually declares. The entry excuses \
                        nothing today and would keep excusing the name on the day the object is \
                        dropped — which is the exact failure the rule exists to catch.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the corpus is every comment in the schema, and it is not empty")
    void theCorpusIsEveryCommentInTheSchema() {
        assertThat(comments)
                .as("""
                        Comments read from pg_description. Both rules above are population rules \
                        over this map, so an empty or truncated read is a green suite that checks \
                        nothing. 49 migrations write about 195 COMMENT ON statements, some of them \
                        replacing an earlier one — the live count is what psql prints, and it is \
                        the only corpus that can be right, because a superseded comment is not a \
                        comment anybody reads.""")
                .hasSizeGreaterThanOrEqualTo(150);

        assertThat(comments)
                .as("the two comments V49 repaired must be in the corpus, or the rules missed them")
                .containsKeys("table suite_condition", "table role_assignment");
    }

    // ---------------------------------------------------------------------------------------
    // The catalog reads.
    // ---------------------------------------------------------------------------------------

    /**
     * Every comment in {@code public}, keyed by a label a failure message can be read from.
     *
     * <p>Three catalogs, each keyed on {@code classoid} rather than joined on {@code objoid}
     * alone: {@code pg_description.objoid} is only unique <em>within</em> a catalog, so a bare
     * join to {@code pg_class} would attribute a constraint's comment to whatever relation happens
     * to share its oid.
     */
    private static Map<String, String> loadComments() throws SQLException {
        String sql = """
                SELECT CASE WHEN d.objsubid > 0 THEN 'column ' || c.relname || '.' || a.attname
                            ELSE 'table ' || c.relname END AS label,
                       d.description AS text
                  FROM pg_description d
                  JOIN pg_class c ON c.oid = d.objoid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  LEFT JOIN pg_attribute a
                         ON a.attrelid = d.objoid AND a.attnum = d.objsubid
                 WHERE d.classoid = 'pg_class'::regclass AND n.nspname = 'public'
                UNION ALL
                SELECT 'constraint ' || con.conname, d.description
                  FROM pg_description d
                  JOIN pg_constraint con ON con.oid = d.objoid
                  JOIN pg_namespace n ON n.oid = con.connamespace
                 WHERE d.classoid = 'pg_constraint'::regclass AND n.nspname = 'public'
                UNION ALL
                SELECT 'function ' || p.proname, d.description
                  FROM pg_description d
                  JOIN pg_proc p ON p.oid = d.objoid
                  JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE d.classoid = 'pg_proc'::regclass AND n.nspname = 'public'
                """;
        Map<String, String> loaded = new LinkedHashMap<>();
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                loaded.put(rs.getString("label"), rs.getString("text"));
            }
        }
        return loaded;
    }

    /** {@code table -> live column names}, over relations a comment could sensibly reference. */
    private static Map<String, Set<String>> loadColumns() throws SQLException {
        String sql = """
                SELECT c.relname AS table_name, a.attname AS column_name
                  FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public'
                   AND c.relkind IN ('r', 'p', 'v', 'm')
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                """;
        Map<String, Set<String>> loaded = new HashMap<>();
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                loaded.computeIfAbsent(rs.getString("table_name"), key -> new HashSet<>())
                        .add(rs.getString("column_name"));
            }
        }
        return loaded;
    }

    /**
     * Every name this database declares.
     *
     * <p>Functions are collected from <strong>every</strong> namespace on purpose:
     * {@code to_tsvector}, {@code jsonb_to_tsvector} and {@code current_setting} live in
     * {@code pg_catalog}, and a comment naming one is naming a real thing. Exempting them by hand
     * would put three entries on the ignore list for the sake of not asking the database.
     */
    private static Set<String> loadIdentifiers() throws SQLException {
        String sql = """
                SELECT c.relname FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                UNION
                SELECT a.attname FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public' AND a.attnum > 0 AND NOT a.attisdropped
                UNION
                SELECT con.conname FROM pg_constraint con
                  JOIN pg_namespace n ON n.oid = con.connamespace WHERE n.nspname = 'public'
                UNION
                SELECT t.tgname FROM pg_trigger t WHERE NOT t.tgisinternal
                UNION
                SELECT ty.typname FROM pg_type ty
                  JOIN pg_namespace n ON n.oid = ty.typnamespace WHERE n.nspname = 'public'
                UNION
                SELECT p.proname FROM pg_proc p
                """;
        Set<String> loaded = new HashSet<>();
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                loaded.add(rs.getString(1));
            }
        }
        return loaded;
    }
}
