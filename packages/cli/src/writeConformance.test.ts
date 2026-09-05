/**
 * Surface spec §8.3 over the command registry — *every create arm sends a key, every edit arm sends
 * `If-Match`, over the registry rather than per command*.
 *
 * **Four comments in this package have cited this file by name since §139 and it did not exist**
 * (`commands.ts`, `context.ts`, `writes.ts`, `test/harness.ts`). The check itself did — as a
 * `describe` inside `writes.test.ts`, among forty behaviour tests — so unlike its counterpart in
 * `@tcms/mcp` this half was real and only misfiled. It moves here, under the name everything already
 * gives it, and the two registries' checks now live in identically-named files in the two packages,
 * which is the arrangement `tool.ts`'s header claims and §203's pairing rule makes worth having.
 *
 * ## The second question, added with the move
 *
 * The rule was *did the command send what its `kind` implies*. It is now also **does the operation it
 * wrote to declare that header** — the half that found `tcms_report.generate` sending an
 * `Idempotency-Key` to an endpoint with no such parameter. Nothing in this registry was in that state
 * and the assertion is a guard here; it is written on both sides anyway, because a rule proved on one
 * surface and absent from the other is how the two come to disagree about what a create is.
 *
 * ## The table is hand-maintained and the check fails on what it does not know
 *
 * A `Command` says what it *is*, never how to invoke it — the flags a `plan edit` needs and the reads
 * a `result record` makes are properties of the handler. So {@link INVOCATIONS} is written out, and a
 * write command with no entry is a **failure** rather than a skip: a conformance check that silently
 * ignores what it does not recognise is a check on its own table.
 */
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import {
  contractPathOfRequest,
  declaredHeadersOf,
} from '../../client/src/test/contractBodies.js';
import {
  absence,
  allocation,
  board,
  boardSummary,
  cardMoveResult,
  caseDetail,
  casePage,
  caseStatus,
  caseSummary,
  currentUser,
  docBlock,
  docPage,
  docPageSummary,
  execution,
  executionSummary,
  label,
  rankResult,
  release as releaseFixture,
  releaseSummary,
  userRef,
  issue,
  issueComment,
  issueTestCase,
  plan,
  timeEntry,
  timesheetPeriod,
  projectWorkflow,
  proposal,
  review,
  run as runFixture,
  runDetail,
  suite,
  client as clientFixture,
  clientContact,
  budget,
  expense,
  invoiceDetail,
  rateCard,
  rateCardSlot,
} from '../../client/src/test/fixtures.js';
import { COMMANDS } from './commands.js';
import { EXIT } from './main.js';
import { PROJECT, api, mainWith, recorder, type Answer } from './test/harness.js';

/** The headers each `kind` is a promise about — the MCP registry's check states it identically. */
const IMPLIED: Record<string, readonly string[]> = {
  create: ['idempotency-key'],
  edit: ['if-match'],
  // Neither, and asserted rather than left unsaid: the contract records that these are
  // preconditioned on the state, so a header here would refuse a request over a version the caller
  // never claimed to have based anything on.
  state: [],
  delete: [],
};

/** Both halves, so a header sent where none is implied fails too. */
const WATCHED = ['idempotency-key', 'if-match'] as const;

/**
 * Write commands whose endpoint offers no header their `kind` implies, with what that costs.
 *
 * ~~**Empty, and that is a measurement rather than an absence**: all five creates in this registry
 * reach one of the eleven operations that declare the parameter.~~ → **two members since 013 T4**,
 * and both are `edit` arms with nothing to precondition on rather than creates with no key.
 *
 * Both are bounded by the endpoint being a **true delta**: an absent field means *leave it*, so two
 * concurrent editors lose only the fields they both named. Closing either is a contract change on
 * both the response and the request — a `lockVersion` on the resource and an `If-Match` on the
 * operation — not a header this file could start sending.
 */
