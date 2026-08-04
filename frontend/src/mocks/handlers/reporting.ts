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
 * **And PENDING → RUNNING → FAILED, added in §89 with the screen that renders it.** The report's
 * own parameters are validated inside the worker rather than at the request (§87), so a job asked
 * for with no resolvable `runId` fails carrying a sentence — which is the only way `FAILED` is
 * reachable without a backend, and `FAILED` is the state the plan names as this screen's
 * characteristic defect. A state that only ever appears in production is one nobody has seen the
 * rendering of.
 *
 * What mock mode structurally **cannot** prove is stated rather than inferred: it signs in as one
 * persona holding every key, so neither gate below can be observed to refuse anybody. The
 * verification queue's rows for `report:view` and `report:export` are therefore live-only.
 */
import { HttpResponse, delay, http } from 'msw';
import { db, newId, reporting } from '../db';
import { API, LATENCY, holdsOn, meRef, notFound, problem, requireAuth } from './shared';

/** The four the contract declares. `QUEUED` was never one of them. */
type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

interface MockJob {
  jobId: string;
  projectId: string;
  type: string;
  status: JobStatus;
  format: string;
  /**
   * Stored verbatim, exactly as the server freezes them onto the artifact — and read by `advance`,
   * which is what makes a FAILED job reachable here at all (see below).
   */
  parameters: Record<string, unknown>;
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
const FORMATS = ['MD', 'HTML'];

/** What each format is served as — the server takes both from the renderer that produced it. */
const MEDIA: Record<string, { type: string; extension: string }> = {
  MD: { type: 'text/markdown; charset=utf-8', extension: 'md' },
  HTML: { type: 'text/html; charset=utf-8', extension: 'html' },
  JSON: { type: 'application/json', extension: 'json' },
};

/** Resets on reload, like every other part of the mock store. */
const jobs = new Map<string, MockJob>();

/**
 * A saved report — `report_definition`, and §91's writer for it.
 *
 * Owner-scoped on the server: a definition belongs to the person who saved it and nobody else can
 * list, run or delete it. **Mock mode cannot show that**, because it signs in as one persona — so
 * "mine" and "every definition in this project" are the same set here, and the browser rows that
 * prove the bound are live-only. Stated rather than left to be discovered by an implementation that
 * drops the owner filter and passes everything the mock can check.
 */
interface MockDefinition {
  id: string;
  projectId: string;
  type: string;
  name: string;
  parameters: Record<string, unknown>;
}

const definitions = new Map<string, MockDefinition>();

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
    /*
      **The report's own parameters are checked HERE, not at the request, and that is the server's
      arrangement rather than a shortcut** (§87): a `runId` that is absent, or names a run in
      another project, cannot be refused without a database read, and doing that on the request
      path would mean a second copy of every report's rules. It surfaces as a FAILED job carrying
      the sentence.

      Reproducing it is what makes `FAILED` **reachable in mock mode at all**. §88's mock advanced
      PENDING → RUNNING → COMPLETED unconditionally, so the one job state a client is most likely
      to render wrong — the plan names it as F1's characteristic defect — could not be produced
      without a backend. A state that only ever appears in production is one nobody has seen the
      rendering of. This is the same call `tcmsFaults` exists for: build the switch before the
      behaviour it proves.
    */
    const runId = job.parameters.runId;
    if (typeof runId !== 'string' || !db.runs.some((run) => run.id === runId)) {
      job.status = 'FAILED';
      job.error =
        'This report needs a run to compute over, and no run with that id belongs to this '
        + 'project. Ask for it again naming a run from this project.';
      return;
    }
    job.status = 'COMPLETED';
    job.artifactId = newId();
    // Every completed job has a JSON model as well as what was asked for, so the screen and the
    // export are one model rendered twice (plan §6e). When JSON *is* what was asked for, both
    // fields name one artifact rather than two files that are equal only until they are not.
    job.modelArtifactId = job.format === 'JSON' ? job.artifactId : newId();
  }
}

