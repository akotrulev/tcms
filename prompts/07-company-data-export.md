# Slice 7 — Company data export

**Lane:** backend / operator CLI · **Migration:** none · **Depends on:** Slices 3 and 5 (their columns)

---

## Prompt

```text
Implement **Slice 7 — Company data export** from `docs/plans/2026-08-18-unplanned-gaps.md`.

**First act: run `ep-writing-plans` on this slice alone** and save it as
`docs/plans/2026-08-18-gap-7-company-data-export.md`. Then execute with `ep-executing-plans`.

Read first: `docs/agent-context/be-java.md` and **`docs/specs/2026-07-30-tcms-erd.md`** — you are
about to read every table, so the entity design is the map. Then the Slice 7 section of the master
plan. **`/be-java`**.

**What this slice is:** companies are created by exactly one path — `provision-company` — and
there is no way out. No offboarding, nothing to hand a client who leaves, nothing for a GDPR
request. Add an export.

**Shape: an operator CLI command, not an HTTP endpoint.** `export-company --slug=acme --out=...`.
The act is an operator's, it is long-running, and it must be possible without a browser.

The registration point is
`backend/src/main/java/com/tcms/provisioning/OperatorCommands.java`, and the three existing
commands are the pattern to mirror — each is a `*Command` + `*Result` (+ `*Service`) triple:
`ProvisionCompanyCommand`, `GrantInstanceAdminCommand`, `IssueInvitationCommand`, all in
`backend/src/main/java/com/tcms/provisioning/`. Follow that shape; do not invent a fourth.

**Run it after Slices 3 and 5** so it exports the columns they add rather than needing a second
pass.

**Handoff:** next free § from `docs/HANDOFF.md`. Orientation cache in this slice's own commit.
```

---

## The three traps this slice turns on

**1. The whole export must run inside one `CompanyContext` and one read-only transaction.**
A table read outside context returns **zero rows**, silently — fail-closed is the design. An export
that opens context per table, or drops out of it partway, produces a short archive that looks
complete. Use the scope guard (`try (var ignored = CompanyContext.open(id))`), never a setter.

**2. Blobs are on the filesystem, not in Postgres.** Evidence, report artifacts and attachments live
behind the `storage` module's ports. An export that walks only tables produces a manifest whose
references point at bytes it did not include — which is worse than not exporting, because it looks
whole.

**3. Decide what is redacted, and write the decision down.** Password hashes, token secrets and the
SSO client secrets (`SecretCipher` exists for a reason) must not leave in the clear. Record the
choice and its reason in the handoff the way this repo's plans do — a future reader will otherwise
assume the omission was an oversight.

## Standing rules that bind this slice

- **Tests run on `shanks`** — `~/bin/tcms-remote backend ./gradlew build`.
- **Acceptance gate:** export a seeded company, assert per-table row counts match a direct count,
  and assert **company B's rows are absent**. The second half is the one that matters.
- The command runs in the same image as the backend
  (`docker compose run --rm backend export-company ...`) — check it works there, not only from a test.
- Backend mutation-check rows into the queue; run nothing (§65).
