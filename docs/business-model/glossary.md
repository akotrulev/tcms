# Glossary

**Created:** 2026-08-03 · canonical terms for the business model.
Authoritative source for names/scopes: `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`
— referenced here rather than restated in full.

- **Instance** — one deployment of the product, run by an operator.
- **Company** — a separate client organisation; the hard isolation boundary. Never call this a
  "tenant" in business-facing text — the product's own vocabulary is "company."
- **Project** — one whole client product or engagement under test. **Not** a feature area — a
  common misreading the spec explicitly warns against. "Payments" is a Feature or a suite
  folder, never a project.
- **Suite** — a folder in a project's test-case tree.
- **Feature (classification)** — a project-owned, hierarchical classification axis, distinct
  from a suite folder, e.g. `Payments › Refunds › Partial refund`.
- **Test Type / Test Level / Execution Phase** — company-wide classification vocabularies,
  shared across a client's projects so they stay comparable to each other.
- **Display key** (`TC-1`, `TS-1`, `TP-1`, `TR-1`, `DOC-1`) — a human-facing, company-scoped
  sequential id. Never appears in an API path (only the UUID does), specifically so it can't be
  used to guess or enumerate content.
- **Lifecycle status** (of a case or a suite) — DRAFT / IN_REVIEW / APPROVED / DEPRECATED. See
  ENT-001.
- **Plan status** — DRAFT / IN_REVIEW / APPROVED / ACTIVE / CLOSED. See ENT-002. Distinct from a
  case/suite's lifecycle status — a plan additionally has an *execution* phase the others don't.
- **Run status** — NOT_STARTED / IN_PROGRESS / COMPLETED / ABORTED. See ENT-003. "Closed" means
  COMPLETED or ABORTED; there is deliberately no separate "CLOSED" value.
- **Review standing** — OUTSIDE_REVIEW / IN_REVIEW / APPROVED; the review record's own read on a
  subject, derived from (not independent of) the subject's own lifecycle/plan status. See
  ENT-004.
- **Grant** — a role assignment to a user or a team, at SYSTEM, COMPANY, or PROJECT scope. See
  DEC-001.
- **Qualifier** (ALL / OWN / PLAN_SCOPE) — restricts *which rows* a held permission covers, not
  whether it's held.
- **Company Member baseline** — the automatic, non-assignable, non-revocable `company:read`
  every member gets on joining a company.
- **Automation Service** — the non-human role attached to a CI/API token, never a person.
- **Evidence** — a file (typically a screenshot) attached to a recorded execution result.
- **Artifact** — a registered reference to an external document relevant to a project or one
  plan (not a document store in itself — see AREA-006's open question).
