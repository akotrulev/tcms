/**
 * The probe, the project rule, and the capability summary.
 *
 * These are the tests for the parts of the surface that have no endpoint of their own: what the
 * session decided before any tool ran, and what the host was told about it.
 */
import { describe, expect, it } from 'vitest';

import { INSTRUCTIONS_BUDGET, bytes, renderInstructions } from './instructions.js';
import { buildServer } from './server.js';
import { ENV_PROJECT } from './session.js';
import { TOOLS } from './tools.js';
import { describeFor, isListed } from './tool.js';
import { whoami } from './whoami.js';
import {
  AGENT_KEYS,
  ME,
  PROJECT,
  agentRoutes,
  api,
  permissions,
  sessionWith,
  withApi,
} from './test/harness.js';

describe('the probe', () => {
  it('resolves the one visible project and asks for permissions in it', async () => {
    const stub = api(agentRoutes());
    const session = await sessionWith(stub);

    expect(session.reach.project?.key).toBe('OLB');
    expect(session.projectId()).toBe(PROJECT.id);
    expect(session.reach.user.email).toBe(ME.email);

    const asked = stub.sent.find((request) => request.path.endsWith('/me/permissions'));
    expect(asked?.search).toBe(`?projectId=${PROJECT.id}`);
  });

  it('keeps the qualifier, not only the key', async () => {
    const stub = api(agentRoutes({
      '/me/permissions': {
        status: 200,
        body: permissions([...AGENT_KEYS], { 'artifact:edit': 'PLAN_SCOPE' }),
      },
    }));
    const session = await sessionWith(stub);

    expect(session.reach.permissions.has('artifact:edit')).toBe(true);
    expect(session.reach.qualifiers.get('artifact:edit')).toBe('PLAN_SCOPE');
    expect(session.reach.qualifiers.get('case:edit')).toBe('ALL');
  });

  it('does not choose between several projects, and says how to bind one', async () => {
    const stub = api({
      '/me': { status: 200, body: ME },
      '/projects': { status: 200, body: [PROJECT, { ...PROJECT, id: 'p-2', key: 'MOB', name: 'Mobile' }] },
      // No project term, so the answer is the SYSTEM + COMPANY set — which is a different set
      // rather than this one filtered, and is why no project-scoped tool can list.
      '/me/permissions': { status: 200, body: permissions(['company:read']) },
    });
    const session = await sessionWith(stub);

    expect(session.reach.project).toBeUndefined();
    expect(session.reach.unscoped).toContain('OLB, MOB');
    expect(session.reach.unscoped).toContain(ENV_PROJECT);
    expect(() => session.projectId()).toThrow(/projects/);

    const asked = stub.sent.find((request) => request.path.endsWith('/me/permissions'));
    expect(asked?.search).toBe('');
  });

  it('fails loudly when a configured project does not resolve', async () => {
    const stub = api(agentRoutes());
    await expect(sessionWith(stub, 'NOPE')).rejects.toThrow(/No project 'NOPE'/);
  });

  it('sends the credential and the MCP user agent on every call', async () => {
    const stub = api(agentRoutes());
    await sessionWith(stub);

    for (const request of stub.sent) {
      expect(request.headers.get('Authorization')).toBe('Bearer tcms_abc');
      expect(request.headers.get('User-Agent')).toMatch(/^tcms-mcp\//);
    }
  });
});

describe('the capability summary', () => {
  it('names who the session acts as and the project in scope', async () => {
    const session = await sessionWith(api(agentRoutes()));
    const text = renderInstructions(session.reach, TOOLS);

    expect(text).toContain('Ana Engineer');
    expect(text).toContain('OLB — Online Banking');
    expect(bytes(text)).toBeLessThanOrEqual(INSTRUCTIONS_BUDGET);
  });

  it('names the permission behind each unavailable tool, and never a role', async () => {
    const stub = api(agentRoutes({
      '/me/permissions': { status: 200, body: permissions(['project:read', 'case:read']) },
    }));
    const session = await sessionWith(stub);
    const text = renderInstructions(session.reach, TOOLS);

    expect(text).toContain('doc:read');
    // §4.5 and `core/refusal.ts`: the role→permission catalogue is behind `company:read`, which
    // this credential does not hold, so a role named here would be a claim about a server this
    // package has never read.
    for (const role of ['Test Engineer', 'Test Lead', 'Company Admin', 'Viewer', 'Agent']) {
      expect(text).not.toContain(role);
    }
  });

  /*
    The section shortens rather than disappearing — audit C10, and it is a repair rather than a
    new rule.

    `fit` drops whole sections from the end, so the "and here is what would let me" list was
    always going to be sacrificed as the registry grew — and the credential it is sacrificed for
    is the one holding almost nothing, which is precisely the reader it exists for. Two new tools
    took the detailed form to 1848 bytes and the whole section vanished, silently: a dropped
    section is indistinguishable from a credential with nothing hidden.

    The permission keys are the half that survives, because they are what a user takes to an
    administrator (`instructions.ts`' header makes that argument about naming the permission
    rather than the role, and it holds a second time here).
  */
  it('keeps the permissions when the titles will not fit, rather than losing the section', async () => {
    const stub = api(agentRoutes({
      // The minimum this surface can hold: every tool but `tcms_orient` is hidden, which is the
      // longest this list ever gets and therefore the case that overflows first.
      '/me/permissions': { status: 200, body: permissions(['project:read']) },
    }));
    const session = await sessionWith(stub);
    const text = renderInstructions(session.reach, TOOLS);

    expect(bytes(text)).toBeLessThanOrEqual(INSTRUCTIONS_BUDGET);
    // The keys are still there, and a permission this credential DOES hold is not listed as
    // missing — which is what proves the compact form is derived from the same set.
    expect(text).toContain('case:read');
    expect(text).toContain('artifact:edit');
    expect(text).not.toMatch(/project:read[,.]/);
  });

  it('says nothing about a project when the permission answer is empty', async () => {
    const stub = api({
      '/me': { status: 200, body: ME },
      '/projects': { status: 200, body: [PROJECT] },
      '/me/permissions': { status: 200, body: { projectId: PROJECT.id, permissions: [] } },
    });
    const session = await sessionWith(stub);
    const text = renderInstructions(session.reach, TOOLS);

    // The §6t branch: an unreachable project and a non-existent one answer identically, so the
    // summary must not turn `[]` into a claim that "here" is somewhere.
    expect(text).toContain('does not exist, or this token cannot see it');
  });

  it("stays inside the host's 2KB ceiling even when nothing is available", async () => {
    const stub = api({
      '/me': { status: 200, body: { ...ME, displayName: 'Ана Инженерова-Петровска' } },
      '/projects': { status: 200, body: [] },
      '/me/permissions': { status: 200, body: { projectId: null, permissions: [] } },
    });
    const session = await sessionWith(stub);

    expect(bytes(renderInstructions(session.reach, TOOLS))).toBeLessThanOrEqual(INSTRUCTIONS_BUDGET);
  });
});

describe('tools/list', () => {
  it('lists only what this credential holds the gate for', async () => {
    const stub = api(agentRoutes({
      '/me/permissions': { status: 200, body: permissions(['project:read', 'case:read']) },
    }));
    const session = await sessionWith(stub);
    const listed = TOOLS.filter((tool) => isListed(tool, session.reach.permissions));

    expect(listed.map((tool) => tool.name)).toContain('tcms_whoami');
    expect(listed.map((tool) => tool.name)).not.toContain('tcms_doc_tree');
  });

  it('lists a narrowly-held tool and says what it is narrowed to, rather than hiding it', async () => {
    const stub = api(agentRoutes({
      '/me/permissions': {
        status: 200,
        body: permissions([...AGENT_KEYS], { 'case:read': 'OWN' }),
      },
    }));
    const session = await sessionWith(stub);
    const tool = TOOLS.find((candidate) => candidate.name === 'tcms_search_cases');

    // Listed, not hidden: `OWN` still lets the caller act on their own rows, so withholding the
    // tool would withhold something they can do. The description carries the restriction —
    // §31's disable-don't-hide, rendered for a reader that reads a list instead of a screen.
    expect(isListed(tool!, session.reach.permissions)).toBe(true);
    expect(describeFor(tool!, session.reach.permissions, session.reach.qualifiers))
      .toContain('only rows this user authored');
  });
});

describe('tcms_whoami', () => {
  it('is always loaded and needs no permission', () => {
    expect(whoami.gate.permissions).toHaveLength(0);
    expect(whoami.meta?.['anthropic/alwaysLoad']).toBe(true);
  });

  it('re-reads the server rather than repeating the connect-time snapshot', async () => {
    const stub = api(agentRoutes());
    const session = await sessionWith(stub);
    const before = stub.sent.length;

    stub.set('/me/permissions', { status: 200, body: permissions(['project:read', 'case:read']) });
    const answer = await withApi(stub, () => whoami.run(session, {})) as {
      permissions: { key: string; qualifier: string }[];
      changed: boolean;
    };

    expect(stub.sent.length).toBeGreaterThan(before);
    expect(answer.permissions.map((held) => held.key)).toEqual(['case:read', 'project:read']);
    expect(answer.changed).toBe(true);
    // The snapshot the tool list is derived from has to move with it, or the list stays stale.
    expect(session.reach.permissions.has('doc:edit')).toBe(false);
  });

  it('reports permissions as key and qualifier pairs, not as stringified objects', async () => {
    const stub = api(agentRoutes({
      '/me/permissions': { status: 200, body: permissions(['case:edit'], { 'case:edit': 'OWN' }) },
    }));
    const session = await sessionWith(stub);
    const answer = await withApi(stub, () => whoami.run(session, {})) as {
      permissions: { key: string; qualifier: string }[];
    };

    expect(answer.permissions).toEqual([{ key: 'case:edit', qualifier: 'OWN' }]);
    expect(JSON.stringify(answer)).not.toContain('[object Object]');
  });
});

describe('the server', () => {
  it("is built per session, so its instructions cannot be another caller's", async () => {
    const first = await sessionWith(api(agentRoutes()));
    const second = await sessionWith(api({
      '/me': { status: 200, body: { ...ME, displayName: 'Bo Lead', email: 'bo@other.test' } },
      '/projects': { status: 200, body: [{ ...PROJECT, id: 'p-9', key: 'PAY', name: 'Payments' }] },
      '/me/permissions': { status: 200, body: permissions(['project:read', 'case:read']) },
    }));

    // Not an assertion about the SDK — an assertion that this package never shares the object
    // the summary is baked into. `instructions` is a constructor argument, sent once.
    expect(renderInstructions(first.reach, TOOLS)).toContain('ana@acme.test');
    expect(renderInstructions(second.reach, TOOLS)).toContain('bo@other.test');
    expect(renderInstructions(second.reach, TOOLS)).not.toContain('ana@acme.test');

    expect(buildServer(first)).not.toBe(buildServer(second));
  });
});
