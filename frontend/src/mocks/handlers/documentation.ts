/** The documentation space and the artifact register. */
import { HttpResponse, delay, http } from 'msw';
import type {
  Artifact,
  BlockLink,
  DocBlock,
  DocPage,
  DocPageStatus,
  DocPageSummary,
} from '../../api/types';
import { allocateDocPageKey, db, newId } from '../db';
import { API, LATENCY, holdsOn, notFound, problem, userRef } from './shared';

/** Mirrors the server's own cap, so the message lands on the field without a round trip. */
const MAX_TITLE = 200;

type BlockInput = { id?: string | null; blockType?: string; level?: number | null; text?: string };
type PageInput = { title?: string; parentId?: string | null; blocks?: BlockInput[] };

const BLOCK_TYPES = ['heading', 'paragraph', 'list', 'code', 'table', 'image'];

/**
 * The server's plan-document template, mirrored.
 *
 * A copy, and it is the kind §52 warns about — two definitions of one thing that drift. It is here
 * anyway because the alternative is a mock that serves an empty document, which would leave the
 * whole screen unexercised in the mode this app is developed in. The drift that matters is the
 * SECTION HEADINGS, since the SPA finds "Test cases" by name to know where its create button
 * belongs; a prompt's wording drifting costs nothing.
 */
const PLAN_TEMPLATE: { blockType: DocBlock['blockType']; level?: number; text: string }[] = [
  { blockType: 'heading', level: 1, text: 'Business requirements' },
  {
    blockType: 'paragraph',
    text:
      'What the product under test does, in the words of the people who asked for it. Describe ' +
      'the system in a sentence or two, then list its main functionalities below — one bullet each.',
  },
  { blockType: 'list', level: 1, text: 'A capability the product offers.' },

  { blockType: 'heading', level: 1, text: 'Test artefacts' },
  { blockType: 'heading', level: 2, text: 'Test environments' },
  { blockType: 'list', level: 1, text: 'Environment name — its URL, and what build or branch it runs.' },
  { blockType: 'heading', level: 2, text: 'Accounts' },
  {
    blockType: 'paragraph',
    text:
      'Who each account is and what it can reach. Record where the credential lives — a vault ' +
      'entry, a shared secret store — and never the credential itself. A password typed here is ' +
      'readable by everyone who can read this plan, survives every export of it, and outlives the ' +
      'person who set it.',
  },
  {
    blockType: 'list',
    level: 1,
    text: 'Account identifier — the role or team it stands for, and where its secret is kept.',
  },

  { blockType: 'heading', level: 1, text: 'Scope' },
  {
    blockType: 'paragraph',
    text:
      'What this plan undertakes to test. Sentences, not case keys — the suites and cases that ' +
      'will actually be executed are the plan\'s own scope, set on the plan itself, and this ' +
      'section is what a reader needs in order to agree that the selection is the right one.',
  },
  { blockType: 'list', level: 1, text: 'Something this plan will verify.' },

  { blockType: 'heading', level: 1, text: 'Out of scope' },
  {
    blockType: 'paragraph',
    text:
      'What this plan deliberately does not cover, and — where it is not obvious — why. An ' +
      'unstated exclusion reads as an oversight when the gap is found later.',
  },
  { blockType: 'list', level: 1, text: 'Something this plan will not verify.' },

  { blockType: 'heading', level: 1, text: 'Test cases' },
  {
    blockType: 'paragraph',
    text:
      'The plan\'s test cases, grouped by area. Add one by title as you think of it — each is ' +
      'created as a draft in the case repository and can be written up properly later. Indent a ' +
      'bullet to put it under the one above.',
  },
  { blockType: 'heading', level: 2, text: 'Area' },
  { blockType: 'list', level: 1, text: 'A case to run in this area.' },
  { blockType: 'list', level: 2, text: 'A variation of the case above.' },
  { blockType: 'list', level: 3, text: 'A specific condition within that variation.' },
];


