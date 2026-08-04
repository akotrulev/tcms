-- V29 — a documentation page may be ABOUT another artifact.
--
-- A test plan has two halves. The executable half is `test_plan`: the milestone, the entry and exit
-- criteria, the suites and cases in scope. The half somebody writes — business requirements, test
-- artefacts, what is in scope and what is out, and the scenario outline — had nowhere to live, and
-- the handed-over example plan is almost entirely that half.
--
-- It lives here, as an ordinary `doc_page`, and the link between the two is these two columns.
--
-- WHY THE LINK IS ON THIS SIDE, AND WHY IT IS OPAQUE
--
-- `test_plan.doc_page_id` is not a column anybody may add: `execution` may depend only on `tenancy`,
-- `identity` and `project` (plan §3, enforced by ArchUnit), and `documentation` is bound by exactly
-- the same rule in the other direction. Neither module may hold a foreign key into the other, so the
-- reference is a type-and-id pair with no FK behind it and nothing joins on it in SQL.
--
-- That is `review.subject_type` / `subject_id` arriving a second time (§76), and it buys the same
-- thing: one page type serves several artifact kinds without any of them being named in this table's
-- structure. `subject_type` admits one value today. It is a CHECK rather than an open TEXT column so
-- that a typo'd kind is refused at the row rather than becoming a document about nothing — invisible
-- to the plan it was written for and to every list that filters by kind.
ALTER TABLE doc_page
    ADD COLUMN subject_type TEXT,
    ADD COLUMN subject_id   UUID;

ALTER TABLE doc_page
    ADD CONSTRAINT ck_doc_page_subject_kind
        CHECK (subject_type IS NULL OR subject_type IN ('TEST_PLAN')),
    -- Both or neither. A subject_id with no type is unreadable — nothing can tell which table the id
    -- names — and a type with no id is a page that claims to be about something it cannot name.
    ADD CONSTRAINT ck_doc_page_subject_paired
        CHECK ((subject_type IS NULL) = (subject_id IS NULL));

-- One live document per subject.
--
-- PARTIAL ON `deleted_at IS NULL`, which is the whole reason it is written this way: a page's delete
-- is soft, so a plain unique index would make a deleted document permanently block its plan from
-- ever having another one — and the failure would arrive as a duplicate-key 500 on the create,
-- naming a row the user cannot see.
--
-- `company_id` leads, per the tenancy rule: every previously-global uniqueness constraint in this
-- schema is scoped by company first. It is redundant with the id's own uniqueness and it is what
-- keeps the rule mechanical rather than case-by-case.
CREATE UNIQUE INDEX uq_doc_page_subject
    ON doc_page (company_id, subject_type, subject_id)
 WHERE subject_id IS NOT NULL
   AND deleted_at IS NULL;

COMMENT ON COLUMN doc_page.subject_type IS
    'The kind of artifact this page documents, or NULL for a free-standing page. Opaque: no FK, and '
    'nothing joins on it — see V29''s header for why the reference cannot live on either side as a key.';

COMMENT ON COLUMN doc_page.subject_id IS
    'The documented artifact''s id. Paired with subject_type by ck_doc_page_subject_paired.';
