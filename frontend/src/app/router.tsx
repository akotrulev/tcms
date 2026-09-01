import { Skeleton } from '../components/ui';
import { Fragment, Suspense, lazy, type ReactNode } from 'react';
import { Navigate, Outlet, Route, Routes, useMatch, useParams } from 'react-router-dom';
import { AppShell } from './AppShell';
import { useProject } from '../api/queries/projects';
import { describeError } from '../lib/describeError';
import { RequireAuth } from '../auth/RequireAuth';
import { SessionGate } from '../auth/SessionGate';
import { AcceptInvitationPage } from '../features/auth/AcceptInvitationPage';
import { LoginPage } from '../features/auth/LoginPage';
import { SsoCallbackPage } from '../features/auth/SsoCallbackPage';
import { NotFoundPage } from '../features/NotFoundPage';
import { ProjectsPage } from '../features/projects/ProjectsPage';

/**
 * Route topology.
 *
 * `/login`, `/accept-invitation` and `/sso/callback` are the public routes — the second because
 * someone holding an invitation has no account to authenticate with, the third because somebody
 * arriving from their own issuer holds a token and nothing this app has yet confirmed. Everything else sits behind `<RequireAuth>` inside the
 * persistent `<AppShell>` — a layout, so the rail and its state survive navigation.
 *
 * Feature routes are **project-scoped** (`/projects/:projectId/…`) because a project is a real
 * navigation boundary — a whole client product or engagement, not a feature area — so the
 * breadcrumbs, the rail and permission scoping all read the project from the URL.
 *
 * Note the justification changed. It used to be "keys are ambiguous without a project: TC-42
 * exists in every project." That is no longer true — display keys are now sequential per
 * *company* and unique across all its projects (plan §6t), so `TC-42` identifies exactly one
 * case. The topology stayed because the boundary is meaningful on its own, not because a key
 * needs disambiguating. Flattening to `/cases/:caseId` is now *possible*; it was deferred so
 * the whole URL shape — including the `/c/{companySlug}` segment that arrives with backend
 * M6 — gets designed once rather than twice.
 *
 * Heavy screens are code-split. ~~The grid, the editor, the dashboard and the board each pull
 * in a sizable chunk of MUI X, and a user who only reviews plans should not pay for the DataGrid
 * on first load.~~ → T16 removed MUI X; the split stayed because the weight moved rather than
 * left. The editor carries the Gherkin document surface and its corpus, the board carries
 * `@dnd-kit`, the dashboard and the statistics page carry their own chart drawing, and the grid
 * carries TanStack Table — none of which belongs in the first load of someone who only reviews
 * plans. The boundaries are the same; only the reason for each is different.
 */

