/**
 * The `integration` module's one surface (§218) — a project's notification channels, and what came
 * back from each attempt to deliver through them.
 *
 * ## Every read here is gated, which is unusual in this directory and is the slice's decision
 *
 * Almost every other read module fetches unconditionally and lets a screen decide what to *do* with
 * the answer. These six operations all cost `integration:manage`, **reads included**, so a caller
 * without the key gets a 403 rather than a narrowed answer. That makes an ungated fetch the wrong
 * client behaviour twice over: it would raise a global "Something didn't load" over a screen that is
 * working exactly as designed, and it would do so on every visit by a Test Engineer.
 *
 * So both read hooks take an `enabled` the caller drives from the gate's verdict — `useApiTokens`'
 * shape, for `useApiTokens`' reason. **The verdict must be resolved, not merely fail-closed**: pass
 * `gate.pending ? false : gate.can('integration:manage')` and render nothing while it is pending,
 * or the list flashes a denial at an administrator on every navigation.
 *
 * ## Why the read is gated at all, since it is the part that looks like an oversight
 *
 * `getProjectSettings` one path family over is readable by anybody who can see the project, and this
 * is deliberately not that. Nothing in this product branches on whether a channel exists, so there
 * is no reader who needs the answer and cannot have the key — while a channel carries a
 * `secretHint` (the tail of a live credential) and `recipients` (people's addresses), and a delivery
 * row's `error` is whatever a remote endpoint said. The refusal splits the way `readGranted` splits
 * everywhere: a project the caller cannot see is 404, a project they are looking at without the key
 * is 403.
 *
 * ## The secret is write-only and this module is the last place that could get it wrong
 *
 * `NotificationChannelInput.secret` is **absent means unchanged** on the `PUT` — the one field in
 * this API where an omission is not a clear. `useUpdateNotificationChannel` therefore takes the
 * input already composed by the form and does nothing to it: it does not default, normalise or
 * fill the field. A `secret: ''` reaching the server blanks the credential of a channel somebody
 * merely renamed, the row goes on reading as configured, and delivery starts failing hours later at
 * a time unconnected to the edit that caused it.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api, TIMEOUTS } from '../client';
import type {
  NotificationChannel,
  NotificationChannelInput,
  NotificationDelivery,
} from '../types';
import { base, keys } from './keys';

const channels = (projectId: string) => `${base(projectId)}/notification-channels`;

/**
 * The project's channels, ordered by name.
 *
 * `staleTime` matches the token list rather than the five-minute vocabularies: this is a screen
 * somebody is editing, and the row they just saved on another tab is the row they came back to
 * check.
 */
export function useNotificationChannels(
  projectId: string,
  enabled: boolean,
): UseQueryResult<NotificationChannel[]> {
  return useQuery({
    queryKey: keys.notificationChannels(projectId),
    queryFn: ({ signal }) =>
      api.get<NotificationChannel[]>(channels(projectId), { signal, timeoutMs: TIMEOUTS.interactive }),
    enabled: enabled && !!projectId,
    staleTime: 30_000,
  });
}

/**
 * One channel.
 *
 * No consumer on the settings screen — it renders from the list, which serves the identical shape —
 * and it exists for the deep link the `Location` header of the create names. `enabled` gates on the
 * channel id as well as the verdict, so a screen that has not selected one fires nothing.
 */
