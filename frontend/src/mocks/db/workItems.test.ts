import { describe, expect, it } from 'vitest'
import { currentUser } from '../fixtures/core'
import { db } from './store'
import { personalBoard, workItems } from './workItems'

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
