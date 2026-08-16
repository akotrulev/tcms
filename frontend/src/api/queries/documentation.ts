/** The documentation space and the artifact register. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useRef } from 'react';
import { ApiError, api, fetchArtifact, newIntentKey, qs } from '../client';
import type {
  Artifact,
  ArtifactCirculation,
  ArtifactEditInput,
  ArtifactFileInput,
  ArtifactInput,
  Attachment,
  BlockLink,
  BlockLinkInput,
  DocPage,
  DocPageInput,
  DocPageSearchHit,
  DocPageStatus,
  DocPageSummary,
  LinkRelationship,
} from '../types';
import { base, keys } from './keys';

export function useDocPages(projectId: string): UseQueryResult<DocPageSummary[]> {
  return useQuery({
    queryKey: keys.docPages(projectId),
    queryFn: ({ signal }) => api.get<DocPageSummary[]>(`${base(projectId)}/doc-pages`, { signal }),
  });
}

/**
 * Full-text search over the project's documentation.
 *
 * Not a typeahead, and the budget says so: matching is by lexeme, so a half-typed word answers
 * nothing at all and results-as-you-type would read as broken until the moment a word completes.
 * The box submits, so this is an ordinary interactive read.
 *
 * Disabled on an empty query rather than asking the server for nothing — the server answers `[]`
 * either way, and a query that does not run leaves no cache entry to render while the reader is
 * still deciding what to look for.
 */
