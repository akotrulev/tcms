/**
 * `@tcms/client` — the typed TCMS API client and the command core.
 *
 * Phase 4 of `docs/plans/2026-08-08-mcp-server-wip-plan.md`. Two adapters sit on top of this
 * package and neither of them contains a request: `@tcms/cli` (phases 5 and 7) and the MCP server
 * (phase 8). Everything whose divergence between those two would be a defect — an edit that
 * forgot `If-Match`, a refusal that named a role it should not have, a create without an
 * idempotency key — lives here, once. See
 * `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` §1 and §4.
 */
export { createTcmsClient } from './client.js';
export type { TcmsClient, TcmsClientOptions } from './client.js';

export { TIMEOUTS } from './budgets.js';
export type { TimeoutClass } from './budgets.js';

export { API_PREFIX, resolveBaseUrl } from './http.js';
export type { TcmsRequestOptions, TokenSource, TransportOptions } from './http.js';

export {
  TcmsApiError,
  TcmsClientError,
  TcmsTimeoutError,
  isAbort,
  isTransient,
  retryAfterMs,
} from './errors.js';

export type { components, operations, paths } from './schema.js';