/**
 * The page as both stores hold it.
 *
 * `db.docPages` is the tree and `db.docPageDetails` is the body, and every write has to touch
 * both — a create that only pushed a summary would produce a page in the sidebar that 404s when
 * clicked, which is a shape only the mock can have and therefore one only the mock can teach
 * somebody is normal.
 */
function upsert(page: DocPage) {
  const summary: DocPageSummary = {
    id: page.id,
    key: page.key,
    parentId: page.parentId,
    title: page.title,
    slug: page.slug,
    status: page.status,
    updatedAt: page.updatedAt,
    subjectType: page.subjectType ?? null,
    subjectId: page.subjectId ?? null,
  };
  const index = db.docPages.findIndex((p) => p.id === page.id);
  if (index < 0) db.docPages.push(summary);
  else db.docPages[index] = summary;
  db.docPageDetails[page.id] = page;
  return page;
}

/** The stored page, filled in for the fixture pages that carry no authored body. */
function load(pageId: string): DocPage | null {
  const stored = db.docPageDetails[pageId];
  if (stored) return stored;
  const summary = db.docPages.find((p) => p.id === pageId);
  if (!summary) return null;
  return { ...summary, currentVersionNo: 1, lockVersion: 1, blocks: [] };
}

/**
 * Validates a page payload the way the server does, field keys included.
 *
 * Written from the server's rules rather than from what the editor happens to send, which is the
 * §52 lesson applied to a write: a handler that only accepts what one client produces is a handler
 * that agrees with that client and with nothing else. The level rule and the unknown-id refusal in
 * particular exist so the editor's own error handling has something to render in dev.
 */
function validate(input: PageInput, existingIds: string[], onCreate: boolean) {
  const errors: Record<string, string[]> = {};

  const title = input.title?.trim() ?? '';
  if (!title) errors.title = ['Enter a title.'];
  else if (title.length > MAX_TITLE) {
    errors.title = [`Keep the title under ${MAX_TITLE} characters.`];
  }

  const seen = new Set<string>();
  (input.blocks ?? []).forEach((block, index) => {
    const at = `blocks[${index}]`;
    if (!block.blockType || !BLOCK_TYPES.includes(block.blockType)) {
      errors[`${at}.blockType`] = [
        'Choose a block type: heading, paragraph, list, code, table or image.',
      ];
    }
    if (block.text == null) errors[`${at}.text`] = ['Enter text.'];

    if (block.blockType === 'heading') {
      if (block.level == null) errors[`${at}.level`] = ['Choose a heading depth between 1 and 6.'];
      else if (block.level < 1 || block.level > 6) {
        errors[`${at}.level`] = ['Heading depth is between 1 and 6.'];
      }
    } else if (block.blockType === 'list') {
      // Optional on a list, where it is the bullet's indent depth — the field a nested scenario
      // outline is made of. Three arms here because there are three on the server.
      if (block.level != null && (block.level < 1 || block.level > 6)) {
        errors[`${at}.level`] = ['Indent depth is between 1 and 6.'];
      }
    } else if (block.level != null) {
      // Refused, not dropped: a level on a paragraph means the client believes it is sending a
      // heading, and honouring the type while discarding the depth renders that belief silently.
      errors[`${at}.level`] = ['Only a heading or a list bullet has a depth.'];
    }

    if (!block.id) return;
    if (onCreate) {
      errors[`${at}.id`] = ["A new page's blocks are created with it; leave the id out."];
    } else if (!existingIds.includes(block.id)) {
      errors[`${at}.id`] = ['That block is not on this page. Leave the id out to add a new block.'];
    } else if (seen.has(block.id)) {
      errors[`${at}.id`] = ['This block appears twice in the page.'];
    }
    seen.add(block.id);
  });

  return errors;
}

