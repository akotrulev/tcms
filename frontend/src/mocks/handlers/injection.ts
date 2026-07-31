/**
 * Fault injection — one wildcard that pre-empts every other handler.
 *
 * MSW resolves handlers in order and lets a resolver fall through by returning nothing, so one
 * wildcard here covers every endpoint — the alternative, threading a check into all forty
 * handlers, would be forty chances to forget one.
 *
 * Everything it does is controlled from the console via `tcmsFaults` (see ../faults.ts), and
 * with no fault armed it returns `undefined` and costs nothing.
 *
 * **This must stay first in the composed array** (see ./index.ts). It is exported alone rather
 * than inside a group so that ordering requirement has somewhere to be stated.
 */
import { HttpResponse, delay, http } from 'msw';
import { verdictFor } from '../faults';
import { API } from './shared';

export const faultHandler = http.all(`${API}/*`, async ({ request }) => {
  const verdict = verdictFor(new URL(request.url).pathname);

  switch (verdict.kind) {
    case 'none':
      return undefined;

    case 'hang':
      // Longer than the longest budget in the client, so the client's own deadline is what
      // ends the request — which is exactly the behaviour under test.
      await delay(120_000);
      return undefined;

    case 'drift':
      // A 200 whose body is the wrong shape. This must surface as a FAILED region, never as
      // an empty one: "the service is broken" and "there is nothing here" are different facts,
      // and a `catch` that turns the first into the second is the defect being guarded against.
      return HttpResponse.json({ unexpected: 'shape' });

    case 'status':
      return HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Injected fault',
          status: verdict.status,
          detail: 'This failure was injected from the console for resilience testing.',
        },
        {
          status: verdict.status,
          headers: {
            // A correlation id on every failure, so the notification's copyable reference and
            // the dedupe-by-id behaviour can both actually be exercised.
            'x-correlation-id': `fault-${Math.random().toString(36).slice(2, 10)}`,
            ...(verdict.retryAfter !== null ? { 'retry-after': String(verdict.retryAfter) } : {}),
          },
        },
      );
  }
});
