/**
 * Convenience aliases over the generated OpenAPI schema.
 *
 * Everything here resolves back to docs/specs/openapi.yaml — if a shape looks
 * wrong, fix the spec and re-run `npm run gen:api`, don't hand-edit schema.d.ts.
 */
import type { components } from './schema';

type S = components['schemas'];

// auth & identity
export type AuthResult = S['AuthResult'];
export type CurrentUser = S['CurrentUser'];
export type InvitationPreview = S['InvitationPreview'];
export type UserRef = S['UserRef'];
export type User = S['User'];
export type Team = S['Team'];
export type Role = S['Role'];
export type RoleAssignment = S['RoleAssignment'];
export type RoleAssignmentInput = S['RoleAssignmentInput'];
export type PermissionKey = S['PermissionKey'];
export type EffectivePermissions = S['EffectivePermissions'];
export type RoleName = Role['name'];
export type RoleScope = Role['scope'];

// project
export type Project = S['Project'];
export type ProjectInput = S['ProjectInput'];
export type ClassificationDimension = S['ClassificationDimension'];
export type ClassificationValue = S['ClassificationValue'];
export type CaseStatus = S['CaseStatus'];

// repository
export type Suite = S['Suite'];
export type TestCase = S['TestCase'];
export type TestCaseSummary = S['TestCaseSummary'];
export type TestStep = S['TestStep'];
export type Condition = S['Condition'];
export type CaseConditionRef = S['CaseConditionRef'];
export type SharedStep = S['SharedStep'];
export type BulkOperation = S['BulkOperation'];
export type BulkResult = S['BulkResult'];
export type EntityVersion = S['EntityVersion'];
export type VersionMarker = S['VersionMarker'];
export type Facet = S['Facet'];

// execution
export type TestPlan = S['TestPlan'];
export type TestPlanSummary = S['TestPlanSummary'];
export type TestRun = S['TestRun'];
export type TestRunDetail = S['TestRunDetail'];
export type TestExecution = S['TestExecution'];
export type TestExecutionSummary = S['TestExecutionSummary'];
export type StepResult = S['StepResult'];
export type Evidence = S['Evidence'];
export type Configuration = S['Configuration'];
export type Criterion = S['Criterion'];
export type ResultCounts = S['ResultCounts'];
export type DefectLink = S['DefectLink'];

// history & reporting
export type CaseHistorySummary = S['CaseHistorySummary'];
export type ExecutionHistoryEntry = S['ExecutionHistoryEntry'];
export type SuiteRunSummary = S['SuiteRunSummary'];
export type SuiteHistoryResponse = S['SuiteHistoryResponse'];
export type RepositoryDashboard = S['RepositoryDashboard'];
export type DashboardTile = S['DashboardTile'];
export type DashboardBreakdown = S['DashboardBreakdown'];
export type PlanStatistics = S['PlanStatistics'];

// documentation & artifacts
export type DocPage = S['DocPage'];
export type DocPageSummary = S['DocPageSummary'];
export type DocBlock = S['DocBlock'];
export type DocLink = S['DocLink'];
export type Artifact = S['Artifact'];

// work
export type WorkItem = S['WorkItem'];
export type WorkItemStatus = S['WorkItemStatus'];
export type WorkItemInput = S['WorkItemInput'];

// enums
export type LifecycleStatus = S['LifecycleStatus'];
export type AutomationStatus = S['AutomationStatus'];
export type Priority = S['Priority'];
export type StepFormat = S['StepFormat'];
export type StatusKind = S['StatusKind'];
export type HealthSignal = S['HealthSignal'];
export type AggregationPolicy = S['AggregationPolicy'];

export interface Paged<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  facets?: Facet[];
}

/** Case list filter state. Mirrored into the URL so views are shareable (plan §6o/§6q). */
export interface CaseFilters {
  q?: string;
  suiteId?: string;
  featureId?: string;
  testTypeId?: string;
  testLevelId?: string;
  /** One owner. Exists so the dashboard's by-owner bars link to the cases they counted (§6o). */
  ownerId?: string;
  lifecycleStatus?: LifecycleStatus[];
  automationStatus?: AutomationStatus[];
  priority?: Priority[];
  healthSignal?: HealthSignal[];
  releaseVersion?: string;
  page?: number;
  size?: number;
}
