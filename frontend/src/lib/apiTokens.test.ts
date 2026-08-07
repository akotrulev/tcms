import { describe, expect, it } from 'vitest'
import type { ApiToken } from '../api/types'
import { tokenScopeLabel, tokenState } from './apiTokens'

/**
 * A credential's state, derived — and the two collapses this file exists to refuse.
 *
 * Neither is visible to the build, and each turns into a wrong control on the row:
 *
 *   1. **Expired is not revoked.** Collapse them and Revoke is disabled on an expired token, so
 *      the one deliberate act left — closing a lapsed credential for good rather than leaving it
 *      one date edit from working — becomes unreachable.
 *   2. **A token with no `projectId` is the WIDE one, not the unset one.** Read as missing, it
 *      renders as a blank cell, and the row with the largest blast radius on the screen is the one
 *      that looks like it has no scope at all.
 */

const BASE: ApiToken = {
  id: 'tok-1',
  name: 'Nightly regression',
  tokenPrefix: 'tcms_abc123',
  ownerId: 'user-1',
  roleId: 'role-automation',
  roleName: 'Automation Service',
  projectId: null,
  expiresAt: null,
  lastUsedAt: null,
  revokedAt: null,
  createdAt: '2026-08-01T09:00:00Z',
}

const NOW = Date.parse('2026-08-07T12:00:00Z')

describe('tokenState', () => {
  it('is live when it has neither expired nor been revoked', () => {
    expect(tokenState(BASE, NOW)).toBe('LIVE')
  })

  it('is live when its expiry is still ahead', () => {
    expect(tokenState({ ...BASE, expiresAt: '2026-09-01T00:00:00Z' }, NOW)).toBe('LIVE')
  })

  it('is expired once the expiry has passed', () => {
    expect(tokenState({ ...BASE, expiresAt: '2026-08-06T00:00:00Z' }, NOW)).toBe('EXPIRED')
  })

  it('is expired at the instant it expires, not a moment later', () => {
    // The boundary is `<=`: a credential is not live at the second it lapses. Off by one here
    // reads as a working token on the screen of the person checking whether it still works.
    expect(tokenState({ ...BASE, expiresAt: new Date(NOW).toISOString() }, NOW)).toBe('EXPIRED')
  })

  it('reports revoked rather than expired when it is both', () => {
    // THE HEADLINE. Revocation is the fact somebody decided; reporting it as merely lapsed
    // suggests that extending the date would bring the credential back, which it would not.
    const token = { ...BASE, expiresAt: '2026-08-02T00:00:00Z', revokedAt: '2026-08-05T00:00:00Z' }
    expect(tokenState(token, NOW)).toBe('REVOKED')
  })
})

describe('tokenScopeLabel', () => {
  it('says a token with no project reaches every project its owner can', () => {
    expect(tokenScopeLabel(BASE, undefined)).toMatch(/every project/i)
  })

  it('names the project when it is one the reader can see', () => {
    expect(tokenScopeLabel({ ...BASE, projectId: 'p1' }, 'Acme Online Banking')).toBe(
      'Acme Online Banking',
    )
  })

  it('falls back to the id rather than claiming the token is unscoped', () => {
    // Reachable: the token list is company-wide and project visibility is not, so a Company Admin
    // can hold a row naming a project absent from their own `/projects`. An unresolved name must
    // never fall through to the wide sentence — that would understate a credential's limits by
    // reporting a pinned token as reaching everything.
    const label = tokenScopeLabel({ ...BASE, projectId: 'p-unseen' }, undefined)
    expect(label).toBe('p-unseen')
    expect(label).not.toMatch(/every project/i)
  })
})
