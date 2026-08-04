import { describe, expect, it } from 'vitest'
import { currentUser } from '../fixtures/core'
import { db } from './store'
import { personalBoard, teamBoard, workItems } from './workItems'

/**
 * The mock's board generators — and this file exists because of what §81 found in one of them.
 *
 * The review generator did not mirror the server: it drew a card for every case whose
 * `lifecycleStatus` was `IN_REVIEW`, put it in the `IN_REVIEW` column, and assigned it to the case's
 * **owner** — while the server produced no review cards at all, and, once it did, refused to accept
 * the artifact's own submitter as a reviewer. Mock mode therefore showed a reviewer inbox no
 * deployment could produce, to the wrong person, from Phase 1 to §80. Nothing failed: the contract
 * has carried `kind: REVIEW` since §53 on purpose, so both sides typechecked perfectly.
 *
 * `npm run build` cannot catch that class and neither can a page test that supplies its own props.
 * What catches it is asserting the *rules* the generator claims to implement, against the fixture
 * store, here.
 */
describe('The mock board’s review cards', () => {
  const project = db.projects[0]
  const cards = () => workItems(project.id, project.key).filter((item) => item.kind === 'REVIEW')

  it('draws a card only for somebody who was ASKED, never for the artifact’s owner', () => {
    // TC-8 is `IN_REVIEW` and its only review row is REJECTED with no assignees — so the fixture
    // set contains exactly the case the old generator drew a card for and the server never would.
    expect(cards().map((item) => item.linkedKey)).not.toContain('TC-8')
    expect(cards()).not.toHaveLength(0)
  })

  it('files the card in TODO, and names the artifact rather than the review', () => {
    const planCard = cards().find((item) => item.sourceType === 'PLAN')

    expect(planCard?.status).toBe('TODO')
    expect(planCard?.sourceId).toBe(
      db.reviews.find((review) => review.subjectType === 'PLAN')?.subjectId,
    )
    expect(planCard?.allowedTransitions).toEqual([])
  })

  it('expands a team at read time, and drops anybody who has already spoken', () => {
    // The plan review was asked of user 2 by name, of the QA Chapter as a team, and of user 0 by
    // name. User 2 has left a CHANGES_REQUESTED verdict, so the ball is with the author and their
    // card is gone; user 0 is in the QA Chapter AND named directly, which is one request, not two.
    const planCards = cards().filter((item) => item.sourceType === 'PLAN')
    const spoke = db.reviews.find((review) => review.subjectType === 'PLAN')?.participations[0]?.user

    expect(planCards.map((item) => item.assignee?.id)).not.toContain(spoke?.id)
    expect(planCards).toHaveLength(1)
  })

  it('says who asked, because a queue entry with no asker is not a request', () => {
    expect(cards()[0]?.description).toMatch(/^Review requested by /)
  })
})

/**
 * The derived generators' project scope (§83) — the second defect this file's existence caught.
 *
 * `db.runs`, `db.cases` and `db.plans` carry no `projectId`: the contract puts the project in the
 * *path* for all of those endpoints, so there is no field on the wire shape and none in the
 * fixture. The execution and maintenance generators therefore drew every seeded run and every
 * flagged case onto **whichever project's board was asked for** — invisible on the one project the
 * fixtures are dense in, a broken link on the other, and on §83's cross-project board a duplicate
 * of every derived card with a **duplicate React key** to go with it.
 */
describe('Which project a derived card belongs to', () => {
  const repository = db.projects[0]
  const other = db.projects[1]

  it('draws the seeded repository’s cards on the project that owns it', () => {
    const kinds = new Set(workItems(repository.id, repository.key).map((item) => item.kind))

    expect(kinds.has('EXECUTION')).toBe(true)
    expect(kinds.has('CASE_MAINTENANCE')).toBe(true)
  })

  it('draws none of them on a project that does not own the repository', () => {
    // Asserted for both kinds, not for the count: an empty board here would also pass a
    // "fewer cards" check, and this project legitimately has cards of its own (a task).
    const kinds = new Set(workItems(other.id, other.key).map((item) => item.kind))

    expect(kinds.has('EXECUTION')).toBe(false)
    expect(kinds.has('CASE_MAINTENANCE')).toBe(false)
  })

  it('stamps every card with the project it was asked for', () => {
    // The half that was never wrong and must stay right: a card's `projectId` is what `linkFor`
    // routes on since §83, so a card stamped with a project it is not from is a 404 on its key.
    for (const item of workItems(other.id, other.key)) {
      expect(item.projectId).toBe(other.id)
      expect(item.projectKey).toBe(other.key)
    }
  })
})

