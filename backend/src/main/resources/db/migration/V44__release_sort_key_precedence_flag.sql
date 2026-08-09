-- =============================================================================
-- V44 — a pre-release stops sorting after the release it precedes
--
-- Source: handoff §151, which is §150's finding 4a taken as the slice §150 said
--         it needed. See ReleaseSortKey's class javadoc for the format and for
--         the measurement behind the choice of flag character.
--
-- V1: "Computed on write from release_version — semver parsed to
-- %05d.%05d.%05d ... Without this, '1.10' sorts before '1.9' and every
-- latest-release query breaks SILENTLY (plan §6q)."
--
-- It prevented that one and introduced the same failure through the other end.
-- A pre-release suffix was appended straight after the padded triple, so:
--
--     4.3.0        ->  00004.00003.00000
--     4.3.0-rc.1   ->  00004.00003.00000-rc.1
--
-- The release's key is a strict PREFIX of its own release candidate's, and a
-- prefix collates first. So the candidate reads as the newer of the two and
-- "what did the newest release do" answers about the thing that was not
-- released — silently, with a version number on the screen that looks right.
-- Semver says the opposite in as many words: a pre-release has LOWER precedence
-- than the associated normal version.
--
-- Nothing observed it because NOTHING NAMED THE CLASS until §150 opened a unit
-- test lane, and nothing ORDERS BY this column yet either (see §4 below). The
-- class javadoc had claimed the correct behaviour since M0.
--
-- -----------------------------------------------------------------------------
-- 1. THE FORMAT CHANGE — A FOURTH COMPONENT, AND WHY IT IS A DIGIT
-- -----------------------------------------------------------------------------
--
-- A precedence flag goes between the triple and the suffix: 0 for a pre-release,
-- 1 for the release it precedes.
--
--     4.3.0-rc.1     ->  00004.00003.00000.0-rc.1
--     4.3.0          ->  00004.00003.00000.1
--     4.3.0+build.7  ->  00004.00003.00000.1+build.7   (build metadata is not a
--                                                       pre-release; semver
--                                                       excludes it from
--                                                       precedence entirely)
--
-- THE OBVIOUS FIX IS A '~' SENTINEL AND IT IS A NO-OP ON THIS DATABASE. Debian's
-- version ordering appends a character that sorts above everything, and the
-- reason it cannot be used here is that POSTGRES ORDERS THIS COLUMN, NOT JAVA.
-- This instance's collation is en_US.utf8 under the libc provider — glibc's
-- multi-level collation, which weights punctuation below letters. Measured
-- against the running database:
--
--     '00004.00003.00000-rc.1' < '00004.00003.00000~'   -- en_US.utf8 : FALSE
--                                                       -- C          : true
--                                                       -- und-x-icu  : true
--
-- It passes under two collations and fails under the one actually in use, so the
-- obvious repair would have shipped green and changed nothing. A digit is a
-- strong character in every collation and compares at the primary level under
-- all three, which is what makes this ordering a property of the key rather than
-- of how a given cluster was initialised.
--
-- What is deliberately NOT fixed: precedence AMONG pre-releases. '-rc.10' still
-- sorts before '-rc.9', because two suffixes are still compared as text. That is
-- the same shape one level down, confined to one release's candidates rather
-- than sitting between a release and its candidate, and repairing it is a second
-- format change plus a second backfill. ReleaseSortKeyTest pins it as a known
-- limit.
--
-- -----------------------------------------------------------------------------
-- 2. THIS FILE IS DATA-ONLY, AND IT REWRITES THE KEY RATHER THAN RE-DERIVING IT
-- -----------------------------------------------------------------------------
--
-- No DDL: no table, no column, no index, no policy. The FORCE ROW LEVEL SECURITY
-- count stays at 63 and SchemaConventionTest pins it.
--
-- The backfill does NOT re-implement the parser in SQL. It does not have to: the
-- new key is a pure STRING REWRITE of the old one, because every part of the old
-- key survives into the new one and only the flag is inserted. Re-deriving from
-- release_version would mean a second implementation of ReleaseSortKey.of() in
-- PL/pgSQL that could disagree with the Java one and write rows the application
-- would never write — the divergence this project keeps finding. A rewrite of
-- the stored value cannot disagree about parsing, because it does not parse.
--
-- THE PREDICATE IS ANCHORED ON PURPOSE. It matches a padded triple followed by
-- nothing, or by a semver separator — exactly the shape of() produces when it
-- parsed — and therefore skips:
--
--   * the text fallback ('nightly', 'nightly-2026-08-01'), which is stored raw
--     and must stay raw;
--   * a fallback that merely LOOKS padded. '12345.12345.12345 hotfix' does not
--     parse (the suffix is neither '-' nor '+'), falls back to itself, and would
--     be corrupted by an unanchored prefix match into '12345.12345.12345.1
--     hotfix' — a key the application would never write;
--   * rows already in the new format, whose fourth component is '.0'/'.1' and so
--     matches neither arm. THE MIGRATION IS THEREFORE IDEMPOTENT, which is what
--     lets ReleaseSortKeyBackfillTest execute this file a second time and assert
--     that it changes nothing.
--
-- -----------------------------------------------------------------------------
-- 3. RLS: THE OWNER IS NOT EXEMPT, SO THE CONTEXT IS SET PER COMPANY
-- -----------------------------------------------------------------------------
--
-- All three tables are under FORCE ROW LEVEL SECURITY, which covers the table
-- owner this migration runs as (V1's RLS block). An UPDATE issued without
-- app.current_company set would match ZERO ROWS AND REPORT SUCCESS — fail-closed,
-- and the silent half of it is exactly what makes a backfill worth writing
-- carefully. company itself is instance-level (ERD §0.1), so the loop can see
-- every company to iterate over.
--
-- This is V15's and V18's pattern, third use. set_config(..., is_local => true)
-- scopes each setting to this migration's transaction, and the final reset
-- returns it to '' rather than leaving the last company set — the difference
-- between "no context" and "somebody else's context" on a connection Flyway
-- hands back to the pool.
--
-- Not batched, deliberately, against the migrations skill's default. The loop is
-- already per company; this is a single-instance self-hosted product, migrations
-- run pre-deploy with nothing else connected, and the UPDATE touches only rows
-- that have a release version at all. A batching loop here would add a resume
-- cursor for a statement that has no plausible row count to need one.
--
-- -----------------------------------------------------------------------------
-- 4. WHAT THIS DOES NOT FIX, AND IS THE REASON THE DEFECT SURVIVED
-- -----------------------------------------------------------------------------
--
-- NOTHING READS THIS COLUMN. It is written on three tables, carries three
-- indexes (test_run_release_idx, test_execution_release_idx,
-- execution_fact_release_idx), and there is no ORDER BY release_sort_key
-- anywhere in the application — releaseVersion is an exact-match filter in every
-- operation that takes it. So the wrong ordering has never reached a screen, and
-- could not have: the column is maintained for a query nobody has written yet.
--
-- That is recorded rather than acted on. Giving it a reader is a feature (plan
-- §6q's latest-release view) and not this slice; correcting the stored value now
-- is what keeps the eventual reader from being written against three tables of
-- keys that order releases backwards.
-- =============================================================================


DO $$
DECLARE
    v_company   RECORD;
    v_table     TEXT;
    v_updated   integer;
    v_total     integer := 0;
    -- A padded triple followed by nothing, or by a semver separator. See §2 for
    -- each thing this anchoring deliberately refuses to match.
    c_shape  CONSTANT TEXT := '^[0-9]{5}[.][0-9]{5}[.][0-9]{5}([-+].*)?$';
    c_preRel CONSTANT TEXT := '^[0-9]{5}[.][0-9]{5}[.][0-9]{5}-';
BEGIN
    FOR v_company IN SELECT id FROM company ORDER BY id LOOP
        PERFORM set_config('app.current_company', v_company.id::text, true);

        FOREACH v_table IN ARRAY ARRAY['test_run', 'test_execution', 'execution_fact'] LOOP
            EXECUTE format(
                'UPDATE %I SET release_sort_key ='
                '         substring(release_sort_key from ''^[0-9]{5}[.][0-9]{5}[.][0-9]{5}'')'
                '      || CASE WHEN release_sort_key ~ $2 THEN ''.0'' ELSE ''.1'' END'
                '      || substring(release_sort_key from ''^[0-9]{5}[.][0-9]{5}[.][0-9]{5}(.*)$'')'
                '  WHERE release_sort_key ~ $1', v_table)
                USING c_shape, c_preRel;
            GET DIAGNOSTICS v_updated = ROW_COUNT;
            v_total := v_total + v_updated;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);

    RAISE NOTICE 'V44: re-flagged % release sort key(s) across test_run, test_execution and '
                 'execution_fact', v_total;
END $$;


-- =============================================================================
-- 5. THE COMMENT
-- =============================================================================
--
-- V1's version described the format, and the format is what changed. Rewritten
-- rather than appended to, and it now names the property the column exists for
-- in both directions — the padding was only ever half of it.

COMMENT ON COLUMN test_run.release_sort_key IS
    'Computed on write from release_version — semver parsed to %05d.%05d.%05d, then a PRECEDENCE '
    'FLAG (0 pre-release, 1 release), then any suffix verbatim: 4.3.0-rc.1 is '
    '"00004.00003.00000.0-rc.1" and 4.3.0 is "00004.00003.00000.1". Non-parsing values fall back to '
    'raw collation. Two silent wrong answers, not one: without the padding "1.10" sorts before '
    '"1.9", and without the flag a release key is a PREFIX of its own release candidate''s and the '
    'candidate reads as newer (V44, §6q). '
    '  THE FLAG IS A DIGIT AND NOT A ''~'' SENTINEL because this column is ordered by Postgres '
    'under a libc collation that weights punctuation below letters, where the sentinel is a no-op — '
    'it works under C and ICU and fails under the collation in use. See V44 §1 and ReleaseSortKey. '
    '  Precedence AMONG pre-releases is still text order, so -rc.10 sorts before -rc.9: a known '
    'limit pinned by ReleaseSortKeyTest, not an oversight.';
