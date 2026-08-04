# §0. IMPORTANT — Use the installed plugin skills

This project is being built with the **`fe-react`, `be-java`, `data`, `qa-java`, `devops` and `business-analysis` plugin skills**. They are not optional colour — the work so far follows them, and continuing without them will produce inconsistent output.

**At the start of any new session:**

0. **Read the orientation cache FIRST — `docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md`.**
   They are the `ep-agent-orientation-cache` protocol's output: the version lane, the layout, and —
   the part worth the most — a **seam list** recording what each shipped slice extracted, why, and the
   defect it prevents. That is the fastest path to "what already exists that I must reuse rather than
   re-derive", and it is where a slice's `#confirmed` findings live between sessions.
   - **Read it before `HANDOFF.md`'s narrative and before the raw DDL.** The narrative records what
     each slice *did*, which over-represents finished work (the same reason `docs/audits/` §1 gives
     for not deriving from it); the cache records what the code *is*.
   - **Validate, don't inherit.** Check its `Baseline commit:` header against `git log` and read the
     delta. A cache whose header lags is normal — §76 updated the body and not the header — so trust
     the body over the header, and re-derive anything a commit since the baseline could have moved.
     Its claims are marked `#confirmed`; a claim that decides your slice's shape still gets one grep.
   - **Update it before you finish**, in the same commit as the slice. `fe-react.md` was last written
     at §70 and went stale across five frontend-touching slices (§72–§76) because only the backend
     half of that habit stuck.
1. **Frontend work** → invoke the `fe-react:fe-react` skill (or `Skill` tool with `fe-react:fe-react`). It loads the agent definition at
   `~/.claude/plugins/cache/skills-library/fe-react/<hash>/frontend/react/agents/fe-react.md`
   and runs a **phased pipeline**. Read the phase table there and load **only that phase's `SKILL.md` files** before producing anything they cover. Skills live at
   `~/.claude/plugins/cache/skills-library/fe-react/<hash>/frontend/react/skills/<name>/SKILL.md`.
   - Phase 0–1 are **already done** (see §4). Next session enters **Phase 2 — Feature build**, which requires loading:
     `component-writer`, `routing`, `forms`, `data-fetching`, `test-ids`, `ui-rules`, `responsive-design`, `animation`, `design/ux-writing`.
   - The agent runs **inline**, not as a subagent, unless asked otherwise.
2. **Backend work** → `be-java:be-java` skill. Also `be-java:be-java-database-code` for JPA/persistence and `data:data-postgresql` / `data:data-migrations` for schema and migration work.
3. **Never improvise a pattern a skill owns.** The skill wins over instinct, and over general guidance, for its domain. If a listed skill is missing on disk, say so and proceed with general rules — never substitute invented guidance.

3a. **A plugin enabled in `.claude/settings.json` may still not be INVOKABLE, and §82 hit this.**
   That session's registry loaded only the user-scope plugins (`fe-react`, `devops`,
   `business-analysis`, `qa-dotnet`, `be-dotnet`); the three enabled at **project** scope —
   **`be-java`, `data`, `qa-java`** — did not register, so `Skill be-java:be-java` answered
   **`Unknown skill`** while the plugin was correctly defined in `marketplace.json` and fully
   present on disk.

   **Check, do not assume, and do not silently improvise if it fails.** The files are the fallback
   and they are authoritative:

   ```
   ~/.claude/plugins/cache/skills-library/be-java/<hash>/backend/java/agents/be-java.md
   ~/.claude/plugins/cache/skills-library/be-java/<hash>/backend/java/skills/<name>/SKILL.md
       # pattern-guide · pattern-writer · database-code · code-review · auth0 · cognito
   ```

   `Read` the agent definition and follow it as operating instructions — the phase table, the
   brownfield orientation pass and the output contract all still apply. Say in one line that the
   plugin did not register and that you are reading it from disk, so the difference is visible in
   the transcript rather than looking like the skill was skipped. A `/plugin` install at **user**
   scope, or a fresh session, is the real fix.
4. The user's global `CLAUDE.md` requires: **no remote/cloud planning**, and all generated `.md` artifacts saved **into this project** under `docs/plans/`, `docs/specs/`, `docs/audits/` — never to a global directory.