const CaseListPage = lazy(() =>
  import('../features/cases/CaseListPage').then((m) => ({ default: m.CaseListPage })),
);
const CaseSearchPage = lazy(() =>
  import('../features/cases/CaseSearchPage').then((m) => ({ default: m.CaseSearchPage })),
);
const CaseDetailPage = lazy(() =>
  import('../features/cases/CaseDetailPage').then((m) => ({ default: m.CaseDetailPage })),
);
const CaseEditorPage = lazy(() =>
  import('../features/cases/CaseEditorPage').then((m) => ({ default: m.CaseEditorPage })),
);
const FeatureImportPage = lazy(() =>
  import('../features/cases/FeatureImportPage').then((m) => ({ default: m.FeatureImportPage })),
);
const CaseHistoryPage = lazy(() =>
  import('../features/history/CaseHistoryPage').then((m) => ({ default: m.CaseHistoryPage })),
);
const SuiteHistoryPage = lazy(() =>
  import('../features/history/SuiteHistoryPage').then((m) => ({ default: m.SuiteHistoryPage })),
);
const ProjectOverviewPage = lazy(() =>
  import('../features/projects/ProjectOverviewPage').then((m) => ({
    default: m.ProjectOverviewPage,
  })),
);
const PlanListPage = lazy(() =>
  import('../features/plans/PlanListPage').then((m) => ({ default: m.PlanListPage })),
);
const PlanStatisticsPage = lazy(() =>
  import('../features/plans/PlanStatisticsPage').then((m) => ({ default: m.PlanStatisticsPage })),
);
const RunListPage = lazy(() =>
  import('../features/runs/RunListPage').then((m) => ({ default: m.RunListPage })),
);
const RunDetailPage = lazy(() =>
  import('../features/runs/RunDetailPage').then((m) => ({ default: m.RunDetailPage })),
);
const RunSchedulePage = lazy(() =>
  import('../features/runs/RunSchedulePage').then((m) => ({ default: m.RunSchedulePage })),
);
const ExecutionPage = lazy(() =>
  import('../features/runs/ExecutionPage').then((m) => ({ default: m.ExecutionPage })),
);
const DocsPage = lazy(() => import('../features/docs/DocsPage').then((m) => ({ default: m.DocsPage })));
const DocPageEditorPage = lazy(() =>
  import('../features/docs/DocPageEditorPage').then((m) => ({ default: m.DocPageEditorPage })),
);
const ReportsPage = lazy(() =>
  import('../features/reports/ReportsPage').then((m) => ({ default: m.ReportsPage })),
);
const IngestionsPage = lazy(() =>
  import('../features/ingestion/IngestionsPage').then((m) => ({ default: m.IngestionsPage })),
);
const ProposalsPage = lazy(() =>
  import('../features/proposals/ProposalsPage').then((m) => ({ default: m.ProposalsPage })),
);
const ProposalPage = lazy(() =>
  import('../features/proposals/ProposalPage').then((m) => ({ default: m.ProposalPage })),
);
const IngestionJobPage = lazy(() =>
  import('../features/ingestion/IngestionJobPage').then((m) => ({ default: m.IngestionJobPage })),
);
const ReportViewPage = lazy(() =>
  import('../features/reports/ReportViewPage').then((m) => ({ default: m.ReportViewPage })),
);
const ArtifactsPage = lazy(() =>
  import('../features/artifacts/ArtifactsPage').then((m) => ({ default: m.ArtifactsPage })),
);
const BoardPage = lazy(() => import('../features/board/BoardPage').then((m) => ({ default: m.BoardPage })));
const WorkTablePage = lazy(() =>
  import('../features/tracker/WorkTablePage').then((m) => ({ default: m.WorkTablePage })),
);
const IssueNewPage = lazy(() =>
  import('../features/tracker/IssueNewPage').then((m) => ({ default: m.IssueNewPage })),
);
const IssueDetailPage = lazy(() =>
  import('../features/tracker/IssueDetailPage').then((m) => ({ default: m.IssueDetailPage })),
);
const MyWorkPage = lazy(() => import('../features/board/MyWorkPage').then((m) => ({ default: m.MyWorkPage })));
const TeamBoardPage = lazy(() =>
  import('../features/board/TeamBoardPage').then((m) => ({ default: m.TeamBoardPage })),
);
const TeamsPage = lazy(() => import('../features/admin/TeamsPage').then((m) => ({ default: m.TeamsPage })));
const OrgChartPage = lazy(() =>
  import('../features/org-chart/OrgChartPage').then((m) => ({ default: m.OrgChartPage })),
);
const ApiTokensPage = lazy(() =>
  import('../features/admin/ApiTokensPage').then((m) => ({ default: m.ApiTokensPage })),
);
const SsoProvidersPage = lazy(() =>
  import('../features/admin/SsoProvidersPage').then((m) => ({ default: m.SsoProvidersPage })),
);
const ProjectAccessPage = lazy(() =>
  import('../features/admin/ProjectAccessPage').then((m) => ({ default: m.ProjectAccessPage })),
);
const AccountSecurityPage = lazy(() =>
  import('../features/account/AccountSecurityPage').then((m) => ({
    default: m.AccountSecurityPage,
  })),
);
const TrackerConnectionsPage = lazy(() =>
  import('../features/account/TrackerConnectionsPage').then((m) => ({
    default: m.TrackerConnectionsPage,
  })),
);
const ClassificationSettingsPage = lazy(() =>
  import('../features/settings/ClassificationSettingsPage').then((m) => ({
    default: m.ClassificationSettingsPage,
  })),
);
const GeneralSettingsPage = lazy(() =>
  import('../features/settings/GeneralSettingsPage').then((m) => ({
    default: m.GeneralSettingsPage,
  })),
);
const WorkflowSettingsPage = lazy(() =>
  import('../features/settings/WorkflowSettingsPage').then((m) => ({
    default: m.WorkflowSettingsPage,
  })),
);
const BoardSettingsPage = lazy(() =>
  import('../features/settings/BoardSettingsPage').then((m) => ({
    default: m.BoardSettingsPage,
  })),
);
const ReviewSettingsPage = lazy(() =>
  import('../features/settings/ReviewSettingsPage').then((m) => ({
    default: m.ReviewSettingsPage,
  })),
);
const TrackerSettingsPage = lazy(() =>
  import('../features/settings/TrackerSettingsPage').then((m) => ({
    default: m.TrackerSettingsPage,
  })),
);
const NotificationSettingsPage = lazy(() =>
  import('../features/settings/NotificationSettingsPage').then((m) => ({
    default: m.NotificationSettingsPage,
  })),
);
/**
 * The audit trail (§242) — **one lazy component behind two routes**, project-scoped and
 * company-scoped, which it tells apart by whether `useParams` yields a `projectId`.
 *
 * One chunk rather than two because the two screens are the same twelve columns behind different
 * gates; splitting them would ship the rendering twice and give the two copies somewhere to drift.
 */
