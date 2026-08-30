/**
 * A cron expression, read out loud.
 *
 * Render G writes `Every day at 01:00 · Europe/Sofia`; the build printed the raw `0 30 2 * * *`
 * (renders audit 1.9). A schedule's cadence is the one thing every reader of that table needs and
 * the one thing a six-field expression does not say.
 *
 * It reads the shapes the scheduler is actually configured with and **falls back to the expression
 * itself** for everything else. That fallback is the whole design: a humaniser that guessed at `L`,
 * `#`, a step or a list would state a cadence the scheduler does not keep, which is worse than the
 * raw string it replaces — a reader who cannot parse `0 0 0 L * *` at least knows they cannot,
 * where "Every day at 00:00" is a confident lie. So every branch below is one this can prove.
 *
 * Spring's six-field form (second first), which is what `RunSchedule.cronExpression` carries.
 */
const DAYS: Record<string, string> = {
  '*': 'Every day',
  '?': 'Every day',
  'MON-FRI': 'Every weekday',
  SUN: 'Every Sunday',
  MON: 'Every Monday',
  TUE: 'Every Tuesday',
  WED: 'Every Wednesday',
  THU: 'Every Thursday',
  FRI: 'Every Friday',
  SAT: 'Every Saturday',
};

export function describeCron(expression: string, timezone?: string | null): string {
  const suffix = timezone ? ` · ${timezone}` : '';
  const parts = expression.trim().split(/\s+/);
  if (parts.length !== 6) return expression + suffix;

  const [second, minute, hour, dayOfMonth, month, dayOfWeek] = parts;
  const isNumber = /^\d+$/;

  // A non-zero second, a stepped minute, a range of hours — all real cadences this cannot phrase,
  // and all of them fall through to the expression rather than to an approximation of it.
  if (second !== '0' || !isNumber.test(minute) || !isNumber.test(hour)) return expression + suffix;
  if (month !== '*' && month !== '?') return expression + suffix;
  if (dayOfMonth !== '*' && dayOfMonth !== '?') return expression + suffix;

  const cadence = DAYS[dayOfWeek.toUpperCase()];
  if (!cadence) return expression + suffix;

  const at = `${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`;
  return `${cadence} at ${at}${suffix}`;
}
