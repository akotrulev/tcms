/** The documentation space and the artifact register. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, api, qs } from '../client';
import type {
  Artifact,
  ArtifactInput,
  BlockLink,
  BlockLinkInput,
  DocPage,
  DocPageInput,
  DocPageStatus,
  DocPageSummary,
} from '../types';
import { base, keys } from './keys';

export function useDocPages(projectId: string): UseQueryResult<DocPageSummary[]> {
  return useQuery({
    queryKey: keys.docPages(projectId),
    queryFn: ({ signal }) => api.get<DocPageSummary[]>(`${base(projectId)}/doc-pages`, { signal }),
  });
}

export function useDocPage(projectId: string, pageId: string | undefined): UseQueryResult<DocPage> {
  return useQuery({
    queryKey: keys.docPage(projectId, pageId ?? ''),
    queryFn: ({ signal }) => api.get<DocPage>(`${base(projectId)}/doc-pages/${pageId}`, { signal }),
    enabled: !!pageId,
  });
}

/**
 * Creates a page.
 *
 * `status` is deliberately absent from `DocPageInput` — the server always creates a `DRAFT`, and
 * publishing is a different endpoint with a different permission. A `status` here would be a field
 * the write path ignores, which `ui-rules` names as the worst kind of form defect.
 */
export function useCreateDocPage(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: DocPageInput) => api.post<DocPage>(`${base(projectId)}/doc-pages`, input),
    // The dialog renders field errors on its own inputs and the 403 in its own alert, so the
    // global notification would be a second, vaguer copy of a message already on screen.
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.docPages(projectId) }),
  });
}

/**
 * Retitles a page and replaces its blocks.
 *
 * `lockVersion` travels as `If-Match`, never in the body — the header is where the contract puts
 * it, and a body field would be a value on the input schema that must never persist. §52's suite
 * rename is the precedent: the SPA sent it in the body for as long as no server read it.
 *
 * **Every block that already exists must be sent back with its `id`.** Omitting an id makes the
 * server issue a new one, which is the same as deleting the block and creating a replacement —
 * and every case linked to it points at nothing afterwards, silently (plan §6i/§6m). The editor
 * carries the ids through its form state for exactly this reason.
 */
export function useUpdateDocPage(projectId: string, pageId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ lockVersion, ...input }: DocPageInput & { lockVersion: number }) =>
      api.patch<DocPage>(`${base(projectId)}/doc-pages/${pageId}`, input, {
        headers: { 'If-Match': String(lockVersion) },
      }),
    meta: { handled: true },
    onSuccess: (page) => {
      client.setQueryData(keys.docPage(projectId, pageId), page);
      void client.invalidateQueries({ queryKey: keys.docPages(projectId) });
    },
  });
}

/**
 * Publishes, unpublishes or archives a page.
 *
 * A separate hook rather than a `status` field on the edit, because it is a separate permission
 * (`doc:publish`, which Test Engineer does not hold) — and `ui-rules` is explicit that a state
 * transition is an action, never an editable status control.
 */
export function useSetDocPageStatus(projectId: string, pageId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (status: DocPageStatus) =>
      api.put<DocPage>(`${base(projectId)}/doc-pages/${pageId}/status`, { status }),
    meta: { handled: true },
    onSuccess: (page) => {
      client.setQueryData(keys.docPage(projectId, pageId), page);
      void client.invalidateQueries({ queryKey: keys.docPages(projectId) });
    },
  });
}

export function useDeleteDocPage(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (pageId: string) => api.del<void>(`${base(projectId)}/doc-pages/${pageId}`),
    // The 409 here says a case still links to this page's content, or that it has children —
    // messages the dialog shows in place, beside the page they are about. Handed to the global
    // surface they would arrive as a toast detached from the thing they describe.
    meta: { handled: true },
    /*
      The list, and deliberately NOT the deleted page's own query.

      `removeQueries` on a key that still has a mounted observer does not just forget the data —
      it makes that observer refetch immediately, and the row is gone, so the answer is a 404.
      The live pass saw the result: a delete that succeeded rendered "Not found" in the panel and
      raised a global "Something didn't load", while the tree beside it correctly showed the page
      was gone.

      Leaving the key alone lets the screen keep showing what it had for the instant before the
      caller navigates away, after which the entry is garbage-collected like any other. The
      screen's own redirect (see `DocsPage`) is what guarantees nobody is left reading it.
    */
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.docPages(projectId) });
    },
  });
}

