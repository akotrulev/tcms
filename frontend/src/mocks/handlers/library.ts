/** The reusable library — conditions and shared steps. */
import { HttpResponse, delay, http } from 'msw';
import type { Condition, SharedStep } from '../../api/types';
import { caseDetail, db, newId } from '../db';
import { API, LATENCY, requireAuth } from './shared';

/**
 * Distinct live cases referencing each entry — the same rule the server applies, and the reason
 * the fixture's own `usageCount` is ignored.
 *
 * Distinct *cases*, not step rows: a case referencing the same entry twice counts once, matching
 * `SharedStepUsage` on the backend. Getting this wrong in only one of the two places is how a
 * ranking looks right in development and reorders itself the first time it meets a real server.
 *
 * Case details are materialized lazily in this mock, so the steps have to be pulled through
 * `caseDetail` rather than read off the summary rows — a summary carries no steps at all, and
 * counting over the summaries would answer 0 for everything, forever.
 */
function usageOf(sharedStepId: string): number {
  let cases = 0;
  for (const summary of db.cases) {
    const detail = caseDetail(summary.id);
    if (detail?.steps.some((step) => step.sharedStepId === sharedStepId)) cases += 1;
  }
  return cases;
}

function withUsage(step: SharedStep): SharedStep {
  return { ...step, usageCount: usageOf(step.id) };
}

/**
 * The two condition counts, DERIVED — the server unmapped `condition.usage_count` in §95 and the
 * fixture's stored number is ignored here for the same reason it is there.
 *
 * They are **two numbers and are never added**. `usageCount` counts distinct live *cases* attaching
 * the entry directly; `suiteUsageCount` counts *suites*, each of which propagates to every case
 * beneath it. Blending them would produce a figure about nothing — and the client renders this as a
 * pre-edit impact warning, where understating is the failure that matters.
 *
 * Distinct cases, not attachment rows: a case may legitimately attach the same entry as both a pre-
 * and a postcondition, and counting rows would report two cases where there is one.
 */
function countsFor(conditionId: string): { usageCount: number; suiteUsageCount: number } {
  let usageCount = 0;
  for (const summary of db.cases) {
    const detail = caseDetail(summary.id);
    const attached = [...(detail?.preconditions ?? []), ...(detail?.postconditions ?? [])].some(
      // Own attachments only. An inherited row is the SUITE's attachment showing through, so
      // counting it here would count the same suite once per case beneath it and call the result
      // a case count.
      (ref) => !ref.inherited && ref.conditionId === conditionId,
    );
    if (attached) usageCount += 1;
  }

  const suiteUsageCount = db.suites.filter((suite) =>
    (suite.conditions ?? []).some((ref) => ref.conditionId === conditionId),
  ).length;

  return { usageCount, suiteUsageCount };
}

function withCounts(condition: Condition): Condition {
  return { ...condition, ...countsFor(condition.id) };
}

/** The server's two refusals, in the same shape — see `ConditionEdit.validate`. */
function validateCondition(input: { title?: unknown; type?: unknown; body?: unknown }): {
  errors: Record<string, string[]>;
  title: string;
  body: string[];
} {
  const errors: Record<string, string[]> = {};
  const title = typeof input.title === 'string' ? input.title.trim() : '';
  // Blank lines dropped, an all-blank body refused — the pair, since dropping a trailing blank is
  // right and storing an empty block puts an empty line into every case attaching the entry.
  const body = (Array.isArray(input.body) ? input.body : [])
    .map((line: unknown) => (typeof line === 'string' ? line.trim() : ''))
    .filter(Boolean);

  if (!title) errors.title = ['Enter a title.'];
  else if (title.length > 150) errors.title = ['Keep the title under 150 characters.'];
  // Never defaulted to BOTH: an entry typed BOTH appears in both pickers, so a caller who forgot
  // the field would get an entry offered as a postcondition it was never written to be.
  if (input.type !== 'PRE' && input.type !== 'POST' && input.type !== 'BOTH') {
    errors.type = ['Choose whether this is a precondition, a postcondition, or both.'];
  }
  if (body.length === 0) errors.body = ['Add at least one action.'];

  return { errors, title, body };
}

