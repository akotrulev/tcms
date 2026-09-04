-- =============================================================================
-- V10 — staffing (plan 009 module M7, plan 017)
--
-- THE VERSION IS V10, AND THE DIRECTORY IS WHAT SAYS SO. 009's table reserved V9
-- for this module, then corrected itself to V10 when M6 landed first. `ls` on
-- 2026-09-04: V1..V9, V9__time.sql being M6's, so this is V10 and the plan's
-- column was right this time. That is the fifth correction to that column in
-- five weeks, and re-reading the directory is cheap next to the failure it
-- avoids: `out-of-order` is unset in application.yml, so a reused number is
-- SILENTLY not applied, and Testcontainers builds every test database from empty
-- and therefore cannot show the conflict.
--
-- Five tables. NO capacity column anywhere: capacity is derived from
-- weekly_hours, absence and the calendar on every read (017 D-a). A stored
-- capacity is a cache with four invalidators — the profile changing, an absence
-- recorded, an absence deleted, and the calendar rolling forward — and the
-- fourth cannot be hooked. A capacity table that silently ages is the
-- two-contradictory-percentages failure with a schema behind it.
--
-- NO rate, NO cost. That is M8, and it is the sharpest boundary in the
-- expansion (scope §7). An allocation is a percentage of somebody's time, and
-- what that time is worth is a question this module must not be able to answer.
-- =============================================================================

-- 1. PERSON_PROFILE — contracted hours, and the three facts directory.html shows.
--
-- weekly_hours is the ONLY input to capacity. Daily capacity is this over five,
-- Monday to Friday (017 D-c): there is no public-holiday calendar, because no
-- mockup configures one and a company-wide holiday in a product serving several
-- client companies from one instance would have to be per company AND per
-- country. A public holiday is an `absence` of kind HOLIDAY, which is per person
-- and already zeroes capacity everywhere.
--
-- experience_since is a DATE and not a count of years (017 D-b). directory.html
-- renders "6 years"; a stored integer is right the day it is typed and wrong
-- every day after, silently, and nothing here would ever tell anybody. The
-- contract agrees: PersonProfile carries BOTH experienceSince and a derived
-- experienceYears, and only the first of them is a column.
--
-- A person with NO ROW HERE has no capacity figure at all — not zero, and not a
-- default of 40. Every hours figure the API serves for them is null and the
-- screens read em dash. That is a real state (nobody has recorded their
-- contracted hours) and inventing a number for it is the failure 016 D-j
-- refused to commit. The DEFAULT 40 below applies to a row somebody creates
-- without naming the hours, never to a person who has no row.
CREATE TABLE person_profile (
    id               UUID          PRIMARY KEY,
    company_id       UUID          NOT NULL REFERENCES company(id),
    user_id          UUID          NOT NULL REFERENCES app_user(id),
    weekly_hours     NUMERIC(5,2)  NOT NULL DEFAULT 40,
    job_title        TEXT,
    location         TEXT,
    experience_since DATE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_person_profile UNIQUE (company_id, user_id),
    CONSTRAINT ck_person_profile_hours CHECK (weekly_hours >= 0 AND weekly_hours <= 80)
);
COMMENT ON TABLE person_profile IS
    'Contracted hours and the three facts directory.html shows about a person. Company-owned '
    'because one account may belong to several companies (identity package comment) and be '
    'contracted differently in each — 40h at one client, 20h at another.';
COMMENT ON COLUMN person_profile.experience_since IS
    '017 D-b — a DATE, so directory.html''s "6 years" is derived and cannot rot. A stored integer '
    'is correct on the day it is typed and wrong every day after, with nothing to notice.';
-- NO separate index on (company_id, user_id): uq_person_profile is a UNIQUE
-- CONSTRAINT, so Postgres already built exactly that btree, in exactly that
-- column order, to enforce it. 017 T2's draft listed an ix_person_profile_user
-- beside it — a byte-for-byte duplicate that would double the write cost of every
-- profile edit and be chosen by the planner never. Every lookup this module makes
-- ("this person's profile in this company", "the profiles of these people") seeks
-- the constraint's index. A UNIQUE CONSTRAINT is an index; a second one is waste.

