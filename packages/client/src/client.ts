/**
 * The typed client — `openapi-fetch` over `schema.d.ts`, which is generated from
 * `docs/specs/openapi.yaml` by `npm run gen:api`.
 *
 * ## Why generated rather than hand-written, when the SPA's client is hand-written
 *
 * The SPA reaches the contract through `src/api/queries/`, and two checks
 * (`contractOperations.test.ts`, `contractParameters.test.ts`) exist to compare the URLs it
 * builds against the paths the contract declares — because a hand-built template literal can
 * name a path that does not exist and nothing else notices. Those checks are ~450 lines of AST
 * walking that reconstruct what a call site meant.
 *
 * Here the path and the method **are** the type parameters, so a path the contract does not
 * declare, a method that path does not have, a missing path parameter and a response field that
 * was never on the wire are all compile errors. That is the same guarantee, obtained from the
 * generator instead of re-derived from the source, and it is the reason this package is worth
 * having at all rather than the CLI calling `fetch` directly.
 *
 * ## Why every method throws
 *
 * openapi-fetch answers `{ data, error, response }`. The transport under it (`http.ts`) has
 * already thrown on any non-2xx, so `error` is populated on no code path that reaches a caller —
 * a result tuple whose failure arm cannot occur is an invitation to write `if (error)` branches
 * that are never taken and are therefore never right. These methods return the data and throw
 * `TcmsApiError`, which is also what makes the command core's refusal handling one place
 * (`core/refusal.ts`) rather than one per call site.
 */
import createOpenApiClient from 'openapi-fetch';
import type { FetchResponse, MaybeOptionalInit } from 'openapi-fetch';
import type { HttpMethod, PathsWithMethod, RequiredKeysOf } from 'openapi-typescript-helpers';

import { createTransport, withOptions, type TcmsRequestOptions, type TransportOptions } from './http.js';
import type { paths } from './schema.js';

type Json = `application/json`;

/**
 * One verb, typed over every path in the contract that declares it.
 *
 * The `...init` rest is how openapi-fetch spells "optional when nothing in it is required": a
 * `GET /me` takes no argument, a `GET /projects/{projectId}` must be given its path parameter,
 * and both are the same method.
 */
type Verb<Method extends HttpMethod> = <
  Path extends PathsWithMethod<paths, Method>,
  Init extends MaybeOptionalInit<paths[Path], Method>,
>(
  path: Path,
  ...init: RequiredKeysOf<Init> extends never
    ? [(Init & { tcms?: TcmsRequestOptions })?]
    : [Init & { tcms?: TcmsRequestOptions }]
) => Promise<NonNullable<FetchResponse<NonNullable<paths[Path][Method]>, Init, Json>['data']>>;

export interface TcmsClient {
  GET: Verb<'get'>;
  POST: Verb<'post'>;
  PUT: Verb<'put'>;
  PATCH: Verb<'patch'>;
  DELETE: Verb<'delete'>;
  /**
   * The instance origin plus `/api/v1`, resolved and validated.
   *
   * Exposed because two responses carry a **server-composed** URL — a report artifact's
   * `downloadUrl` and evidence's `url` — which already include the prefix, deliberately, so the
   * client holds no copy of the server's routing. A consumer fetching one needs to check it is
   * inside this base before attaching the credential to it.
   */
  readonly baseUrl: string;
}

export type TcmsClientOptions = TransportOptions;

/**
 * Builds a client bound to one instance and one credential.
 *
 * There is no `projectId` here and no ambient project: the token is bound to at most one project
 * server-side, and the surface spec §4.1 requires the project to come from the token or from an
 * explicit `--project`, never from a hidden default that a caller could be wrong about.
 */
export function createTcmsClient(options: TcmsClientOptions): TcmsClient {
  const transport = createTransport(options);
  const baseUrl = transport.baseUrl;

  const inner = createOpenApiClient<paths>({ baseUrl, fetch: transport.fetch });

  const call = (method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE') =>
    async (path: string, init?: Record<string, unknown>): Promise<unknown> => {
      const { tcms, ...rest } = init ?? {};
      const result = await (inner[method] as (p: string, i: unknown) => Promise<FetchResponse<never, never, Json>>)(
        path,
        withOptions(rest, (tcms as TcmsRequestOptions) ?? {}),
      );
      /*
        The status, before it is dropped.

        `result.data` is the whole of what a caller has ever been given, and until §204 that was
        also the whole of what this function kept — so a success status existed nowhere in this
        package. That is fine for 122 of the 132 operations, which declare one; it is not fine for
        the sixteen creates that declare **200 and 201**, where the contract says in as many words
        that the 200 means the `Idempotency-Key` had already done this and nothing was created by
        this request. Dropping it made a replay indistinguishable from a create for every consumer
        `packages/` has.
      */
      (tcms as TcmsRequestOptions | undefined)?.observeStatus?.(result.response.status);
      // A 204 has no body and openapi-fetch reports `data: undefined`. The contract's deletes are
      // the only ones, and `undefined` is the honest value for them — not an empty object, which
      // a caller could destructure without noticing there was nothing there.
      return result.data;
    };

  return {
    GET: call('GET') as TcmsClient['GET'],
    POST: call('POST') as TcmsClient['POST'],
    PUT: call('PUT') as TcmsClient['PUT'],
    PATCH: call('PATCH') as TcmsClient['PATCH'],
    DELETE: call('DELETE') as TcmsClient['DELETE'],
    baseUrl,
  };
}
