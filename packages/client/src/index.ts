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

/* ---- the command core: the rules that must not diverge between the CLI and MCP ---- */

export {
  KEY_PREFIXES,
  classify,
  isUuid,
  parseDisplayKey,
} from './core/identifiers.js';
export type { DisplayKey, Identifier, KeyedEntity } from './core/identifiers.js';

export { MAX_IDEMPOTENCY_KEY, newIntentKey, requireIntentKey } from './core/idempotency.js';

export { ifMatchFor, isVersionConflict, versionOf } from './core/concurrency.js';
export type { Concurrency, NoVersionReason } from './core/concurrency.js';

export { createIntent, editIntent, requireExplicitVersion } from './core/mutation.js';
export type { CreateIntent, CreateOptions, EditVersion, WriteKind } from './core/mutation.js';

export { caseInputFrom, planInputFrom, suiteInputFrom } from './core/rewrite.js';

export { REQUIRED_CASE_FIELDS, requireProposableCase } from './core/proposal.js';

export {
  CASE_SHAPE,
  ISSUE_SHAPE,
  READ_ENUMS,
  SHAPES,
  bindsRequired,
  fieldsFor,
  flagOf,
  guideFor,
  isRequired,
  jsonSchemaFor,
  mechanicsFor,
  optionSpecFor,
  shapeOf,
  spellableInArgv,
  templateFor,
  usageFor,
  violationsOfShape,
} from './core/shapes.js';
export type {
  ActionKind,
  ActionSpec,
  CliBinding,
  EntityShape,
  FieldScope,
  FieldSpec,
  Requirement,
} from './core/shapes.js';

export { isProcessEntryPoint } from './core/entrypoint.js';

export { describeReach, describeRefusal } from './core/refusal.js';
export type { RefusalCopy, RefusedOperation } from './core/refusal.js';

export { describeFailure } from './core/outcome.js';
export type { Failure, FailureCode, FailedOperation } from './core/outcome.js';

export {
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  clampPageSize,
  eachItem,
  hasMore,
} from './core/paging.js';
export type { Page, PageMeta } from './core/paging.js';

export { resolveId } from './core/resolve.js';
export type { ResolveOptions } from './core/resolve.js';

export { resolveProject } from './core/project.js';
export type { ResolveProjectOptions, ResolvedProject } from './core/project.js';