/**
 * The user guide (§252) — a session-level screen like `/my-work` and `/account/security`: its
 * subject is the product, not a project, so any project segment in its URL would be an arbitrary
 * one and the rail's switcher would appear to change a page it cannot change.
 *
 * Lazy, and it earns it more than most: eleven sections of prose plus the role matrix are dead
 * weight on every session that never opens Help, which is most of them.
 */
const HelpPage = lazy(() => import('../features/help/HelpPage').then((m) => ({ default: m.HelpPage })));

const AuditLogPage = lazy(() =>
  import('../features/audit/AuditLogPage').then((m) => ({ default: m.AuditLogPage })),
);

/**
 * Remounts the case editor when the address names a different case.
 *
 * React Router reuses a route's component across a change of params, so moving from one case's
 * editor straight to another's kept the first case's document text, its `seeded` latch and its
 * dirty state while `caseTextKey` had already switched to the second case — and the autosave
 * effect then wrote case A's Gherkin into case B's local draft buffer. A `key` is the framework's
 * own answer to "this is a different subject, not new props for the same one".
 */
function KeyedByCase({ children }: { children: ReactNode }) {
  const { caseId } = useParams();
  return <Fragment key={caseId}>{children}</Fragment>;
}

/** Route-level fallback, shaped like a page so the shell doesn't jump while a chunk loads. */
function RouteFallback() {
  return (
    <div className="flex flex-col gap-4" data-testid="Route.Loading" aria-busy="true">
      <Skeleton className="h-10 w-60 rounded-sm" />
      <Skeleton className="h-[120px] rounded-sm" />
      <Skeleton className="h-[320px] rounded-sm" />
    </div>
  );
}

/**
 * Resolves `:projectId` before any project-scoped route renders, and sends a wrong address to the
 * not-found screen instead of into the app.
 *
 * `/projects/1/cases` used to render the complete furnished project shell — rail, project
 * switcher, breadcrumb, every nav group — around a project that does not exist, and then fail the
 * data underneath it. The reader was told they were somewhere they were not, and the exits the
 * shell offered all silently switched them to a different project rather than acknowledging the
 * problem. The shell asserting a project is a claim, so it is checked once here, above the pages,
 * rather than separately discovered by each of them.
 *
 * It is a **pathless** layout route because the project routes carry absolute paths rather than
 * nesting under a shared parent — so it owns no params of its own and reads the id from the
 * location instead.
 *
 * Only `isAddressWrong` diverts. A project that fails for any other reason — a 500, a timeout — is
 * a data failure the pages below already own, with region states, stale markers and retry;
 * replacing that with "page not found" would say something untrue about a project that exists.
 */
