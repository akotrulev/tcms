import { useMemo } from 'react';
import { useMyCompanyPermissions, useMyPermissions } from '../api/queries';
import type { EffectivePermissions, PermissionKey } from '../api/types';
import { permissionLabels } from '../lib/permissions';
import { useCurrentUserId } from './useAuth';
import type { UseQueryResult } from '@tanstack/react-query';

/**
 * Whether this caller may do a thing here — and, when they may not, the sentence that says so.
 *
 * ## Hidden is not protected, so this is an affordance layer and nothing more
 *
 * Every action gated here is *independently* rejected by the server (plan §6b). Nothing about
 * this hook is a security control; if it were removed, the app would be exactly as safe and
 * merely ruder. What it buys is the `ui-rules` requirement the SPA has violated since Phase 2:
 * an action the backend will refuse renders **disabled with a reason**, rather than inviting a
 * click that ends in a 403 the user cannot do anything about.
 *
 * ## Three states, and the asymmetry between two of them is the whole design
 *
 * | State | Verdict | Why |
 * |---|---|---|
 * | Loading | **denied**, "Checking your permissions…" | A flash of disabled-then-enabled costs a grey moment. Enabled-then-disabled is the defect: the click lands in the window before the answer does. |
 * | Resolved | the server's answer | — |
 * | Failed | **allowed** | See below. |
 *
 * **Failure falls open, deliberately, and it is the only rule here that looks wrong.** A wrong
 * *enable* costs one 403 rendered in the dialog the user is already looking at — the app's
 * behaviour for its entire life so far, and survivable. A wrong *disable* tells a Test Lead they
 * may not do their job, offers no retry, and shows no error anywhere, because the failure is in
 * a query that renders nothing. The server is still the gate either way, so the cheap failure is
 * the enabled one.
 *
 * Reads permissions for **one project**. {@link useCanInCompany} is the company-wide variant, which
 * the project picker's "New project" needed — `project:create` is COMPANY scope and there is no
 * project to ask about.
 */
export interface Gate {
  /** True when the caller holds the permission — or when the answer could not be obtained. */
  can: (permission: PermissionKey) => boolean;
  /** The tooltip for a denied action. `undefined` when it is allowed and needs no explanation. */
  reason: (permission: PermissionKey) => string | undefined;
  /**
   * The same question asked **about a row at a given scope** — the client half of the server's
   * `canDoOnScope`.
   *
   * A qualified grant is held *and* restricted: ~~a Test Engineer's `artifact:edit` is
   * `PLAN_SCOPE`, so {@link can} says true (it is held) while a project-scope write is refused~~ →
   * **`V52` (§210) de-qualified that last grant, so nothing carries `PLAN_SCOPE` any more** and
   * this arm currently answers what {@link can} answers — kept live for {@link canOnAuthored}'s
   * reason, one method down. Until §65 the
   * response could not say so and this method could not exist — the control enabled and the submit
   * failed, which is the one place §31's disable-don't-hide was knowingly unfollowable.
   *
   * **Mirrors the server rather than reinterpreting it.** What `PLAN_SCOPE` *means* is decided in
   * `IdentityProjectAccess.canDoOnScope`; this is the same three arms in the same order, so a screen
   * asks a question rather than owning an authorization rule. A second reading of a qualifier is the
   * bug class plan §6b names, and it is no less one for living on the client.
   */
  canOnScope: (permission: PermissionKey, rowIsPlanScoped: boolean) => boolean;
  /** The tooltip for a scope-denied action, distinguishing "not held" from "not here". */
  reasonOnScope: (permission: PermissionKey, rowIsPlanScoped: boolean) => string | undefined;
  /**
   * The same question asked **about a row's author** — the client half of the server's
   * `canDoOnAuthored`, and {@link canOnScope}'s twin over the other restrictive axis.
   *
   * **No grant is `OWN`-qualified since `V51` (§207)**, so this currently answers exactly what
   * {@link can} answers, for every permission and every row. It is kept rather than deleted for
   * the reason the server keeps its arm: the qualifier is *data*, and a client that hard-coded
   * "authorship never matters" would silently disagree with a future `OWN` grant — showing an
   * enabled Delete that 403s, which is the failure this hook exists to prevent, only inverted.
   *
   * ~~A Test Engineer's `case:delete` is `OWN` (roles spec §A5), so {@link can} says true — they
   * do delete cases — while deleting a *colleague's* case is refused. Until `createdBy` reached
   * the wire the client could not tell those apart, so Delete enabled on every row and the caller
   * learned which were theirs by being refused.~~ That was the state from §65 until 2026-08-14.
   *
   * **`authorId` may be null and that arm is the interesting one** *if a grant ever carries `OWN`
   * again.* `test_case.created_by` is nullable, and the server answers a null author `false` for
   * an `OWN` grant — a grant covering "your own work" must not stretch to work nobody can
   * attribute. Passing the author through unchanged, rather than defaulting it, is what keeps the
   * two sides agreeing on that row.
   */
  canOnAuthored: (permission: PermissionKey, authorId: string | null | undefined) => boolean;
  /** The tooltip for an authorship-denied action, distinguishing "not held" from "not yours". */
  reasonOnAuthored: (
    permission: PermissionKey,
    authorId: string | null | undefined,
  ) => string | undefined;
  /**
   * Whether the answer has not arrived yet — **"not yet", which is not "no"**.
   *
   * Every verdict above is fail-closed while pending, which is right for *disabling a control* and
   * wrong for anything that acts on the verdict once. A dialog that moves the user off a scope
   * they may not use must not move everybody off it during the load; that defect shipped into this
   * file's first draft and two unrelated tests caught it, because the form silently submitted at
   * the wrong scope rather than showing anything.
   *
   * So this is exposed rather than inferred. Use it only to *defer* a decision — never to grant
   * one, or the enabled-then-disabled flash this hook's header rules out comes back.
   */
  pending: boolean;
}