const CREATE_WITHOUT_A_KEY: Record<string, string> = {
  /* Billing (019 T1). Five entries, and each names what a lost or raced write actually costs. */
  'billing draft': 'POST /invoices declares no Idempotency-Key, the same scope wall as '
    + '`client create` below: the parameter deduplicates within `(company, project, key)` and an '
    + 'invoice is addressed to a CLIENT. There is no constraint standing in for it either — two '
    + 'drafts over one period for one client are a legitimate state — so a lost response leaves a '
    + 'second draft. It CANNOT double-bill: a draft stores no lines, both drafts offer the same '
    + 'hours, and whichever is issued first takes them (019 D-c). The second recomputes on its next '
    + 'read with those hours gone and the invoice that took them named.',
  'expense record': 'POST /expenses declares no Idempotency-Key, and this is the one entry in this '
    + 'map the parameter\'s own scope does NOT excuse: an expense is addressed to a project, so the '
    + 'header could be offered. It is not, because nothing in the product reads an expense back '
    + 'except `expense list` (019 D-j), so a duplicate is visible to and removable by the same '
    + 'caller that made it. Closing it is one contract line whenever an expense gains a reader.',
  'budget set': 'PUT /projects/{projectId}/budget declares no If-Match, and `Budget` carries no '
    + '`lockVersion` on the wire to precondition on — the wall `client edit` meets on the MCP side. '
    + 'Bounded by the row: a budget is a basis, an amount and a currency, so two people setting one '
    + 'at once lose a ceiling rather than a body of work, and the loser can see the winner\'s value '
    + 'on the next `billing budgets`.',
  'rate-card set': 'PUT /rate-cards/{rateCardId} declares no If-Match and `RateCard` carries no '
    + '`lockVersion`. WORSE than `budget set` above and stated rather than implied: the endpoint is '
    + 'a FULL replacement including the entries, so two people editing one card at once lose a '
    + 'whole rate card and not one field of it. The POST half of this command meets the create wall '
    + 'instead, where `uq_rate_card_client` bounds it — a raced create answers 409 rather than '
    + 'giving one client two cards.',
  'client create': 'POST /clients declares no Idempotency-Key, and the parameter\'s own scope is '
    + 'the reason `staffing absence` below gives: it deduplicates within `(company, project, key)` '
    + 'and a client is addressed to no project. What a lost response costs is bounded by a '
    + 'CONSTRAINT rather than by a header — `uq_client_name UNIQUE (company_id, name)` means the '
    + 'retry answers 400 on `name` instead of recording Acme Retail twice.',
  'client contact-add': 'POST /clients/{clientId}/contacts, same scope wall, and this one has no '
    + 'constraint behind it: `client_contact` is deliberately NOT unique on email, because two '
    + 'people at one client may share invoices@. So a lost response leaves a duplicate contact — '
    + 'one row, on a list the client page renders in full, with a delete beside it.',
  'staffing absence': 'POST …/absences declares no Idempotency-Key, and the parameter\'s own scope '
    + 'is the reason: it deduplicates within `(company, project, key)` and an absence is not '
    + 'addressed to a project. What a lost response costs is a duplicate row over the same days, '
    + 'which double-counts nothing — capacity is computed per DAY from the absences covering it '
    + '(017 D-a) — and which is on the person\'s own list to remove. It is the first entry here '
    + 'that is a CREATE rather than an edit with nothing to precondition on.',
  'release edit': 'PATCH /releases/{releaseId} declares no If-Match and `Release` carries no '
    + 'lockVersion, so two people renaming one version at once lose a rename.',
  'issue release': 'PATCH /issues/{key} declares no If-Match and `Issue` carries no lockVersion — '
    + 'the same wall `tcms_write_issue.update` meets. The field is one reference, so the loss is '
    + 'bounded to two people retargeting one issue in the same moment.',
  'issue attach-pr': 'PATCH /issues/{key} again, so it meets the same wall as `issue release` above '
    + 'and is listed separately rather than covered by implication. Narrower still: the field only '
    + 'ever goes from empty to a URL, and the loss is a link to a pull request that is still on the '
    + 'history as an `issue.pr_url_set` row.',
};