function ProjectGuard() {
  const nested = useMatch('/projects/:projectId/*');
  const root = useMatch('/projects/:projectId');
  const project = useProject(nested?.params.projectId ?? root?.params.projectId);

  if (!nested && !root) return <Outlet />;
  if (project.isPending) return <RouteFallback />;
  if (project.isError && describeError(project.error).isAddressWrong) return <NotFoundPage />;

  return <Outlet />;
}

export function AppRouter() {
  return (
    <SessionGate>
      <AppRoutes />
    </SessionGate>
  );
}

/**
 * Split from `AppRouter` so no route is matched — and no route's queries fire — until the
 * stored token has been confirmed against `/me`. See `SessionGate`.
 */
function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      {/*
        Public, and it has to be: the holder of an invitation has no account yet, by definition.
        The path matches the backend's `tcms.invitation.accept-path`, and the token arrives in
        the query string exactly as `InvitationProperties` builds the emailed link.
      */}
      <Route path="/accept-invitation" element={<AcceptInvitationPage />} />

      {/*
        Where an external sign-in lands (§6r, §222) — the third public route, and un-prefixed for
        the reason `/login` is: the person arriving here holds a token in a fragment and no company,
        so the segment M6d adds cannot be known until `/me` has answered.

        The path is `tcms.oidc.spa-callback-path` on the server side and the two must agree. The
        server builds the URL, so a rename here silently sends every federated sign-in to the
        not-found page — which is exactly the state §217 shipped in.

        Not lazy: it is sixty lines, and a code-split chunk between the issuer and the session is one
        more thing that can fail while somebody is signing in.
      */}
      <Route path="/sso/callback" element={<SsoCallbackPage />} />

      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route
            path="/"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ProjectsPage />
              </Suspense>
            }
          />
          <Route path="/projects" element={<Navigate to="/" replace />} />

          {/*
            §6l's cross-project board (§83), and the one feature route with **no project segment**.

            The comment above says the project segment is a real navigation boundary rather than a
            disambiguator, and that is exactly why this route sits outside it: this screen spans
            every project the caller can see, so any project in its URL would be an arbitrary one —
            naming a project in the breadcrumb that most of the cards are not from, and making the
            rail's project switcher appear to change a board it cannot change. `/admin/teams` is
            the precedent: a screen whose subject is not a project does not live under one.

            `/my-work` rather than `/board`, because `/projects/:projectId/board` already exists and
            two routes with one name for two scopes is the IA defect the rail's own Company/Project
            split was made to avoid.
          */}
          {/*
            The caller's own sign-in identities (V58) — a session-level screen like `/my-work`:
            its subject is the account, so any project or company segment in its URL would be an
            arbitrary one. The accept page's first-run leg lands here with `?welcome=1`.
          */}
          <Route
            path="/account/security"
            element={
              <Suspense fallback={<RouteFallback />}>
                <AccountSecurityPage />
              </Suspense>
            }
          />

          {/*
            The caller's own tracker credentials (004 D2) — beside `/account/security` and under the
            same reasoning: a credential belongs to a person, not to a project, so a project segment
            in this URL would be an arbitrary one and would suggest connecting again per project.
            Plural, because one account connects to as many hosts as its projects are bound to.
          */}
          <Route
            path="/account/trackers"
            element={
              <Suspense fallback={<RouteFallback />}>
                <TrackerConnectionsPage />
              </Suspense>
            }
          />

          {/*
            Two paths, one component. `/help` has no landing page of its own — it redirects to the
            first section — so it is declared here rather than as a `<Navigate>`: the redirect
            target is `helpSections[0]`, which lives with the content, and a literal path here
            would be a second copy of it that goes stale the day the ordering changes.
          */}
          <Route
            path="/help"
            element={
              <Suspense fallback={<RouteFallback />}>
                <HelpPage />
              </Suspense>
            }
          />
          <Route
            path="/help/:sectionId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <HelpPage />
              </Suspense>
            }
          />

          <Route
            path="/my-work"
            element={
              <Suspense fallback={<RouteFallback />}>
                <MyWorkPage />
              </Suspense>
            }
          />

          {/*
            §249's cross-project case search — the third route outside the project segment, for
            `/my-work`'s reason stated once more because it is the same reason: with no `projectId`
            its rows span every project the reader can open, so a project in the URL would be an
            arbitrary one, and the rail's project switcher would appear to change a result set it
            cannot change. Here the argument is stronger than on either board: not knowing which
            project holds the case is the entire premise of the screen.

            `/search` and not `/cases/search`: there is no top-level `/cases` in this SPA — a case
            is reached through its project — so a `/cases` prefix would imply a projectless case
            frame that does not exist. The API path is `/cases/search` because it is scoped by the
            resource it returns; a route is scoped by the frame it renders in.
          */}
          <Route
            path="/search"
            element={
              <Suspense fallback={<RouteFallback />}>
                <CaseSearchPage />
              </Suspense>
            }
          />

          {/*
            §6l's team board (§84) — the second route outside the project segment, and for the
            same reason: with no `projectId` its cards span every project the reader can open.

            **Beside `/admin/teams`, not beneath it.** Every affordance on that screen is gated on
            `company:teams`; this board is gated on nothing — any authenticated member of the
            company may open any team's board (§6k), and §84 found there is no key to gate it on
            that would not be a 403 nobody could grant their way out of. A screen every member may
            open, filed under a prefix that reads "administration", teaches the opposite of what is
            true.

            `/teams/:teamId/board` mirrors `/projects/:projectId/board` deliberately. That is not
            the collision `/my-work` refused: a bare `/board` would have been one name for two
            scopes, while a board whose subject is named by its own segment is unambiguous at both.
          */}
          <Route
            path="/teams/:teamId/board"
            element={
              <Suspense fallback={<RouteFallback />}>
                <TeamBoardPage />
              </Suspense>
            }
          />

          {/*
            One route for the epic, the story and the sub-task (011 T6) — and **outside the project
            segment**, which is the third route to sit there beside `/my-work` and
            `/teams/:teamId/board`.

            The contract addresses an issue by its display KEY rather than by a uuid, because that
            is what a person types into a commit message and what every screen shows; `project.key`
            is unique per company and the counter is per project, so the pair is unique in the only
            scope RLS lets a caller see. A project id in the path would therefore be a second copy
            of a fact the key already carries — and one a reader following `ACME-118` out of a
            commit message does not have.

            Which of the three screens it is depends on `type.level`, which the key does not say.
            That is the whole argument for one route rather than three: three would need a read to
            decide where to navigate, on the read being navigated to.
          */}
          <Route
            path="/issues/:key"
            element={
              <Suspense fallback={<RouteFallback />}>
                <IssueDetailPage />
              </Suspense>
            }
          />

          <Route element={<ProjectGuard />}>
          <Route
            path="/projects/:projectId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ProjectOverviewPage />
              </Suspense>
            }
          />

          {/* Repository */}
          <Route
            path="/projects/:projectId/cases"
            element={
              <Suspense fallback={<RouteFallback />}>
                <CaseListPage />
              </Suspense>
            }
          />
          {/*
            `new` is declared before `:caseId` so it is matched as a literal segment rather
            than as a case id — otherwise "New case" would fetch a case called "new" and 404.
          */}
          <Route
            path="/projects/:projectId/cases/new"
            element={
              <Suspense fallback={<RouteFallback />}>
                <CaseEditorPage mode="create" />
              </Suspense>
            }
          />
          {/*
            `import` is declared before `:caseId` for `new`'s reason above and with the same
            consequence if it is not: the segment would be matched as a case id, and the entry
            point would fetch a case called "import" and 404 (§255).
          */}
          <Route
            path="/projects/:projectId/cases/import"
            element={
              <Suspense fallback={<RouteFallback />}>
                <FeatureImportPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/cases/:caseId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <CaseDetailPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/cases/:caseId/edit"
            element={
              <Suspense fallback={<RouteFallback />}>
                <KeyedByCase>
                  <CaseEditorPage mode="edit" />
                </KeyedByCase>
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/cases/:caseId/history"
            element={
              <Suspense fallback={<RouteFallback />}>
                <CaseHistoryPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/suites/:suiteId/history"
            element={
              <Suspense fallback={<RouteFallback />}>
                <SuiteHistoryPage />
              </Suspense>
            }
          />

          {/* Execution */}
          <Route
            path="/projects/:projectId/plans"
            element={
              <Suspense fallback={<RouteFallback />}>
                <PlanListPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/plans/:planId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <PlanStatisticsPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/runs"
            element={
              <Suspense fallback={<RouteFallback />}>
                <RunListPage />
              </Suspense>
            }
          />
          {/*
            `/run-schedules`, NOT `/runs/schedules`. A schedule is not a run and is not nested under
            one — it outlives every run it makes, and the runs outlive it. The path is kept beside
            the run routes so the family reads together and the nested spelling cannot be added
            without seeing this one.
          */}
          <Route
            path="/projects/:projectId/run-schedules"
            element={
              <Suspense fallback={<RouteFallback />}>
                <RunSchedulePage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/runs/:runId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <RunDetailPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/runs/:runId/executions/:executionId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ExecutionPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/board"
            element={
              <Suspense fallback={<RouteFallback />}>
                <BoardPage />
              </Suspense>
            }
          />
          {/*
            §5's Work screen (011 D34) — **one route for five views**, which switch on `?view=` so
            the filter travels between them in the query string rather than in a store the URL
            cannot restore. Four of the five are not built in M1 and say which module builds them;
            `/board` above is the board that exists today and stays reachable, because a link that
            leads nowhere is worse than a tab that explains itself.

            `new` is declared before nothing here — the sub-path is a literal segment with no
            sibling parameter to be mistaken for, unlike `/cases/new`. It is still a child route
            rather than a `?new` on this one, because the create form is a page with its own title,
            its own breadcrumb and its own back-out.
          */}
          <Route
            path="/projects/:projectId/work"
            element={
              <Suspense fallback={<RouteFallback />}>
                <WorkTablePage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/work/new"
            element={
              <Suspense fallback={<RouteFallback />}>
                <IssueNewPage />
              </Suspense>
            }
          />

          {/* Documentation & artifacts */}
          <Route
            path="/projects/:projectId/docs"
            element={
              <Suspense fallback={<RouteFallback />}>
                <DocsPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/docs/:pageId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <DocsPage />
              </Suspense>
            }
          />
          {/*
            A route rather than a mode flag on the page above: editing a specification is a long
            form, and `ui-rules` puts those on a URL — bookmarkable, back-button-friendly, and
            reload-safe. The same call `CaseEditorPage` makes.
          */}
          <Route
            path="/projects/:projectId/docs/:pageId/edit"
            element={
              <Suspense fallback={<RouteFallback />}>
                <DocPageEditorPage />
              </Suspense>
            }
          />
          {/*
            Project administration. `/admin/teams` is the COMPANY view of the same access model and
            stays where it is; this is the per-project one, so it lives under the project segment
            like every other screen whose subject is a project. The two render through the same
            table and role reference, so the split is one of scope rather than of implementation.
          */}
          <Route
            path="/projects/:projectId/access"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ProjectAccessPage />
              </Suspense>
            }
          />

          {/*
            The project's own vocabulary. Under `/settings/` rather than beside `/access`, because
            it is the first of several — statuses and configurations are the same kind of screen and
            have no writer yet — and a flat `/classification` would have to be renamed the day the
            second one lands, breaking every link anyone had saved to the first.
          */}
          <Route
            path="/projects/:projectId/settings/classification"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ClassificationSettingsPage />
              </Suspense>
            }
          />

          {/*
            011 T7's two tracker settings screens. `general` is the project's identity and its issue
            types; `workflow` is story B1's statuses, their kinds and the legal moves between them.

            **Routed for everybody**, like Review beside them and for the same reason: both reads are
            gated on project visibility alone — every screen that renders a status picker needs the
            workflow document — and each page disables its controls for a caller without
            `issue:workflow` or `project:settings`. A route guarded by a permission would be the
            client deciding an authorization question the server already answers, and it would make
            one pasted link behave differently for two people with no explanation shown to either.
          */}
          <Route
            path="/projects/:projectId/settings/general"
            element={
              <Suspense fallback={<RouteFallback />}>
                <GeneralSettingsPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/settings/workflow"
            element={
              <Suspense fallback={<RouteFallback />}>
                <WorkflowSettingsPage />
              </Suspense>
            }
          />

          {/*
            012 T9's board settings — `settings-boards.html`. Routed for everybody, like General and
            Workflow above and for their reason: `listBoards` and `getBoardConfig` cost `board:read`,
            which every role holds, and the page renders read-only for a caller without
            `board:configure`. It is also the screen that explains what a board IS — a lens, owning
            no issue — which is the sentence a reader needs most when they cannot change one.
          */}
          <Route
            path="/projects/:projectId/settings/boards"
            element={
              <Suspense fallback={<RouteFallback />}>
                <BoardSettingsPage />
              </Suspense>
            }
          />

          {/*
            The second screen the comment above predicted. §6g's four review settings — the opt-in
            that decides whether the review panel exists at all, and the three rules that decide what
            approval requires. Under `/settings/` for the same reason, and the path shape is now
            carrying its weight rather than being speculative.
          */}
          <Route
            path="/projects/:projectId/settings/review"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ReviewSettingsPage />
              </Suspense>
            }
          />

          {/*
            The third, and the one the `/settings/` shape was worth having for (§218). Where this
            project sends word that a run finished or a task landed on somebody.

            **Routed for everybody, unlike the data behind it.** Every operation under it costs
            `integration:manage`, reads included, so a contributor reaching this URL gets the page
            and a sentence saying who can configure it — not a redirect and not a 404. A route
            guarded by a permission would be the client deciding an authorization question the
            server is already answering, and it would make a pasted link behave differently for two
            people with no explanation shown to either.
          */}
          <Route
            path="/projects/:projectId/settings/notifications"
            element={
              <Suspense fallback={<RouteFallback />}>
                <NotificationSettingsPage />
              </Suspense>
            }
          />

          {/*
            The fourth (004 Phase 1) — which tracker this project links and files into, and who on
            the team has connected to it. Routed for everybody for the reason above: the reads cost
            `integration:manage`, and a contributor who pastes this link gets the page and the
            sentence rather than a redirect nobody can explain.

            The credential itself is deliberately NOT here. It is a person's, and it lives at
            `/account/trackers`.
          */}
          <Route
            path="/projects/:projectId/settings/trackers"
            element={
              <Suspense fallback={<RouteFallback />}>
                <TrackerSettingsPage />
              </Suspense>
            }
          />

          {/*
            §6e's reports (§89). Under the project segment, unlike the two boards outside it: a
            report is computed over one project's facts and says so in its own header — "this
            report covers <project> in full and no other project" — so switching projects genuinely
            changes what this screen is about, which is the test the rail's own headings apply.
          */}
          <Route
            path="/projects/:projectId/reports"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ReportsPage />
              </Suspense>
            }
          />

          {/*
            One report on screen (§90, F2). A route rather than a dialog because a report is a
            document somebody reads and refers back to — and because a job id in the address bar is
            the only durable handle a report has while nothing lists jobs: the cards on the screen
            above live in component state and a reload loses them, where this URL survives one and
            can be sent to a colleague. It resolves through the server's own job-by-id operation, so
            it is not the client-side register §89 refused.
          */}
          <Route
            path="/projects/:projectId/reports/jobs/:jobId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ReportViewPage />
              </Suspense>
            }
          />

          {/*
            CI ingestion (§119). Under the project segment for the reason Reports is: what a report
            was posted *to* is one project, and switching projects genuinely changes what this
            screen is about.

            Two routes rather than a dialog over the list, because the two ENDPOINTS serve
            different shapes — the list carries no unmatched names at all — so opening a job is a
            fetch and not an expansion, and a job's URL is something a person pastes to whoever
            owns the tests that did not bind.
          */}
          <Route
            path="/projects/:projectId/ingestions"
            element={
              <Suspense fallback={<RouteFallback />}>
                <IngestionsPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/ingestions/:jobId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <IngestionJobPage />
              </Suspense>
            }
          />

          {/*
            Generated cases (§138). Under the project segment like every other content screen, and
            the path says `generated-cases` where the API says `ai-proposals`: the URL is read by
            people, and what is waiting there is a case somebody's agent wrote. `ai-proposal` is the
            table's name and belongs in the contract, not in a breadcrumb.

            Two routes rather than a dialog, for the ingestion pair's reason — the two endpoints
            serve different shapes, so opening a proposal is a fetch, and its URL is something a
            reviewer pastes to whoever owns the agent that filed it.
          */}
          <Route
            path="/projects/:projectId/generated-cases"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ProposalsPage />
              </Suspense>
            }
          />
          <Route
            path="/projects/:projectId/generated-cases/:proposalId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ProposalPage />
              </Suspense>
            }
          />

          <Route
            path="/projects/:projectId/artifacts"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ArtifactsPage />
              </Suspense>
            }
          />
          </Route>

          {/*
            The org chart (§6k) — **beside `/admin/teams`, not beneath it**, and the third route
            outside the project segment for the same reason as the second: it is company-wide and
            does not change when the rail's project switcher does.

            Every affordance on `/admin/teams` is gated on `company:teams`. This screen is gated on
            NOTHING — any member of the company may read the chart, and §84 found there is no key to
            gate it on that would not be a 403 nobody could grant their way out of. A screen every
            member may open, filed under a prefix that reads "administration", teaches the opposite
            of what is true, and the URL is the part of a screen people quote to each other.
            `/teams/:teamId/board` is the existing precedent for exactly this placement.
          */}
          <Route
            path="/org-chart"
            element={
              <Suspense fallback={<RouteFallback />}>
                <OrgChartPage />
              </Suspense>
            }
          />

          {/* Administration */}
          <Route
            path="/admin/teams"
            element={
              <Suspense fallback={<RouteFallback />}>
                <TeamsPage />
              </Suspense>
            }
          />

          {/*
            API tokens (§118). Company-scoped like `/admin/teams` and for the same reason: a token
            belongs to the company that issued it even when its `projectId` pins it to one project,
            so a route under `/projects/:projectId` would make the rail's project switcher appear
            to change a list it cannot change.
          */}
          <Route
            path="/admin/tokens"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ApiTokensPage />
              </Suspense>
            }
          />

          {/*
            Single sign-on (§222). Company-scoped like `/admin/teams` and `/admin/tokens`, and for
            the same reason: a provider belongs to the company, so a project segment would make the
            rail's switcher appear to change a list it cannot change.
          */}
          <Route
            path="/admin/sso"
            element={
              <Suspense fallback={<RouteFallback />}>
                <SsoProvidersPage />
              </Suspense>
            }
          />

          {/*
            The two audit trails (§242). Routed for everybody, like every other route in this file:
            a caller without the permission gets the page and a sentence saying who to ask, because
            a route guarded by a permission would be the client deciding an authorization question
            the server already answers — and would make a pasted link behave differently for two
            people with no explanation shown to either.

            The project one is under the project segment because what an act was performed *on* is
            one project, and switching projects genuinely changes what the screen is about. The
            company one is beside `/admin/tokens` for that entry's reason: `audit:view` is a
            company-wide key, so a project segment would make the rail's switcher appear to change
            a list it cannot change.
          */}
          <Route element={<ProjectGuard />}>
            <Route
              path="/projects/:projectId/audit"
              element={
                <Suspense fallback={<RouteFallback />}>
                  <AuditLogPage />
                </Suspense>
              }
            />
          </Route>
          <Route
            path="/admin/audit"
            element={
              <Suspense fallback={<RouteFallback />}>
                <AuditLogPage />
              </Suspense>
            }
          />

          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>

      {/* Unmatched public URL: send them to sign in rather than showing a chromeless 404. */}
      <Route
        path="*"
        element={
          <div className="p-8">
            <NotFoundPage />
          </div>
        }
      />
    </Routes>
  );
}