export const libraryHandlers = [
  http.get(`${API}/projects/:projectId/conditions`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const q = url.searchParams.get('q')?.toLowerCase();
    const type = url.searchParams.get('type');
    let rows = db.conditions;
    // A BOTH entry matches type=PRE and type=POST, because BOTH means "usable in either
    // position" — not a third role. Exact matching would make marking a condition reusable
    // everywhere the one way to hide it everywhere. The real API does the same; see
    // ConditionType.matching on the backend, which is where the decision is written down.
    if (type) rows = rows.filter((c) => c.type === type || (type !== 'BOTH' && c.type === 'BOTH'));
    if (q) rows = rows.filter((c) => c.title.toLowerCase().includes(q));
    // Most-reused first — the whole point of a reusable-condition search is to surface the one
    // 400 cases already share, not the one someone wrote yesterday. Ranked by the CASE count, not
    // by the sum of the two: they measure different things, so a blended sort key would order by a
    // quantity that does not exist. Title then id break the ties, so the same keystroke returns the
    // same list.
    return HttpResponse.json(
      rows
        .map(withCounts)
        .sort(
          (a, b) =>
            b.usageCount - a.usageCount ||
            a.title.localeCompare(b.title) ||
            a.id.localeCompare(b.id),
        ),
    );
  }),

  http.post(`${API}/projects/:projectId/conditions`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    const input = (await request.json()) as { title?: string; type?: string; body?: string[] };
    const { errors, title, body } = validateCondition(input);
    if (Object.keys(errors).length > 0) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Validation failed', status: 400, errors },
        { status: 400 },
      );
    }

    // Both counts zero and lockVersion 0 by construction — nothing can attach an entry that did
    // not exist a moment ago.
    const created: Condition = {
      id: newId(),
      title,
      type: input.type as Condition['type'],
      body,
      usageCount: 0,
      suiteUsageCount: 0,
      lockVersion: 0,
    };
    db.conditions.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),

  http.put(`${API}/projects/:projectId/conditions/:conditionId`, async ({ request, params }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    const existing = db.conditions.find((c) => c.id === params.conditionId);
    // A retired entry, another project's, and an unknown id are one answer on the server, so they
    // are one answer here — this endpoint is not an existence oracle for a library the caller
    // cannot read.
    if (!existing) return new HttpResponse(null, { status: 404 });

    // Checked BEFORE the body is judged: re-submitting a corrected body against a version somebody
    // else has moved on from would fail again for a reason the first message did not mention.
    const ifMatch = request.headers.get('If-Match');
    if (ifMatch != null && Number(ifMatch) !== existing.lockVersion) {
      return HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Someone else saved first',
          status: 409,
          detail: 'Reload the condition to see the newer version, then reapply your change.',
        },
        { status: 409 },
      );
    }

    const input = (await request.json()) as { title?: string; type?: string; body?: string[] };
    // The SAME validation as the create, deliberately: a rule relaxed here would let an entry
    // reach, across every case attaching it, a state the create refused.
    const { errors, title, body } = validateCondition(input);
    if (Object.keys(errors).length > 0) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Validation failed', status: 400, errors },
        { status: 400 },
      );
    }

    existing.title = title;
    existing.type = input.type as Condition['type'];
    existing.body = body;
    existing.lockVersion += 1;

    // The propagation, and it needs no fan-out here for the same reason it needs none on the
    // server: every attachment resolves through this row. What DOES need doing is refreshing the
    // denormalized copies the mock's case details carry, since this store materializes them.
    for (const summary of db.cases) {
      const detail = caseDetail(summary.id);
      if (!detail) continue;
      for (const kind of ['preconditions', 'postconditions'] as const) {
        for (const ref of detail[kind] ?? []) {
          if (ref.conditionId === existing.id) {
            ref.title = existing.title;
            ref.body = existing.body;
          }
        }
      }
    }

    return HttpResponse.json(withCounts(existing));
  }),

  http.get(`${API}/projects/:projectId/shared-steps`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);
    const q = new URL(request.url).searchParams.get('q')?.toLowerCase();
    const rows = q ? db.sharedSteps.filter((s) => s.title.toLowerCase().includes(q)) : db.sharedSteps;
    // Most-used first, then title, then id — the server's comparator, ties included. Sorting by
    // usage alone left the equal-usage rows in insertion order, so a newly created entry jumped
    // around between keystrokes.
    return HttpResponse.json(
      rows
        .map(withUsage)
        .sort(
          (a, b) =>
            b.usageCount - a.usageCount || a.title.localeCompare(b.title) || a.id.localeCompare(b.id),
        ),
    );
  }),

  http.post(`${API}/projects/:projectId/shared-steps`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    const input = (await request.json()) as { title?: string; body?: string[] };
    const title = input.title?.trim();
    // The same two refusals the server makes, in the same shape — blank lines dropped, an
    // all-blank body refused. A mock more permissive than the server is how a form ships with
    // validation nobody ever saw fail.
    const body = (input.body ?? []).map((line) => line.trim()).filter(Boolean);

    const errors: Record<string, string[]> = {};
    if (!title) errors.title = ['Enter a title.'];
    else if (title.length > 150) errors.title = ['Keep the title under 150 characters.'];
    if (body.length === 0) errors.body = ['Add at least one action.'];

    if (Object.keys(errors).length > 0) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Validation failed', status: 400, errors },
        { status: 400 },
      );
    }

    // usageCount 0 by construction — nothing can reference an entry that did not exist a moment
    // ago. Stored so the list read that follows finds it; the GET recomputes regardless.
    const created: SharedStep = { id: newId(), title: title!, body, usageCount: 0 };
    db.sharedSteps.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),
];