export function useArtifacts(
  projectId: string,
  scope?: 'PROJECT' | 'PLAN',
  planId?: string,
): UseQueryResult<Artifact[]> {
  return useQuery({
    queryKey: keys.artifacts(projectId, scope, planId),
    queryFn: ({ signal }) =>
      api.get<Artifact[]>(`${base(projectId)}/artifacts${qs({ scope, planId })}`, { signal }),
  });
}

/**
 * Registers a reference in the artifact register.
 *
 * Only the two **reference** kinds are accepted — `FILE` needs the storage port that open question
 * #28 resolved and nobody built, `GENERATED_REPORT` needs the `reporting` module. The contract's
 * `ArtifactInput.kind` is a narrower enum than `Artifact.kind` for exactly this reason, so the
 * unregistrable kinds are a compile error here rather than a 400 at runtime.
 *
 * **Both scopes are invalidated, not just the one written.** A project-scope artifact appears in
 * every plan's register as an inherited row (plan §6n), so writing one changes lists this mutation
 * never named. `keys.artifacts` is keyed by scope and plan, so invalidating the prefix is what
 * makes the inherited copy refresh — invalidating the exact key would leave a plan's register
 * showing a register that is one document short.
 */
export function useCreateArtifact(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ArtifactInput) =>
      api.post<Artifact>(`${base(projectId)}/artifacts`, input),
    // The dialog renders field errors on its own inputs — including the scope-qualified 403, which
    // the server words as advice about the scope selector — so the global notification would be a
    // second, vaguer copy of a message already on screen.
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId, 'artifacts'] }),
  });
}

/**
 * A plan's document, or `null` when it has not been started.
 *
 * **`null` rather than an error**, which is the whole reason this hook exists rather than
 * `useDocPage` with a resolved id. The server answers 404 for a plan with no document — the
 * ordinary first state, since a plan is created long before anybody writes one — and letting that
 * reach the query client would raise a global "Something didn't load" on a screen whose correct
 * rendering is an invitation to start writing.
 *
 * Every other 404 (unreachable project, unknown plan) reaches the same `null`, and that is
 * acceptable here for the reason the server gives them one status: the caller reached this screen
 * through the plan, so it already knows the plan resolves.
 */
export function usePlanDocument(
  projectId: string,
  planId: string,
): UseQueryResult<DocPage | null> {
  return useQuery({
    queryKey: keys.planDocument(projectId, planId),
    queryFn: ({ signal }) =>
      api
        .get<DocPage>(`${base(projectId)}/plans/${planId}/document`, { signal })
        .catch((error: unknown) => {
          if (error instanceof ApiError && error.status === 404) return null;
          throw error;
        }),
  });
}

/*
  ~~`useStartPlanDocument` — creates the plan's document from the server's five-section template.~~
  → **retired 2026-08-05 (§97)** with the `POST` it called. Every one of those five sections now has
  a home of its own (business requirements is a page the plan *adopts*, test artefacts is the
  artifact register, scope and out of scope are prose on the plan row, and the case outline is the
  plan's sectioned scope items), so the template's only remaining job was to seed a second place to
  write each of them. Its replacement is `PUT .../document`, which links a page that already exists
  — and the hook for that arrives with the tab that offers it (§97 phase 5), not here, so that the
  contract phase adds no affordance it cannot yet render.

  The reasoning it carried is kept because it is about the *cache* and outlives the endpoint: a plan
  document is one page reached two ways — by plan and by page id — so a write must seed both keys
  and invalidate the tree, or the documentation sidebar is one page short until something else does.
*/