/**
 * The stored model, in the shape §88 gave it: `{ header, report }`.
 *
 * The header is not decoration and is not the screen's to assemble — §6e makes it mandatory on the
 * output, and F2 must render the one the export printed rather than composing a second from the job
 * row. A mock that answered a bare `{ title, sections }` would let that screen be built against a
 * shape the server does not serve, which is §81's finding and §87's, one slice apart.
 */
function mockModel(job: MockJob) {
  return {
    header: {
      reportType: job.type,
      description: 'A single run: status breakdown, progress per tester, and results by suite.',
      company: 'Northwind Trading',
      projectScope: 'MOCK — Mock mode',
      scopeNote:
        'This report covers MOCK — Mock mode in full and no other project. A project grant is ' +
        'all-or-nothing, so a reader with access to this project sees the same numbers as any ' +
        'other; cross-project reports are not available in this version.',
      generatedBy: 'Mock Mode',
      generatedAt: '2026-08-04 12:00:00 UTC',
      // The selection is what the CLIENT sent, never what the report understood (§88) — so it is
      // read off the stored parameters rather than reconstructed from the job row.
      selection: Object.entries(job.parameters).map(([label, value]) => ({
        label,
        value: String(value),
      })),
    },
    report: {
      title: `Run summary — ${runKey(job)}`,
      metadata: [{ label: 'Pass rate (of recorded)', value: '50.0%' }],
      sections: [
        {
          heading: 'Results',
          narrative: 'Shares are of the 2 executions recorded in this run.',
          table: {
            columns: ['Status', 'Executions', 'Share'],
            rows: [
              ['PASSED', '1', '50.0%'],
              ['FAILED', '1', '50.0%'],
            ],
          },
          series: [
            {
              name: 'Executions by status',
              points: [
                { label: 'PASSED', value: 1 },
                { label: 'FAILED', value: 1 },
              ],
            },
          ],
        },
      ],
    },
  };
}

/** The key of the run a job was asked for, so the rendered title names the real subject. */
function runKey(job: MockJob): string {
  const run = db.runs.find((candidate) => candidate.id === job.parameters.runId);
  return run?.key ?? 'unknown run';
}

/**
 * The bytes of the requested format.
 *
 * HTML is a whole document rather than a fragment, because that is what the format IS (§88): one
 * self-contained file with no network dependency. A mock serving a snippet would make a download
 * look like it worked while producing something no browser opens as a report.
 */
function rendered(job: MockJob) {
  if (job.format === 'HTML') {
    return [
      '<!DOCTYPE html>',
      '<html lang="en">',
      `<head><meta charset="utf-8"><title>Run summary — ${runKey(job)}</title></head>`,
      `<body><h1>Run summary — ${runKey(job)}</h1>`,
      `<p>Generated in mock mode for job ${job.jobId}.</p>`,
      '</body>',
      '</html>',
      '',
    ].join('\n');
  }
  return `# Run summary\n\nGenerated in mock mode for job ${job.jobId}.\n`;
}

const definitionView = (definition: MockDefinition) => ({
  id: definition.id,
  type: definition.type,
  name: definition.name,
  parameters: definition.parameters,
  owner: meRef(),
});

const forbiddenToView = () =>
  problem(403, 'Not allowed', 'You do not have permission to view reports in this project.');

