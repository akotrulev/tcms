-- V21 — team names are unique per company, compared CASE-INSENSITIVELY.
--
-- V1 shipped `CONSTRAINT uq_team_name UNIQUE (company_id, name)`, an exact-match constraint, at a
-- time when nothing could create a team: `team` had readers from the beginning (the org chart, the
-- grant subject, the permission evaluator's team term) and no writer anywhere, so the constraint
-- had never adjudicated a single insert. §71 gives the table its first writer, and the app-level
-- check that writer makes is case-insensitive — "QA" and "qa" are the same team to everyone who
-- reads an org chart, and a company with both has two rows nobody can tell apart in a picker.
--
-- The point of this migration is that the BACKSTOP MUST BE THE SAME CHECK. An application check
-- that is stricter than its constraint is a check with no backstop at all: two administrators
-- creating "QA" and "qa" at the same instant both read no conflict, both insert, and the exact-match
-- constraint lets both through. §69 landed V20 for the identical reason one vocabulary over, and
-- §58's E1 is the general shape — two copies of one rule under an instruction to keep them in step,
-- already drifted.
--
-- Case folding is `lower()` rather than `citext`. The column is TEXT and every read of it is
-- ordinary equality or an ORDER BY; swapping the type would change the semantics of comparisons
-- this migration has no reason to touch, where a functional index changes exactly one thing.

ALTER TABLE team DROP CONSTRAINT uq_team_name;

CREATE UNIQUE INDEX uq_team_name ON team (company_id, lower(name));

COMMENT ON INDEX uq_team_name IS
    'Team names are unique per company, compared case-insensitively (V21). Replaces V1''s '
    'exact-match UNIQUE (company_id, name), which was strictly weaker than the check '
    'TeamEdit makes — so a race could store two teams one picker cannot tell apart.';
