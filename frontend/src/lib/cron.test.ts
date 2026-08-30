import { describe, expect, it } from 'vitest';

import { describeCron } from './cron';

describe('a cron expression, read out loud', () => {
  it.each([
    ['0 30 2 * * *', 'Every day at 02:30 · Europe/Sofia'],
    ['0 0 3 * * MON-FRI', 'Every weekday at 03:00 · Europe/Sofia'],
    ['0 15 6 * * SUN', 'Every Sunday at 06:15 · Europe/Sofia'],
    ['0 0 1 ? ? *', 'Every day at 01:00 · Europe/Sofia'],
  ])('reads %s as %s', (expression, prose) => {
    expect(describeCron(expression, 'Europe/Sofia')).toBe(prose);
  });

  it.each([
    ['0 0 0 L * *'],
    ['0 */15 * * * *'],
    ['0 0 9-17 * * *'],
    ['30 0 3 * * *'],
    ['0 0 3 1 * *'],
    ['not a cron'],
  ])('hands back %s rather than approximating it', (expression) => {
    // The load-bearing half. A humaniser that guessed here would state a cadence the scheduler
    // does not keep, and a reader has no way to catch that — where the raw expression at least
    // tells them they need to read it.
    expect(describeCron(expression, 'UTC')).toBe(`${expression} · UTC`);
  });

  it('says nothing about a zone it was not given', () => {
    expect(describeCron('0 30 2 * * *', null)).toBe('Every day at 02:30');
  });
});
