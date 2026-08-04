/**
 * The repository status dashboard and the report pipeline.
 *
 * ## What was wrong here until §87, and it was wrong in the way that is hardest to see
 *
 * `POST …/reports/generate` answered `{ id, status: 'QUEUED', format: 'PDF', requestedAt, … }`:
 * `id` where the contract says `jobId`, a `requestedAt` the contract does not declare, and
 * `QUEUED` — **not one of the four values `ReportJob.status` has ever had**. Nothing complained,
 * because nothing called it. That is §52's "the mock agrees with an idea of the feature" and §81's
 * finding, where the same class had been shipped and wrong for 28 slices; it is cheap to fix here
 * only because it is being fixed before the screen exists rather than after.
 *
 * ## The pipeline is real, not a stub, and that is the point of a stateful mock
 *
 * Generation is asynchronous by contract (§6e), so a mock that answered COMPLETED immediately would
 * make the polling loop — the whole client-side shape of this feature — impossible to develop or to
 * demonstrate. Jobs are held in module state and advance one state per poll: PENDING → RUNNING →
 * COMPLETED, after which the artifact route serves the two files a completed job has.
 *
 * What mock mode structurally **cannot** prove is stated rather than inferred: it signs in as one
 * persona holding every key, so neither gate below can be observed to refuse anybody. The
 * verification queue's rows for `report:view` and `report:export` are therefore live-only.
 */
import { HttpResponse, delay, http } from 'msw';
import { db, newId, reporting } from '../db';
import { API, LATENCY, holdsOn, notFound, problem, requireAuth } from './shared';

/** The four the contract declares. `QUEUED` was never one of them. */
type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

interface MockJob {
  jobId: string;
  projectId: string;
  type: string;
  status: JobStatus;
  format: string;
  /** Set when the job completes — the client never composes a content URL itself. */
  artifactId: string | null;
  modelArtifactId: string | null;
  error: string | null;
}

/**
 * What this mock can produce, mirroring the server's two registries.
 *
 * The refusals below read these rather than a literal beside each check, for the server's own
 * reason: a list is correct today and silently wrong the day another report or renderer lands,
 * because nothing would fail.
 */
const TYPES = ['run_summary'];
const FORMATS = ['MD'];

/** Resets on reload, like every other part of the mock store. */
const jobs = new Map<string, MockJob>();

const contentUrl = (projectId: string, artifactId: string) =>
  `${API}/projects/${projectId}/reports/artifacts/${artifactId}/content`;

const view = (job: MockJob) => ({
  jobId: job.jobId,
  type: job.type,
  status: job.status,
  format: job.format,
  downloadUrl: job.artifactId ? contentUrl(job.projectId, job.artifactId) : null,
  modelUrl: job.modelArtifactId ? contentUrl(job.projectId, job.modelArtifactId) : null,
  error: job.error,
});

/**
 * One tick of the worker, charged to whoever polls.
 *
 * The real worker runs on a schedule; here the poll is the only clock there is. Advancing one state
 * per poll rather than completing on the first is what makes the client's PENDING and RUNNING
 * renderings reachable at all — a mock that skipped them would leave two states that only ever
 * appear in production.
 */
function advance(job: MockJob) {
  if (job.status === 'PENDING') {
    job.status = 'RUNNING';
    return;
  }
  if (job.status === 'RUNNING') {
    job.status = 'COMPLETED';
    job.artifactId = newId();
    // Every completed job has a JSON model as well as what was asked for, so the screen and the
    // export are one model rendered twice (plan §6e). When JSON *is* what was asked for, both
    // fields name one artifact rather than two files that are equal only until they are not.
    job.modelArtifactId = job.format === 'JSON' ? job.artifactId : newId();
  }
}

/** The job an artifact belongs to — the server proves the project through this row, so this does. */
function jobForArtifact(projectId: string, artifactId: string) {
  return [...jobs.values()].find(
    (job) =>
      job.projectId === projectId &&
      (job.artifactId === artifactId || job.modelArtifactId === artifactId),
  );
}