type ArtifactRegistration = {
  scope?: 'PROJECT' | 'PLAN';
  planId?: string | null;
  category?: string;
  kind?: 'DOC_PAGE_REF' | 'EXTERNAL_URL';
  title?: string;
  description?: string | null;
  docPageId?: string | null;
  url?: string | null;
};

/**
 * Validates a registration the way the server does, field keys included.
 *
 * Written from the server's rules rather than from what the dialog happens to send — the §52
 * lesson, and the reason the `javascript:` refusal is here even though the dialog's own input
 * would not produce one. A handler that only accepts what one client produces is a handler that
 * agrees with that client and with nothing else.
 */
function validateArtifact(input: ArtifactRegistration) {
  const errors: Record<string, string[]> = {};

  const title = input.title?.trim() ?? '';
  if (!title) errors.title = ['Give the artifact a title.'];
  else if (title.length > MAX_TITLE) {
    errors.title = [`Keep the title to ${MAX_TITLE} characters.`];
  }
  if (!input.category?.trim()) errors.category = ['Give the artifact a category.'];

  if (input.scope === 'PLAN') {
    if (!input.planId) errors.planId = ['Choose the plan this artifact belongs to.'];
  } else if (input.planId) {
    // Refused rather than dropped: a client sending a plan id believes the artifact is scoped to
    // it, and storing a project-scope row while ignoring the field is the silent disagreement §50
    // built two checks to catch.
    errors.planId = ['A project-scope artifact belongs to every plan, so it takes no plan.'];
  }

  if (input.kind === 'DOC_PAGE_REF') {
    if (!input.docPageId) errors.docPageId = ['Choose the page this artifact points to.'];
    else if (!db.docPages.some((p) => p.id === input.docPageId)) {
      errors.docPageId = ['That page is not in this project.'];
    }
  } else if (input.kind === 'EXTERNAL_URL') {
    const url = input.url?.trim() ?? '';
    if (!url) errors.url = ['Give the link this artifact points to.'];
    else if (!/^https?:\/\//i.test(url)) {
      // The register renders these inside an anchor, so a `javascript:` value is stored XSS in
      // the product's own markup — on the screen where a colleague's link is clicked unread.
      errors.url = ['Links must start with http:// or https://.'];
    }
  } else {
    errors.kind = ['Choose what kind of artifact this is.'];
  }

  return errors;
}

/** `doc:edit` / `doc:publish` on the project in the path — the gate the SPA's buttons mirror. */
function gate(projectId: string, permission: string) {
  return holdsOn(projectId, permission)
    ? null
    : problem(
        403,
        'Not allowed',
        `You do not have permission to ${
          permission === 'doc:publish' ? 'publish' : 'edit'
        } documentation pages in this project.`,
      );
}