/**
 * Adopts an existing page as the plan's business-requirements document (§97 D4′).
 *
 * **`doc:edit`, not `plan:edit`** — the write lands on the page's `subjectType`/`subjectId` and
 * nothing on the plan, which is the same act as editing the page seen from the other end.
 *
 * Both keys are seeded, and the plan key by **prefix**: this page is reached as *the plan's
 * document* and as an ordinary page, and a plan whose tab still says "no page is linked" after the
 * adoption succeeded is the shape a second key is for. The doc-page tree moves too, because the
 * summary now carries a subject.
 *
 * `handled` because two of its three refusals are 409s naming *which* plan already holds the page —
 * a sentence about the row the author just clicked, which a corner toast detaches from it.
 */
export function useLinkPlanDocument(projectId: string, planId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (pageId: string) =>
      api.put<DocPage>(`${base(projectId)}/plans/${planId}/document`, { pageId }),
    meta: { handled: true },
    onSuccess: (page) => {
      client.setQueryData(keys.planDocument(projectId, planId), page);
      client.setQueryData(keys.docPage(projectId, page.id), page);
      void client.invalidateQueries({ queryKey: keys.docPages(projectId) });
    },
  });
}

/**
 * Releases the plan's document. **The page survives** — this clears a link, and deleting the page
 * is `DELETE /doc-pages/{pageId}`, a different act with a different blast radius. The control says
 * so; this hook is the half that must not tidy up after it.
 *
 * The plan key is set to `null` rather than removed, which is `usePlanDocument`'s own answer for a
 * plan with no document: `removeQueries` on a key with a mounted observer forces an immediate
 * refetch, and the refetch is a 404 — the tab would render "Not found" a beat after an unlink that
 * worked (`useDeleteDocPage` records the same trap from the other side).
 */
export function useUnlinkPlanDocument(projectId: string, planId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api.del<void>(`${base(projectId)}/plans/${planId}/document`),
    meta: { handled: true },
    onSuccess: () => {
      client.setQueryData(keys.planDocument(projectId, planId), null);
      void client.invalidateQueries({ queryKey: keys.docPages(projectId) });
    },
  });
}

/**
 * Links a test case or suite to one block.
 *
 * Invalidates the page rather than patching the block in place. The response is one link and the
 * screen renders a block's whole list, so merging it here would be a second assembly of a shape the
 * server already sends — and the server is the only side that knows whether the link was created or
 * had been there all along.
 */
export function useLinkBlock(projectId: string, pageId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ blockId, ...input }: BlockLinkInput & { blockId: string }) =>
      api.post<BlockLink>(
        `${base(projectId)}/doc-pages/${pageId}/blocks/${blockId}/links`,
        input,
      ),
    meta: { handled: true },
    onSuccess: () => invalidatePageEverywhere(client, projectId, pageId),
  });
}

/** Removes a link. Hard delete on the server — the claim is withdrawn, not tombstoned. */
export function useUnlinkBlock(projectId: string, pageId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (linkId: string) =>
      api.del<void>(`${base(projectId)}/doc-pages/${pageId}/links/${linkId}`),
    meta: { handled: true },
    onSuccess: () => invalidatePageEverywhere(client, projectId, pageId),
  });
}

/**
 * Invalidates a page under every key that serves it.
 *
 * One page, two keys — its own and, when it documents a plan, that plan's. A link written from the
 * plan's document tab must not leave the same page stale on the documentation screen, and neither
 * mutation above knows which of the two the caller is looking at.
 *
 * The plan key is invalidated by **prefix**, because this function has the page id and not the plan
 * id. That is broader than necessary and it is the cheap direction: the alternative is threading a
 * plan id through two hooks that are also used from a screen where there is no plan.
 */
function invalidatePageEverywhere(
  client: ReturnType<typeof useQueryClient>,
  projectId: string,
  pageId: string,
) {
  void client.invalidateQueries({ queryKey: keys.docPage(projectId, pageId) });
  void client.invalidateQueries({ queryKey: ['projects', projectId, 'plans'] });
}
