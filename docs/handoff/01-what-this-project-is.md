# §1. What this project is

A **self-hosted, Java-based test case management system** serving **one or more companies from a single instance**, where a **company is a separate CLIENT organisation**. It owns the manual and automated test asset lifecycle: authoring cases, organizing suites and plans, executing runs, ingesting automated results, and reporting quality signals traceable from documentation through to defects.

**The hierarchy, because getting it wrong propagates into every example:**

| Level | What it is | Example |
|---|---|---|
| Instance | One deployment, operated by you | — |
| **Company** | **A separate client organisation.** The isolation boundary. | Acme Corporation |
| **Project** | **A full client project** — an entire product or engagement. *Not* a feature area. | Acme Online Banking · Acme Mobile App |
| Suite tree | Structure within one project | `Onboarding` › `KYC checks` |
| Feature dimension | The feature-level classification axis (§6d) | `Payments` › `Refunds` › `Partial refund` |

So "Payments" is a **Feature** or a suite folder, never a project. A company holds a handful of whole products, not dozens of features.

**Three access tiers** (§6b): Instance Admin (everything, every company) → Company Admin (everything in the companies they're assigned to) → regular user (**only the projects they are explicitly granted** — company membership conveys authentication and *zero* project visibility).

**Explicit non-goals:** not a test runner (it ingests results), not a defect tracker (it links to Jira), **not public multi-tenant SaaS** (companies are provisioned by an operator script, not self-signup — no billing, no custom domains), not a step-definition editor. No cross-company anything.
