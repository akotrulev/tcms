/**
 * The mock database, re-exported so `from './db'` stays the one import the handlers use.
 *
 * The store lives in `store.ts` and does not split (see its header); everything else here is a
 * per-domain reader or writer over it. A new domain means a new file and one line below — which
 * is the point: adding a mock for a new endpoint family no longer edits a 500-line file that
 * every other in-flight change is also editing.
 */
export { db, newId, reporting, users, configurations, statusByKind, uid, type MockInvitation } from './store';
export {
  allocateCaseKey,
  allocateDocPageKey,
  allocatePlanKey,
  allocateRunKey,
  allocateSuiteKey,
} from './keys';
export { descendantSuiteIds, suiteCaseCount, suiteOwnCaseCount, suiteTree } from './suites';
export { caseDetail, caseVersions, recordCaseVersion, writeCase } from './cases';
export { materialiseRun, planScopeCaseIds, recountRun, runExecutions } from './executions';
export { addWorkItem, personalBoard, teamBoard, workItems } from './workItems';
export {
  addComment,
  assignReviewer,
  commentExists,
  defaultProjectSettings,
  openReviewFor,
  recordVerdict,
  setResolution,
  threadRoot,
  reviewMoved,
  reviewsForSubject,
  saveProjectSettings,
  settingsForProject,
  unassignReviewer,
  type MockReview,
  type ReviewStanding,
} from './reviews';