/**
 * §6l's cross-project personal board (§83) — the mock mirroring `BoardService.personalBoard`.
 *
 * Written against the *server's* rules rather than against what `useMyWork` sends, which is §52's
 * rule and the one this module got wrong for twenty-eight slices.
 */
describe('The mock’s personal board', () => {
  it('keeps only the caller’s own cards', () => {
    const foreign = personalBoard(currentUser.id).filter(
      (item) => item.assignee?.id !== currentUser.id,
    )

    // Including the unassigned: a card with no assignee is nobody's personal work, and the server's
    // filter is `assignee != null && assignee.id == caller` for exactly that reason.
    expect(foreign).toEqual([])
  })

  it('spans more than one project, which is the only thing this board adds', () => {
    // If this ever reads 1, either the fixture lost its second-project task or the aggregate
    // quietly became one project's board — and every other assertion here would still pass.
    const projectIds = new Set(personalBoard(currentUser.id).map((item) => item.projectId))

    expect(projectIds.size).toBeGreaterThan(1)
  })

  it('draws from no project outside the visible set', () => {
    // The visible set is `db.projects`, the same list `GET /projects` answers — one source, so the
    // board cannot show work from a project the switcher will not offer. This is the assertion that
    // reddens if a future generator reads a global fixture instead of a per-project one, which is
    // precisely what the execution and maintenance generators were doing until §83.
    const visible = new Set(db.projects.map((p) => p.id))

    for (const item of personalBoard(currentUser.id)) {
      expect(visible.has(item.projectId)).toBe(true)
    }
  })

  it('is the union of the per-project boards, filtered — not a separate assembly', () => {
    // Pinned because the server assembles once and filters afterwards, and says why: pushing the
    // personal filter down into each generator is four predicates to keep in step, and the one that
    // is forgotten silently shows somebody else's work on a personal board.
    const union = db.projects
      .flatMap((project) => workItems(project.id, project.key))
      .filter((item) => item.assignee?.id === currentUser.id)
      .map((item) => item.id)

    expect(personalBoard(currentUser.id).map((item) => item.id)).toEqual(union)
  })
})

/**
 * §6l's team board (§84) — the mock mirroring `BoardService.teamBoard`.
 *
 * **The pair below is the shape worth copying, and it is the reason a fixture row was added.**
 * Every card in this fixture set used to be assigned to `users[0]` (the signed-in user) or
 * `users[1]`, both of whom are in *Payments Squad*. So an implementation that ignored team
 * membership entirely — or that quietly answered the personal board — passed every assertion
 * anybody would think to write, because the team bound and the personal bound gave the same answer.
 * §84's own backend fixture had to be extended to escape exactly that vacuity, and the frontend's
 * had the same hole.
 *
 * So the assertions come in a pair whose halves each stop the other being vacuous: Payments Squad
 * must hold the signed-in user's work and NOT Priya's, and Checkout Squad must hold Priya's and
 * NOT theirs.
 *
 * *What the fixture cannot express*, stated rather than left to be inferred: the mock has no
 * project the signed-in user is barred from — `db.projects` IS their visible set — so the server's
 * decision that a team board is bounded by the READER rather than by the team is structural here
 * rather than demonstrated. That is §83's recorded limit for the personal board, and it is now
 * load-bearing for two screens. What IS demonstrated is the other bound and the shared visible set.
 */
