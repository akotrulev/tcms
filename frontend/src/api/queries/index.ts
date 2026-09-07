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
  useMyPermissionsAcross,
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
  useBoardCoverage,
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
/*
  Imports (014 M4). Two queries and three mutations — and the two mutations that look like reads are
  mutations because their bodies carry an API token, which must never become a cache key. The
  argument is in `imports.ts`' header and is the one thing in this module a reader is most likely to
  "simplify".
*/
export {
  useImportRun,
  useImportRuns,
  useListSourceProjects,
  usePreviewImport,
  useStartImport,
} from './imports';
export {
  EPIC_COMPLETION_LIMIT,
  useAgentTaskContext,
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
export { useConnectAssistant, useMyAssistants, useRevokeAssistant } from './account';
export {
  useAddRepository,
  useDeleteRepository,
  useProjectRepositories,
  useUpdateRepository,
} from './projectRepos';
export {
  useAmendTimeEntry,
  useCurrentTimesheetPeriod,
  useDecideTimesheet,
  useDeleteTimeEntry,
  useEpicTimeRollup,
  useIssueTimeRollup,
  useIssueTimeRollups,
  useLogTime,
  useSubmitTimesheet,
  useTimesheetApprovals,
  useTimesheetPeriod,
  useTimesheetPeriods,
} from './time';

/*
  Staffing (017 M7). Fifteen operations, and the split of scopes is the thing to read first: the
  `/staffing` reads are COMPANY scope behind `staffing:directory` and the `/projects/{id}` ones are
  PROJECT scope behind `staffing:read`. `useMyCapacity` is the exception at both ends — no staffing
  permission at all, because it is the caller's own figure (017 D-m).
*/
export {
  useAbsences,
  useCreateAbsence,
  useCreateAllocation,
  useCreateSkill,
  useDeleteAbsence,
  useDeleteAllocation,
  useMyCapacity,
  useProjectStaffing,
  usePutPersonSkills,
  usePutProfile,
  useSkills,
  useStaffingCapacity,
  useStaffingPeople,
  useStaffingPerson,
  useUpdateAllocation,
} from './staffing';

/*
  Clients (018 Mc). Eight operations, and the gate splits by SCOPE rather than by read-vs-write:
  everything under `/clients` costs the COMPANY-scope `client:read` / `client:create` /
  `client:edit` / `client:delete`, while the project picker and the attach both cost
  `project:settings`, which is PROJECT scope — story C2's key, because a Project Manager holds no
  company client key at all.

  Both reads are `enabled`-gated on the permission rather than allowed to 403: a refusal here would
  raise a notification for a control the reader was never meant to see.
*/
export {
  useAddClientContact,
  useClient,
  useClients,
  useCreateClient,
  useDeleteClient,
  useProjectClientOptions,
  useRemoveClientContact,
  useSetProjectClient,
  useUpdateClient,
} from './clients';

/*
  Billing (019 M8). Sixteen operations, and the split of gates is what to read first: everything
  here costs one of the seven COMPANY-scope money keys except two — `useProjectBudgetBurn` is
  `project:read`, because its shape has no amount and no currency member at all to withhold
  (019 D-h), and `useClientBilling` is `client:read`, because the server decides per caller whether
  its answer carries amounts and says so in `amountsVisible` (019 D-i).

  Every read is `enabled`-gated on the permission rather than allowed to 403: the rail entry into
  `/billing` is ABSENT without `billing:read` (019 D-o), so a screen that fired one anyway would
  report a refusal for a control the reader was never meant to see.

  There are no expense hooks. `/expenses` exists and is reachable from the CLI and MCP, but an
  expense is never an invoice line and no mockup draws a panel for one (019 D-j) — see the module
  header.
*/
export {
  useBillingSummary,
  useBudgets,
  useClearProjectBudget,
  useClientBilling,
  useCreateRateCard,
  useDeleteRateCard,
  useDiscardInvoice,
  useDraftInvoice,
  useInvoice,
  useInvoices,
  useIssueInvoice,
  useProjectBudgetBurn,
  useRateCards,
  useSetProjectBudget,
  useUpdateRateCard,
  type InvoiceFilter,
} from './billing';

/*
  Delivery analytics (020, M9). Three reads and no writes — nothing on that screen changes anything,
  so there is no invalidation helper to export beside them.
*/
export {
  CYCLE_TIME_WINDOWS,
  DEFAULT_CYCLE_TIME_DAYS,
  DEFAULT_FLOW_WEEKS,
  FLOW_WINDOWS,
  useBurnUp,
  useCycleTime,
  useFlow,
} from './analytics';
