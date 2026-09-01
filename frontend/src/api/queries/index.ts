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
  useChangeMyPassword,
  useCurrentUser,
  useDirectoryLogin,
  useInvitationPreview,
  useMyCompanyPermissions,
  useMyPermissions,
  useMySocialIdentities,
  useSignOutOtherSessions,
  useSocialProviders,
  useSsoProviders,
  useStartSocialLink,
  useUnlinkSocialIdentity,
} from './identity';
export {
  useClassification,
  useCreateClassificationValue,
  useCreateProject,
  useProject,
  useProjectMembers,
  useProjects,
  useRetireClassificationValue,
  useSetProjectArchived,
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
  useAttachCaseFile,
  useBulkCases,
  useCase,
  useCaseDraft,
  useCaseFeature,
  useCaseHistory,
  useCaseVersions,
  useCases,
  useCloneCase,
  useCreateCase,
  useCreatePlanCase,
  useProjectTags,
  useDeleteCase,
  useDeleteCaseFile,
  useDiscardCaseDraft,
  useFeatureImport,
  useLinkCaseExternally,
  useParseGherkin,
  useQuarantineCase,
  useSetCaseStatus,
  useRelabelCaseExternalLink,
  useUnlinkCaseExternal,
  useSaveCaseDraft,
  useReferencedCases,
  useRenderGherkin,
  useUpdateCase,
  useCaseSearch,
  isSearchable,
  type CaseSearchFilters,
  type BulkInput,
  type CaseHistoryResponse,
  type CaseReference,
  type CaseVersion,
  type ReferenceCases,
  type ReferencedCasesState,
} from './cases';
export {
  useConditions,
  useCreateCondition,
  useStepCorpus,
  useStepSuggestions,
  useUpdateCondition,
} from './library';
export {
  useAddReviewComment,
  useAssignReviewer,
  useProjectSettings,
  useRecordReviewVerdict,
  useReviewImpact,
  useReviews,
  useSetReviewCommentResolution,
  useUnassignReviewer,
  useUpdateProjectSettings,
} from './reviews';
export {
  useClonePlan,
  useCreatePlan,
  useDeletePlan,
  usePlan,
  usePlanStatistics,
  usePlans,
  useSetPlanArchived,
  useSetPlanStatus,
  useUpdatePlan,
  type CriterionInput,
  type TestPlanUpdateInput,
} from './plans';
export {
  MAX_EVIDENCE_BYTES,
  STEP_DETAIL_LIMIT,
  executionQueryOptions,
  useAssignExecution,
  useAttachEvidence,
  useBulkAssignExecutions,
  useCloneRun,
  useConfigurations,
  useCreateRun,
  useCreateRunSchedule,
  useDeleteEvidence,
  useDeleteRunSchedule,
  useExecution,
  useExecutionSteps,
  useLinkDefect,
  useRun,
  useRunSchedule,
  useRunSchedules,
  useRuns,
  useSetRunStatus,
  useSubmitResult,
  useUnlinkDefect,
  useUpdateRunSchedule,
  type DefectLinkInput,
  type EvidenceUploadInput,
  type ExecutionResultInput,
  type ExecutionSteps,
  type ExecutionStepsState,
} from './runs';
export {
  useArtifacts,
  useAttachDocPageFile,
  useAttachmentBlob,
  useCreateArtifact,
  useDeleteArtifact,
  useCreateDocPage,
  useDeleteDocPage,
  useDeleteDocPageFile,
  useDocPage,
  useDocPageSearch,
  useDocPages,
  useLinkBlock,
  useRelabelBlockLink,
  useLinkPlanDocument,
  usePlanDocument,
  useRegisterArtifactFile,
  useSetArtifactStatus,
  useUpdateArtifact,
  useSetDocPageStatus,
  useUnlinkBlock,
  useUnlinkPlanDocument,
  useUpdateDocPage,
} from './documentation';
export {
  useConnectTracker,
  useCreateIssueLink,
  useCreateTrackerBinding,
  useCreateTrackerIssue,
  useDeleteIssueLink,
  useDeleteTrackerBinding,
  useDisconnectTracker,
  useIssueLinks,
  useMyTrackerIdentities,
  useTestTrackerIdentity,
  useTrackerBindings,
  useTrackerConnectedMembers,
  useTrackerIssue,
  useTrackerIssueSearch,
  useUpdateTrackerBinding,
} from './tracker';
export {
  useCreateNotificationChannel,
  useDeleteNotificationChannel,
  useNotificationChannel,
  useNotificationChannels,
  useNotificationDeliveries,
  useUpdateNotificationChannel,
} from './integration';
export {
  useAutomationMappings,
  useConfirmAutomationMapping,
  useDeleteAutomationMapping,
  useIngestion,
  useIngestions,
} from './ingestion';
export {
  useAcceptProposal,
  useAiProposal,
  useAiProposals,
  useRejectProposal,
} from './proposals';
export {
  isJobFinished,
  TERMINAL_JOB_STATES,
  useDashboard,
  useDeleteReportDefinition,
  useGenerateReport,
  useReportCapabilities,
  useReportDefinitions,
  useReportJob,
  useReportJobs,
  useReportModel,
  useSaveReportDefinition,
} from './reporting';
export {
  useApiTokens,
  useEmailDomains,
  useGrantRole,
  useCreateTeam,
  useDeleteTeam,
  useInviteUser,
  useIdentityProviders,
  useIssueApiToken,
  useGrantees,
  useOrgChart,
  useRevokeApiToken,
  useSaveEmailDomains,
  useSaveIdentityProvider,
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
export { type AuditPage, useCompanyAuditEvents, useProjectAuditEvents } from './audit';
export { useBoard, useMyWork, usePutCardAnnotation, useTeamBoard } from './board';
/*
  The PROJECT board (012 M2). `board.ts` above is the composite board, and the two are a different
  endpoint, a different card shape and a different set of columns (D-a) — which is why this module
  carries the long name, ~~`boards.ts`~~ → `projectBoards.ts`.
*/
export {
  reorder,
  useBacklog,
  useBoardCards,
  useBoardConfig,
  useBoardConfigs,
  useBoards,
  useCreateBoard,
  useCreateLabel,
  useDeleteBoard,
  useDeleteLabel,
  useLabels,
  useMoveCard,
  usePutIssueLabels,
  useRankIssue,
  useUpdateBoard,
  useUpdateLabel,
  type RankPlacement,
} from './projectBoards';
/*
  Releases (013 M3). The QA-link hooks are here rather than in `issues.ts` because they read and
  write the edge readiness is computed over — the file that owns readiness owns them.
*/
export {
  useCaseVerifications,
  useCreateRelease,
  useDeleteRelease,
  useIssueTestCases,
  usePublishRelease,
  usePutIssueTestCases,
  useReadiness,
  useRelease,
  useReleases,
  useRoadmap,
  useUpdateRelease,
} from './releases';
export {
  EPIC_COMPLETION_LIMIT,
  useBulkUpdateIssues,
  useCreateIssue,
  useCreateIssueComment,
  useDeleteIssue,
  useEpicCompletions,
  useIssue,
  useIssueComments,
  useIssueDocLinks,
  useIssues,
  useProjectWorkflow,
  useRemoveWorkflowStatus,
  useReplaceWorkflow,
  useSetIssueWatch,
  useTransitionIssue,
  useUpdateIssue,
  type BulkIssueResult,
  type EpicCompletionsState,
  type IssueSort,
} from './issues';
