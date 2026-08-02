-- =============================================================================
-- V20 — one name per parent per dimension, among the values that are still live
--
-- `classification_value` has had no uniqueness of any kind since V1, and until
-- now that cost nothing: the only writer was `tcms_seed_company_defaults`, which
-- guards each insert with its own `WHERE NOT EXISTS (… AND name = v_value)` and
-- therefore enforced the property it needed by hand, once, in one place.
--
-- The vocabulary editor is the second writer, and a controlled vocabulary whose
-- values are not distinguishable is not a controlled vocabulary. Two Features
-- both called "Payments" under the same parent produce a picker with two
-- identical checkboxes, a case list facet that splits one client's feature
-- across two rows, and a coverage number that is right about neither.
--
-- -----------------------------------------------------------------------------
-- WHY THE PREDICATE IS PER-PARENT AND NOT PER-DIMENSION
-- -----------------------------------------------------------------------------
--
-- The Feature dimension is hierarchical, and handoff §68 turned on exactly this:
-- two leaves under different parents may legitimately share a name — "Refunds"
-- under Payments and "Refunds" under Billing are two different things, which is
-- the whole reason the case detail had to serve classification value IDS rather
-- than the names it was already serving. A per-dimension unique index would
-- refuse the second one and make the tree unable to express a taxonomy the
-- product already renders.
--
-- COALESCE against the nil UUID rather than a plain `parent_id` column, for the
-- reason V1 gives on `classification_dimension_key_idx`: NULL is not equal to
-- NULL, so roots — every value of the three flat vocabularies, and a Feature
-- tree's top level — would be exempt from the constraint entirely. That is the
-- silent half of this defect class: the uniqueness would hold everywhere except
-- the level where a duplicate is most visible.
--
-- -----------------------------------------------------------------------------
-- WHY `lower(name)` AND WHY `WHERE deleted_at IS NULL`
-- -----------------------------------------------------------------------------
--
-- Case-insensitive, because "Payments" and "payments" as siblings is a duplicate
-- in every sense a human uses the word, and a vocabulary that admits both hands
-- the next author a coin toss on every case. `citext` would say the same thing in
-- the column type, and is deliberately NOT used: `name` is display text elsewhere
-- in this schema too, and changing its type to make one index case-insensitive
-- changes every comparison anyone ever writes against it, including ones that
-- want the case. The index is the narrower statement.
--
-- PARTIAL, because retiring a value is a soft delete — the row survives so a past
-- run still resolves what it was classified as (see `classification_value`'s
-- `deleted_at` and handoff §68's bound). Without the predicate a retired
-- "Payments" would permanently block a new one, so the only way back would be to
-- resurrect the retired row, and a resurrected value silently re-attaches itself
-- to every case that carried it before it was retired. Retiring must be a decision
-- a client can reverse by making a NEW value.
--
-- -----------------------------------------------------------------------------
-- THIS IS A BACKSTOP, NOT THE CHECK
-- -----------------------------------------------------------------------------
--
-- `ClassificationValueEdit` refuses a duplicate inside the write transaction and
-- answers it as the field error it is. This index closes the window that check
-- cannot: two administrators submitting "Payments" at the same instant both see
-- no row and one of them loses at the constraint. The loser gets the same 400 on
-- the same field, via `ProjectController#onKeyRace`, which already exists for the
-- identical race on `uq_project_key`.
--
-- `company_id` leads, per ERD §0.1 point 3 and `SchemaConventionTest` — which
-- would fail the build on this migration if it did not.
-- =============================================================================

CREATE UNIQUE INDEX classification_value_sibling_name_idx
    ON classification_value (
        company_id,
        dimension_id,
        COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'),
        lower(name))
 WHERE deleted_at IS NULL;

COMMENT ON INDEX classification_value_sibling_name_idx IS
    'One live value per (dimension, parent, case-insensitive name). Partial because retiring is a '
    'soft delete and a retired name must not block a new value; per-parent because a hierarchical '
    'dimension legitimately repeats a leaf name under different parents (handoff §68).';