describe('write conformance — surface spec §8.3', () => {
  const writes = COMMANDS.filter((command) => command.write !== undefined);

  it('finds every write command in the registry', () => {
    // A vacuity guard: a registry this could not see would pass every assertion below by having
    // nothing to assert on, which reads exactly like a green run.
    expect(writes.length).toBeGreaterThanOrEqual(21);
  });

  it.each(writes.map((command) => [command.name, command] as const))(
    '%s sends what its declaration implies',
    async (name, command) => {
      const invocation = INVOCATIONS[name];
      if (!invocation) {
        throw new Error(
          `'${name}' declares a write and this test does not know how to invoke it. Add it to `
            + 'INVOCATIONS — the check exists so a new write command cannot ship without its headers '
            + 'being asserted.',
        );
      }

      const out = recorder();
      const stub = api({ ...scaffolding(), ...invocation.routes });
      const code = await mainWith(invocation.argv(), out, stub.fetch);
      expect(`${String(code)} ${out.stderr}`).toBe(`${String(EXIT.ok)} `);

      const sent = stub.write(invocation.path);
      const carried = WATCHED.filter((header) => sent.headers.get(header) !== null);

      /*
        Resolved from the URL the command actually sent rather than from a column in the table below:
        a hand-written operation name would be a second declaration of where the command writes, and
        it would agree right up until somebody moved one.
      */
      const contractPath = contractPathOfRequest(sent.path);
      expect(contractPath, `${name} wrote to ${sent.method} ${sent.path}, which resolves to no path in openapi.yaml`)
        .toBeDefined();
      const declared = declaredHeadersOf(sent.method.toLowerCase(), contractPath ?? '');
      expect(declared, `${name} wrote to ${sent.method} ${sent.path}, which the contract has no operation for`)
        .toBeDefined();

      const implied = IMPLIED[command.write?.kind ?? ''];
      expect(implied, `${name} declares kind '${String(command.write?.kind)}', which this check has no rule for`)
        .toBeDefined();

      // What it *should* have sent: what the kind implies, minus anything the operation does not
      // offer. A header the endpoint never reads is not a weaker version of the guarantee, it is the
      // absence of it wearing the guarantee's name.
      const expected = (implied ?? []).filter((header) => (declared ?? []).includes(header));
      expect(carried, `${name} sent [${carried.join(', ')}] to ${sent.method} ${sent.path}`)
        .toEqual(expected);

      const missing = (implied ?? []).filter((header) => !(declared ?? []).includes(header));
      if (missing.length > 0) {
        expect(CREATE_WITHOUT_A_KEY[name], `${name} is a '${String(command.write?.kind)}' and `
          + `${sent.method} ${sent.path} declares no ${missing.join(', ')}. Either the contract should `
          + 'offer it, or the shortfall is a decision and belongs in CREATE_WITHOUT_A_KEY with what it '
          + 'costs a caller.')
          .toBeDefined();
      }
    },
  );

  it('declares a permission for every write, since the 403 body names none', () => {
    for (const command of writes) {
      expect(command.write?.permission, command.name).toMatch(/^[a-z]+:[a-z]+$/);
    }
  });

  it('records nothing as a decision that is not one', () => {
    // §162's rule: a one-way ignore list cannot report a member that stopped needing to be on it.
    for (const name of Object.keys(CREATE_WITHOUT_A_KEY)) {
      expect(writes.some((command) => command.name === name), `${name} is not a write command`).toBe(true);
    }
  });
});

/* ----------------------------------------------------------------- fixtures ---- */

const CASE = caseDetail({ id: 'c-9', key: 'TC-42', lockVersion: 3 });
const SUITE = suite({ key: 'TS-7', lockVersion: 2 });
const PAGE = docPage({
  id: 'd-1', key: 'DOC-3', title: 'Payments spec', slug: 'payments-spec',
  blocks: [docBlock({ id: 'b-1' })], lockVersion: 5,
});
const PLAN = plan({ lockVersion: 7 });
const RUN = runFixture({ id: 'tr-5', key: 'TR-5', status: 'IN_PROGRESS' });
const PASSED = caseStatus({ id: 'st-1', name: 'Passed', kind: 'PASSED' });
const REVIEW = review({ subjectId: 'c-9' });
const ISSUE = issue();
const BOARD = board();
const RELEASE = releaseFixture();
const RELEASE_ROW = releaseSummary();

