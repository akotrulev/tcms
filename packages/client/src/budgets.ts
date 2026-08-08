/**
 * Timeout budgets, per endpoint class rather than one global constant.
 *
 * The numbers are **not** the SPA's, and the difference is the point. `frontend`'s budgets are
 * chosen for a person watching a region: 3s for a typeahead, because a search-as-you-type that
 * waits ten is broken. Nothing on this surface is typed a character at a time — the CLI and the
 * MCP server issue whole operations — so the shortest class here is the SPA's middle one.
 *
 * What both share is the reason a budget exists at all: a request nothing aborts hangs until the
 * runtime's own limit. In a browser that renders as a spinner nobody can clear; in an MCP server
 * it holds a tool call open past the host's patience and the model retries — which, on a write
 * without an `Idempotency-Key`, is how one intent becomes two records.
 */
export const TIMEOUTS = {
  /** Ordinary reads and writes. */
  interactive: 15_000,
  /** Report generation, exports, uploads, bulk operations. */
  long: 120_000,
} as const;

export type TimeoutClass = keyof typeof TIMEOUTS;
