# §0. IMPORTANT — Use the installed plugin skills

This project is being built with the **`fe-react`, `be-java`, `data`, `qa-java`, `devops` and `business-analysis` plugin skills**. They are not optional colour — the work so far follows them, and continuing without them will produce inconsistent output.

**At the start of any new session:**

1. **Frontend work** → invoke the `fe-react:fe-react` skill (or `Skill` tool with `fe-react:fe-react`). It loads the agent definition at
   `~/.claude/plugins/cache/skills-library/fe-react/<hash>/frontend/react/agents/fe-react.md`
   and runs a **phased pipeline**. Read the phase table there and load **only that phase's `SKILL.md` files** before producing anything they cover. Skills live at
   `~/.claude/plugins/cache/skills-library/fe-react/<hash>/frontend/react/skills/<name>/SKILL.md`.
   - Phase 0–1 are **already done** (see §4). Next session enters **Phase 2 — Feature build**, which requires loading:
     `component-writer`, `routing`, `forms`, `data-fetching`, `test-ids`, `ui-rules`, `responsive-design`, `animation`, `design/ux-writing`.
   - The agent runs **inline**, not as a subagent, unless asked otherwise.
2. **Backend work** → `be-java:be-java` skill. Also `be-java:be-java-database-code` for JPA/persistence and `data:data-postgresql` / `data:data-migrations` for schema and migration work.
3. **Never improvise a pattern a skill owns.** The skill wins over instinct, and over general guidance, for its domain. If a listed skill is missing on disk, say so and proceed with general rules — never substitute invented guidance.
4. The user's global `CLAUDE.md` requires: **no remote/cloud planning**, and all generated `.md` artifacts saved **into this project** under `docs/plans/`, `docs/specs/`, `docs/audits/` — never to a global directory.