export function useDocPageSearch(
  projectId: string,
  query: string,
): UseQueryResult<DocPageSearchHit[]> {
  return useQuery({
    queryKey: keys.docPageSearch(projectId, query),
    queryFn: ({ signal }) =>
      api.get<DocPageSearchHit[]>(`${base(projectId)}/doc-pages/search${qs({ q: query })}`, {
        signal,
      }),
    enabled: query.trim().length > 0,
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
 * An attachment's **bytes**, for the one surface that has to render them rather than offer them.
 *
 * ## Why this exists at all, and why it is not an `<img src>`
 *
 * The session is a bearer token this client sets per request — `SecurityConfiguration` is
 * `STATELESS` and there is no cookie anywhere — so a browser-initiated request to an `/api/v1/…`
 * URL carries **no credential** and answers **401**. An `<img src={attachment.url}>` renders as a
 * broken image with nothing failing in any gate, which is exactly the hole `FileDownloadLink` was
 * extracted to close for anchors. `fetchArtifact` is the same guarded, budgeted, credential-carrying
 * read both use.
 *
 * ## Why an image block gets this and an attachment ROW does not
 *
 * §126 phase 4 recorded the absence of thumbnails as a decision: honouring them on the file list
 * would mean fetching **every** attachment of a page into memory on load, unbounded, with no
 * thumbnail endpoint to ask for something smaller. An `image` block is the opposite case on all
 * three counts — the picture *is* the content, the count is bounded by the blocks somebody wrote,
 * and a block that renders nothing is not a tidier list but a missing paragraph.
 *
 * ## The cached value is the `Blob`, never an object URL
 *
 * An object URL is a **lifetime**, not a value: whoever creates one must revoke it, and a revoked
 * URL sitting in a query cache is a broken image on the next mount that reads it. So the cache holds
 * the blob — an ordinary immutable value — and the component derives a URL from it in an effect and
 * revokes that URL when it unmounts. Two blocks showing one file then share a single download.
 *
 * `staleTime: Infinity` because these bytes cannot change: an attachment is never rewritten, it is
 * hard-deleted, and a deleted one takes the block with it (the server refuses the delete otherwise).
 */
export function useAttachmentBlob(
  url: string | undefined,
): UseQueryResult<Blob> {
  return useQuery({
    queryKey: keys.attachmentBlob(url ?? ''),
    queryFn: ({ signal }) => fetchArtifact(url ?? '', { signal }).then((result) => result.blob),
    enabled: !!url,
    staleTime: Infinity,
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
 * (`doc:publish` — ~~which Test Engineer does not hold~~ → held by every contributor since `V52`,
 * §210, and still its own key and its own audit row) — and `ui-rules` is explicit that a state
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

/**
 * Attaches a file to a documentation page (#49), gated on **`doc:edit`**.
 *
 * `doc:edit` and not `doc:publish`: an image on a page is part of what the page says, and the
 * publication state is a separate act with a separate key. It is also **not** author-qualified, for
 * `useAttachCaseFile`'s reason one entity over — the same key already rewrites every block on the
 * page, so an authorship arm on the picture would be stricter than the paragraph beside it.
 *
 * ## Both keys, because one page is reached two ways
 *
 * A page that documents a plan is served under `keys.docPage` *and* under that plan's
 * `keys.planDocument`, which is why `invalidatePageEverywhere` exists at the bottom of this file.
 * A file attached from `/docs` must not leave the plan's Business-requirements tab one attachment
 * short, and vice versa. Reusing that helper rather than invalidating the page key is the whole
 * difference.
 */
export function useAttachDocPageFile(projectId: string, pageId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      // FormData, and no hand-set Content-Type — `api.upload` is the one call that leaves it to the
      // browser so the multipart boundary survives. It also carries the long budget.
      const form = new FormData();
      form.append('file', file);
      return api.upload<Attachment>(`${base(projectId)}/doc-pages/${pageId}/attachments`, form);
    },
    // The panel renders the refusal beside the control. A 413 is refused by the servlet container
    // before any handler runs, so it carries no field to land on and no problem document to read.
    meta: { handled: true },
    onSuccess: () => invalidatePageEverywhere(client, projectId, pageId),
  });
}

/** Removes a file from a page. Hard delete, `doc:edit`, no author arm — see the attach above. */
export function useDeleteDocPageFile(projectId: string, pageId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (attachmentId: string) =>
      api.del<void>(`${base(projectId)}/doc-pages/${pageId}/attachments/${attachmentId}`),
    meta: { handled: true },
    onSuccess: () => invalidatePageEverywhere(client, projectId, pageId),
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
 * ~~Only the two **reference** kinds are accepted — `FILE` needs the storage port that open question
 * #28 resolved and nobody built, `GENERATED_REPORT` needs the `reporting` module.~~ → **three of the
 * four are, since audit C6 gave `GENERATED_REPORT` a writer**; `FILE` is the one this operation
 * refuses, and it goes to `useRegisterArtifactFile` because the bytes travel with the registration.
 * The contract's
 * `ArtifactInput.kind` is a narrower enum than `Artifact.kind` for exactly this reason, so the
 * unregistrable kind is a compile error here rather than a 400 at runtime.
 *
 * **Both scopes are invalidated, not just the one written.** A project-scope artifact appears in
 * every plan's register as an inherited row (plan §6n), so writing one changes lists this mutation
 * never named. `keys.artifacts` is keyed by scope and plan, so invalidating the prefix is what
 * makes the inherited copy refresh — invalidating the exact key would leave a plan's register
 * showing a register that is one document short.
 */
export function useCreateArtifact(projectId: string) {
  const client = useQueryClient();
  /*
    One key per mounted dialog — `useCreateCase`'s rule and its reason (B4, `V59`): it survives
    re-renders and failed attempts, so pressing Register again after a timeout sends the SAME key
    and the server answers with the artifact the first attempt made. A key generated inside
    `mutationFn` would be a fresh one per attempt, which looks right and does nothing.
  */
  const intentKey = useRef(newIntentKey());
  return useMutation({
    mutationFn: (input: ArtifactInput) =>
      api.post<Artifact>(`${base(projectId)}/artifacts`, input, {
        idempotencyKey: intentKey.current,
      }),
    // The dialog renders field errors on its own inputs — including the scope-qualified 403, which
    // the server words as advice about the scope selector — so the global notification would be a
    // second, vaguer copy of a message already on screen.
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId, 'artifacts'] }),
  });
}

/**
 * Registers an **uploaded file** as an artifact — one request carrying the register fields and the
 * bytes together (#49 phase 3).
 *
 * ## This is deliberately not a third copy of the attachment family
 *
 * A case and a page *have* files; a `FILE` artifact **is** one. `V1` gives `artifact` a single
 * nullable `attachment_id` beside three sibling payload columns — four kinds, four payloads — so
 * there is no list here, no attach-afterwards, no `PUT` and no detach. Replacing the document is
 * *superseding* it (plan §6n), which is a second row naming the first, not new bytes under the old
 * author and timestamp. A hook offering re-upload would be an affordance for an endpoint that does
 * not exist.
 *
 * ## The `artifact` part is a JSON **Blob**, and a plain string would 415
 *
 * The server binds it with `@RequestPart("artifact") ArtifactFileInput`, which resolves through a
 * message converter chosen by *that part's own* `Content-Type`. `form.append('artifact', json)` with
 * a string sends `text/plain` and no converter matches; wrapping it in a `Blob` typed
 * `application/json` is what makes the part readable. It is a one-word difference that fails at the
 * transport layer with a message about media types, nowhere near the field it is really about.
 *
 * ## Both scopes are invalidated
 *
 * `useCreateArtifact`'s rule, unchanged and for the same reason: a project-scope artifact appears in
 * every plan's register as an inherited row, so writing one changes lists this mutation never named.
 * The prefix is what makes the inherited copy refresh.
 *
 * `handled` because the dialog lands the server's `errors` map on its own controls — including the
 * scope-qualified 403, which the server words as advice about the scope selector.
 */
export function useRegisterArtifactFile(projectId: string) {
  const client = useQueryClient();
  const intentKey = useRef(newIntentKey());
  return useMutation({
    mutationFn: ({ artifact, file }: { artifact: ArtifactFileInput; file: File }) => {
      const form = new FormData();
      form.append(
        'artifact',
        new Blob([JSON.stringify(artifact)], { type: 'application/json' }),
      );
      form.append('file', file);
      /*
        The key matters more here than anywhere else in this file (B4): this is the longest request
        the product makes, so it is the one that meets a proxy timeout while the server is still
        writing — and a retry used to leave a second register row, a second `attachment` row and a
        second copy of the bytes on disk, of which only the first is visible.
      */
      return api.upload<Artifact>(`${base(projectId)}/artifacts/files`, form, {
        idempotencyKey: intentKey.current,
      });
    },
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId, 'artifacts'] }),
  });
}