/** The server's own sentence, in one place because three handlers now make the same refusal. */
const unknownType = (type: string | undefined) =>
  `This server has no report called '${type ?? ''}'. It can produce: ${TYPES.join(', ')}.`;

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

  http.get(`${API}/projects/:projectId/reports`, async ({ request, params }) => {
    const denied = requireAuth(request);
    if (denied) return denied;

    const projectId = String(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('That project');
    if (!holdsOn(projectId, 'report:view')) return forbiddenToView();

    await delay(LATENCY);
    // Newest first, as the server orders it — the useful order on a list nobody has curated.
    return HttpResponse.json(
      [...definitions.values()]
        .filter((definition) => definition.projectId === projectId)
        .reverse()
        .map(definitionView),
    );
  }),

  http.post(`${API}/projects/:projectId/reports`, async ({ request, params }) => {
    const denied = requireAuth(request);
    if (denied) return denied;

    const projectId = String(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('That project');
    // report:view, not report:export — saving is not carrying off, and the persona that may read a
    // report on screen must be able to save the selection it reads (§91).
    if (!holdsOn(projectId, 'report:view')) return forbiddenToView();

    const body = (await request.json()) as {
      type?: string;
      name?: string;
      parameters?: Record<string, unknown>;
    };
    const errors: Record<string, string[]> = {};

    if (!body.type || !TYPES.includes(body.type)) {
      errors.type = [unknownType(body.type)];
    }
    if (!body.name || !body.name.trim()) {
      errors.name = ['Give this saved report a name.'];
    }

    await delay(LATENCY);
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    const definition: MockDefinition = {
      id: newId(),
      projectId,
      type: body.type as string,
      name: (body.name as string).trim(),
      parameters: body.parameters ?? {},
    };
    definitions.set(definition.id, definition);
    return HttpResponse.json(definitionView(definition), { status: 201 });
  }),

  http.delete(`${API}/projects/:projectId/reports/:definitionId`, async ({ request, params }) => {
    const denied = requireAuth(request);
    if (denied) return denied;

    const projectId = String(params.projectId);
    if (!db.projects.some((p) => p.id === projectId)) return notFound('That project');
    if (!holdsOn(projectId, 'report:view')) return forbiddenToView();

    const definition = definitions.get(String(params.definitionId));
    // Scoped by the project too: an id from a sibling project is a 404 here, not a delete served
    // through a gate that passed for a different project.
    if (!definition || definition.projectId !== projectId) return notFound('That saved report');

    await delay(LATENCY);
    definitions.delete(definition.id);
    // The jobs it produced keep working — ON DELETE SET NULL on both foreign keys (V27, V28), so
    // deleting a saved report never deletes the history of what it generated.
    return new HttpResponse(null, { status: 204 });
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

    const body = (await request.json()) as {
      definitionId?: string;
      type?: string;
      format?: string;
      parameters?: Record<string, unknown>;
    };
    const errors: Record<string, string[]> = {};

    // A named definition supplies the type and the base for the parameters (§91). Resolved before
    // the type is checked, because with one the type is the definition's and an absent `type` is no
    // longer a mistake.
    const saved = body.definitionId ? definitions.get(body.definitionId) : undefined;
    if (body.definitionId && (!saved || saved.projectId !== projectId)) {
      errors.definitionId = ['You have no saved report with that id in this project.'];
    } else if (saved && body.type && body.type !== saved.type) {
      errors.type = [
        `This saved report is a '${saved.type}'. Ask for that, or leave 'type' out and let the saved report say.`,
      ];
    } else if (!saved && (!body.type || !TYPES.includes(body.type))) {
      // The server's refusal, worded the same way and for the same reason: a job accepted for
      // something nothing can produce is a row that goes FAILED later, which a client cannot tell
      // apart from a broken server by watching it.
      errors.type = [unknownType(body.type)];
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
      type: saved ? saved.type : (body.type as string),
      status: 'PENDING',
      format: body.format as string,
      // Shallow, key by key at the top level — the server's merge, and the reason is the same: an
      // untyped object has no schema to say whether a nested value should be combined or replaced.
      parameters: saved ? { ...saved.parameters, ...(body.parameters ?? {}) } : (body.parameters ?? {}),
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
      const format = isModel ? 'JSON' : job.format;
      const media = MEDIA[format] ?? MEDIA.MD;
      const body = isModel ? JSON.stringify(mockModel(job), null, 2) : rendered(job);

      return new HttpResponse(body, {
        status: 200,
        headers: {
          'Content-Type': media.type,
          // `attachment`, as the server serves it: a report is a file somebody keeps, where
          // evidence is `inline` so a screenshot renders beside the step it failed on.
          'Content-Disposition': `attachment; filename="${job.type}-${artifactId}.${media.extension}"`,
        },
      });
    },
  ),
];
