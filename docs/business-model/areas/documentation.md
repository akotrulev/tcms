# AREA-006 — Documentation

**Created:** 2026-08-03 · **Owned by:** `be-shared-business-documentation`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03

Covers the `documentation` module: project documentation pages (specs, notes) and the artifact
register (links out to external sign-off documents, e.g. a test plan document or a compliance
sheet, scoped to the project or to one plan).

## What this area is for

A place for a project's own written material — specifications, notes, procedures — that lives
inside TCMS and is versioned and published like test content is, plus a lightweight register
of *external* documents relevant to the project or to one specific plan (this is a pointer/
register, not a document store for those references).

## Actors

- **Test Engineer and above** — write and edit pages; the Automation Service and Viewer cannot.
- **Test Lead and above** — publish, unpublish or archive a page (`doc:publish` is a distinct
  permission from writing one).
- **Anyone who can see the project** — reads published documentation and the artifact register.
- **Test Engineer, for artifacts** — may edit artifacts, but only within plan-scoped ones
  (`artifact:edit` is plan-scope-qualified for that role — per the roles spec; not
  independently re-verified against code this session).

## Capabilities

1. **View the page tree.** `GET /projects/{id}/doc-pages` — flat list with `parentId`; `[]` is
   normal for a fresh project, since nothing is auto-seeded.
   `DocPageController.java:65-71` `#observed`.
2. **Read one page in full**, with its content blocks in order. `GET /doc-pages/{id}` —
   `DocPageController.java:81-91` `#observed`.
3. **Create a page.** Always starts as `DRAFT`; publishing is a separate act and a separate
   permission. `DocPageController.java:93-101` `#observed`. Key and path are server-assigned
   (`DOC-n`), and a retitle never rewrites the path. `DocPageController.java:96-98` `#observed`.
4. **Edit a page's title and content, with lost-update protection** (`If-Match`/`lockVersion`).
   `DocPageController.java:119-128` `#observed`.
5. **Publish, unpublish or archive a page.** `PUT /doc-pages/{id}/status` —
   `DocPageController.java:158-165` `#observed`.
6. **Delete a page**, only if it has no child pages and nothing links into it.
   `DocPageController.java:182` `#observed`.
7. **View the artifact register**, either for the whole project or for one plan (with the
   project's own register inherited into a plan's view). `GET
   /projects/{id}/artifacts?scope=&planId=` — `ArtifactController.java:56-83` `#observed`.
8. **Register (add) an artifact reference.** `POST /artifacts` — `ArtifactController.java:85-98`
   `#observed`.

## Business rules

- **Editing a page and publishing it are two different permissions, so a contributor can write
  freely without being able to make it visible/final** — a Test Engineer may save a page and
  then be refused when they try to publish it, which is stated as the intended shape, not an
  inconsistency to fix. `DocPageController.java:158-165` `#observed`.
- **Deleting a page respects the document's own structure**: refused while it still has
  children, or while another page/case still links into it. `DocPageController.java:182`
  `#observed`. (Exact wording/conflict semantics of "links into it" not independently traced
  further this session.)
- **A concurrency conflict on an edit has two distinct causes and the response tells them
  apart**: a stale `If-Match` versus an edit that would remove a content block some test case
  still references. `DocPageController.java:119-128` `#observed`.
- **The artifact register's `scope` query parameter is validated, not defaulted past** — asking
  for a plan-scoped register with no plan id, or a project-scoped register while naming a plan,
  is a 400, never a silent fallback to "the whole project's register."
  `ArtifactController.java:29-34,71-77` `#observed`.
- **Nothing is auto-seeded into either the page tree or the artifact register** for a new
  project — an empty state is a normal, correct state, and inventing starter content for a
  client is treated as the same mistake as inventing their Feature-classification tree.
  `DocPageController.java:65-71`, `ArtifactController.java:56-63` `#observed`.
- **A project that does not exist, is in another company, or is one the caller has no grant on
  are indistinguishable — 404, no body — everywhere in this module**, same rule as every other
  area. `DocPageController.java:36-42`, `ArtifactController.java:36-42` `#observed`.

## Failure behavior

| Situation | Response | Evidence |
|---|---|---|
| Publish without `doc:publish` | 403 | `DocPageController.java:158-165` `#observed` |
| Edit conflict — stale version | 409 | `DocPageController.java:119-128` `#observed` |
| Edit conflict — removing a block a case still links to | 409, distinguishable detail | `DocPageController.java:125-128` `#observed` |
| Delete a page with children or inbound links | Refused (per `problem()` mapping) | `DocPageController.java:182` `#observed` |
| Artifact register `scope=PLAN` with no `planId` (or the reverse) | 400, field error | `ArtifactController.java:71-77` `#observed` |
| Unreachable project on any read | 404, no body | `DocPageController.java:36-42` `#observed` |

## Boundaries / not built

- Templated seeding of a starting document set from a project-settings template is named in the
  design but not found as an implemented capability in this trace. `#inferred`

## Open questions for the business

1. Is the artifact register meant to eventually hold the referenced files themselves, or is it
   permanently just a pointer/register to documents that live elsewhere? Not resolved by this
   trace. `#inferred`