describe('The mock’s team board', () => {
  // `users[0]` (the signed-in user) and `users[1]` are Payments Squad; `users[2]` and `users[3]`
  // are Checkout Squad. Resolved by name rather than by index so a fixture reorder fails loudly.
  const payments = db.teams.find((team) => team.name === 'Payments Squad')!
  const checkout = db.teams.find((team) => team.name === 'Checkout Squad')!
  /*
    `flatMap` rather than `map(item => item.assignee?.id)`, and `npm run build` is what said so:
    the optional chain yields `Set<string | undefined>`, which compares happily against a `string[]`
    at runtime and is a type error the test runner never sees. The working agreement's rule in
    miniature — the runner proves behaviour, the build proves the assertions are about what they
    claim to be. Nothing on a team board is unassigned, so nothing is dropped by narrowing it.
  */
  const idsOn = (teamId: string) =>
    new Set((teamBoard(teamId) ?? []).flatMap((item) => (item.assignee ? [item.assignee.id] : [])))

  it('keeps only the work of the team’s own members', () => {
    const outsiders = (teamBoard(payments.id) ?? []).filter(
      (item) => !payments.members.some((member) => member.id === item.assignee?.id),
    )

    // Including the unassigned: a card with no assignee is on nobody's team board, which is why the
    // filter is `assignee != null && members.has(assignee.id)` and not a bare lookup.
    expect(outsiders).toEqual([])
    expect(teamBoard(payments.id)?.length).toBeGreaterThan(0)
  })

  it('is a different board for a different team — the half that stops the first being vacuous', () => {
    const priya = db.teams
      .find((team) => team.name === 'Checkout Squad')!
      .members.map((member) => member.id)

    // Checkout Squad holds work Payments Squad does not, and vice versa. Either assertion alone
    // passes against a board that ignored the team; together they cannot.
    expect([...idsOn(checkout.id)].some((id) => priya.includes(id))).toBe(true)
    expect(idsOn(checkout.id).has(currentUser.id)).toBe(false)
    expect(idsOn(payments.id).has(currentUser.id)).toBe(true)
    expect([...idsOn(payments.id)].some((id) => priya.includes(id))).toBe(false)
  })

  it('is not the personal board wearing a team’s name', () => {
    // Payments Squad contains the signed-in user, so its board is a SUPERSET of theirs — equal
    // lengths would mean the team half was never applied, which is the failure mode that looks
    // most correct on screen.
    expect(teamBoard(payments.id)!.length).toBeGreaterThan(personalBoard(currentUser.id).length)
  })

  it('draws from no project outside the visible set', () => {
    // The same assertion the personal board carries, against the same shared `assemble`. It reddens
    // if a future generator reads a global fixture instead of a per-project one — precisely what
    // the execution and maintenance generators were doing until §83.
    const visible = new Set(db.projects.map((p) => p.id))

    for (const item of teamBoard(checkout.id) ?? []) {
      expect(visible.has(item.projectId)).toBe(true)
    }
  })

  it('spans projects, rather than being one project’s list under a new name', () => {
    // Payments Squad's members hold work in both seeded projects. If this ever reads 1, either a
    // fixture moved or the aggregate quietly became one project's board.
    expect(new Set((teamBoard(payments.id) ?? []).map((item) => item.projectId)).size).toBeGreaterThan(1)
  })

  it('narrows to one project when asked, without reaching outside the visible set', () => {
    // §6l's legal `teamId` + `projectId` conjunction. No screen sends it yet; the mock answers it
    // because the day one does is not the day to find out the mock guessed.
    const one = db.projects[1]
    const narrowed = teamBoard(payments.id, one.id) ?? []

    expect(narrowed.every((item) => item.projectId === one.id)).toBe(true)
    expect(narrowed.length).toBeGreaterThan(0)
    expect(teamBoard(payments.id, 'not-a-project-anybody-can-see')).toEqual([])
  })

  it('answers `undefined` for an unknown team — a 404, never an empty board', () => {
    // The distinction the handler turns into a status, kept here so it cannot be lost in a route.
    // `[]` means the board is reachable and has nothing on it; a team that does not exist is a
    // broken link, and a client that could not tell them apart would render "nothing here" for a
    // team id somebody mistyped.
    expect(teamBoard('11111111-1111-4111-8111-1111111111ff')).toBeUndefined()
  })
})
