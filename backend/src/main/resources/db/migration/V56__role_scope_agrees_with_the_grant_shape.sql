-- =============================================================================
-- V56 — the grant tables refuse a row whose shape disagrees with its role's scope
--
-- THE GAP
--
-- The agreement between role.scope and the shape of a grant was enforced in
-- exactly one place: RoleGrantService.scopeError, an application-layer refusal
-- on the one HTTP path that writes role_assignment. Every other writer — a seed
-- function, a hand-run INSERT, a future migration — could produce the two rows
-- the model has no meaning for, and the evaluator would read both without
-- noticing:
--
--   * a COMPANY-scope role paired with a project_id. Two silent consequences,
--     and the second is the expensive one: findProjectScopedHeldPermissions
--     confers the role's keys as though they were project permissions (it
--     deliberately never joins role), and hasProjectScopedAssignment counts the
--     row as "this project said otherwise" — REPLACING the caller's inherited
--     company-wide default with whatever the row grants. A row meant to widen
--     quietly removes.
--
--   * any non-SYSTEM role in instance_role_assignment. findSystemHeldPermissions
--     confers every key of whatever role the row names, scope unread, so a
--     PROJECT-scope role written there becomes an instance-wide grant of its
--     keys — reachable with no company gate, because the SYSTEM term is
--     deliberately in front of it (PermissionEvaluator's header says why).
--     The mirror case, a SYSTEM role in role_assignment, is the one
--     RoleGrantService already refuses with a 400: instance-wide powers scoped
--     to one company is a tier that does not exist in the model.
--
-- Neither row is writable over HTTP today. This migration is defence in depth of
-- the same kind the repository headers describe for the company_id predicates:
-- the application refusal is the intent, the trigger is the guarantee, and the
-- two agreeing is what makes a drift in either one loud instead of silent.
--
-- WHY TRIGGERS AND NOT CONSTRAINTS
--
-- The fact being checked (role.scope) lives in another table, which a CHECK
-- cannot reach. The declarative alternative — a denormalized role_scope column
-- with a composite FK onto role (id, scope) and a CHECK over it — would have to
-- be populated by every writer: the entity, both seed functions, provisioning.
-- That is per-writer remembering, which is the failure mode this file exists to
-- end. role.scope is immutable in practice (seeded reference data with no UPDATE
-- path in the product), so the cross-row race a trigger cannot see has no writer
-- to race with; V2's classification and team-hierarchy triggers stand on the
-- same argument.
--
-- The functions read only role — reference data, not under RLS — so they work
-- identically for tcms_app, the migration role, and a hand-run psql session.
-- =============================================================================

CREATE OR REPLACE FUNCTION tcms_role_assignment_scope_shape() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    granted_scope TEXT;
BEGIN
    SELECT scope INTO granted_scope FROM role WHERE id = NEW.role_id;
    IF granted_scope = 'SYSTEM' THEN
        RAISE EXCEPTION
            'role % is SYSTEM-scope; its grants live in instance_role_assignment, never here',
            NEW.role_id
            USING ERRCODE = '23514';
    END IF;
    IF granted_scope = 'COMPANY' AND NEW.project_id IS NOT NULL THEN
        RAISE EXCEPTION
            'role % is COMPANY-scope, so its grant covers every project and may not name one',
            NEW.role_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER role_assignment_scope_shape
    BEFORE INSERT OR UPDATE OF role_id, project_id ON role_assignment
    FOR EACH ROW EXECUTE FUNCTION tcms_role_assignment_scope_shape();

CREATE OR REPLACE FUNCTION tcms_instance_role_assignment_scope_shape() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    granted_scope TEXT;
BEGIN
    SELECT scope INTO granted_scope FROM role WHERE id = NEW.role_id;
    IF granted_scope IS DISTINCT FROM 'SYSTEM' THEN
        RAISE EXCEPTION
            'role % is %-scope; instance_role_assignment holds SYSTEM grants only — company and '
            'project grants live in role_assignment, under RLS',
            NEW.role_id, granted_scope
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER instance_role_assignment_scope_shape
    BEFORE INSERT OR UPDATE OF role_id ON instance_role_assignment
    FOR EACH ROW EXECUTE FUNCTION tcms_instance_role_assignment_scope_shape();

-- -----------------------------------------------------------------------------
-- Existing rows must already satisfy what the triggers will enforce, or the
-- guarantee starts with a hole in it. instance_role_assignment is instance-level
-- and readable directly; role_assignment is under FORCE RLS — which covers the
-- migration role too (V15's backfill section) — so the sweep opens each
-- company's context in turn, the same way tcms_seed_company_defaults requires.
-- Transaction-local set_config, so nothing leaks past the migration.
-- -----------------------------------------------------------------------------

DO $$
DECLARE
    c RECORD;
    bad BIGINT;
BEGIN
    SELECT count(*) INTO bad
      FROM instance_role_assignment ira
      JOIN role r ON r.id = ira.role_id
     WHERE r.scope <> 'SYSTEM';
    IF bad > 0 THEN
        RAISE EXCEPTION
            '% instance_role_assignment rows name a non-SYSTEM role. They were conferring their '
            'keys instance-wide; decide per row whether it belongs in role_assignment, then '
            'delete it here before re-running.', bad;
    END IF;

    FOR c IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', c.id::text, true);
        SELECT count(*) INTO bad
          FROM role_assignment ra
          JOIN role r ON r.id = ra.role_id
         WHERE r.scope = 'SYSTEM'
            OR (r.scope = 'COMPANY' AND ra.project_id IS NOT NULL);
        IF bad > 0 THEN
            RAISE EXCEPTION
                '% role_assignment rows in company % pair a role with a shape its scope refuses '
                '(SYSTEM role, or COMPANY role naming a project). Repair them before re-running.',
                bad, c.id;
        END IF;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;