export const reportingHandlers = [
  http.get(`${API}/projects/:projectId/dashboard`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);
    return HttpResponse.json({ ...reporting.dashboard, generatedAt: new Date().toISOString() });
  }),

  // Saved definitions are R5. `[]` is the honest answer rather than a stub — nothing writes them.
  http.get(`${API}/projects/:projectId/reports`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);
    return HttpResponse.json([]);
  }),

  http.post(`${API}/projects/:projectId/reports/generate`, async ({ request, params }) => {
    const denied = requireAuth(request);
    if (denied) return denied;

    const projectId = String(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('That project');
    if (!holdsOn(projectId, 'report:export')) {
      return problem(
        403,
        'Not allowed',
        'You do not have permission to generate reports in this project.',
      );
    }

    const body = (await request.json()) as { type?: string; format?: string };
    const errors: Record<string, string[]> = {};

    // Both refusals are the server's, worded the same way and for the same reason: a job accepted
    // for something nothing can produce is a row that goes FAILED later, which a client cannot
    // tell apart from a broken server by watching it.
    if (!body.type || !TYPES.includes(body.type)) {
      errors.type = [
        `This server has no report called '${body.type ?? ''}'. It can produce: ${TYPES.join(', ')}.`,
      ];
    }
    if (!body.format) {
      errors.format = [
        `Say which format to render. This server can produce: ${FORMATS.join(', ')}.`,
      ];
    } else if (body.format === 'PDF') {
      errors.format = [
        `PDF rendering is not installed on this server. It can produce: ${FORMATS.join(', ')}.`,
      ];
    } else if (!FORMATS.includes(body.format)) {
      errors.format = [
        `This server cannot render '${body.format}'. It can produce: ${FORMATS.join(', ')}.`,
      ];
    }

    await delay(LATENCY);
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    const job: MockJob = {
      jobId: newId(),
      projectId,
      type: body.type as string,
      status: 'PENDING',
      format: body.format as string,
      artifactId: null,
      modelArtifactId: null,
      error: null,
    };
    jobs.set(job.jobId, job);
    return HttpResponse.json(view(job), { status: 202 });
  }),

  http.get(`${API}/projects/:projectId/reports/jobs/:jobId`, async ({ request, params }) => {
    const denied = requireAuth(request);
    if (denied) return denied;

    const projectId = String(params.projectId);
    if (!holdsOn(projectId, 'report:view')) {
      return problem(
        403,
        'Not allowed',
        'You do not have permission to view reports in this project.',
      );
    }

    // Scoped by the project as well as by the id, like the server: a job id from a sibling project
    // is a 404 rather than something served through a gate evaluated against a different project.
    const job = jobs.get(String(params.jobId));
    if (!job || job.projectId !== projectId) return notFound('That report job');

    await delay(LATENCY);
    advance(job);
    return HttpResponse.json(view(job));
  }),

  http.get(
    `${API}/projects/:projectId/reports/artifacts/:artifactId/content`,
    async ({ request, params }) => {
      const denied = requireAuth(request);
      if (denied) return denied;

      const projectId = String(params.projectId);
      if (!holdsOn(projectId, 'report:export')) {
        return problem(
          403,
          'Not allowed',
          'You do not have permission to generate reports in this project.',
        );
      }

      const artifactId = String(params.artifactId);
      const job = jobForArtifact(projectId, artifactId);
      if (!job) return notFound('That report');

      await delay(LATENCY);
      const isModel = job.modelArtifactId === artifactId && job.artifactId !== artifactId;
      const body = isModel
        ? JSON.stringify({ title: 'Run summary', sections: [] }, null, 2)
        : `# Run summary\n\nGenerated in mock mode for job ${job.jobId}.\n`;

      return new HttpResponse(body, {
        status: 200,
        headers: {
          'Content-Type': isModel ? 'application/json' : 'text/markdown; charset=utf-8',
          // `attachment`, as the server serves it: a report is a file somebody keeps, where
          // evidence is `inline` so a screenshot renders beside the step it failed on.
          'Content-Disposition': `attachment; filename="${job.type}-${artifactId}.${isModel ? 'json' : 'md'}"`,
        },
      });
    },
  ),
];
