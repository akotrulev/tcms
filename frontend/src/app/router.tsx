import { Box, Skeleton, Stack } from '@mui/material';
import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './AppShell';
import { RequireAuth } from '../auth/RequireAuth';
import { LoginPage } from '../features/auth/LoginPage';
import { NotFoundPage } from '../features/NotFoundPage';
import { ProjectsPage } from '../features/projects/ProjectsPage';

/**
 * Route topology.
 *
 * `/login` is the only public route. Everything else sits behind `<RequireAuth>` inside the
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
const ArtifactsPage = lazy(() =>
  import('../features/artifacts/ArtifactsPage').then((m) => ({ default: m.ArtifactsPage })),
);
const BoardPage = lazy(() => import('../features/board/BoardPage').then((m) => ({ default: m.BoardPage })));
const TeamsPage = lazy(() => import('../features/admin/TeamsPage').then((m) => ({ default: m.TeamsPage })));

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
    <Routes>
      <Route path="/login" element={<LoginPage />} />

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
