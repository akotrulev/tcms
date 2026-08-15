# The org chart — in plain language

**Companion to `2026-08-15-org-chart.md`.** Same plan, simpler words. It adds no fact the plan does
not contain; where it says "see §N", that is the section of the plan with the detail and the
evidence. If the two ever disagree, the plan is right and this file is the bug.

---

## What we are building

A single screen that answers: **who works here, which team are they in, and what are they allowed to
do on which project?**

It looks like a grid. People down the side, projects across the top, and in each square the role
that person has on that project — "Test Lead", "Viewer", and so on. Beside the grid, the team
structure (who is in which team, who leads it), and a small panel listing the gaps: people in no
team, teams with no leader, people with no project work.

It is **read-only**. You cannot change anything from it. Everything it shows can already be edited
on the existing Teams & roles screen.

## What we are deliberately not building

- **No "who reports to whom".** No manager column, no org tree of bosses. The system has never
  stored that information and this plan does not add it. What we chart is the structure that already
  exists: company → teams → people → projects.
- **No job titles, seniority, start dates or "50% allocated".** None of it is stored today, and any
  of it would be free text nobody keeps up to date.
- **No photo uploads.** Avatars already work if a URL is set; building an image pipeline is a
  separate job.
- **No editing of teams from this screen.** Creating, renaming, moving and deleting teams already
  works elsewhere. Two places to do the same edit is two places to get it wrong.

(Plan §2 has the full list, including who would own each one if it is ever wanted.)

---

## The one decision we need from you

**Who is allowed to see what?**

The short version of our recommendation:

- **Everyone in the company sees every person.** Names, email, which teams they are in, who leads
  each team. This is not a change — the system already works this way today, and the original design
  document says the org chart should be readable by any member of the company.
- **The project columns are your own.** You only see columns for projects you already have access
  to. A project you cannot open does not appear — not as a column, not as a name, not even as a
  count of "3 more". Two colleagues open the same chart, see the same people, and see different
  grids.
- **Administrative details stay hidden.** "When did this person last log in" is only shown to
  company administrators, exactly as it is today on the existing people list.

**Why the columns are clipped:** in this product, being a member of a company gives you *zero*
automatic access to its projects. Projects are whole client engagements, so which ones exist is
confidential. There is already a rule in the code doing precisely this for teams — a comment there
says, in so many words, that the org chart must not become a way around that rule. We are following
it, not inventing it.

**The screen will say so out loud.** A permanent line — *"Only projects you can open are shown
here."* — shown to everyone, including administrators who can see everything. If it only appeared
when something was hidden, its presence would itself tell you that hidden projects exist. That is
the disclosure we are trying to avoid.

**The question we cannot answer for you** is whether seeing a person's *role* on a project you have
access to is fine. Today the system already shows you a *team's* role on such a project. Doing it
per person is new. We recommend allowing it — the original design calls it "the single most useful
piece of information on the chart" — but it is a confidentiality call, so it is yours. (Plan §14,
question Q1. Two smaller questions of the same kind, Q2 and Q3, are there too.)

**Nothing else starts until those three questions are answered.** Building the detail on an
unapproved privacy boundary is the expensive mistake here: the wrong default ships silently, looks
right to whoever built it, and gets discovered by a client whose staffing appeared on another
client's screen.

---

## Three things the earlier survey got wrong, and why it matters

1. **We thought only administrators could see people and teams today. They can't — everyone can.**
   The administrator-only permissions control *changing* teams and people, not *reading* them. So an
   administrator-only org chart would actually take something away that already works.
2. **The "don't leak other clients' projects" rule already exists in the code**, on the closest
   comparable screen, with a comment naming this very feature. We are extending it to one more
   place, not writing a new policy.
3. **A very similar decision was already taken and written down** when the team board was built: an
   org chart is open to every member, there is no permission to gate it on, and inventing one would
   create a lock nobody can unlock. We follow that.

(Plan §1, with the exact file and line for each.)

---

## How it will be built, in outline

**One new read-only address on the server** (`GET /org-chart`) that returns the whole picture in one
go: the teams, the people, and the project columns you are allowed to see.

**Why one new address rather than adding fields to an existing one:** the existing "list the people"
call is used by several other screens and two other programs (the command-line tool and the AI
integration). Changing its shape means changing everything downstream. This way **no existing data
shape changes at all** — which removes the single biggest source of accidental breakage in this
codebase.

**No database change.** Everything the chart shows can be worked out from information already
stored. We argued the other side too — a sort order for teams, a pre-computed table for speed, an
extra index — and none of them earns its keep (plan §6). One task in the list is a database
specialist checking the speed question with real measurements rather than us guessing; if they say
an index is needed, it gets added at that point, not now.

**The maths happens on the server, not in the browser.** This is not a preference. The browser
literally cannot work the answer out: the only list of "who has what role" it can request is
filtered down to the grants *you personally could revoke*, and an ordinary member can revoke
nothing, so they receive an empty list. On top of that, the codebase already has a written warning
about this exact mistake — working roles out with a simple query misses everyone who got their
access through a team, and everyone who is a company administrator, and it does so silently, while
returning a plausible-looking answer.