export function useCan(projectId: string | undefined): Gate {
  // `isPending` covers the disabled case too (no projectId → the query never runs), which is the
  // right answer: without a project there is nothing to be permitted in.
  return useGate(useMyPermissions(projectId), 'in this project');
}

/**
 * The same verdict asked **of the company**, with no project in the question.
 *
 * Its own hook rather than a scope argument on {@link useCan}, because the two read different
 * endpoints' answers and a screen must not be able to slip from one to the other by passing an
 * `undefined` id. The three states, the fall-open on failure and the asymmetry behind it are
 * identical — that part is shared, so a change to the policy cannot land in one and not the other.
 *
 * The sentence differs, and it has to: "you don't have permission to create projects in this
 * project" would send a Test Lead to ask their project admin for something only a Company Admin can
 * grant.
 */
export function useCanInCompany(enabled = true): Gate {
  return useGate(useMyCompanyPermissions(enabled), 'in this company');
}

/**
 * `userId.equals(authorId)` — the server's `OWN` arm, with its null behaviour written out.
 *
 * **Both absences answer false, and neither is an oversight.** A case with no recorded author
 * (`test_case.created_by` is nullable) belongs to nobody, so an `OWN` grant does not reach it; a
 * caller with no id has no session and is about to be sent to sign in. `==`-style coercion would
 * make those two absences equal each other and hand an unauthenticated render a Delete button on
 * every unauthored case.
 */
function isTheAuthor(callerId: string | undefined, authorId: string | null | undefined): boolean {
  return callerId != null && authorId != null && callerId === authorId;
}

