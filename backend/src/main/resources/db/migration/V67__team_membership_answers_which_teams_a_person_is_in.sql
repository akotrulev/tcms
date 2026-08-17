-- =============================================================================
-- V67 — team_membership can answer "which teams is this person in"
--
-- The reverse of the primary key. team_membership_pkey is (team_id, user_id),
-- which answers "who is in this team" and cannot answer the other direction
-- without a scan — and the other direction is the one the permission model asks
-- on every single check.
--
-- MEASURED, NOT REASONED. The finding is docs/plans/2026-08-15-org-chart.md
-- §6.1, taken by T5 on a throwaway PostgreSQL 17.10 container with all 66 prior
-- migrations applied in order as a non-superuser tcms_owner, and every plan taken
-- as tcms_app with app.current_company set — because the RLS predicate is part of
-- the query, and the same plan taken as the container superuser loses the
-- One-Time Filter node entirely. That is the measuring-a-different-query trap and
-- §6.1 records falling into it deliberately to show the difference.
--
-- Instance: 1,000 companies, 40,020 users, 60,030 team_membership rows (8.5 MB),
-- 137,370 role_assignment rows. Company under measurement: 60 members, 12 teams,
-- 90 membership rows, 10 projects, 307 grants.
--
-- WHAT THE PLANS SAID
--
--   findProjectScopedHeldPermissions  3.980 ms -> 0.251 ms   696 -> 14 buffers
--     the evaluator's own query. Its team arm is
--     team_id IN (SELECT tm.team_id FROM team_membership tm WHERE tm.user_id = ?
--     AND tm.company_id = ?), which was a Seq Scan removing 60,028 rows by filter
--     to return 2. 683 of those 696 buffers were that one node.
--   TeamMembershipRepository.findByUserId  2.383 ms -> 0.008 ms  378 -> 4 buffers
--     an Index Scan on team_membership_pkey with company_id demoted to a Filter,
--     i.e. a full traversal of the primary key. An index used for a non-leading
--     column is not an index lookup.
--   ProjectService's Project.myRole (single-member findProjectRoleNames)
--                                     9.006 ms -> 0.291 ms   696 -> 23 buffers
--     Rows Removed by Filter: 60,029, to find one row, on a shipped screen.
--   Org chart's findProjectRoleNames, 60 members
--                                     8.5-19.3 ms -> 6.3-6.5 ms  1894 -> 1219
--
-- The rows the scan discarded were OTHER CLIENT COMPANIES' rows. The cost of
-- answering one tenant's question was a function of every other tenant's data,
-- which is the one shape a multi-tenant schema is not allowed to have.
--
-- TWO COLUMNS, NOT THREE. (company_id, user_id, team_id) was measured: it earns
-- an Index Only Scan and 6.771 ms, noise-identical to this index's 6.4 ms, for
-- 4000 kB instead of 2312 kB. INCLUDE has the same problem. The two-column form
-- wins and the is_lead/joined_at readers keep their heap visit.
--
-- company_id LEADS, per ERD §0.1 point 3 and SchemaConventionTest's
-- companyIdLeadsCompositeIndexes: every lookup is inside one company, and the
-- FORCE ROW LEVEL SECURITY predicate V4 installed is on that column.
--
-- THE DEFECT IS LATENT, AND THAT IS THE ARGUMENT FOR LANDING IT NOW
--
-- Re-measured against a 20-company instance (1,230 rows, 14 pages) the same seq
-- scan costs 0.062 ms — indistinguishable from the indexed figure. Production
-- today, at one company, cannot feel this and nothing shipped is slow because of
-- it. The cost arrives with the second client and the hundredth, which is what
-- this product is for. An index that is free to add now is not free to add then.
--
-- The write side is an administrative action, not a request-path write —
-- TeamWriteService's membership puts and removes — so the one-index tax on
-- INSERT/DELETE buys a read win on every permission check in the instance.
--
-- NOT CREATE INDEX CONCURRENTLY, AND THIS TIME THE QUESTION WAS REAL
--
-- §6.1's recommended DDL was CONCURRENTLY plus a `flyway:no-transaction` hatch.
-- It is taken here in the PLAIN form, and the reasoning is written down because
-- §6.1's is now partly superseded rather than merely unfollowed.
--
-- The precedent. Six migrations have created an index on a live table and none
-- has ever used no-transaction mode. V17, V42, V59 and V62 refuse CONCURRENTLY
-- in their headers, on record; V11 and V14 made the same call without saying so,
-- which V17:66-69 notes on their behalf. V17's is the fullest statement, it calls
-- the refusal "a choice rather than an oversight", and it closes:
--
--     "the day one of them is genuinely hot, that is a decision for all four at
--      once."
--
-- So: is that day here? T5's measurement is the first real candidate — this is
-- not another idempotency-key index on a table of hand-typed rows, it is the
-- authorization hot path, read by every request the instance serves.
--
-- The answer is no, and the reason is that "hot" was doing two jobs in that
-- sentence. What an ACCESS EXCLUSIVE lock costs is its DURATION, and duration is
-- a function of TABLE SIZE. What read-frequency changes is how many requests
-- stall for that duration, not how long it is. team_membership is read-hot and
-- physically tiny: 8.5 MB at the synthetic thousand-company ceiling, and 14 pages
-- at the twenty-company shape closer to today. The build is milliseconds, and
-- Flyway runs pre-deploy in the pipeline rather than at app startup
-- (application.yml, and the migrations skill's rule), so those milliseconds land
-- inside a window where the deploy is already the disruption.
--
-- Against which: taking CONCURRENTLY here would make this the repo's FIRST
-- no-transaction migration, and it buys operational hazard for that blip. A
-- CONCURRENTLY build that fails leaves an INVALID index behind, so the migration
-- stops being idempotent unless it opens with a DROP INDEX IF EXISTS whose own
-- lock is the thing being avoided; Flyway cannot roll back a no-transaction
-- migration, so a failure leaves the schema history in a failed state needing
-- `flyway repair` by hand; and baseline-on-migrate is deliberately false here
-- (V1's RLS block must execute), so recovery has no baseline to fall back to.
--
-- And it would not settle V17's question anyway. Deciding CONCURRENTLY for this
-- one index is not "a decision for all four at once" — it half-reverses a
-- six-migration precedent and leaves the other five where they were. When that
-- decision is genuinely due it wants its own slice: a no-transaction convention,
-- a failed-migration recovery runbook, and all six revisited together.
--
-- NO NEW TABLE, NO NEW COLUMN AND NO NEW POLICY. team_membership is already
-- company-owned and already under FORCE ROW LEVEL SECURITY (V1's list, V4's
-- nullif predicate), so ERD §0.1's exception list and SchemaConventionTest's
-- company_id-on-every-table arm are untouched. This migration adds one index.
-- =============================================================================

CREATE INDEX team_membership_user_idx ON team_membership (company_id, user_id);

COMMENT ON INDEX team_membership_user_idx IS
    'The reverse of the primary key. team_membership_pkey is (team_id, user_id), which answers '
    '"who is in this team" and cannot answer "which teams is this person in" without scanning — '
    'and every arm of the permission model asks the second question: the evaluator''s three '
    'team_id IN (SELECT ... WHERE tm.user_id = :userId) subqueries, the org chart''s three '
    'UNION ALL team arms, and TeamMembershipRepository.findByUserId. company_id leads because '
    'every lookup is inside one company and because the RLS policy''s predicate is on that column '
    '(ERD §0.1 point 3, SchemaConventionTest.companyIdLeadsCompositeIndexes). Measured in '
    'docs/plans/2026-08-15-org-chart.md §6.1: it turned a 60,028-row filtered seq scan on the '
    'authorization hot path into a 4-buffer index scan.';
