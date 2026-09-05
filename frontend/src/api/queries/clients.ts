/**
 * Clients — the company's own customers, their contacts, and the project-settings picker.
 *
 * <h2>One key for the reads, two scopes for the picker</h2>
 *
 * Everything under `/clients` costs `client:read`, a COMPANY-scope key held by Instance Admin,
 * Company Admin, Account Manager and Finance. The picker under `/projects/{id}/settings` costs
 * **`project:settings`** instead (scope D1, plan 010 D-M0-9): Project Manager is PROJECT-scope, so
 * a company-wide `client:read` would be an inert row for them, and granting it to the
 * `Company Member` baseline would break the one sentence that role is defined by.
 *
 * Both reads are gated with `enabled` rather than allowed to 403. A refusal here would raise a
 * notification through `app/queryClient.ts` for a control the reader was never meant to see, which
 * is a failure report about the product rather than about the request.
 *
 * <h2>An empty answer is the module, not an edge of it</h2>
 *
 * A company that builds its own products never records a client (scope D1 rule 2), and every
 * client-shaped surface in this app reads one of these two hooks and renders **nothing** when the
 * answer is empty — no column, no filter, no field, no prompt, and nothing marking a project as
 * missing one. `tcmsFaults.clients('none')` is what makes that state walkable on an instance that
 * has clients, and `clients('some')` the reverse.
 *
 * <h2>Every write sweeps two roots, and neither covers the other</h2>
 *
 * `keys.clientsRoot` is `['clients']`; a project's picker is keyed under `['projects', p, …]` and
 * a project card carries its client's **name**. So a client renamed or archived changes both
 * subtrees, and {@link invalidateClients} sweeps both — see `keys.clients.optionsFor` for why the
 * picker is not under `clientsRoot` in the first place.
 *
 * <h2>No rate, no cost, no invoice</h2>
 *
 * Nothing on this surface carries money. What a client's work is worth is M8's, gated on
 * `billing:*` and `ratecard:read`, and `client.html`'s hours-and-invoices table is that module's
 * whole table rather than three of its five columns (018 D-g).
 */
import { useMutation, useQuery, useQueryClient, type QueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type {
  Client,
  ClientContact,
  ClientContactInput,
  ClientDetail,
  ClientInput,
  ClientRef,
  ClientStatus,
  Project,
} from '../types';
import { keys } from './keys';

/**
 * Every list a client write must refresh, in one place.
 *
 * Two roots because they are two subtrees and neither is a prefix of the other. `['projects']` is
 * swept whole rather than per project: a client's **name** is on every project card that references
 * it and on the overview chip, a rename changes all of them at once, and this module cannot know
 * which projects those are without a read of its own. It is the honest sweep here where it would be
 * far too wide in `staffing` — client writes are rare and a project list is cheap.
 */
function invalidateClients(client: QueryClient) {
  void client.invalidateQueries({ queryKey: keys.clientsRoot });
  void client.invalidateQueries({ queryKey: ['projects'] });
}

/**
 * `clients.html` — every client in the company.
 *
 * `enabled` on the permission, so a reader without `client:read` never fires the request: the rail
 * entry is gated on the same key, and a surface that reached this hook anyway would report a
 * refusal for something it should simply not have drawn.
 */
export function useClients(
  options: { status?: ClientStatus | 'ALL'; accountManagerId?: string; enabled?: boolean } = {},
): UseQueryResult<Client[]> {
  const { status = 'ACTIVE', accountManagerId = '', enabled = true } = options;
  return useQuery({
    queryKey: keys.clients.list(status, accountManagerId),
    queryFn: ({ signal }) =>
      api.get<Client[]>(`/clients${qs({ status, accountManagerId })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled,
  });
}

/** `client.html` — one client with its contacts and its projects, in one read. */
export function useClient(clientId: string | undefined): UseQueryResult<ClientDetail> {
  return useQuery({
    queryKey: keys.clients.one(clientId ?? ''),
    queryFn: ({ signal }) =>
      api.get<ClientDetail>(`/clients/${clientId}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: clientId !== undefined,
  });
}

/**
 * The clients this project may be attached to — story C2's picker, on `project:settings`.
 *
 * It answers `[]` for a company with no clients, which is what makes the Client field on General
 * settings **absent** rather than an empty select. It also carries back any archived client the
 * project already names: a picker that dropped it would render "None (internal)" as the selected
 * value and detach the project on the next save.
 */
export function useProjectClientOptions(
  projectId: string | undefined,
  enabled: boolean,
): UseQueryResult<ClientRef[]> {
  return useQuery({
    queryKey: keys.clients.optionsFor(projectId ?? ''),
    queryFn: ({ signal }) =>
      api.get<ClientRef[]>(`/projects/${projectId}/settings/client-options`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && projectId !== undefined,
  });
}

export function useCreateClient() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ClientInput) =>
      api.post<Client>('/clients', input, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => invalidateClients(client),
  });
}

export function useUpdateClient(clientId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ClientInput) =>
      api.put<Client>(`/clients/${clientId}`, input, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => invalidateClients(client),
  });
}

/**
 * Delete a client no project references.
 *
 * The 409 it can answer is not a failure to report generically — it names archiving as the way
 * forward, and the caller renders that sentence rather than a bare "could not delete" (018 D-k).
 */
export function useDeleteClient() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (clientId: string) =>
      api.del(`/clients/${clientId}`, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => invalidateClients(client),
  });
}

/**
 * Add a contact to a client.
 *
 * **This creates no account**, and nothing on this path can: `ClientContactInput` carries a name, a
 * role, an email and a phone, and no credential field exists to send (scope D1).
 */
export function useAddClientContact(clientId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ClientContactInput) =>
      api.post<ClientContact>(`/clients/${clientId}/contacts`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateClients(client),
  });
}

export function useRemoveClientContact(clientId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (contactId: string) =>
      api.del(`/clients/${clientId}/contacts/${contactId}`, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => invalidateClients(client),
  });
}

/**
 * Attach a project to a client, change it, or detach it.
 *
 * **One field, and it is the target state rather than a verb** — the shape
 * `PUT /projects/{projectId}/archive` already uses, and `null` detaches. It is deliberately not a
 * field on the settings document (018 D-d): there, an absent nullable id and an explicit null are
 * the same value, so a save from the Review screen — which does not offer the field — would have
 * silently detached the project.
 */
export function useSetProjectClient(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (clientId: string | null) =>
      api.put<Project>(`/projects/${projectId}/client`, { clientId }, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateClients(client),
  });
}
