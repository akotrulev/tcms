/**
 * Which project a command runs against — surface spec §4.1.
 *
 * The rule there is that `projectId` and `companyId` **do not appear in any tool schema**: the
 * context comes from the token, *"never from a tool argument"*, and making the parameter not
 * exist is the enforceable spelling of that. The CLI takes `--project` only because one person
 * may hold tokens for several projects, and it resolves before the client call.
 *
 * So the resolution order is: an explicit `--project`, else the one project the caller can see.
 * There is deliberately no "remembered last project" and no environment default beyond the
 * explicit one — a command that writes to a project the user did not name is the failure mode
 * this whole rule exists to avoid, and it is silent.
 */
import { TcmsClientError } from '../errors.js';
import type { TcmsClient } from '../client.js';

export interface ResolvedProject {
  id: string;
  key: string;
  name: string;
}

export interface ResolveProjectOptions {
  client: TcmsClient;
  /** `--project`: a project key (`OLB`) or a UUID. Absent means "infer". */
  requested?: string | undefined;
  signal?: AbortSignal;
}

/**
 * Resolves the project, or explains what to pass.
 *
 * `GET /projects` is the only source: it answers what this credential can see, which for an
 * `Agent` token bound to one project is exactly one row. A token bound to a project the caller
 * cannot read answers zero rows rather than an error, and the message below says so without
 * asserting the project exists — §6t again.
 */
export async function resolveProject(options: ResolveProjectOptions): Promise<ResolvedProject> {
  const visible = await options.client.GET('/projects', {
    tcms: options.signal ? { signal: options.signal } : {},
  });

  if (options.requested) {
    const wanted = options.requested.trim();
    // Matched on key first and id second, and case-insensitively on the key: a project key is a
    // 2–10 character label a human types ("OLB"), which is the entire reason it exists rather
    // than being allocated (`POST /projects`' own description).
    const match = visible.find(
      (project) => project.key.toLowerCase() === wanted.toLowerCase() || project.id === wanted,
    );

    if (!match) {
      throw new TcmsClientError(
        `No project '${wanted}' is visible to this token. Visible: `
          + `${visible.map((project) => project.key).join(', ') || '(none)'}.`,
      );
    }

    return { id: match.id, key: match.key, name: match.name };
  }

  if (visible.length === 1 && visible[0]) {
    return { id: visible[0].id, key: visible[0].key, name: visible[0].name };
  }

  if (visible.length === 0) {
    throw new TcmsClientError(
      'This token can see no projects. It may be bound to a project its owner cannot read, or '
        + 'its owner may hold no project grant.',
    );
  }

  throw new TcmsClientError(
    `This token can see ${visible.length} projects, so the command needs --project. One of: `
      + `${visible.map((project) => project.key).join(', ')}.`,
  );
}
