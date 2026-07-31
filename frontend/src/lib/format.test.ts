import { describe, expect, it } from 'vitest'
import {
  EM_DASH,
  compareReleaseVersions,
  formatAge,
  formatBytes,
  formatDateTime,
  formatDuration,
  formatPercent,
  formatRatio,
  formatSeconds,
  formatTimeOfDay,
  percentOf,
} from './format'

/**
 * The formatters are pure and the module doc explains why they are centralized. What is tested
 * here is only the part that can be *wrong* rather than merely inconsistent — the arithmetic
 * and the ordering. `Intl.DateTimeFormat` is the platform's job, so the date tests assert the
 * shape and the mandatory zone label, not the locale table.
 */

describe('compareReleaseVersions', () => {
  /*
    The bug this function exists to prevent, stated as a test: sorting releases as strings puts
    4.10.0 BEFORE 4.9.0, so a release dropdown offers the wrong "latest" and a history timeline
    reads backwards.
  */
  it('orders 4.10.0 after 4.9.0, where a string compare puts it before', () => {
    expect(compareReleaseVersions('4.10.0', '4.9.0')).toBeGreaterThan(0)
    expect('4.10.0'.localeCompare('4.9.0')).toBeLessThan(0) // the wrong answer, pinned
  })

  it('sorts a realistic release list by precedence', () => {
    const releases = ['4.9.0', '4.10.0', '4.2.1', '10.0.0', '4.9.10', '4.9.2']
    expect([...releases].sort(compareReleaseVersions)).toEqual([
      '4.2.1',
      '4.9.0',
      '4.9.2',
      '4.9.10',
      '4.10.0',
      '10.0.0',
    ])
  })

  it('is antisymmetric and reflexive, so a sort using it is stable rather than order-dependent', () => {
    const pairs: [string, string][] = [
      ['1.0.0', '1.0.1'],
      ['2.0', '10.0'],
      ['1.2.3', '1.2.3'],
      ['1.0.0-alpha', '1.0.0'],
    ]
    for (const [a, b] of pairs) {
      // Summed rather than negated: `-Math.sign(0)` is `-0`, and `toBe` uses Object.is.
      expect(
        Math.sign(compareReleaseVersions(a, b)) + Math.sign(compareReleaseVersions(b, a)),
        `${a} vs ${b}`,
      ).toBe(0)
    }
    expect(compareReleaseVersions('3.1.4', '3.1.4')).toBe(0)
  })

  it('treats a missing segment as zero, so 4.9 and 4.9.0 are the same release', () => {
    expect(compareReleaseVersions('4.9', '4.9.0')).toBe(0)
    expect(compareReleaseVersions('4.9', '4.9.1')).toBeLessThan(0)
  })

  it('orders a pre-release before its own release', () => {
    // '1.0.0' parses to [1,0,0]; '1.0.0-rc1' to [1,0,0,'rc1'], so the fourth segment compares
    // 'rc1' against the 0 default — string vs number, which falls to a locale compare.
    expect(compareReleaseVersions('1.0.0-rc1', '1.0.0')).not.toBe(0)
  })
})

describe('percentOf — the denominator guard', () => {
  it('returns null rather than 0 when the denominator is zero', () => {
    // A rate over nothing is UNKNOWN, not zero. Rendering 0% on an unstarted run reads as
    // total failure, which is the opposite of the truth.
    expect(percentOf(0, 0)).toBeNull()
    expect(percentOf(5, 0)).toBeNull()
    expect(percentOf(5, -1)).toBeNull()
  })

  it('computes an ordinary rate', () => {
    expect(percentOf(1, 4)).toBe(25)
    expect(percentOf(3, 3)).toBe(100)
    expect(percentOf(0, 7)).toBe(0)
  })
})

describe('formatPercent and formatRatio — the unit each one takes', () => {
  it('keeps the two units apart, which is how a report avoids showing "0.9%"', () => {
    expect(formatPercent(89)).toBe('89.0%')
    expect(formatRatio(0.89)).toBe('89.0%')
  })

  it('renders a null or NaN rate as an em dash, never as 0%', () => {
    expect(formatPercent(null)).toBe(EM_DASH)
    expect(formatPercent(undefined)).toBe(EM_DASH)
    expect(formatPercent(Number.NaN)).toBe(EM_DASH)
    expect(formatRatio(null)).toBe(EM_DASH)
    expect(formatRatio(Number.NaN)).toBe(EM_DASH)
  })

  it('renders a genuine zero as 0%, because a measured zero is not unknown', () => {
    expect(formatPercent(0)).toBe('0.0%')
    expect(formatRatio(0)).toBe('0.0%')
  })
})