/** The four `TestCaseInput` requires, plus the step a STRUCTURED case needs to be acceptable. */
const PROPOSABLE = {
  title: 'Login works',
  stepFormat: 'STRUCTURED',
  priority: 'HIGH',
  automationStatus: 'MANUAL',
  steps: [{ action: 'Sign in', expectedResult: 'The dashboard renders.' }],
};

const BLOCK_LINK = {
  id: 'bl-1',
  sourceType: 'TEST_CASE',
  sourceId: 'c-9',
  sourceKey: 'TC-42',
  sourceTitle: 'Login works',
  relationship: 'VERIFIES',
};

/** The reads every command makes on its way to a write: project resolution and key lookup. */
function scaffolding(): Record<string, Answer> {
  return {
    '/projects': { status: 200, body: [PROJECT] },
    'GET /cases': {
      status: 200,
      body: casePage([caseSummary({ id: 'c-9', key: 'TC-42' })], { size: 1 }),
    },
    'GET /cases/c-9': { status: 200, body: CASE },
    'GET /suites': { status: 200, body: [SUITE] },
    'GET /suites/s-1': { status: 200, body: SUITE },
    'GET /doc-pages': {
      status: 200,
      body: [docPageSummary({ id: 'd-1', key: 'DOC-3', title: 'Payments spec', slug: 'payments-spec' })],
    },
    'GET /doc-pages/d-1': { status: 200, body: PAGE },
    'GET /plans': { status: 200, body: [PLAN] },
    'GET /plans/tp-1': { status: 200, body: PLAN },
    'GET /runs': { status: 200, body: [RUN] },
    'GET /runs/tr-5': {
      status: 200,
      body: runDetail({ ...RUN, executions: [executionSummary({ id: 'e-1', caseId: 'c-9' })] }),
    },
    'GET /executions/e-1': { status: 200, body: execution({ id: 'e-1', caseId: 'c-9', status: PASSED }) },
    'GET /statuses': { status: 200, body: [PASSED] },
    'GET /reviews': { status: 200, body: [REVIEW] },
    // The create and move commands resolve a NAME against this, which is the point of taking one.
    'GET /workflow': { status: 200, body: projectWorkflow() },
    /*
      The board reads a card verb makes on its way to a write. Method-keyed throughout, because the
      suffix match is not enough here: `/projects/p-1/labels` and `/issues/ACME-124/labels` both end
      in `/labels`, and an unkeyed route would answer the issue's PUT with the project's list.
    */
    'GET /boards': { status: 200, body: [boardSummary()] },
    'GET /boards/bd-1': { status: 200, body: BOARD },
    'GET /labels': { status: 200, body: [label()] },
    'GET /members': { status: 200, body: [userRef()] },
    // The version → id lookup four of the six release writes make, method-keyed for the reason
    // the board routes above are: `POST` to the same path is the create arm.
    'GET /releases': { status: 200, body: [RELEASE_ROW] },
  };
}

function tempJson(name: string, value: unknown): string {
  const path = join(mkdtempSync(join(tmpdir(), 'tcms-cli-')), name);
  writeFileSync(path, JSON.stringify(value));
  return path;
}

/* ------------------------------------------------------------------ the table ---- */

interface Invocation {
  argv: () => string[];
  /** A suffix of the path the write goes to, as `Api.write` matches it. */
  path: string;
  routes: Record<string, Answer>;
}

