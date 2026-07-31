# §21. Backend — the default install stops reporting itself DOWN (2026-07-31)

Run through the **`be-java`** skill (agent definition + `ep-systematic-debugging`), **Mode A — fix**.
Closes §20's *"found, not fixed"*. **One line of production configuration; no Java changed.**

### The chain, verified against Boot's own source rather than reasoned about

1. `compose.yaml` writes `SPRING_MAIL_HOST: ${SMTP_HOST:-}` and `.env.example` ships `SMTP_HOST=`
   empty, so the container receives the property **present and empty**.
2. Boot's `MailSenderAutoConfiguration` gates on `@ConditionalOnProperty("spring.mail.host")`, which
   matches a property that is *present* — **an empty string is present** — so a `JavaMailSender` is
   built with no host.
3. `MailHealthContributorAutoConfiguration` is `@ConditionalOnBean(JavaMailSenderImpl.class)`, so the
   indicator registers because that bean exists.
4. `MailHealthIndicator.doHealthCheck` calls `testConnection()`; an empty host resolves to localhost,
   so the component is DOWN with `"location": ":1025"` — the exact string §20 captured live.
5. The aggregate takes the **worst** of its components → `/actuator/health` = **503**, while both
   probe groups stay UP because they contain only their own indicators. That is why the container
   healthcheck (`/actuator/health/readiness`) was green the whole time and nobody noticed.

### The fix, and why it is a policy decision rather than a workaround

`management.health.mail.enabled: false` in `application.yml`, with the reasoning in the file. Three
arguments, in order of weight:

1. **A mail outage is deliberately survivable here.** `SmtpInvitationMailer` swallows send failures and
   `InvitationMailTest` pins that ("a dead relay does not undo the company it was announcing", §18's
   mutation survivor). An indicator that turns a *tolerated* failure into an aggregate DOWN
   contradicts a decision the suite already enforces.
2. **DOWN is an instruction, not a note** — an orchestrator restarts or depools the container. Neither
   action can reach an SMTP relay, so the signal is **wrong**, not merely noisy.
3. **`testConnection()` runs on every poll**, opening a real SMTP connection each time. At the image's
   30s `HEALTHCHECK` interval that is ~2,900 connections a day to a third-party relay — a shape relays
   rate-limit. This argument holds *even when a relay is configured*, which is why the switch is
   unconditional rather than conditioned on the host being set.

**Why the application and not `compose.yaml`:** compose interpolation cannot omit a key conditionally,
and any other templated deployment produces the same empty value. The app is the only place that
covers every caller. The rejected alternative — an `EnvironmentPostProcessor` stripping a blank
`spring.mail.host` before auto-configuration sees it — fixes the root cause properly but is a lot of
clever machinery for one property, and it would silently change behaviour for anything else reading it.

### What changed

| Path | Change |
|---|---|
| `backend/src/main/resources/application.yml` | `management.health.mail.enabled: false`, plus the three arguments above and the note that the unused empty-host `JavaMailSender` is **known, not missed**. |
| `backend/src/test/java/com/tcms/ops/HealthReportTest.java` | **New, 3 assertions.** The aggregate is 200/UP · no `mailHealthContributor` bean · **a canary** asserting an empty host *still* builds a `JavaMailSender`, so if a future Boot version rejects a blank value the test fails and says the config line is now dead. Also re-pins §18's fix: the mailer must still be the unconfigured fallback. |

### Why 177 green tests could not see it

`EndpointAccessTest` already asserted `/actuator/health` answers **200** — and passed, because the
default test context sets **no** `spring.mail.host` at all, so no indicator is ever registered.
`InvitationMailTest` sets a real host pointing at GreenMail, so its indicator is genuinely UP.
**Neither context used compose's spelling of "no relay" — the property present and empty — and that is
the only spelling that fails.** The gap was a missing *configuration*, not a missing assertion, which
is a fourth species alongside §19's "harness weaker than the deployment": the harness was not weaker
here, it was configured differently from every real install.

### Gate evidence — run, not just built

`./gradlew build` clean, **180 tests** (177 + 3). The two new assertions were watched **fail first**
against the unfixed config, for the right reason: `expected: 200 but was: 503`, and the
`mailHealthContributor` bean present.

Then proved against the live stack with `.env`'s `SMTP_HOST=` empty — the defect's own condition:

| Assertion | Result |
|---|---|
| `GET /actuator/health` | **HTTP 200**, `{"status":"UP","groups":["liveness","readiness"]}` |
| Components with `SHOW_DETAILS=always` (§20's own repro command) | `db` · `diskSpace` · `livenessState` · `ping` · `readinessState` · `ssl` — all UP, **no `mail` component at all** |
| Container healthcheck | `Up (healthy)` |
| `provision-company` on the live stack | the **"No SMTP relay is configured"** WARN still fires, the link still prints, no stack trace — §18 defect 3's fix survives |

### Defect class swept — instance-only

The class is *"an empty string is present, so a presence gate reads unconfigured as configured."*
Both sides checked mechanically rather than by reading: **`SPRING_MAIL_HOST` is the only variable in
`compose.yaml` with an empty default (`:-}`)**, the only other property gate in main code is
`MailConfiguration`'s already-hardened `@ConditionalOnExpression(… isBlank())`, and there are **no
custom health indicators**. Nothing else to fix.

### Deliberately not done

- **The unused `JavaMailSender` is still in the context** when the host is empty, pointed at nowhere.
  Nothing autowires it (`MailConfiguration` selects the fallback mailer on the same blank check), so it
  is inert — but it is a live landmine for the first code that injects `JavaMailSender` without
  checking. Recorded in `application.yml` and covered by the canary assertion rather than removed,
  because removing it means excluding a Boot auto-configuration conditionally.
- **`compose.yaml` untouched.** Changing `${SMTP_HOST:-}` cannot express "omit the key", and the app
  now tolerates the empty value from any source.
- **No mail monitoring replacement.** Relay reachability is an operational concern with an operational
  home; it is not a readiness gate for an application that works without it.
