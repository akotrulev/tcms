import { Box, Skeleton, Stack } from '@mui/material';
import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './AppShell';
import { RequireAuth } from '../auth/RequireAuth';
import { SessionGate } from '../auth/SessionGate';
import { AcceptInvitationPage } from '../features/auth/AcceptInvitationPage';
import { LoginPage } from '../features/auth/LoginPage';
import { NotFoundPage } from '../features/NotFoundPage';
import { ProjectsPage } from '../features/projects/ProjectsPage';

/**
 * Route topology.
 *
 * `/login` and `/accept-invitation` are the public routes — the second because someone holding
 * an invitation has no account to authenticate with. Everything else sits behind `<RequireAuth>` inside the
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
 * Heavy screens are code-split. The grid, the editor, the dashboard and the board each pull
 * in a sizable chunk of MUI X, and a user who only reviews plans should not pay for the
 * DataGrid on first load.
 */

const CaseListPage = lazy(() =>
  import('../features/cases/CaseListPage').then((m) => ({ default: m.CaseListPage })),
);
const CaseDetailPage = lazy(() =>
  import('../features/cases/CaseDetailPage').then((m) => ({ default: m.CaseDetailPage })),
);
const CaseEditorPage = lazy(() =>
  import('../features/cases/CaseEditorPage').then((m) => ({ default: m.CaseEditorPage })),
);
const CaseHistoryPage = lazy(() =>
  import('../features/history/CaseHistoryPage').then((m) => ({ default: m.CaseHistoryPage })),
);
const SuiteHistoryPage = lazy(() =>
  import('../features/history/SuiteHistoryPage').then((m) => ({ default: m.SuiteHistoryPage })),
);
const DashboardPage = lazy(() =>
  import('../features/dashboard/DashboardPage').then((m) => ({ default: m.DashboardPage })),
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
const ReportViewPage = lazy(() =>
  import('../features/reports/ReportViewPage').then((m) => ({ default: m.ReportViewPage })),
);
const ArtifactsPage = lazy(() =>
  import('../features/artifacts/ArtifactsPage').then((m) => ({ default: m.ArtifactsPage })),
);
const BoardPage = lazy(() => import('../features/board/BoardPage').then((m) => ({ default: m.BoardPage })));
const MyWorkPage = lazy(() => import('../features/board/MyWorkPage').then((m) => ({ default: m.MyWorkPage })));
const TeamBoardPage = lazy(() =>
  import('../features/board/TeamBoardPage').then((m) => ({ default: m.TeamBoardPage })),
);
const TeamsPage = lazy(() => import('../features/admin/TeamsPage').then((m) => ({ default: m.TeamsPage })));
const ProjectAccessPage = lazy(() =>
  import('../features/admin/ProjectAccessPage').then((m) => ({ default: m.ProjectAccessPage })),
);
const ClassificationSettingsPage = lazy(() =>
  import('../features/settings/ClassificationSettingsPage').then((m) => ({
    default: m.ClassificationSettingsPage,
  })),
);
const ReviewSettingsPage = lazy(() =>
  import('../features/settings/ReviewSettingsPage').then((m) => ({
    default: m.ReviewSettingsPage,
  })),
);

/** Route-level fallback, shaped like a page so the shell doesn't jump while a chunk loads. */
function RouteFallback() {
  return (
    <Stack sx={{ gap: 2 }} data-testid="Route.Loading" aria-busy="true">
      <Skeleton variant="text" width={240} height={40} />
      <Skeleton variant="rounded" height={120} />
      <Skeleton variant="rounded" height={320} />
    </Stack>
  );
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
          <Route
            path="/my-work"
            element={
              <Suspense fallback={<RouteFallback />}>
                <MyWorkPage />
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

          <Route
            path="/projects/:projectId"
            element={
              <Suspense fallback={<RouteFallback />}>
                <DashboardPage />
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
                <CaseEditorPage mode="edit" />
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

          <Route
            path="/projects/:projectId/artifacts"
            element={
              <Suspense fallback={<RouteFallback />}>
                <ArtifactsPage />
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

          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>

      {/* Unmatched public URL: send them to sign in rather than showing a chromeless 404. */}
      <Route
        path="*"
        element={
          <Box sx={{ p: 4 }}>
            <NotFoundPage />
          </Box>
        }
      />
    </Routes>
  );
}