-- 2. SKILL — the company's vocabulary.
--
-- CITEXT, and the precedent is narrower than "every name in V1": V1 uses citext
-- for app_user.email and company_email_domain.domain, and TEXT with a
-- UNIQUE INDEX ON (company_id, lower(name)) for team, board and issue_label. Both
-- spellings make the vocabulary case-folded, so both would stop "Playwright" and
-- "playwright" becoming two skills — a directory whose filter misses half the
-- people who have the skill is worse than no filter. citext is chosen because
-- this vocabulary is looked up BY NAME on the write path (POST /staffing/skills
-- is a find-or-create against a name a person typed) while team and board are
-- looked up by id, and citext puts the rule in the type rather than in every
-- call site's memory to write lower().
--
-- WHAT THAT COSTS T3, so it is not discovered at startup: citext reports as
-- Types#OTHER and a String field maps to varchar, so `ddl-auto: validate`
-- REFUSES TO START with "found [citext (Types#OTHER)], but expecting
-- [varchar(255)]" unless the entity says @Column(columnDefinition = "citext").
-- AppUser.email and CompanyEmailDomain.domain both carry that and both explain
-- why; the Skill entity must too, and a native query comparing to a parameter
-- casts it (AppUserRepository's `CAST(:email AS citext)`).
--
-- No lock_version and no updated_at: the contract has POST and GET on this
-- resource and nothing else, so a skill is created and listed but never edited.
-- A rename arrives as a column when a screen renames one.
CREATE TABLE skill (
    id         UUID        PRIMARY KEY,
    company_id UUID        NOT NULL REFERENCES company(id),
    name       CITEXT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_skill_name UNIQUE (company_id, name),
    CONSTRAINT ck_skill_name CHECK (length(trim(name)) > 0)
);
COMMENT ON TABLE skill IS
    'A company''s skill vocabulary. CITEXT so "Playwright" and "playwright" are one skill — a '
    'directory whose filter misses half the people who have the skill is worse than no filter.';
COMMENT ON COLUMN skill.name IS
    'CITEXT. The entity mapping it MUST declare @Column(columnDefinition = "citext") or the '
    'context refuses to start under ddl-auto: validate — AppUser.email''s trap, same reason.';

-- 3. PERSON_SKILL — the join, and it carries company_id like every other join
--    table in this schema.
--
-- V1 shipped five join tables WITHOUT company_id and the omission was found by
-- scripting the comparison, not by reading (working agreement). A join table is
-- the easiest place in a schema to forget the isolation column and the hardest
-- place to notice it is missing, because both its parents are isolated and the
-- rows look right in every test that uses one company. ERD §0.1's list names all
-- five; SchemaConventionTest is why a sixth cannot ship.
--
-- NO proficiency level. directory.html draws chips with no level on them, and a
-- level nothing renders is a column that would be filled in inconsistently by
-- three people and then used to filter.
CREATE TABLE person_skill (
    company_id UUID        NOT NULL REFERENCES company(id),
    user_id    UUID        NOT NULL REFERENCES app_user(id),
    skill_id   UUID        NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, user_id, skill_id)
);
COMMENT ON TABLE person_skill IS
    'Which people know which skills. A JOIN TABLE THAT CARRIES company_id — the shape V1 got wrong '
    'five times, because both parents are isolated so the rows look right in any one-company test.';
CREATE INDEX ix_person_skill_skill ON person_skill (company_id, skill_id, user_id);
COMMENT ON INDEX ix_person_skill_skill IS
    'The directory''s "who has this skill" filter reads this one. skill_id leads because that is '
    'the question; the (company_id, user_id, skill_id) PK serves "what does this person know".';

-- 4. ALLOCATION — a percentage of a person's time, on a project, over a range.
--
-- project_id is a real FK: `project` is a foundation module. person_id
-- references app_user, which is instance-level and has no company_id of its own
-- — the company boundary on this row is its OWN company_id, which is what the
-- RLS policy below reads, exactly as time_entry does.
--
-- percent is a whole number 1..100 and is a percentage OF THAT PERSON'S OWN
-- CAPACITY, not of a notional 40-hour week. Somebody contracted 20 hours who is
-- allocated 50% is allocated 10 hours, and the same 50% on a full-timer is 20.
--
-- NO EXCLUSION CONSTRAINT on (person, project, range), deliberately, and this is
-- the one place a reader will expect one. An EXCLUDE USING gist would need
-- btree_gist for the two UUID equality terms, and the failure it produces is a
-- Postgres constraint-violation string that the API would have to pattern-match
-- to turn into 017 D-n's 409 naming the conflicting range. The overlap check
-- lives in AllocationService, over a query ix_allocation_person serves, and
-- AllocationOverlapTest asserts it against a real Postgres. Two allocations of
-- the same person to DIFFERENT projects over the same days are the ordinary case
-- and are exactly what over-allocation exists to report, so the constraint could
-- never have been on (person, range) either.
CREATE TABLE allocation (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    project_id   UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    person_id    UUID        NOT NULL REFERENCES app_user(id),
    percent      SMALLINT    NOT NULL,
    starts_on    DATE        NOT NULL,
    ends_on      DATE        NOT NULL,
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_allocation_percent CHECK (percent >= 1 AND percent <= 100),
    CONSTRAINT ck_allocation_range   CHECK (ends_on >= starts_on)
);
COMMENT ON TABLE allocation IS
    'A percentage of one person''s capacity on one project over a date range. The percentage is of '
    'THEIR capacity, not of a notional 40h week: 50% of a 20h contract is 10 hours.';
COMMENT ON COLUMN allocation.percent IS
    'Whole percent, 1..100. Over-allocation is the sum of overlapping percentages ON A DAY '
    '(017 D-e) — a monthly sum would flag two consecutive half-month allocations at 200%.';
