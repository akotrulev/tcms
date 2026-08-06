# §108 — The signing key was in the repository, and the line that put it there was the line meant to prevent it

**Date:** 2026-08-06 · **Lane:** backend + deploy · **Migrations:** none · **Contract:** unchanged
**Severity:** the production instance was signing every session token with a string committed to
this repository, from §18 (`dd0e5f2`, 2026-07-31) until today.

Found while verifying §107's deploy fix against the live box — not by looking for it.

## How it surfaced

§107 added `TCMS_JWT_SECRET` to `compose.prod.yaml` because it was required by `application.yml`
and appeared in no production artifact. That was written up as *"either the box supplies it somehow,
or the container could not start"*. The box then answered: it supplies it **nowhere**.

```
$ docker inspect tcms-backend-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -i jwt
(nothing)
$ sed -E 's/=.*/=<set>/' /etc/tcms/tcms.env | grep -i jwt
(nothing)
```

No env var, no `env_file`, no `-D` in `JDK_JAVA_OPTIONS`, no mount — and `tcms-backend-1` was
`Up About an hour (healthy)` on `0.2.1`. A required property with no value and a healthy context is
a contradiction, and resolving it is the whole of this section.

## The defect

`application.yml:81`, from §18:

```yaml
secret: ${TCMS_JWT_SECRET:?a JWT signing secret of at least 32 characters is required}
```

**`${VAR:?message}` is POSIX-shell and Docker-Compose syntax for "fail if unset". Spring has no
such form.** Spring's `PropertyPlaceholderHelper` splits on the first `:` and takes everything
after it as the **default value**. Verified directly against `spring-core-6.2.11`:

```
${TCMS_JWT_SECRET:?a JWT signing secret of at least 32 characters is required}
  -> [?a JWT signing secret of at least 32 characters is required]     length 59
${TCMS_JWT_SECRET}                       -> REFUSED: PlaceholderResolutionException
```

So the effective HS256 signing key, on every deployment that did not set the variable, was that
59-character sentence. It clears `@NotBlank` and `@Size(min = 32)` on `JwtProperties` — the
validation was real, and satisfied. The issuer is defaulted too (`${TCMS_JWT_ISSUER:tcms}`), so the
`iss` check `JwtConfiguration` adds on purpose was no obstacle either. **Anyone who could read this
repository could mint a valid session token for any user on the instance.**

## Why nothing caught it, which is the part worth keeping

Four independent safeguards were present and all four were satisfied:

| Safeguard | Why it passed |
|---|---|
| The comment above the line | Stated the correct *intention* — "No default, deliberately: a signing key in the repository is a forged session for anyone who can read it". True as intent, false as description. |
| `@NotBlank @Size(min = 32)` on `JwtProperties.secret` | The accidental default is 59 characters. |
| The 1000+ integration tests | `IntegrationTestBase:107` sets `tcms.auth.jwt.secret` via `@DynamicPropertySource`, which **replaces the placeholder outright** — so no test has ever evaluated the expression that ships. |
| Startup on the real box | It started. That was the symptom, read for a year as the absence of one. |

`JwtProperties`' own javadoc predicted the outcome exactly: *"a defaulted signing key is … the kind
of default that survives all the way to production because nothing ever fails because of it."* It
was right about the mechanism and wrong about only one thing — that the code did not have one.

**The general lesson is the working agreement's, unchanged and now paid for twice:** *a
catalogue-wide invariant needs a computed check, not a careful reading.* Every safeguard above
checks a *resolved value*. None checked the *shipped text*. The one that would have caught it is the
one nobody writes, because reading the line makes its meaning obvious — and the meaning was wrong.

**The specific lesson is narrower and worth stating on its own:** this repo writes both compose
files and Spring config, `${VAR:?msg}` is correct in one and silently wrong in the other, and they
sit four directories apart. `compose.prod.yaml` uses `:?` correctly on eight variables.

## What changed

| Path | Change |
|---|---|
| `backend/src/main/resources/application.yml` | `${TCMS_JWT_SECRET}` — no colon. An unresolvable placeholder throws at bind time and the context does not start. The full mechanism is written into the file so the next reader does not re-derive it. |
| `backend/src/test/java/com/tcms/config/ConfigConventionTest.java` | **new** — 3 tests over the *source text* of `application*.yml`: no `:?` or `:-` in any placeholder, the JWT secret spelled with no default, and no profile reintroducing one. Boots no context, needs no database, runs in milliseconds. Comments are stripped first, because `application.yml` legitimately quotes `${SMTP_HOST:-}` when explaining what compose writes. |
| `backend/src/test/java/com/tcms/IntegrationTestBase.java` | the comment that asserted the false belief now records why the suite could not have caught this. |

**The negative case was run**: restoring the old line fails all three tests; the fix passes all
three. A convention test that has never been seen to fail is a decoration.

## What the operator must do — this is not closed by the code change

1. **Rotate the secret. The current key is public.** `openssl rand -base64 48` into
   `/etc/tcms/tcms.env` as `TCMS_JWT_SECRET=…`. §107 already made `compose.prod.yaml` require it,
   so the next deploy fails at `docker compose config` until this is done — deliberately.
2. **Every token issued before rotation must be considered forgeable.** Rotation invalidates them
   all; users are logged out and lose nothing else.
3. Two independent refusals now exist (compose interpolation, and Spring bind), which is the point:
   the single point of failure was a single point.

## Also on the record

`spring.datasource.password` is `${DB_PASSWORD:tcms}` — a defaulted database password, same family,
lower severity. It is **not** exploitable as deployed: the box sets `DB_PASSWORD`, Postgres is
host-native and listens only on loopback and the pinned bridge, and `pg_hba` requires
`scram-sha-256`. Left as-is rather than swept into this section, because changing it is a decision
about the local-development loop (`compose.yaml` already requires the variable) and not a fix to
this defect. Named here so it is a choice rather than an oversight.

`ConfigConventionTest` covers it for the class, not the instance: the rule it enforces is about
*syntax that does not do what it appears to*, and `${DB_PASSWORD:tcms}` does exactly what it appears
to.
