-- =============================================================================
-- V48 — a traceability link's RELATIONSHIP becomes correctable, and the table
--       learns to say who corrected it
--
-- Source: handoff §174, taking §173's named next slice — and browser-queue row
--         `M6-38`, which has asked for this since §115 and named its own
--         fallback while waiting: "If the editor cannot change a link's
--         relationship, say so — the row is then a finding about the editor,
--         and the fallback is to record it as a new link and delete the old."
--
-- WHY THE FIELD IS WORTH CORRECTING AT ALL. `relationship` is the difference
-- between "this case PROVES the statement" and "this case MENTIONS it", and
-- the coverage matrix counts only the first. A link recorded as VERIFIES when
-- the case merely REFERENCES the statement is what turns a coverage number
-- into a false claim — the roles the field exists to keep apart — so the one
-- field on this row a person most needs to fix is the one that was, until now,
-- fixable only by destroying the link and making another.
--
-- -----------------------------------------------------------------------------
-- 1. NOTHING HERE MAKES THE COLUMN MUTABLE, BECAUSE NOTHING EVER MADE IT
--    IMMUTABLE
-- -----------------------------------------------------------------------------
--
-- `doc_link.relationship` has been an ordinary NOT NULL TEXT with a CHECK since
-- V1. There is no trigger, no rule and no grant standing between a caller and
-- an UPDATE of it. The immutability was **entirely in Java** — `DocLink` maps
-- every column `updatable = false` and its class header states the policy in
-- words: *"Three factories, no setters"*. That policy is right for the target
-- coordinates (a link that silently changes which file it points at is worse
-- than one that is deleted and remade) and it was applied to this column by
-- being applied to all of them.
--
-- So the Java change is where the decision actually lives, and this migration
-- exists for the half the database DOES have to answer: who changed it, and
-- when.
--
-- -----------------------------------------------------------------------------
-- 2. TWO NULLABLE AUDIT COLUMNS, AND NULL IS AN ANSWER RATHER THAN A GAP
-- -----------------------------------------------------------------------------
--
-- Both are nullable with no default and no backfill, which is deliberate on
-- every count:
--
--   * NULL means **never relabelled since it was created**, which is true of
--     every row that exists today and is a different fact from "relabelled by
--     the person who created it, at the moment they created it". Defaulting
--     `updated_at` to `created_at` — the tempting one-liner — would state that
--     second fact about several thousand rows it is false of, and no later
--     reader could tell the fabricated ones from the real ones.
--
--   * `updated_by` is nullable for `created_by`'s reason, one line up in V1:
--     an actor can be a token or a job, and a user row can be removed while
--     its traces stay. It carries no FK to `app_user` for the same reason
--     `created_by` carries none.
--
-- Adding a nullable column with no default is metadata-only in Postgres — no
-- table rewrite, no lock held for the scan, so no expand-contract phasing is
-- needed for a table this size or any size.
--
-- ONE LOGICAL CHANGE, NO DML. There is no backfill in this file and there must
-- not be: the correct value for existing rows is the NULL they get.
-- =============================================================================

ALTER TABLE doc_link
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN updated_by UUID;

COMMENT ON COLUMN doc_link.updated_at IS
    'When the relationship was last corrected. NULL means never — the row still says what it said '
    'when it was created, which is a different fact from "corrected at creation time" and is why '
    'this is not defaulted to created_at (V48).';

COMMENT ON COLUMN doc_link.updated_by IS
    'Who last corrected the relationship. Nullable for created_by''s reason: an actor may be a '
    'token or a job, and no FK to app_user, so removing a user does not remove the trace (V48).';

-- The target coordinates stay immutable and that is enforced in Java, not here
-- — see section 1. A comment on the table rather than a trigger, because a
-- trigger would be a second implementation of a rule the entity already states
-- and the two could disagree.
COMMENT ON COLUMN doc_link.relationship IS
    'What the source claims about the target: VERIFIES is counted as coverage, DERIVED_FROM and '
    'REFERENCES are not. The ONLY field on this row that may be updated after creation (V48) — '
    'the target coordinates may not, because a link that silently changes what it points at is '
    'worse than one that is withdrawn and remade.';