export function useNotificationChannel(
  projectId: string,
  channelId: string | undefined,
  enabled: boolean,
): UseQueryResult<NotificationChannel> {
  return useQuery({
    queryKey: keys.notificationChannel(projectId, channelId ?? ''),
    queryFn: ({ signal }) =>
      api.get<NotificationChannel>(`${channels(projectId)}/${channelId!}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && !!projectId && !!channelId,
    staleTime: 30_000,
  });
}

/**
 * A channel's delivery attempts — newest first, most recent 50.
 *
 * `staleTime: 0`, unlike every other read in this module, and it is the point of the panel: an
 * operator opens this immediately after saving a webhook to find out whether it works, and a cached
 * answer from thirty seconds ago is the one state that cannot tell them.
 */
export function useNotificationDeliveries(
  projectId: string,
  channelId: string | undefined,
  enabled: boolean,
): UseQueryResult<NotificationDelivery[]> {
  return useQuery({
    queryKey: keys.notificationDeliveries(projectId, channelId ?? ''),
    queryFn: ({ signal }) =>
      api.get<NotificationDelivery[]>(`${channels(projectId)}/${channelId!}/deliveries`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && !!projectId && !!channelId,
    staleTime: 0,
  });
}

/**
 * Adds a channel.
 *
 * **No `Idempotency-Key`**, and that is the contract's decision rather than an omission: there is no
 * idempotency record behind this path, so a client that sent the header and trusted it would replay
 * a timed-out create and get a second channel that delivers every notice twice. §205's finding is
 * what makes declaring one anyway the expensive mistake.
 *
 * **The list is invalidated rather than appended to.** The server orders by name and assembles
 * `secretHint` from a value this client is holding in plaintext and must not keep — writing the
 * response into the list by hand would be one more place the credential's masked form is derived,
 * and the list is one small request behind a screen somebody is already waiting on.
 *
 * `meta: { handled: true }`: the dialog lands `name`, `recipients` and `secret` field errors on its
 * own inputs and the 403 in its own alert, so a global toast would be a vaguer second copy of a
 * message already on screen.
 */
export function useCreateNotificationChannel(
  projectId: string,
): UseMutationResult<NotificationChannel, Error, NotificationChannelInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: NotificationChannelInput) =>
      api.post<NotificationChannel>(channels(projectId), input),
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: keys.notificationChannels(projectId) }),
  });
}

/**
 * Replaces a channel — a `PUT` of the whole value, with `lockVersion` on the wire as `If-Match`.
 *
 * Three things here are decisions:
 *
 *   - **`If-Match` is always sent.** The header's own description says the SPA does; omitting it is
 *     the documented last-write-wins path, and a channel edit is a *field* edit typed over what
 *     somebody else read, which is the case that earns a precondition. A 409 is a lost update and
 *     `describeError` owns the sentence for it — do not compose one at the page.
 *   - **The input is forwarded untouched**, so `secret` stays absent when the form left it blank.
 *     See this module's header; this is the one field in the API where an omission is not a clear.
 *   - **Both the list and this channel's own entry are invalidated**, and the detail is not written
 *     from the response even though the response is the whole channel. `secretHint` is derived
 *     server-side from a value that may have just been rotated, and a rename reorders the list, so
 *     there is nothing here the client can assemble more cheaply than the server can answer.
 */
export function useUpdateNotificationChannel(
  projectId: string,
): UseMutationResult<
  NotificationChannel,
  Error,
  { channelId: string; lockVersion: number; input: NotificationChannelInput }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ channelId, lockVersion, input }) =>
      api.put<NotificationChannel>(`${channels(projectId)}/${channelId}`, input, {
        headers: { 'If-Match': String(lockVersion) },
      }),
    meta: { handled: true },
    onSuccess: (_channel, { channelId }) => {
      void client.invalidateQueries({ queryKey: keys.notificationChannels(projectId) });
      void client.invalidateQueries({ queryKey: keys.notificationChannel(projectId, channelId) });
    },
  });
}

/**
 * Removes a channel, and its delivery history with it.
 *
 * **No `If-Match`**, mirroring the endpoint: a delete names an absolute destination, so a
 * precondition could only refuse somebody who wanted exactly what happened.
 *
 * The two entries beneath the channel are **removed rather than invalidated**, which is the one
 * place this module departs from the invalidate-everything habit. The server hard-deletes the
 * history along with the channel, so an invalidation would schedule a refetch of a sub-resource
 * whose parent is gone — a 404 rendered as a failed region on a screen where nothing failed.
 */
export function useDeleteNotificationChannel(
  projectId: string,
): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (channelId: string) => api.del<void>(`${channels(projectId)}/${channelId}`),
    meta: { handled: true },
    onSuccess: (_result, channelId) => {
      client.removeQueries({ queryKey: keys.notificationChannel(projectId, channelId) });
      client.removeQueries({ queryKey: keys.notificationDeliveries(projectId, channelId) });
      void client.invalidateQueries({ queryKey: keys.notificationChannels(projectId) });
    },
  });
}
