/**
 * A session is one credential, probed once at connect.
 *
 * ## Why this is per-connection and not per-process, which is the slice's load-bearing shape
 *
 * The CLI is one credential per invocation, so `connect()` reads the environment and that is the
 * whole of it. This server is **remote HTTPS** (WIP plan §4, which reverses §6w's stdio-first
 * answer), so one process serves many callers and the credential arrives on the request:
 * `Authorization: Bearer …`, per the same §4.
 *
 * That interacts with two decisions taken elsewhere in a way neither of them anticipated:
 *
 *   - `tools/list` is **permission-filtered** (§6w.3), so the tool list is a function of the
 *     credential; and
 *   - the capability summary is carried in the server's **`instructions`** (§6.2), because a host
 *     loads those at session start even when tool definitions are deferred.
 *
 * `instructions` is a constructor argument of the SDK's `Server`. So a process-wide server object
 * would hand every later caller the *first* caller's capability summary — their role, their
 * project's name, the size of their reach — through a field that reads like documentation and is
 * never re-sent. **One `Server` per session** is therefore not a style choice; it is what keeps
 * one company's shape out of another's session. `server.ts` builds one from this.
 *
 * ## The project is resolved here, never asked for
 *
 * Surface spec §4.1: `projectId` and `companyId` do not appear in any tool schema, because §6w.3
 * requires the context to come from the token *"never from a tool argument"*. A token binds at
 * most one project, so the usual answer is the single row `GET /projects` returns.
 *
 * When it returns several — a person's token rather than an `Agent` one — this server **does not
 * choose**. The CLI's answer (`--project`) is a person naming one; the equivalent here would be a
 * tool argument, which is the thing §4.1 forbids. `TCMS_PROJECT` lets the *operator* bind the
 * session at configuration time, which is a different party making the choice, and with neither
 * of those the session is unscoped and says so. It needs no special-casing downstream: an
 * unscoped `GET /me/permissions` answers SYSTEM and COMPANY terms only, so every project-scoped
 * tool falls out of `tools/list` on its own gate.
 */
import {
  createTcmsClient,
  resolveProject,
  TcmsApiError,
  TcmsClientError,
  type ResolvedProject,
  type TcmsClient,
  type TcmsRequestOptions,
} from '@tcms/client';

/** The version this build reports. */
export const MCP_VERSION = '0.1.0';

/**
 * The `User-Agent` every request carries.
 *
 * Load-bearing for the same reason the CLI's is: `audit_event` has no `via` column (surface spec
 * §0.6), so §6w.3's *"an agent did it is never an acceptable audit line"* is met by
 * `actor_token_id` plus this string. It names the client and nothing else — not the MCP host, not
 * the model, neither of which the server can verify.
 */
export const USER_AGENT = `tcms-mcp/${MCP_VERSION}`;

export const ENV_URL = 'TCMS_URL';
export const ENV_TOKEN = 'TCMS_TOKEN';
export const ENV_PROJECT = 'TCMS_PROJECT';

export interface SessionOptions {
  /** The TCMS instance origin. */
  baseUrl: string;
  /** The bearer this session acts as — from the request header, or from the environment. */
  token: string;
  /** `TCMS_PROJECT`, when the operator bound the session to one. */
  project?: string | undefined;
}

/**
 * WHICH ROWS a held key covers — roles spec §A5, and the half of the answer that is easy to drop.
 *
 * `GET /me/permissions` has answered `{key, qualifier}` pairs since §65, and it was an array of
 * bare keys before that. The omission had a cost the SPA could not work around and the contract
 * still records it: a Test Engineer holds `artifact:edit` at `PLAN_SCOPE`, so a keys-only answer
 * says *held*, the project-scope control enables, and the server refuses the submit.
 *
 * **`tools/list` is the same decision one client over.** §6w.3 filters the list so *"an agent
 * shown a tool it cannot use will call it, be refused, and retry"* — which is §31's
 * disable-don't-hide arriving at the opposite affordance — and a filter computed from keys alone
 * lists a tool for rows the caller cannot touch, producing exactly the refuse-and-retry loop the
 * filtering exists to prevent. The surface spec's §2 reasons entirely in keys and never mentions
 * a qualifier.
 *
 * The resolution is **not** to hide: `OWN` still lets the caller act on their own rows, so
 * hiding would withhold something they can do. The tool is listed and its description says what
 * the qualifier restricts it to — which is disable-don't-hide's actual shape, rendered for a
 * reader that reads a list instead of a screen.
 */
export type Qualifier = 'ALL' | 'OWN' | 'PLAN_SCOPE';

/** Who this credential is, and what it can reach. Answered once, at connect. */
export interface Reach {
  user: { displayName: string; email: string; isSystemAdmin: boolean };
  /** The project every project-scoped tool acts in, when there is exactly one answer. */
  project?: ResolvedProject | undefined;
  /** Why there is no project, when there is none. Rendered into the instructions. */
  unscoped?: string | undefined;
  /** The keys held — the owner's set ∩ the token's role's, for `project` if any. */
  permissions: ReadonlySet<string>;
  /** The same answer with its {@link Qualifier}s kept. See that type for why it matters here. */
  qualifiers: ReadonlyMap<string, Qualifier>;
  /** Every project this credential can see, for the summary's benefit. */
  visible: readonly { key: string; name: string }[];
}