**A new screen at its own address** (`/org-chart`), sitting beside the Teams & roles screen rather
than underneath it. Underneath would put it at an address containing the word "admin", which would
tell every reader it is an administrators-only page when it is the opposite. It reuses the standard
building blocks the rest of the app uses for loading states, monospace identifiers and labels, so it
looks and behaves like everything else.

**One small piece of tidying is required, not optional.** The existing Teams & roles screen has
clever logic for drawing the team tree — including handling teams whose parent has vanished, which
it deliberately shows rather than silently hides. The org chart draws the same tree. That logic gets
moved into one shared place so the two screens cannot disagree. If it were copied instead, the two
would eventually differ, and they would differ precisely on the data that is already broken.

---

## Safety checks it has to pass

This product serves several client companies from one installation, so keeping one client's data
invisible to another is the invariant everything else rests on. The plan lists the checks (§8.4).
In plain terms:

- The company is taken from who you are signed in as, never from anything in the web request.
- Every database query names the company explicitly, and the database itself enforces it a second
  time as a backstop.
- A specific, known trap: if a database access class forgets one annotation, every read silently
  returns **nothing** instead of failing loudly. There is a test that fails the build if any class
  forgets it. This plan says so explicitly, because the failure is invisible.
- A test proves that data from a second company never appears in the first company's chart.

---

## The work, in order

Roughly a dozen tasks, each assigned to the specialist that would do it (plan §10 has the full table
with the exact commands each must pass).

1. **Get the three privacy questions answered.** Everything waits on this.
2. **Write the contract** — the formal description of the new address and its data, plus regenerate
   the types both front-end code bases read. This also fixes an incorrect sentence we found in that
   contract (see below).
3. **Backend, part one** — generalise two existing database queries so they can answer for many
   people at once instead of one, and put the tricky "which role wins" rule in a single shared place.
   This is the riskiest task because it touches something already on screen, so it is done on its
   own.
4. **Backend, part two** — the new endpoint itself, with the privacy rules applied.
5. **Tests**, including the most valuable one in the whole plan: proving the chart never claims
   somebody has a role on a project they cannot actually open, *and* never leaves out somebody who
   can.
6. **The database speed check.**
7. **Front end** — the shared tree logic, then the data layer, then the screen.
8. **A security pass** proving the hidden things are hidden in the raw response, not just missing
   from the picture.
9. **Manual test cases**, and **the written record** (this project keeps a per-slice history file and
   two "orientation" files that the next person or agent reads first).

Tasks run **one at a time**. This project has an expensive history with parallel work — three
separate losses of committed changes, with no error message and a clean-looking repository
throughout.

---

## Checks to run later, written down now

This project deliberately batches its hands-on verification instead of doing it during each piece of
work. So the plan **writes out the checks** — six things to click through in a browser, eight
deliberate "break it on purpose and confirm the right test fails" experiments — and runs none of
them (plan §11). They get pasted into the two queue files when the work lands.

Two of the break-it experiments are expected to **pass**, and are recorded as gaps rather than
successes. That is on purpose: writing down "nothing would catch this" is more honest than quietly
counting it as covered.

---

## Something we found and did not fix

The API's own documentation contains a sentence saying the org chart is *not* readable by ordinary
members. It is false — it has been readable by every member for some time. It is also the sentence
most likely to be read by whoever next asks this exact question, which is how a wrong answer
propagates.

We have **not** edited it: this plan writes no code and touches no other file. Correcting it is
folded into the contract task, which is already editing that file. There is an automated check in
this codebase that catches this class of mistake — a written claim about permissions that has gone
stale — but it does not recognise this particular phrasing. Extending it would be its own piece of
work and would very likely turn up more than one bad sentence. That is noted, not scheduled.

(Plan §13.)

---

## What could still go wrong

- **The privacy filter gets removed later by accident.** Everything would still work, the screen
  would still look right, and more data would quietly come out. This is why there is a deliberate
  break-it experiment for exactly that line, and why the security check inspects the raw server
  response rather than the picture.
- **The riskiest task breaks something already on screen.** Mitigated by doing it alone, and by
  requiring the existing tests to stay green with no edits.
- **The shared "which role wins" rule gets copied instead of shared.** Then a future change to that
  rule reaches one screen and not the other. Nothing automatic would catch it, which is why the plan
  names it as the defect this work would ship if that one task is skipped.
- **The chart shows stale information right after somebody changes staffing** — which is exactly the
  moment somebody opens it. There are nine different actions that can change it; the plan lists all
  nine and requires a test for each.
- **The automated build does not run the backend tests** on every push, by design, for speed. The
  full run happens overnight. So whoever does the backend tasks has to run it themselves before
  pushing.

(Plan §15.)