describe('formatAge', () => {
  const now = Date.parse('2026-07-31T12:00:00Z')
  const ago = (ms: number) => new Date(now - ms).toISOString()

  it.each([
    ['never executed', null, 'never'],
    ['30 seconds', ago(30_000), 'just now'],
    ['1 minute', ago(60_000), '1m ago'],
    ['59 minutes', ago(59 * 60_000), '59m ago'],
    ['60 minutes', ago(60 * 60_000), '1h ago'],
    ['23 hours', ago(23 * 3_600_000), '23h ago'],
    ['24 hours', ago(24 * 3_600_000), '1d ago'],
    ['29 days', ago(29 * 86_400_000), '29d ago'],
    ['30 days', ago(30 * 86_400_000), '1mo ago'],
    ['400 days', ago(400 * 86_400_000), '1y ago'],
  ])('renders %s as %s', (_label, iso, expected) => {
    expect(formatAge(iso, now)).toBe(expected)
  })

  it('does not render a future timestamp as a negative age', () => {
    // Clock skew between the server and the browser is routine, and "-3m ago" reads as a bug.
    expect(formatAge(new Date(now + 5 * 60_000).toISOString(), now)).toBe('just now')
  })
})

describe('formatDuration and formatSeconds', () => {
  it.each([
    [0, '0ms'],
    [999, '999ms'],
    [1_000, '1.0s'],
    [1_400, '1.4s'],
    [59_999, '60.0s'],
    [60_000, '1m'],
    [260_000, '4m 20s'],
    [3_600_000, '1h 0m'],
    [3_900_000, '1h 5m'],
  ])('renders %ims as %s', (ms, expected) => {
    expect(formatDuration(ms)).toBe(expected)
  })

  it('renders an absent duration as an em dash rather than 0ms', () => {
    expect(formatDuration(null)).toBe(EM_DASH)
    expect(formatDuration(undefined)).toBe(EM_DASH)
    expect(formatSeconds(null)).toBe(EM_DASH)
  })

  it('reads authored estimates in seconds, not milliseconds', () => {
    // The trap this exists to avoid: an estimate of 260 authored in seconds rendering as
    // "260ms" — a four-minute case that looks instant.
    expect(formatSeconds(260)).toBe('4m 20s')
    expect(formatDuration(260)).toBe('260ms')
  })
})

describe('formatBytes', () => {
  it.each([
    [0, '0 B'],
    [999, '999 B'],
    [1_000, '1.0 kB'],
    [842_000, '842 kB'],
    [1_500_000, '1.5 MB'],
    [2_400_000_000, '2.4 GB'],
    // Beyond the unit table it stays in GB rather than falling off the end into `undefined`.
    [5_000_000_000_000, '5000 GB'],
  ])('renders %i bytes as %s', (bytes, expected) => {
    expect(formatBytes(bytes)).toBe(expected)
  })
})

describe('timestamps always carry their zone', () => {
  it('labels a date-time with UTC, because an unlabeled wall clock is ambiguous', () => {
    expect(formatDateTime('2026-07-31T02:14:00Z')).toBe('2026-07-31 02:14 UTC')
    expect(formatDateTime('2026-07-31T02:14:09Z', true)).toBe('2026-07-31 02:14:09 UTC')
  })

  it('formats in UTC regardless of the machine running the test', () => {
    // The same instant, expressed in a different offset, must render identically.
    expect(formatDateTime('2026-07-31T04:14:00+02:00')).toBe('2026-07-31 02:14 UTC')
  })

  it('labels the staleness marker, which is read as a time and nothing else', () => {
    expect(formatTimeOfDay(Date.parse('2026-07-31T11:25:00Z'))).toBe('11:25 UTC')
  })

  it('renders an absent timestamp as an em dash rather than the epoch', () => {
    expect(formatDateTime(null)).toBe(EM_DASH)
    expect(formatTimeOfDay(null)).toBe(EM_DASH)
    expect(formatTimeOfDay(undefined)).toBe(EM_DASH)
  })
})
