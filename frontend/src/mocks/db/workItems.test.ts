import { describe, expect, it } from 'vitest'
import { db } from './store'
import { workItems } from './workItems'

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