CREATE INDEX ix_allocation_person  ON allocation (company_id, person_id, starts_on, ends_on);
CREATE INDEX ix_allocation_project ON allocation (company_id, project_id, starts_on, ends_on);
COMMENT ON INDEX ix_allocation_person IS
    'Serves both daily-peak questions: the capacity read walks one person''s overlapping spans, and '
    'the 017 D-n overlap check reads the same rows narrowed by project_id before it refuses a 409.';

-- 5. ABSENCE — days somebody is away, and the fraction of each one.
--
-- day_fraction rather than a half_day boolean (017 D-d): member.html shows
-- "29 Aug · half day", and a boolean answers that mockup and nothing else, while
-- start/end TIMES would invent a working-hours-of-day model this product has no
-- other use for. The CHECK is the wider (0, 1]; AbsenceInput's own minimum of
-- 0.25 is the narrower rule and it belongs on the wire, because a quarter day is
-- what a FORM should refuse to go below and not what the schema should make
-- unrepresentable.
--
-- An absence is per person and needs NO project. It zeroes capacity everywhere
-- capacity is shown — staffing, the directory, the timesheet's KPI, My Work's
-- meter — because all four read the one function that subtracts it (017 D-a).
-- That is the acceptance criterion of story S1's third bullet, and the reason it
-- can be met is that no screen computes capacity for itself.
CREATE TABLE absence (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    person_id    UUID          NOT NULL REFERENCES app_user(id),
    starts_on    DATE          NOT NULL,
    ends_on      DATE          NOT NULL,
    kind         TEXT          NOT NULL,
    day_fraction NUMERIC(3,2)  NOT NULL DEFAULT 1.00,
    note         TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    lock_version BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_absence_range CHECK (ends_on >= starts_on),
    CONSTRAINT ck_absence_kind  CHECK (kind IN ('LEAVE','SICK','HOLIDAY','OTHER')),
    CONSTRAINT ck_absence_fraction
        CHECK (day_fraction > 0 AND day_fraction <= 1)
);
COMMENT ON TABLE absence IS
    'Days somebody is away. HOLIDAY is how a public holiday is recorded — there is no holiday '
    'calendar (017 D-c), and a per-person row is what actually zeroes capacity.';
COMMENT ON COLUMN absence.day_fraction IS
    '017 D-d — the share of each day in the range that is absent. 1.00 whole, 0.50 the half day '
    'member.html shows. Not a boolean, so a half day is not a second table.';
CREATE INDEX ix_absence_person ON absence (company_id, person_id, starts_on, ends_on);

-- 6. ROW LEVEL SECURITY — five more forced policies, predicate byte-identical to
--    V1's (SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate
--    compares the text and fails equally on an explicit WITH CHECK — a
--    USING-only policy reuses USING as the insert check, so the read boundary IS
--    the write boundary).
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to the table's OWNER too, and
-- tcms_owner is what Flyway connects as. Without it a migration or an owner-run
-- query reads every company's rows (docs/specs/tenancy-invariants.md).
--
-- person_skill is in this list for the reason its own comment gives: a join
-- table is where the column is forgotten, and an unprotected one is a path from
-- one client's skill row to another client's people.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['person_profile','skill','person_skill','allocation','absence'];
BEGIN
    FOREACH t IN ARRAY protected LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = t) THEN
            RAISE EXCEPTION 'the RLS list names a table that does not exist: %', t;
        END IF;
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY company_isolation ON %I USING '
            '(company_id = nullif(current_setting(''app.current_company'', true), '''')::uuid)', t);
    END LOOP;

    IF array_length(protected, 1) <> 5 THEN
        RAISE EXCEPTION 'expected 5 new company-isolated tables, found %', array_length(protected, 1);
    END IF;
END $$;

-- 7. tcms_seed_project_defaults IS DELIBERATELY NOT TOUCHED.
--
-- Nothing here is a per-project default row. A new project starts with no
-- allocations, which is the correct empty state — staffing.html's own empty
-- reading is "nobody is on this yet", not a row of zeroes. The function is not
-- restated "to be safe": CREATE OR REPLACE FUNCTION replaces the whole body
-- (V3 §5, V8 §5, V9 §6), so reproducing it is the only way this file could
-- break it, and a block omitted from the reproduction silently stops seeding.
--
-- tcms_seed_company_defaults is untouched for the same reason and one of its
-- own: `skill` is a company vocabulary, but it is a vocabulary of a client's
-- OWN words — "Playwright", "SAP FI", the tool their product is built on — and
-- seeding a starter list would put this repository's guesses into every client's
-- directory filter, where they would be indistinguishable from skills somebody
-- entered. An empty vocabulary is the honest empty state.
--
-- The app role needs no explicit GRANT: ALTER DEFAULT PRIVILEGES FOR ROLE
-- tcms_owner covers every new table in schema public
-- (deploy/postgres/initdb/10-tcms-roles.sql).
