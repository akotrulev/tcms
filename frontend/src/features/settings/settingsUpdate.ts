import type { ProjectSettings, ProjectSettingsUpdate } from '../../api/types';

/**
 * The whole settings document, as the `PUT` body — **the one place the field list is written.**
 *
 * `PUT /projects/{id}/settings` is a full replacement and the server reads an absent boolean as
 * `false`. So a page that saves one switch by sending one switch does not save one switch: it
 * silently turns every other one off. That is `ui-rules`' highest-severity form defect, and for a
 * gating rule it means somebody fixing a peer-review count quietly reopens a gate.
 *
 * Two screens edit this document — Review owns the §6g policy, General owns "Require review to
 * close issues" (D33) and "Track time on this project" (016 D-g), because that is where the mockup
 * and the reader look for them — so the list lives here rather than twice. A field added to the
 * contract and not added here is the failure this function exists to make impossible to have in
 * only one of the two.
 *
 * `trackTime` is the one field whose omission the server reads as **true** rather than false, and
 * it still has to be listed: the server's reading is the safety net for a client that dropped it,
 * not a licence to drop it. Sending the current value is what makes the Review screen's save leave
 * the switch where the administrator put it, whichever way round the server's default is.
 */
export function fullSettingsUpdate(
  current: ProjectSettings,
  patch: Partial<ProjectSettings> = {},
): ProjectSettingsUpdate {
  const next = { ...current, ...patch };
  return {
    reviewEnabledCase: next.reviewEnabledCase,
    reviewEnabledSuite: next.reviewEnabledSuite,
    reviewEnabledPlan: next.reviewEnabledPlan,
    reviewEnabledDoc: next.reviewEnabledDoc,
    reviewEnabledIssue: next.reviewEnabledIssue,
    requiredPeerReviews: next.requiredPeerReviews,
    authorMayApproveOwnWork: next.authorMayApproveOwnWork,
    editAfterApproval: next.editAfterApproval,
    requireApprovedCaseForRun: next.requireApprovedCaseForRun,
    trackTime: next.trackTime,
  };
}