/**
 * Archives an artifact, or restores it — `ARCHIVED`'s only writer (§209).
 *
 * ## Not `handled`, unlike the two creates
 *
 * There is no form here and no field to land a message on: the control is a menu item in a table
 * row, so a refusal has nowhere to render except the global notification. The two refusals it can
 * produce are both worth reading — the scope-qualified 403 (this row belongs to the project and
 * your grant does not) and the 409 (a newer artifact replaced this one) — and both are worded by
 * the server for exactly this reader.
 *
 * ## Both scopes are invalidated
 *
 * `useCreateArtifact`'s rule and its reason: a project-scope artifact appears in every plan's
 * register as an inherited row (plan §6n), so archiving one changes lists this mutation never
 * named. The prefix is what refreshes the inherited copy.
 */
/**
 * Corrects an artifact's title, category or description — **A6, and the register's first edit**.
 *
 * `artifact:edit` has had a UI writer since §126 and it could only ever *register* or *archive*;
 * nothing could change what a row says. So `ArtifactTable`'s own comment was right that a typo
 * could only be answered by superseding or by delete-and-recreate, and both of those say something
 * untrue about the project in order to fix a spelling.
 *
 * Invalidation is `useCreateArtifact`'s prefix and for its reason: a project-scope artifact appears
 * in every plan's register as an inherited row, so correcting one changes lists this mutation never
 * named.
 *
 * `handled` because the dialog lands the server's `errors` map on its own controls — including the
 * scope-qualified 403 and the write-once 409, which is a sentence about the record rather than
 * about the caller.
 */
export function useUpdateArtifact(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ artifactId, input }: { artifactId: string; input: ArtifactEditInput }) =>
      api.patch<Artifact>(`${base(projectId)}/artifacts/${artifactId}`, input),
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId, 'artifacts'] }),
  });
}

/**
 * Unregisters an artifact — **`DELETE /artifacts/{id}` shipped in §126 and had no caller at all**.
 *
 * Same prefix invalidation, same reason. **No optimistic removal**: the server refuses two states
 * with a 409 — a sign-off record, and a row another artifact supersedes — and taking the row off
 * screen before the answer arrives would put it back with an error beside it, for the one action
 * whose refusal the reader most needs to read.
 *
 * `handled` because that 409 belongs in the confirm dialog that raised it, not in a corner toast
 * detached from the row it is about.
 */
export function useDeleteArtifact(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (artifactId: string) =>
      api.del<void>(`${base(projectId)}/artifacts/${artifactId}`),
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId, 'artifacts'] }),
  });
}

export function useSetArtifactStatus(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ artifactId, status }: { artifactId: string; status: ArtifactCirculation }) =>
      api.put<Artifact>(`${base(projectId)}/artifacts/${artifactId}/status`, { status }),
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

/**
 * Corrects what a block link claims (§174).
 *
 * Invalidates the page, for `useLinkBlock`'s reason: `DocBlock.links` is assembled off `doc_link`
 * inside the same GET, so the refetch returns the corrected row. This is the opposite call from
 * `useRelabelCaseExternalLink` one file over, and the difference is the read model rather than the
 * act — the case end reads a projection an event refreshes, and a refetch there would put the old
 * claim back.
 *
 * The coverage marker is why the invalidate matters here beyond the row itself: `VERIFIES` counts
 * and the other two do not, so a correction the page keeps rendering as coverage is a false number
 * that looks exactly like a correct one.
 */
export function useRelabelBlockLink(projectId: string, pageId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ linkId, relationship }: { linkId: string; relationship: LinkRelationship }) =>
      api.patch<BlockLink>(
        `${base(projectId)}/doc-pages/${pageId}/links/${linkId}`,
        { relationship },
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
