/**
 * Refusal copy — surface spec §4.5, and the one part of the core whose failure is a security
 * finding rather than a defect.
 *
 * §6t's rule: 403 is *"reached only after the read gate has passed"*. An unreachable project or
 * case answers **404**, and `GET /me/permissions` answers the **empty set** identically for an
 * unreachable project and a non-existent one, *"so this endpoint cannot be used to probe for
 * projects in other companies."*
 *
 * | Situation | What may be said |
 * |---|---|
 * | Past the read gate, write refused (403) | name the missing permission |
 * | Not past the read gate (404), or an empty permission answer | nothing about permissions or roles |
 *
 * Getting the table backwards turns this surface into the enumeration oracle §6t exists to
 * prevent. It is computed in one place for that reason, and never composed at a call site.
 *
 * ## The spec asks for one thing this cannot say, and that is a finding, not an omission
 *
 * §4.5 asks the 403 branch to name *"the missing permission and the **lowest built-in role that
 * holds it**"*. Naming the role needs the role→permission catalogue, and **this credential cannot
 * obtain it.** `GET /roles` is the only endpoint carrying it, `AdminDirectoryService.listRoles`
 * gates it on `company:read`, and `V40` §6 withholds `company:read` from `Agent` deliberately —
 * *a token is not a member*. So an agent token gets `[]`, not a 403: the data is not merely
 * unreachable, its absence is indistinguishable from an empty catalogue.
 *
 * Three ways to get it anyway, and why each is refused for now:
 *
 *   - **Bake the catalogue into this package.** It would be a second copy of the authoritative
 *     source (roles spec §A3), which this repo bans on its own repeated evidence — and worse
 *     than the usual case, because `@tcms/cli` is versioned independently of the instance it is
 *     pointed at, so the copy would be a claim about a server it has never seen. That is
 *     `JsonReportRenderer`'s recorded argument for not sharing a mapper, one client over.
 *   - **Generate it at build time from `V3`/`V40`/`V41`.** Same objection; derived staleness is
 *     still staleness.
 *   - **Widen `GET /roles`, or add a permission→roles read outside `company:read`.** A contract
 *     change and a decision about what an unauthenticated-ish caller may learn about the
 *     instance's role model. Legitimate, and not this slice's to make.
 *
 * So this says **less** than §4.5 permits, never more. That direction is safe: the check §8.4 of
 * the spec calls for — the 404 branch names no role and no permission — holds a fortiori, and the
 * 403 branch still tells the user the actionable half ("this token's role does not hold
 * `case:approve`"), which is what they take to an administrator.
 */
import { TcmsApiError, retryAfterMs } from '../errors.js';

export interface RefusalCopy {
  /** One sentence, safe to print or to hand a model. */
  message: string;
  /** True when the caller may be told which permission was missing. */
  namesPermission: boolean;
}

/**
 * The permission an operation needed, as declared by the tool or command that ran it.
 *
 * It comes from the caller, not from the response: the API's 403 body is a fixed
 * *"You do not have permission to do this"* and does not name a key, deliberately.
 */
export interface RefusedOperation {
  /** What the user asked for, in their words — "approve this case". */
  what: string;
  /** The permission the surface declares for it — `case:approve`. */
  permission: string;
}

/**
 * Turns a refusal into copy that respects the 404-vs-403 boundary.
 *
 * Anything that is not a 403 past the read gate goes down the silent branch, including a
 * transport failure — a caller that cannot reach the server has learned nothing about
 * permissions and must not be handed a sentence implying it has.
 */
export function describeRefusal(error: unknown, operation: RefusedOperation): RefusalCopy {
  if (!(error instanceof TcmsApiError)) {
    return { message: `Could not ${operation.what}.`, namesPermission: false };
  }

  switch (error.status) {
    case 403:
      // Past the read gate. The caller can see the thing and may be told what was missing.
      return {
        message:
          `Cannot ${operation.what}: this token's role does not hold \`${operation.permission}\`. `
          + 'Ask a project administrator for a role that does, or use a token issued under one.',
        namesPermission: true,
      };

    case 404:
      /*
        The load-bearing branch. It must not distinguish "exists but you cannot see it" from
        "does not exist", and must name neither a permission nor a role — a message that said
        "you lack case:read on this" would confirm the row exists to a caller in another company.
      */
      return {
        message: 'No such item, or it is not visible to this token.',
        namesPermission: false,
      };

    case 401:
      return {
        message: 'The credential was not accepted. It may be revoked, expired, or for another '
          + 'instance.',
        namesPermission: false,
      };

    case 429: {
      /*
        The rate limit, and the branch whose ABSENCE was the defect. Until it existed a 429 fell
        to `default:` and read as an unexplained failure — and what a model does with an
        unexplained failure, halfway through surveying a project, is try again. That is the loop
        the server-side limit exists to prevent, produced by the client one refusal later.

        So the copy carries the two facts that decide what to do next, and neither of them is
        obvious from the status alone:

          - HOW LONG. Read from `Retry-After` rather than from the sentence, so this agrees with
            the header even if the server's prose changes. Only stated when the server said it;
            an invented number is worse than none, because it would be obeyed.
          - THAT NOTHING HAPPENED. The refusal is written by a filter before routing, so unlike a
            timeout there is no handler that half-ran. A timed-out write gets the opposite
            instruction ("read the resource before trying again") and the two must not be
            confused — this is the one failure that can be retried as-is.

        `namesPermission` is false: nothing here is about what the caller may do.
      */
      const wait = retryAfterMs(error);
      const when = wait === null
        ? 'Wait before trying again.'
        : `Wait ${Math.max(1, Math.ceil(wait / 1000))} seconds before trying again.`;
      return {
        message: `Rate limited: too many requests from this token. ${when} `
          + 'Nothing was changed, so the same call can simply be repeated — but a tighter loop '
          + 'will keep being refused, so slow down rather than retrying immediately.',
        namesPermission: false,
      };
    }

    default:
      return { message: `Could not ${operation.what}: ${error.detail}`, namesPermission: false };
  }
}

/**
 * The same boundary for the capability summary (`tcms_whoami` and the server instructions).
 *
 * An **empty** permission answer is on the silent side of the table, and this is the case that
 * looks harmless: `GET /me/permissions` returns `[]` identically for a project in another
 * company and a project that does not exist, so summarising it as "you hold nothing *here*"
 * asserts that *here* is somewhere. It is not.
 */
export function describeReach(permissions: readonly string[], projectNamed: boolean): string {
  if (permissions.length > 0) {
    return `This token holds ${permissions.length} permission${permissions.length === 1 ? '' : 's'}.`;
  }

  return projectNamed
    ? 'No permissions resolved for that project — it does not exist, or this token cannot see it.'
    : 'No project is in scope, so only instance-wide permissions were resolved.';
}
