/**
 * Generated cases waiting for a person — the review queue (§138, plan §6w.6).
 *
 * **Four hooks and only two of them are writes**, which is the shape of the whole feature: the SPA
 * never *files* a proposal. Filing is what an agent does over MCP or the CLI, and this screen is
 * the other end of that — the boundary where §6w.6's rule (*"nothing generated writes to
 * `test_case` without a human accepting it"*) is actually enforced by a person clicking.
 *
 * **Accept returns a `TestCase`, not a proposal**, because the accept path *is* the create path:
 * the server hands the stored payload to the same service `POST /cases` uses, so what comes back is
 * a case like any other. That is why {@link useAcceptProposal} invalidates the case list as well as
 * the queue — a screen showing this project's cases is stale the moment one is accepted, and
 * nothing else would tell it.
 *
 * **A 400 from accept is normal and is not a bug.** The payload was stored unvalidated on purpose,
 * so a suite that has since been deleted or a retired shared step lands as ordinary field errors on
 * `TestCaseInput`'s own names. The caller renders them; it must not treat them as an unexpected
 * failure, which is why both mutations carry `meta: { handled: true }` and the screen shows the
 * message itself.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api, qs } from '../client';
import type {
  AiProposal,
  AiProposalSummary,
  ProposalStatus,
  TestCase,
} from '../types';
import { base, invalidateCaseLists, keys } from './keys';

/** What the queue endpoint answers — `PageMeta` plus the rows. */
interface ProposalPage {
  items: AiProposalSummary[];
  page: number;
  size: number;
  total: number;
}

/**
 * The queue, in one status.
 *
 * Defaults to `PENDING` on the server too, and the parameter is sent explicitly anyway: a client
 * that relied on the default would silently start showing a different list if the server's default
 * ever changed, and the status is in the query key regardless.
 */
export function useAiProposals(
  projectId: string,
  status: ProposalStatus = 'PENDING',
): UseQueryResult<ProposalPage> {
  return useQuery({
    queryKey: keys.aiProposals(projectId, status),
    queryFn: ({ signal }) =>
      // `qs`, not an interpolated `?status=…`: contractOperations.test.ts reads the literal path
      // out of this file and matches it against openapi.yaml, and an inline query string is part of
      // the literal it reads. Every other query-carrying call in this module goes through the
      // helper for the same reason.
      api.get<ProposalPage>(`${base(projectId)}/ai-proposals${qs({ status })}`, { signal }),
  });
}

/**
 * One proposal, with its payload and its grounding.
 *
 * A separate hook and a separate key rather than a `find` over the queue, for
 * {@link useIngestion}'s reason: the two endpoints serve different shapes on purpose, so a screen
 * reading the row it already had would render a proposal with no steps and no evidence, and nothing
 * would say that anything was missing.
 */
export function useAiProposal(
  projectId: string,
  proposalId: string | undefined,
): UseQueryResult<AiProposal> {
  return useQuery({
    queryKey: keys.aiProposal(projectId, proposalId ?? ''),
    queryFn: ({ signal }) =>
      api.get<AiProposal>(`${base(projectId)}/ai-proposals/${proposalId}`, { signal }),
    enabled: !!proposalId,
  });
}

/**
 * Accepts a proposal — and what comes back is the **case** it became.
 *
 * Invalidates three things, and each is load-bearing: the queue (the row has left it), the proposal
 * itself (it now names a case), and **the project's cases** (there is a new one, and the grid is
 * the screen most likely to be open beside this).
 */
export function useAcceptProposal(
  projectId: string,
): UseMutationResult<TestCase, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (proposalId: string) =>
      api.post<TestCase>(`${base(projectId)}/ai-proposals/${proposalId}/accept`, {}),
    // The 400 this can answer is a field-error response the screen renders itself — see the module
    // header. A global notification for it would say "something went wrong" over a message that
    // says exactly what is wrong.
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.aiProposalsRoot(projectId) });
      invalidateCaseLists(client, projectId);
    },
  });
}

/** Rejects a proposal. The row stays — see `ProposalStatus`. */
export function useRejectProposal(
  projectId: string,
): UseMutationResult<AiProposal, Error, { proposalId: string; note: string | null }> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ proposalId, note }) =>
      api.post<AiProposal>(`${base(projectId)}/ai-proposals/${proposalId}/reject`, { note }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.aiProposalsRoot(projectId) });
    },
  });
}
