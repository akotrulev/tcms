# §60. The clock that out-resolved its database — the carried debt of §58, closed

**Date:** 2026-08-02

§58 left two tests `@Disabled` over a real product defect, on request, to unblock the deploy lane, and
recorded the one-line fix in three places. This is that line, plus the two call sites that would have
silently undone it and the rule that now watches all of them.

## The defect

`TcmsApplication#clock()` returned `Clock.systemUTC()`. Every instant it stamps is written to a
Postgres `timestamptz`, which stores **microseconds**; `Instant` carries **nanoseconds**. So any
value that is both *returned in memory* and *later read back from the database* is **two different
values**, differing in the last three digits — and nothing in the type system says so.

`InvitationService.issueIfPending` is where it surfaced, because it is one of the few methods that
returns the same logical value down two paths:

- it **mints**, and returns the in-memory `now.plus(validity)`;
- an invitation is already outstanding, and it returns `existing.getExpiresAt()` — the Postgres
  round-trip of that same value.

Consequences: `provision-company` printed an expiry that is not the one stored, and a client
comparing `expiresAt` across two calls watched it move.

## Why it took a deployment to find, and a rule to keep fixed

**The defect cannot fail on macOS.** `Clock.systemUTC()` there already yields only microsecond
precision — re-measured this session, **20000/20000 samples landing on a whole microsecond** — so the
round trip is lossless and every local `./gradlew build` was green. Linux `clock_gettime` gives true
nanoseconds. `master` was red on CI for seven consecutive runs and no session noticed, because the
only machine that could see it was the one nobody was looking at.

Then it got worse in a way worth stating plainly: **since §59, CI runs no tests at all**
(`./gradlew build -x test`). The observer that caught this defect no longer exists. That is the whole
argument for the ArchUnit rule below — the next instance of this class has nothing watching for it,
so the check has to be one that fails on the machine the suite is actually run on.

## The test written before the fix, and why it asserts the bean's identity

`ClockResolutionTest` (`com.tcms.ops`) makes three assertions, and the split between them is the
point:

| Assertion | Red before the fix? |
|---|---|
| the bean equals `Clock.tick(Clock.systemUTC(), 1000ns)` | **Yes, on any platform** — the load-bearing one |
| `Clock.tick(base, ofNanos(1))` returns the base clock unchanged | pins the trap below |
| an instant the app stamps survives a Postgres round trip | **No on macOS** — green with the defect present |

The third is the *real* property and the one that actually broke, and it **passed on this machine
with the defect in place** — measured, not assumed. That is why the first exists. Asserting what a
bean *is* rather than only what it does is normally the wrong altitude; here the behavioural
assertion is structurally unable to fail on the only machine that runs it, so it is the wrong tool on
its own.

**The trap the second assertion pins:** `Clock.tick(Clock.systemUTC(), Duration.ofNanos(1))` returns
**the base clock unchanged** — there is nothing to truncate to. It reads like the fix, compiles, and
is exactly the unfixed code. It differs from the working value by one character.

## The sweep — two call sites that bypassed the bean entirely

Fixing the bean fixes nothing for code that never asks it. `Instant.now()` in production, before this
slice:

- **`ExecutionService.record`** — the *identical* defect. It stamps an `executedAt` that is both
  persisted and returned, so a client that records a result and re-reads it gets a different
  timestamp. Same shape as the invitation expiry, in a module nobody had connected to it.
- **`SessionTokens.issue`** — returns `Session.expiresAt` as an in-memory `Instant` while the JWT
  claim beside it is seconds-precision. A different medium, the same family: one value with two
  spellings.

Both now take the injected `Clock`.

## The rule

`ArchitectureRulesTest.nothingCallsNowDirectly` — no production class may call `Instant.now()`,
`LocalDate/LocalDateTime/LocalTime/OffsetDateTime/ZonedDateTime.now()` or
`System.currentTimeMillis()`. The `Clock`-taking overloads (`Instant.now(clock)`) are deliberately
allowed: they take the bean.

**Verified by reintroducing the defect** — `SessionTokens` was reverted to `Instant.now()`, the rule
went red naming it, and the file was restored. A rule that has never been seen to fail is a rule
nobody has tested.

This is §48's shape again: *a catalogue-wide invariant needs a computed check, not a careful reading*.
The two bypasses were found by grep this session, but grep at review time is exactly what missed them
for the entire life of both modules.

## Gate

`./gradlew build` clean in 2m45s, **1078 tests, 0 failures, 0 skipped**. The zero is the load-bearing
number: it is what says no test is `@Disabled` any more, and it is the count §58 could not report.
Both `@Disabled` annotations are gone
and their comments now record the fix rather than the diagnosis. `secondInviteIsUnchanged` also
restores coverage of `200` / `emailed=false` / `issued=false` / *exactly one outstanding invitation*
on a re-invite — none of which was asserted anywhere else while it was off.

**Not verified on Linux**, and this session cannot verify it there: the fix is a no-op on macOS by
construction, so a green local suite is consistent with both the fixed and the unfixed clock. What
this machine *can* prove is that the bean is now the ticking one, and that is what the first assertion
does. `docs/plans/2026-08-01-tcms-homelab-target.md` §9 remains the standing proposal for a Linux
gate, and it is now the only thing that would close this loop properly.

## For the next reader

The two things worth carrying out of this:

1. **A local gate that structurally cannot observe a defect class is not a gate for that class.**
   Both times this bit, every local signal was green and honest. When a fix is a no-op on your
   platform, you need an assertion about the *decision*, not about the behaviour.
2. **The handoff index had fallen two slices behind** — §59 shipped with no section at all. Written
   this session as `59-issue-invitation-and-the-undeliverable-link.md`; the finding is recorded at
   the end of it.
