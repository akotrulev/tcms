-- V16 — Automation Service gets project:read, without which the role cannot reach anything.
--
-- WHAT WAS WRONG
--
-- V3 seeds Automation Service the five capabilities of roles spec §A6 and, alone among the
-- built-in roles, no `project:read`. Every other role's seed block grants it explicitly —
-- Viewer, the smallest *human* role, included.
--
-- `project:read` is not a capability in §A6's sense. It is the visibility precondition: it is
-- the permission `ProjectAccess.canView` asks, and every project-scoped endpoint asks canView
-- first so that a project in another company, and one in this company the caller holds no
-- grant on, are indistinguishable 404s (§6t). A role without it therefore does not merely lose
-- the project list — it 404s on every project-scoped path, whatever else it holds.
--
-- So the five permissions were unreachable in their entirety. Not `run:create` alone:
-- `result:submit`, `evidence:attach`, `case:read` and the quarantine `case:create` too. The
-- role that exists so a CI job can post results could not post a result, and M3's whole
-- ingestion premise rests on it.
--
-- The claim in `ProjectAccess.canView`'s javadoc — "every one of the six built-in roles holds
-- project:read" — was the load-bearing justification for why a write may not reuse canView,
-- and it was false when written. Corrected in the same commit as this migration.
--
-- WHY THIS REPAIR AND NOT THE OTHER ONE
--
-- The alternative is to stop asking `project:read` for reachability and ask "holds any grant on
-- this project" instead. Rejected: it changes the meaning of the one predicate two hundred call
-- sites depend on, to repair a single missing seed row. Same shape as the `company:read` repair
-- decided this session (handoff §43) and resolved the same way — grant the missing precondition,
-- leave the evaluator's model alone. Loosening a gate to reach a permission is how a gate stops
-- meaning anything.
--
-- WHY THIS DOES NOT WIDEN WHAT A LEAKED TOKEN CAN DO
--
-- §A6's argument is about what a stolen CI token can *do*: post results and add quarantine
-- stubs; not edit or delete a real case, not close a run, not read reports. `project:read`
-- touches none of that. A token that may already read the project's cases can hardly be
-- prevented from knowing the project exists — and today it cannot read them either, which is
-- the defect. The spec's "exactly five" was counting capabilities and did not have the
-- visibility precondition in view; §A6 is corrected to say so rather than being quietly
-- contradicted here.
--
-- Idempotent, like every seed migration: ON CONFLICT DO NOTHING, and re-running
-- `provision-company` is unaffected because role_permission is instance-level catalogue data,
-- not per-company rows.

INSERT INTO role_permission (role_id, permission_key)
SELECT '10000000-0000-4000-8000-000000000005', 'project:read'
ON CONFLICT DO NOTHING;


-- V1's comment on `role` still says "Seeded with exactly six built-ins" and names them. V15
-- added a seventh (Company Member, handoff §43) and did not correct it, so the comment became
-- false the moment that migration ran. Corrected here rather than left: the next reader checking
-- how many built-ins there are will read the table comment, and §41's lesson is that a comment
-- true only until someone changes the thing it describes is a comment that will lie.
--
-- V1 itself is immutable — it has run everywhere — so the repair is a COMMENT statement, which
-- is why this is DDL in what is otherwise a seed migration.
COMMENT ON TABLE role IS
    'Seeded with seven built-ins: Instance Admin (SYSTEM), Company Admin and Company Member '
    '(COMPANY), Test Lead / Test Engineer / Automation Service / Viewer (PROJECT). Company '
    'Member is the company:read baseline every member holds and is deliberately invisible on '
    'the wire — see V15 and handoff §43. See '
    'docs/specs/2026-07-30-tcms-roles-permissions-naming.md, authoritative for the grants.';