export interface Session {
  client: TcmsClient;
  /**
   * What this credential could reach **when it was last probed**.
   *
   * Mutable, and that is the honest shape: it is a cache of a server-side answer with no
   * invalidation of its own. A grant revoked mid-session leaves it stale, and the tool list the
   * host is holding is derived from it, so the staleness is visible to the model as tools that
   * exist and refuse. {@link Session.refresh} is the only thing that re-reads it, `tcms_whoami`
   * is the only thing that calls that, and `server.ts` turns a change into
   * `notifications/tools/list_changed` — which is why the "live" in §6.2's *"returns the same
   * summary, live"* is load-bearing rather than a nicety.
   */
  reach: Reach;
  /** Per-call options — the deadline, and the host's cancellation. */
  request: TcmsRequestOptions;
  /** The project id, or a refusal that says how to bind one. */
  projectId: () => string;
  /** Re-probes and returns the fresh reach, after replacing {@link Session.reach}. */
  refresh: () => Promise<Reach>;
  /**
   * The `Idempotency-Key` a create arm sends, derived from the tool-call id — surface spec §4.2.
   *
   * **It deduplicates a transport retry and not a model retry**, and the difference is worth
   * stating because the header's whole purpose is the second one. A host re-delivering the same
   * JSON-RPC request carries the same id, so the create is recognised as a replay. A model that
   * decides to *call the tool again* issues a new id, and the server sees a new intent — which is
   * correct, because a second call after a visible failure may genuinely be a second intent, and
   * the client cannot tell. What it buys is the case that actually bites an agent: a write whose
   * response was lost, retried by the machinery rather than by the model.
   */
  callId?: string | undefined;
  /** Set by `server.ts`. Called when a refresh changed which tools are listed. */
  onListChanged?: (() => void) | undefined;
}

/**
 * Builds the client and probes it.
 *
 * The probe is three reads and they are the same three the CLI's `whoami` makes, deliberately:
 * this is the one place the two surfaces answer the same question, and answering it differently
 * would mean the capability summary and `tcms whoami` could disagree about the same token.
 */
export async function openSession(options: SessionOptions): Promise<Session> {
  if (!options.baseUrl) {
    throw new TcmsClientError(`No TCMS instance. Set ${ENV_URL}=https://tcms.example.com.`);
  }
  if (!options.token) {
    throw new TcmsClientError('No credential. This server acts as the bearer it is given.');
  }

  const client = createTcmsClient({
    baseUrl: options.baseUrl,
    token: options.token,
    userAgent: USER_AGENT,
  });

  const session: Session = {
    client,
    reach: await probe(client, options.project),
    request: {},
    projectId: () => {
      if (!session.reach.project) {
        throw new TcmsClientError(
          session.reach.unscoped
            ?? 'This session is not bound to a project, so there is nothing to act in.',
        );
      }
      return session.reach.project.id;
    },
    refresh: async () => {
      session.reach = await probe(client, options.project);
      return session.reach;
    },
  };

  return session;
}

async function probe(client: TcmsClient, requested: string | undefined): Promise<Reach> {
  const me = await client.GET('/me');
  const visible = await client.GET('/projects');

  const { project, unscoped } = await pickProject(client, visible, requested);

  /*
    The project term is only sent when one is in scope, and the two answers are different sets
    rather than the same set filtered: `GET /me/permissions` resolves SYSTEM and COMPANY without a
    project, and SYSTEM + COMPANY + PROJECT with one. Passing a project nobody bound would report
    a reach this session does not have anywhere in particular.
  */
  const answer = await client.GET('/me/permissions', {
    params: { query: project ? { projectId: project.id } : {} },
  });

  const qualifiers = new Map<string, Qualifier>(
    answer.permissions.map((held) => [held.key, held.qualifier]),
  );

  return {
    user: {
      displayName: me.displayName,
      email: me.email,
      isSystemAdmin: me.isSystemAdmin,
    },
    ...(project ? { project } : {}),
    ...(unscoped ? { unscoped } : {}),
    permissions: new Set(qualifiers.keys()),
    qualifiers,
    visible: visible.map((row) => ({ key: row.key, name: row.name })),
  };
}

async function pickProject(
  client: TcmsClient,
  visible: readonly { id: string; key: string; name: string }[],
  requested: string | undefined,
): Promise<{ project?: ResolvedProject; unscoped?: string }> {
  if (requested) {
    /*
      A configured project that does not resolve is a **loud** failure and not a fallback to
      "unscoped". The operator stated an intent; silently serving a session with no project would
      answer that intent with an empty tool list and no explanation of which of the two things
      went wrong.
    */
    return { project: await resolveProject({ client, requested }) };
  }

  if (visible.length === 1 && visible[0]) {
    const only = visible[0];
    return { project: { id: only.id, key: only.key, name: only.name } };
  }

  if (visible.length === 0) {
    return {
      unscoped: 'This credential can see no projects. It may be bound to a project its owner '
        + 'cannot read, or its owner may hold no project grant.',
    };
  }

  return {
    unscoped: `This credential can see ${visible.length} projects `
      + `(${visible.map((row) => row.key).join(', ')}) and this surface takes no project `
      + 'argument by design, so it acts in none of them. Issue a token bound to one project, or '
      + `set ${ENV_PROJECT} on the server.`,
  };
}

/**
 * Whether a failure at connect is the credential's fault, which is the only thing a caller may
 * be told before the read gate.
 *
 * 401 and 403 on `GET /me` are the same answer to an MCP host — *this bearer is not usable here*
 * — and neither may say more. A revoked token and an unknown one being indistinguishable is a
 * requirement rather than an accident (WIP plan §4).
 */
export function isCredentialFailure(error: unknown): boolean {
  return error instanceof TcmsApiError && (error.status === 401 || error.status === 403);
}