const INVOCATIONS: Record<string, Invocation> = {
  'client create': {
    // No `--project`: a client is COMPANY scope, so this is one of the few writes with no project
    // in its path at all.
    argv: () => ['client', 'create', '--name', 'Acme Retail'],
    path: '/clients',
    routes: { 'POST /clients': { status: 201, body: clientFixture() } },
  },
  'client contact-add': {
    argv: () => ['client', 'contact-add', 'cl-1', '--name', 'Hanna Lund'],
    path: '/clients/cl-1/contacts',
    routes: {
      'POST /clients/cl-1/contacts': { status: 201, body: clientContact() },
    },
  },
  /*
    Billing (019 T1). None of these carries a project in its path except the two budget arms and
    `expense record`, and none carries an idempotency key — the parameter deduplicates within
    `(company, project, key)` and a client-addressed write has no project (CREATE_WITHOUT_A_KEY).
  */
  'billing draft': {
    argv: () => [
      'billing', 'draft', '--client', 'cl-1', '--from', '2026-09-01', '--to', '2026-09-30',
    ],
    path: '/invoices',
    routes: { 'POST /invoices': { status: 201, body: invoiceDetail() } },
  },
  'billing issue': {
    argv: () => ['billing', 'issue', 'inv-1'],
    path: '/invoices/inv-1/issue',
    routes: {
      'POST /invoices/inv-1/issue': {
        status: 200,
        body: invoiceDetail({
          invoice: {
            ...invoiceDetail().invoice,
            number: 'INV-2026-001',
            status: 'ISSUED',
            issuedAt: '2026-10-01T09:00:00Z',
            issuedBy: 'Fin Ance',
          },
        }),
      },
    },
  },
  'billing discard': {
    // `--yes` because the delete arm asks first, as `doc unlink` above does.
    argv: () => ['billing', 'discard', 'inv-1', '--yes'],
    path: '/invoices/inv-1',
    routes: { 'DELETE /invoices/inv-1': { status: 204 } },
  },
  'budget set': {
    argv: () => ['budget', 'set', '--basis', 'HOURS', '--amount', '600'],
    path: '/budget',
    routes: { 'PUT /budget': { status: 200, body: budget() } },
  },
  'budget clear': {
    argv: () => ['budget', 'clear', '--yes'],
    path: '/budget',
    routes: { 'DELETE /budget': { status: 204 } },
  },
  'expense record': {
    argv: () => [
      'expense', 'record', '--on', '2026-09-02', '--amount', '240',
      '--currency', 'EUR', '--what', 'Device lab hire',
    ],
    path: '/expenses',
    routes: { 'POST /expenses': { status: 201, body: expense() } },
  },
  'expense remove': {
    argv: () => ['expense', 'remove', 'ex-1', '--yes'],
    path: '/expenses/ex-1',
    routes: { 'DELETE /expenses/ex-1': { status: 204 } },
  },
  'rate-card set': {
    // The list read is in `routes` rather than in `scaffolding` because it is what this command
    // uses to decide between POST and PUT, and the slot's `card: null` is what makes it a POST —
    // the arm a client with no card takes, which is every client on its first rate.
    argv: () => [
      'rate-card', 'set', '--client', 'cl-1', '--currency', 'EUR',
      '--entry', 'ALL_ROLES::90:2026-01-01',
    ],
    path: '/rate-cards',
    routes: {
      'GET /rate-cards': { status: 200, body: [rateCardSlot()] },
      'POST /rate-cards': { status: 201, body: rateCard() },
    },
  },
  'rate-card remove': {
    argv: () => ['rate-card', 'remove', 'rc-1', '--yes'],
    path: '/rate-cards/rc-1',
    routes: { 'DELETE /rate-cards/rc-1': { status: 204 } },
  },
  'staffing allocate': {
    argv: () => [
      'staffing', 'allocate', '--person', 'Ana Engineer',
      '--percent', '50', '--from', '2026-09-01', '--to', '2026-11-30',
    ],
    path: '/allocations',
    routes: { 'POST /allocations': { status: 201, body: allocation() } },
  },
  'staffing absence': {
    // No `--person`, so it records the CALLER's own — the arm that needs no staffing key at all
    // (017 D-h), which is why the `/me` read is in the routes rather than in `scaffolding`.
    argv: () => ['staffing', 'absence', '--from', '2026-09-24', '--to', '2026-09-24'],
    path: '/absences',
    routes: {
      'GET /me': { status: 200, body: currentUser() },
      'POST /absences': {
        status: 201,
        body: absence({ startsOn: '2026-09-24', endsOn: '2026-09-24' }),
      },
    },
  },
  'case edit': {
    argv: () => ['case', 'edit', 'TC-42', '--title', 'x'],
    path: '/cases/c-9',
    routes: { 'PATCH /cases/c-9': { status: 200, body: CASE } },
  },
  'case propose': {
    argv: () => ['case', 'propose', '--file', tempJson('case.json', PROPOSABLE)],
    path: '/ai-proposals',
    routes: { 'POST /ai-proposals': { status: 201, body: proposal() } },
  },
  'suite create': {
    argv: () => ['suite', 'create', '--name', 'Smoke'],
    path: '/suites',
    routes: { 'POST /suites': { status: 201, body: SUITE } },
  },
  'suite edit': {
    argv: () => ['suite', 'edit', 'TS-7', '--name', 'Smoke again'],
    path: '/suites/s-1',
    routes: { 'PATCH /suites/s-1': { status: 200, body: SUITE } },
  },
  'suite status': {
    argv: () => ['suite', 'status', 'TS-7', '--status', 'IN_REVIEW'],
    path: '/suites/s-1/status',
    routes: { 'PUT /suites/s-1/status': { status: 200, body: SUITE } },
  },
  'doc create': {
    argv: () => ['doc', 'create', '--title', 'Payments spec'],
    path: '/doc-pages',
    routes: { 'POST /doc-pages': { status: 201, body: PAGE } },
  },
  'doc edit': {
    argv: () => ['doc', 'edit', 'DOC-3', '--title', 'Payments spec v2'],
    path: '/doc-pages/d-1',
    routes: { 'PATCH /doc-pages/d-1': { status: 200, body: PAGE } },
  },
  'doc link': {
    argv: () => ['doc', 'link', 'DOC-3', '--block', 'b-1', '--case', 'TC-42'],
    path: '/blocks/b-1/links',
    routes: { 'POST /blocks/b-1/links': { status: 201, body: BLOCK_LINK } },
  },
  'doc unlink': {
    // `--yes` because the delete arm asks first now; the header conformance this file checks is
    // downstream of the consent gate and unchanged by it.
    argv: () => ['doc', 'unlink', 'DOC-3', '--link', 'l-1', '--yes'],
    path: '/links/l-1',
    routes: { 'DELETE /links/l-1': { status: 204 } },
  },
  'plan create': {
    argv: () => ['plan', 'create', '--name', 'Release 4.2'],
    path: '/plans',
    routes: { 'POST /plans': { status: 201, body: PLAN } },
  },
  'plan edit': {
    argv: () => ['plan', 'edit', 'TP-1', '--name', 'Release 4.3'],
    path: '/plans/tp-1',
    routes: { 'PATCH /plans/tp-1': { status: 200, body: PLAN } },
  },
  'plan status': {
    argv: () => ['plan', 'status', 'TP-1', '--status', 'IN_REVIEW'],
    path: '/plans/tp-1/status',
    routes: { 'PUT /plans/tp-1/status': { status: 200, body: PLAN } },
  },
  'run create': {
    argv: () => ['run', 'create', '--name', 'Nightly'],
    path: '/runs',
    routes: { 'POST /runs': { status: 201, body: RUN } },
  },
  'run status': {
    argv: () => ['run', 'status', 'TR-5', '--status', 'COMPLETED'],
    path: '/runs/tr-5/status',
    routes: { 'PUT /runs/tr-5/status': { status: 200, body: RUN } },
  },
  'result record': {
    argv: () => ['result', 'record', 'TR-5', 'TC-42', '--status', 'Passed'],
    path: '/executions/e-1',
    routes: {
      'PATCH /executions/e-1': {
        status: 200,
        body: execution({ id: 'e-1', caseId: 'c-9', status: PASSED }),
      },
    },
  },
  'review comment': {
    argv: () => ['review', 'comment', 'TC-42', '--body', 'Looks right.'],
    path: '/comments',
    routes: { 'POST /comments': { status: 201, body: REVIEW } },
  },
  'issue create': {
    argv: () => ['issue', 'create', '--type', 'Story', '--title', 'Sign-out clears the session'],
    path: '/issues',
    routes: { 'POST /issues': { status: 201, body: ISSUE } },
  },
  'issue move': {
    argv: () => ['issue', 'move', 'ACME-124', '--status', 'In Progress'],
    path: '/issues/ACME-124/transitions',
    routes: { 'POST /issues/ACME-124/transitions': { status: 200, body: ISSUE } },
  },
  'issue comment': {
    argv: () => ['issue', 'comment', 'ACME-124', '--body', 'Reproduced on staging.'],
    path: '/issues/ACME-124/comments',
    routes: { 'POST /issues/ACME-124/comments': { status: 201, body: issueComment() } },
  },
  'board move': {
    argv: () => ['board', 'move', 'ACME-124', '--column', 'In flight'],
    path: '/cards/ACME-124/move',
    routes: { 'POST /cards/ACME-124/move': { status: 200, body: cardMoveResult() } },
  },
  rank: {
    argv: () => ['rank', 'ACME-124', '--after', 'ACME-99'],
    path: '/issues/ACME-124/rank',
    routes: { 'PUT /issues/ACME-124/rank': { status: 200, body: rankResult() } },
  },
  'label add': {
    argv: () => ['label', 'add', '--name', 'Regression', '--color', 'label-5'],
    path: '/labels',
    routes: { 'POST /labels': { status: 201, body: label({ name: 'Regression', color: 'label-5' }) } },
  },
  'label set': {
    argv: () => ['label', 'set', 'ACME-124', '--labels', 'Flaky'],
    path: '/issues/ACME-124/labels',
    routes: { 'PUT /issues/ACME-124/labels': { status: 200, body: [label()] } },
  },
  'release create': {
    argv: () => ['release', 'create', '--version', '2.6'],
    path: '/releases',
    routes: { 'POST /projects/p-1/releases': { status: 201, body: RELEASE } },
  },
  'release edit': {
    argv: () => ['release', 'edit', '2.4', '--date', '2026-11-21'],
    path: '/releases/rl-1',
    routes: { 'PATCH /releases/rl-1': { status: 200, body: RELEASE } },
  },
  'release publish': {
    argv: () => ['release', 'publish', '2.4'],
    path: '/releases/rl-1/publish',
    routes: {
      'POST /releases/rl-1/publish': {
        status: 200,
        body: releaseFixture({ status: 'RELEASED', releasedAt: '2026-09-01T10:00:00Z' }),
      },
    },
  },
  'release delete': {
    // `--yes` because a `delete` kind asks first; the header conformance this file checks is
    // downstream of the consent gate and unchanged by it.
    argv: () => ['release', 'delete', '2.4', '--yes'],
    path: '/releases/rl-1',
    routes: { 'DELETE /releases/rl-1': { status: 204 } },
  },
  'issue release': {
    argv: () => ['issue', 'release', 'ACME-124', '--release', '2.4'],
    path: '/issues/ACME-124',
    routes: { 'PATCH /issues/ACME-124': { status: 200, body: ISSUE } },
  },
  'issue attach-pr': {
    argv: () => [
      'issue', 'attach-pr', 'ACME-124',
      '--pr-url', 'https://github.com/northwind/acme-storefront/pull/482',
    ],
    path: '/issues/ACME-124',
    routes: { 'PATCH /issues/ACME-124': { status: 200, body: ISSUE } },
  },
  'time log': {
    argv: () => ['time', 'log', '--issue', 'ACME-124', '--hours', '2', '--on', '2026-09-01'],
    path: '/timesheets/entries',
    routes: { 'POST /timesheets/entries': { status: 201, body: timeEntry() } },
  },
  'time submit': {
    argv: () => ['time', 'submit', 'tsp-1'],
    path: '/timesheets/periods/tsp-1/submit',
    routes: {
      'POST /timesheets/periods/tsp-1/submit': {
        status: 200,
        body: timesheetPeriod({ state: 'SUBMITTED', editable: false, submittedAt: '2026-09-16T08:00:00Z' }),
      },
    },
  },
  'time approve': {
    argv: () => ['time', 'approve', 'tsp-1'],
    path: '/timesheets/periods/tsp-1/decision',
    routes: {
      'POST /timesheets/periods/tsp-1/decision': {
        status: 200,
        body: timesheetPeriod({
          state: 'APPROVED',
          editable: false,
          loggedHours: 21,
          approvedHours: 21,
          decidedAt: '2026-09-16T09:00:00Z',
          decidedByName: 'Rae Lindqvist',
        }),
      },
    },
  },
  'issue cases set': {
    argv: () => ['issue', 'cases', 'set', 'ACME-124', '--case', 'TC-42'],
    path: '/issues/ACME-124/test-cases',
    routes: {
      'PUT /issues/ACME-124/test-cases': { status: 200, body: [issueTestCase({ caseId: 'c-9' })] },
    },
  },
};
