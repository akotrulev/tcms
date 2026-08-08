/**
 * Turning what the user typed into the id the API takes — the other half of §4.4.
 *
 * ## Only one of the five keys has a server-side lookup
 *
 * `GET …/cases?q=` short-circuits a key-shaped `q` to an exact lookup (§6q), so `TC-42` costs one
 * request and the server does the matching. **The other four have no equivalent** — there is no
 * `q` on `GET …/suites`, `…/plans`, `…/runs` or `…/doc-pages`.
 *
 * What makes that survivable is that those four are **unpaginated**: each answers a plain array
 * of the project's rows, so resolving `TS-7` is one request and a client-side `find`, not a walk
 * over pages. What it costs is that the whole list crosses the wire to answer a question about
 * one row — fine for suites, and increasingly silly for a project with a thousand runs.
 *
 * That is worth recording as a contract candidate rather than hiding behind a helper: a `q` (or a
 * `key`) filter on those four lists would make every resolution one small request, and it would
 * also give `tcms_list_*` something to paginate. **Today they cannot paginate at all**, which the
 * surface spec's §4.6 — *"default page size 25 on every list tool"* — reads past: for four of the
 * six list tools, a page size is a client-side truncation of a response that already arrived
 * whole, so it bounds the tokens the model sees and not the bytes the server sent.
 */
import { TcmsClientError } from '../errors.js';
import type { TcmsClient } from '../client.js';
import { classify, type KeyedEntity } from './identifiers.js';

export interface ResolveOptions {
  client: TcmsClient;
  projectId: string;
  /** What the user typed — a UUID or a display key. */
  value: string;
  entity: KeyedEntity;
  signal?: AbortSignal;
}

/**
 * The UUID for a UUID or a display key.
 *
 * A UUID is returned untouched and costs no request: this is a resolver, not a validator, and
 * verifying an id the caller already holds would double every read on the surface to protect
 * against a typo the endpoint itself reports.
 */
export async function resolveId(options: ResolveOptions): Promise<string> {
  const identifier = classify(options.value, options.entity);
  if (identifier.kind === 'uuid') return identifier.id;

  const { key } = identifier;
  const found = await lookup(options, key.key);

  if (!found) {
    // Deliberately does not distinguish "no such key" from "not visible to this token" — §6t's
    // rule, and `core/refusal.ts` records why the distinction must not be drawn.
    throw new TcmsClientError(`No ${label(options.entity)} ${key.key}, or it is not visible.`);
  }

  return found;
}

async function lookup(options: ResolveOptions, key: string): Promise<string | undefined> {
  const { client, projectId, entity, signal } = options;
  const tcms = signal ? { signal } : {};
  const path = { projectId };

  switch (entity) {
    case 'CASE': {
      // The one server-side lookup: `q` matching the key pattern short-circuits to an exact hit.
      const result = await client.GET('/projects/{projectId}/cases', {
        params: { path, query: { q: key, size: 1 } },
        tcms,
      });
      return result.items.find((item) => item.key === key)?.id;
    }

    case 'SUITE': {
      const suites = await client.GET('/projects/{projectId}/suites', { params: { path }, tcms });
      return suites.find((suite) => suite.key === key)?.id;
    }

    case 'PLAN': {
      const plans = await client.GET('/projects/{projectId}/plans', { params: { path }, tcms });
      return plans.find((plan) => plan.key === key)?.id;
    }

    case 'RUN': {
      const runs = await client.GET('/projects/{projectId}/runs', { params: { path }, tcms });
      return runs.find((run) => run.key === key)?.id;
    }

    case 'DOC_PAGE': {
      const pages = await client.GET('/projects/{projectId}/doc-pages', { params: { path }, tcms });
      return pages.find((page) => page.key === key)?.id;
    }
  }
}

function label(entity: KeyedEntity): string {
  switch (entity) {
    case 'CASE': return 'test case';
    case 'SUITE': return 'suite';
    case 'PLAN': return 'test plan';
    case 'RUN': return 'test run';
    case 'DOC_PAGE': return 'documentation page';
  }
}