/** The one implementation of the three states, so the two scopes cannot drift apart. */
function useGate(query: UseQueryResult<EffectivePermissions>, where: string): Gate {
  const pending = query.isPending && !query.isError;
  const held = query.data?.permissions;
  // The `OWN` arm compares the row's author against the caller, so the caller's id has to be in
  // scope. Read here rather than passed in by each screen: an id supplied at the call site is one
  // a call site could supply the *wrong* one of, and "which user is this" already has exactly one
  // answer in the app. The non-throwing reader, deliberately — see its own header for why making
  // every gated component require <AuthProvider> is the wrong price for one arm of one grant.
  const callerId = useCurrentUserId();

  return useMemo<Gate>(() => {
    /**
     * The caller's qualifier for one key, or `undefined` for "no answer" — which covers both
     * *pending* and *failed*, the two states with no verdict to give. Not held is `null`.
     *
     * Three-valued on purpose: collapsing "no answer" into "not held" would make every gate below
     * fail closed while the query is in flight *and* when it errors, and the failure policy above
     * is deliberately the opposite.
     */
    const qualifierOf = (permission: PermissionKey) => {
      if (pending || !held) return undefined;
      return held.find((entry) => entry.key === permission)?.qualifier ?? null;
    };

    return {
      pending,
      can: (permission) => {
        const qualifier = qualifierOf(permission);
        if (pending) return false;
        return qualifier === undefined || qualifier !== null;
      },
      reason: (permission) => {
        if (pending) return 'Checking your permissions…';
        const qualifier = qualifierOf(permission);
        if (qualifier === undefined || qualifier !== null) return undefined;
        return `You don't have permission to ${permissionLabels[permission]} ${where}.`;
      },

      // The same three arms as the server's canDoOnScope, in the same order. OWN is not among
      // them: it restricts by who wrote the row, which a scope question has nothing to compare
      // against, and no key is granted with two different restrictive qualifiers. Reaching it
      // would mean the catalogue changed, so it falls open — a wrong enable costs one 403 in the
      // dialog the user is looking at, and this whole hook is an affordance layer.
      canOnScope: (permission, rowIsPlanScoped) => {
        if (pending) return false;
        const qualifier = qualifierOf(permission);
        if (qualifier === undefined) return true;
        if (qualifier === null) return false;
        return qualifier === 'PLAN_SCOPE' ? rowIsPlanScoped : true;
      },
      reasonOnScope: (permission, rowIsPlanScoped) => {
        if (pending) return 'Checking your permissions…';
        const qualifier = qualifierOf(permission);
        if (qualifier === null) {
          return `You don't have permission to ${permissionLabels[permission]} ${where}.`;
        }
        if (qualifier === 'PLAN_SCOPE' && !rowIsPlanScoped) {
          // Deliberately a different sentence from the one above. "You don't have permission to
          // manage artifacts" is false for this caller — they manage them every cycle — and
          // sending them to ask an administrator for a grant they already hold is worse than
          // saying nothing. This names the restriction instead, because the fix is one control
          // away and it is theirs to make.
          return `Your ${permissionLabels[permission]} permission covers a single plan, not the whole project.`;
        }
        return undefined;
      },

      // The same three arms as the server's canDoOnAuthored, in the same order. PLAN_SCOPE is not
      // among them, for canOnScope's mirror-image reason: it restricts by where the row sits, which
      // an authorship question has nothing to compare against. It falls open rather than throwing —
      // the server throws there because it is deciding, and this is only deciding whether to grey
      // out a button.
      canOnAuthored: (permission, authorId) => {
        if (pending) return false;
        const qualifier = qualifierOf(permission);
        if (qualifier === undefined) return true;
        if (qualifier === null) return false;
        return qualifier === 'OWN' ? isTheAuthor(callerId, authorId) : true;
      },
      reasonOnAuthored: (permission, authorId) => {
        if (pending) return 'Checking your permissions…';
        const qualifier = qualifierOf(permission);
        if (qualifier === null) {
          return `You don't have permission to ${permissionLabels[permission]} ${where}.`;
        }
        if (qualifier === 'OWN' && !isTheAuthor(callerId, authorId)) {
          // Names the restriction rather than denying the permission, for reasonOnScope's reason:
          // "you don't have permission to delete test cases" is false for this caller — they delete
          // their own every day — and would send them to ask for a grant they already hold. This
          // says which cases theirs covers, which is the fact they are missing. Deliberately close
          // to the server's own 403 copy, so the disabled tooltip and the refusal agree.
          return `You can only ${permissionLabels[permission]} you created.`;
        }
        return undefined;
      },
    };
  }, [pending, held, where, callerId]);
}