export const documentationHandlers = [
  http.get(`${API}/projects/:projectId/doc-pages`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(db.docPages);
  }),

  http.post(`${API}/projects/:projectId/doc-pages`, async ({ params, request }) => {
    await delay(LATENCY);
    const refused = gate(String(params.projectId), 'doc:edit');
    if (refused) return refused;

    const body = (await request.json()) as PageInput;

    const errors = validate(body, [], true);
    // A parent outside the project is a FIELD error, not a 404: the project resolved, and the
    // message belongs on the picker.
    if (body.parentId && !db.docPages.some((p) => p.id === body.parentId)) {
      errors.parentId = ['That page is not in this project.'];
    }
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    const { key, keySeq } = allocateDocPageKey();
    const title = body.title!.trim();
    const page: DocPage = {
      id: newId(),
      key,
      parentId: body.parentId ?? null,
      title,
      // Derived from the title ONCE and suffixed with the sequence, exactly as the server does.
      // It is never recomputed on a retitle — a slug that follows the title is the heading-anchor
      // failure at page scale.
      slug: `${title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-+)|(-+$)/g, '') || 'page'}-${keySeq}`,
      // Always DRAFT: a status an ordinary create could set is an approval its author granted
      // themselves, and publishing is a different permission entirely (§6g).
      status: 'DRAFT',
      updatedAt: new Date().toISOString(),
      currentVersionNo: 1,
      lockVersion: 0,
      blocks: (body.blocks ?? []).map((block, position) => ({
        id: newId(),
        position,
        blockType: block.blockType as DocBlock['blockType'],
        level: block.level ?? null,
        text: block.text ?? '',
        // A block created a statement ago; nothing can link to one yet.
        links: [],
        // Absent on the wire from the real server; null here because the fixture type has the
        // key. Either way the SPA renders no marker — the server cannot price a link even now
        // that it can create one, which is the whole of DocBlock.coverage's description.
        coverage: null,
      })),
    };
    return HttpResponse.json(upsert(page), { status: 201 });
  }),

  http.get(`${API}/projects/:projectId/doc-pages/:pageId`, async ({ params }) => {
    await delay(LATENCY);
    // Pages without authored blocks are a normal state in a young docs space.
    const page = load(String(params.pageId));
    return page ? HttpResponse.json(page) : notFound('That page');
  }),

  http.patch(`${API}/projects/:projectId/doc-pages/:pageId`, async ({ params, request }) => {
    await delay(LATENCY);
    const refused = gate(String(params.projectId), 'doc:edit');
    if (refused) return refused;

    const page = load(String(params.pageId));
    if (!page) return notFound('That page');

    // The precondition is the HEADER, never a body field (§52).
    const ifMatch = request.headers.get('If-Match');
    if (ifMatch !== null && ifMatch.trim() !== '') {
      const expected = Number(ifMatch.replace(/^"|"$/g, ''));
      if (Number.isNaN(expected)) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          'If-Match': ["Send the page's lockVersion as the If-Match header, or omit the header."],
        });
      }
      if (expected !== page.lockVersion) {
        return problem(
          409,
          'Someone else saved first',
          'Reload the page to see the newer version, then reapply your change.',
        );
      }
    }

    const body = (await request.json()) as PageInput;
    const existing = page.blocks ?? [];
    const errors = validate(body, existing.map((b) => b.id), false);
    if (body.parentId !== undefined && body.parentId !== null && body.parentId !== page.parentId) {
      errors.parentId = ['Moving a page is not supported yet. Leave the parent unchanged.'];
    }
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    let blocks = existing;
    if (body.blocks !== undefined) {
      const kept = new Set(body.blocks.map((b) => b.id).filter(Boolean));

      /*
        The refusal that protects traceability.

        ~~nothing in mock mode can create a doc_link either, so the fixture's `coverage.linkedCases`
        stands in for the link count~~ → **superseded (§93)**: links are real here now. Both are
        counted, because the fixture pages still carry a coverage marker with no links behind it and
        dropping that half would make the editor's 409 branch unreachable on exactly the pages that
        were built to exercise it.
      */
      const breaking = existing
        .filter((block) => !kept.has(block.id))
        .reduce(
          (total, block) => total + (block.coverage?.linkedCases ?? 0) + (block.links?.length ?? 0),
          0,
        );
      if (breaking > 0) {
        return problem(
          409,
          'Something still links here',
          `${breaking} ${breaking === 1 ? 'link points' : 'links point'} at content you are ` +
            'removing, and deleting it would break them silently. Remove the links first, or ' +
            'archive the page instead — an archived page keeps its links resolvable.',
        );
      }

      // Identity is decided by the submitted id, never by position or by matching text. A block
      // sent with its id keeps that id however much its text changed (plan §6i/§6m).
      blocks = body.blocks.map((input, position) => {
        const previous = input.id ? existing.find((b) => b.id === input.id) : undefined;
        return {
          id: previous?.id ?? newId(),
          position,
          blockType: input.blockType as DocBlock['blockType'],
          level: input.level ?? null,
          text: input.text ?? '',
          coverage: previous?.coverage ?? null,
        };
      });
    }

    const title = body.title!.trim();
    // A retitle IS content here, unlike a suite rename, so it authors a version — but only when
    // something actually changed, or opening a page and pressing Save would manufacture history.
    const changed =
      title !== page.title || JSON.stringify(blocks) !== JSON.stringify(existing);

    return HttpResponse.json(
      upsert({
        ...page,
        title,
        blocks,
        currentVersionNo: page.currentVersionNo + (changed ? 1 : 0),
        lockVersion: page.lockVersion + 1,
        updatedAt: new Date().toISOString(),
      }),
    );
  }),

  http.put(`${API}/projects/:projectId/doc-pages/:pageId/status`, async ({ params, request }) => {
    await delay(LATENCY);
    // doc:publish, NOT doc:edit — the whole reason this is a second endpoint.
    const refused = gate(String(params.projectId), 'doc:publish');
    if (refused) return refused;

    const page = load(String(params.pageId));
    if (!page) return notFound('That page');

    const { status } = (await request.json()) as { status?: string };
    if (!status || !['DRAFT', 'PUBLISHED', 'ARCHIVED'].includes(status)) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        status: ['Choose a status: DRAFT, PUBLISHED or ARCHIVED.'],
      });
    }

    // No version bump: nobody authored anything. Idempotent — setting the status a page already
    // has is agreement, not a conflict.
    return HttpResponse.json(
      upsert({
        ...page,
        status: status as DocPageStatus,
        lockVersion: page.lockVersion + 1,
        updatedAt: new Date().toISOString(),
      }),
    );
  }),

  http.delete(`${API}/projects/:projectId/doc-pages/:pageId`, async ({ params }) => {
    await delay(LATENCY);
    const refused = gate(String(params.projectId), 'doc:edit');
    if (refused) return refused;

    const pageId = String(params.pageId);
    const index = db.docPages.findIndex((p) => p.id === pageId);
    if (index < 0) return notFound('That page');

    const children = db.docPages.filter((p) => p.parentId === pageId).length;
    if (children > 0) {
      return problem(
        409,
        'Page is not empty',
        `This page has ${children} ${children === 1 ? 'child page' : 'child pages'}. ` +
          'Move or delete them first.',
      );
    }

    const linked = (load(pageId)?.blocks ?? []).reduce(
      (total, block) => total + (block.coverage?.linkedCases ?? 0),
      0,
    );
    if (linked > 0) {
      return problem(
        409,
        'Something still links here',
        `${linked} ${linked === 1 ? 'link points' : 'links point'} at content you are removing, ` +
          'and deleting it would break them silently. Remove the links first, or archive the ' +
          'page instead — an archived page keeps its links resolvable.',
      );
    }

    db.docPages.splice(index, 1);
    delete db.docPageDetails[pageId];
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${API}/projects/:projectId/artifacts`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const scope = url.searchParams.get('scope');
    const planId = url.searchParams.get('planId');

    if (scope === 'PLAN' || planId) {
      // Plan scope also returns project-scope artifacts, flagged inherited — the
      // inheritance has to be visible or people duplicate what they already have.
      const planScoped = db.artifacts.filter((a) => a.scope === 'PLAN');
      const inherited: Artifact[] = db.artifacts
        .filter((a) => a.scope === 'PROJECT')
        .map((a) => ({ ...a, inherited: true }));
      return HttpResponse.json([...planScoped, ...inherited]);
    }
    const rows = scope ? db.artifacts.filter((a) => a.scope === scope) : db.artifacts;
    return HttpResponse.json(rows);
  }),

  http.post(`${API}/projects/:projectId/artifacts`, async ({ params, request }) => {
    await delay(LATENCY);
    const projectId = String(params.projectId);

    const refused = gate(projectId, 'artifact:edit');
    if (refused) return refused;

    const body = (await request.json()) as ArtifactRegistration;
    const errors = validateArtifact(body);
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Invalid artifact', 'Check the highlighted fields.', errors);
    }

    /*
      The scope qualifier (V19), mirrored.

      This is the one gate in the mock that a permission KEY cannot express, and mirroring it here
      is the whole reason the handler is worth more than a stub: `artifact:edit` is held by a Test
      Engineer, so `gate` above passes, and the refusal depends on the SCOPE of the row being
      written. A mock that stopped at the key would let mock mode register a project-scope artifact
      that live mode refuses — the "mock agrees with itself" class §54 found three instances of.

      403 WITH a body, matching the server: the caller holds the permission and is one radio button
      away from succeeding, so the refusal names the field rather than being bare.
    */
    if (body.scope === 'PROJECT' && !holdsOn(projectId, 'artifact:delete')) {
      // `artifact:delete` stands in for "unqualified artifact:edit" here, because the mock's
      // permission list has no qualifiers to read. The roles spec grants delete to exactly the
      // three roles that hold artifact:edit unqualified (Instance Admin, Company Admin, Test Lead)
      // and withholds it from Test Engineer — so it is the same set, derived rather than hardcoded.
      return problem(
        403,
        'Not allowed at this scope',
        'Your role can add artifacts to a plan, but not to the project.',
        {
          scope: [
            'Your role can add artifacts to a plan, but not to the project as a whole. ' +
              'Project artifacts are inherited by every plan, so a Test Lead owns them.',
          ],
        },
      );
    }

    const artifact: Artifact = {
      id: newId(),
      scope: body.scope!,
      category: body.category!.trim(),
      kind: body.kind!,
      title: body.title!.trim(),
      description: body.description?.trim() || null,
      // Always ACTIVE — a status an ordinary create could set is an approval its author granted
      // themselves (§6g), which is why `status` is not on the input at all.
      status: 'ACTIVE',
      // False at either scope: a row just created is this register's own. It becomes inherited
      // only when read from inside a plan, which the GET above computes.
      inherited: false,
      url: body.kind === 'EXTERNAL_URL' ? body.url!.trim() : null,
      docPageId: body.kind === 'DOC_PAGE_REF' ? body.docPageId! : null,
      filename: null,
      sizeBytes: null,
      updatedBy: userRef(0),
      updatedAt: new Date().toISOString(),
    };

    db.artifacts.push(artifact);
    return HttpResponse.json(artifact, { status: 201 });
  }),

  /*
    A plan's document.

    404 when it has not been started, which is the ordinary first state and is what the screen's
    invitation renders from. Deliberately the same 404 an unknown plan gets, matching the server.
  */
  http.get(`${API}/projects/:projectId/plans/:planId/document`, async ({ params }) => {
    await delay(LATENCY);
    const summary = db.docPages.find(
      (p) => p.subjectType === 'TEST_PLAN' && p.subjectId === String(params.planId),
    );
    if (!summary) return notFound("That plan's document");
    const page = load(summary.id);
    return page ? HttpResponse.json(page) : notFound("That plan's document");
  }),

  http.post(`${API}/projects/:projectId/plans/:planId/document`, async ({ params }) => {
    await delay(LATENCY);
    const refused = gate(String(params.projectId), 'doc:edit');
    if (refused) return refused;

    const planId = String(params.planId);
    const plan = db.plans.find((p) => p.id === planId);
    if (!plan) return notFound('That plan');

    const already = db.docPages.find(
      (p) => p.subjectType === 'TEST_PLAN' && p.subjectId === planId,
    );
    if (already) {
      return problem(
        409,
        'This plan already has a document',
        `This plan's document is ${already.key}. Open it instead — starting a second one would ` +
          'replace what is already written.',
      );
    }

    const { key, keySeq } = allocateDocPageKey();
    const title = `${plan.key} — ${plan.name}`;
    const page: DocPage = {
      id: newId(),
      key,
      parentId: null,
      title,
      slug: `${plan.key.toLowerCase()}-document-${keySeq}`,
      status: 'DRAFT',
      updatedAt: new Date().toISOString(),
      subjectType: 'TEST_PLAN',
      subjectId: planId,
      currentVersionNo: 1,
      lockVersion: 0,
      blocks: PLAN_TEMPLATE.map((block, position) => ({
        id: newId(),
        position,
        blockType: block.blockType,
        level: block.level ?? null,
        text: block.text,
        links: [],
        coverage: null,
      })),
    };
    return HttpResponse.json(upsert(page), { status: 201 });
  }),

  /*
    Links a case or suite to a block.

    200 when the link is already there and 201 when it is written — the same pair the server
    answers, and the mock implements both because a client that treats a repeat as an error is a
    client whose bug only shows up against the real server.
  */
  http.post(
    `${API}/projects/:projectId/doc-pages/:pageId/blocks/:blockId/links`,
    async ({ params, request }) => {
      await delay(LATENCY);
      const refused = gate(String(params.projectId), 'doc:edit');
      if (refused) return refused;

      const page = load(String(params.pageId));
      if (!page) return notFound('That page');
      const block = page.blocks.find((b) => b.id === String(params.blockId));
      if (!block) return notFound('That block');

      const body = (await request.json()) as {
        sourceType?: string;
        sourceId?: string;
        relationship?: string;
      };

      const errors: Record<string, string[]> = {};
      if (body.sourceType !== 'TEST_CASE' && body.sourceType !== 'SUITE') {
        errors.sourceType = ['Choose what is linking: TEST_CASE or SUITE.'];
      }
      if (!body.sourceId) errors.sourceId = ['Choose a test case or suite.'];
      const relationship = body.relationship ?? 'VERIFIES';
      if (!['VERIFIES', 'DERIVED_FROM', 'REFERENCES'].includes(relationship)) {
        errors.relationship = [
          'Choose a relationship: VERIFIES, DERIVED_FROM or REFERENCES.',
        ];
      }
      if (Object.keys(errors).length > 0) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
      }

      // Resolved against the store, so a linked case renders its real key and title. A source
      // that does not resolve is the server's 404, not a link to a name nobody can read.
      const source =
        body.sourceType === 'TEST_CASE'
          ? db.cases.find((c) => c.id === body.sourceId)
          : db.suites.find((suite) => suite.id === body.sourceId);
      if (!source) return notFound('That test case');

      const existing = block.links?.find(
        (link) => link.sourceType === body.sourceType && link.sourceId === body.sourceId,
      );
      if (existing) return HttpResponse.json(existing);

      const link: BlockLink = {
        id: newId(),
        sourceType: body.sourceType as BlockLink['sourceType'],
        sourceId: source.id,
        sourceKey: source.key,
        sourceTitle: 'title' in source ? source.title : source.name,
        relationship: relationship as BlockLink['relationship'],
      };
      block.links = [...(block.links ?? []), link];
      upsert(page);
      return HttpResponse.json(link, { status: 201 });
    },
  ),

  http.delete(
    `${API}/projects/:projectId/doc-pages/:pageId/links/:linkId`,
    async ({ params }) => {
      await delay(LATENCY);
      const refused = gate(String(params.projectId), 'doc:edit');
      if (refused) return refused;

      const page = load(String(params.pageId));
      if (!page) return notFound('That page');

      const linkId = String(params.linkId);
      const block = page.blocks.find((b) => b.links?.some((link) => link.id === linkId));
      // Not an idempotent 204: unlike the create there is no fact here that is already true.
      if (!block) return notFound('That link');

      block.links = (block.links ?? []).filter((link) => link.id !== linkId);
      upsert(page);
      return new HttpResponse(null, { status: 204 });
    },
  ),
];
