/**
 * The data module. Every server read and write in the app is declared here — across the
 * per-domain files beside this one, re-exported through this barrel so `from '../../api/queries'`
 * remains the single import every screen uses.
 *
 * Rules this module exists to enforce in one place rather than per component:
 *
 *   - **Query keys carry every parameter the request depends on**, so a response for a
 *     filter the user has already replaced can never land and overwrite a newer one. This
 *     is a data-correctness property, not a caching nicety.
 *   - **Every call threads the cancel signal and a timeout budget**, chosen per endpoint
 *     class (typeahead vs interactive vs report), so no region can spin forever.
 *   - **Dependent queries are gated with `enabled`**, so they never fire with an
 *     `undefined` id and never sit in a skeleton because their parent failed.
 *   - **No server data goes into a client store** — this cache *is* the server state.
 *
 * **Why a directory rather than the one 700-line file it used to be.** The rules above are
 * properties of the *module*, not of any file, and they survive the split because `keys.ts` is
 * shared and every hook still lands here. What the split buys is that two people — or two
 * agents — adding hooks to different domains no longer edit the same file, which was the single
 * biggest write conflict in the repo. Adding a domain means adding a file and one line here.
 *
 * `keys` stays a **single** factory in `keys.ts`. Do not add a second one: an invalidation in
 * `cases.ts` targets keys `suites.ts` owns, and two factories drift into invalidations that
 * quietly match nothing.
 */
export { base, keys } from './keys';

export {
  useAcceptInvitation,
  useCurrentUser,
  useInvitationPreview,
  useMyCompanyPermissions,
  useMyPermissions,
} from './identity';
export {
  useClassification,
  useCreateClassificationValue,
  useCreateProject,
  useProject,
  useProjectMembers,
  useProjects,
  useRetireClassificationValue,
  useStatuses,
  useUpdateClassificationValue,
} from './projects';
export {
  useCreateSuite,
  useDeleteSuite,
  useRenameSuite,
  useSetSuiteStatus,
  useSuiteHistory,
  useSuites,
  type SuiteInput,
} from './suites';
export {
  useBulkCases,
  useCase,
  useCaseHistory,
  useCaseVersions,
  useCases,
  useCreateCase,
  useDeleteCase,
  useUpdateCase,
  type BulkInput,
  type CaseHistoryResponse,
  type CaseVersion,
} from './cases';
export { useConditions, useCreateSharedStep, useSharedSteps } from './library';
export {
  useAddReviewComment,
  useAssignReviewer,
  useProjectSettings,
  useRecordReviewVerdict,
  useReviews,
  useSetReviewCommentResolution,
  useUnassignReviewer,
  useUpdateProjectSettings,
} from './reviews';
export {
  useCreatePlan,
  useDeletePlan,
  usePlan,
  usePlanStatistics,
  usePlans,
  useSetPlanStatus,
  useUpdatePlan,
  type CriterionInput,
  type TestPlanUpdateInput,
} from './plans';
export {
  MAX_EVIDENCE_BYTES,
  useAttachEvidence,
  useCreateRun,
  useDeleteEvidence,
  useExecution,
  useRun,
  useRuns,
  useSetRunStatus,
  useSubmitResult,
  type EvidenceUploadInput,
  type ExecutionResultInput,
} from './runs';
export {
  useArtifacts,
  useCreateArtifact,
  useCreateDocPage,
  useDeleteDocPage,
  useDocPage,
  useDocPages,
  useSetDocPageStatus,
  useUpdateDocPage,
} from './documentation';
export { useDashboard } from './reporting';
export {
  useGrantRole,
  useCreateTeam,
  useDeleteTeam,
  useInviteUser,
  usePutTeamMember,
  useRemoveTeamMember,
  useRemoveUser,
  useRevokeRole,
  useRoleAssignments,
  useRoles,
  useTeams,
  useUpdateTeam,
  useUsers,
} from './admin';
export { useCreateWorkItem, useMoveWorkItem, useMyWork, useWorkItems } from './workItems';
