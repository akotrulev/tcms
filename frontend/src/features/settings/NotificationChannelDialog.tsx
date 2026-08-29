import { useState } from 'react';
import { ApiError } from '../../api/client';
import {
  useCreateNotificationChannel,
  useUpdateNotificationChannel,
} from '../../api/queries';
import type {
  NotificationChannel,
  NotificationChannelInput,
  NotificationChannelKind,
  NotificationEventType,
} from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Switch,
  Textarea,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';
import {
  NOTIFICATION_CHANNEL_KINDS,
  NOTIFICATION_EVENT_TYPES,
  notificationChannelCredentialHelp,
  notificationChannelKindLabels,
  notificationEventTypeHelp,
  notificationEventTypeLabels,
} from '../../lib/labels';

/**
 * Adding a notification channel, and editing one — **and the whole slice turns on what this form
 * does with an empty secret field.**
 *
 * ## Absent means unchanged, and it is the only field in this API that works that way
 *
 * A Slack or Teams incoming-webhook URL *is* the authentication for the room it posts to, so it is
 * encrypted at rest and returned by nothing: the screen has `secretHint` — the masked tail — and no
 * way to read the value back. That makes the ordinary `PUT` convention impossible here. Everywhere
 * else in this product an omitted field on a full replacement is a **clear**; if it were one here,
 * every edit form in the world would blank the credential of the channel somebody merely renamed.
 *
 * The failure has no error and no symptom. The row goes on reading as configured, the save reports
 * success, and delivery starts failing hours later at a time unconnected to the edit that caused
 * it. So this form is careful in a specific way that is easy to undo:
 *
 *   - **The `secret` property is added to the payload only when the field has something in it.**
 *     Not `secret: value || null`, not `secret: ''` — the property is absent from the object. A
 *     "tidy-up" that always sets the key is the defect, and `NotificationSettingsPage.test.tsx`
 *     asserts the payload rather than the outcome for exactly that reason.
 *   - **The field is never pre-filled**, because there is nothing to fill it with. It renders empty
 *     with the hint beside it and says what leaving it empty means.
 *
 * ## `kind` is disabled on an edit rather than omitted, and the contract forces the choice
 *
 * The `PUT` **ignores** `kind` — a channel's transport is not editable, because turning a Slack
 * channel into an email one would keep the row's name and history while replacing everything they
 * were about. But `NotificationChannelInput` lists it as **required**, so a form that dropped the
 * control would have to invent a value or send an invalid body. Disabling the control and
 * submitting the channel's own kind is the only arrangement that both sends a legal body and tells
 * the user the truth; the helper text names Delete-and-create as the way to move a transport, which
 * is what the endpoint's own description says.
 *
 * ## Subscriptions are the complete set, so unchecking is how you unsubscribe
 *
 * The array is reconciled server-side against what is stored — an event type left out is removed.
 * That is `updateProjectSettings`' argument arriving here: the form shows every control and submits
 * every control, so "leave this one alone" is not something a user of this screen can mean. It also
 * means the failure mode of forgetting a checkbox is *silent unsubscription*, which is why the
 * checkbox list is derived from `NOTIFICATION_EVENT_TYPES` rather than written out.
 */

const TestId = 'Notifications.Dialog';

/** One row of the subscription list, as the form holds it before it becomes an input array. */
interface SubscriptionDraft {
  wanted: boolean;
  minFailures: number;
}

type FieldName = 'name' | 'recipients' | 'secret';

export function NotificationChannelDialog({
  open,
  projectId,
  channel,
  onClose,
}: {
  open: boolean;
  projectId: string;
  /** The channel being edited, or `undefined` to create one. */
  channel?: NotificationChannel;
  onClose: () => void;
}) {
  const editing = channel !== undefined;
  const create = useCreateNotificationChannel(projectId);
  const update = useUpdateNotificationChannel(projectId);
  const save = editing ? update : create;

  const [kind, setKind] = useState<NotificationChannelKind>(channel?.kind ?? 'SLACK');
  const [name, setName] = useState(channel?.name ?? '');
  const [enabled, setEnabled] = useState(channel?.enabled ?? true);
  /*
    Free text rather than a chip editor, and parsed on submit. The server validates each address and
    lands `errors.recipients` on this field, so a client-side address parser here would be a second
    opinion about what an address is — and the one that is wrong is the one nobody can appeal.
  */
  const [recipients, setRecipients] = useState((channel?.recipients ?? []).join(', '));
  /*
    ALWAYS EMPTY on mount, including on an edit, because there is nothing to pre-fill it with — the
    value has never left the server. See this file's header: the emptiness is the signal, and what
    the form does with it is the slice.
  */
  const [secret, setSecret] = useState('');
  const [subscriptions, setSubscriptions] = useState<Record<string, SubscriptionDraft>>(() =>
    Object.fromEntries(
      NOTIFICATION_EVENT_TYPES.map((eventType) => {
        const held = channel?.subscriptions.find((row) => row.eventType === eventType);
        return [eventType, { wanted: !!held, minFailures: held?.minFailures ?? 0 }];
      }),
    ),
  );

  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({});
  const [formError, setFormError] = useState<string | undefined>(undefined);

  const isWebhook = kind === 'SLACK' || kind === 'TEAMS';
  const trimmedSecret = secret.trim();
  /*
    Required on the create for a webhook kind and never on the edit, mirroring the contract exactly.
    An edit with no secret is the ORDINARY case — somebody renaming a channel or changing what it
    subscribes to — so treating a blank field as an incomplete form here would make the common path
    unsubmittable to prevent nothing.
  */
  const secretMissing = !editing && isWebhook && trimmedSecret === '';
  const submittable = name.trim() !== '' && !secretMissing && !save.isPending;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setFieldErrors({});
    setFormError(undefined);

    const input: NotificationChannelInput = {
      // The channel's own kind on an edit — the control is disabled, and the server ignores this
      // value there anyway. Required by the input schema either way.
      kind,
      name: name.trim(),
      enabled,
      // `[]` for the webhook kinds, which post to whatever room their URL names. The contract says
      // to send it rather than omit it.
      recipients: kind === 'EMAIL' ? parseRecipients(recipients) : [],
      subscriptions: NOTIFICATION_EVENT_TYPES.filter((type) => subscriptions[type].wanted).map(
        (eventType) => ({ eventType, minFailures: subscriptions[eventType].minFailures }),
      ),
      /*
        THE line this dialog exists to get right. The property is present only when the field has
        something in it — never `secret: ''` and never `secret: null`, both of which the server
        would read as a rotation to an empty credential.
      */
      ...(trimmedSecret === '' ? {} : { secret: trimmedSecret }),
    };

    try {
      if (channel) {
        await update.mutateAsync({
          channelId: channel.id,
          // The version the client read, as `If-Match`. A 409 is a lost update and `describeError`
          // owns the sentence for it.
          lockVersion: channel.lockVersion,
          input,
        });
      } else {
        await create.mutateAsync(input);
      }
      onClose();
    } catch (error) {
      // The server keys `Problem.errors` by the input schema's field names, so each message lands on
      // the control that caused it. Anything without a field — a 403, a 409 — goes to the form-level
      // alert through `describeError`, the one place an error becomes user-facing copy.
      if (error instanceof ApiError && error.fieldErrors) {
        const landed: Partial<Record<FieldName, string>> = {};
        for (const [field, messages] of Object.entries(error.fieldErrors)) {
          if (field === 'name' || field === 'recipients' || field === 'secret') {
            landed[field] = messages.join(' ');
          }
        }
        if (Object.keys(landed).length > 0) {
          setFieldErrors(landed);
          return;
        }
      }
      setFormError(describeError(error).body);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TestId}>
        <form onSubmit={(event) => void submit(event)} noValidate aria-busy={save.isPending}>
          <DialogHeader>
            <DialogTitle>{editing ? `Edit ${channel.name}` : 'Add a notification channel'}</DialogTitle>
          </DialogHeader>

          <div className="mt-4 flex max-h-[62vh] flex-col gap-4 overflow-y-auto pr-1">
            {formError && (
              <div data-testid={`${TestId}.Error`}>
                <Banner tone="bad">
                  <span role="alert">{formError}</span>
                </Banner>
              </div>
            )}

            <Field
              label="Transport"
              htmlFor="notification-kind"
              help={
                editing
                  ? 'A channel’s transport cannot be changed. To move these notices somewhere else, delete this channel and add a new one — the delivery history belongs to the transport and goes with it.'
                  : 'Slack and Teams post to a room through an incoming webhook. Email is sent through this instance’s own mail relay.'
              }
            >
              <NativeSelect
                id="notification-kind"
                required
                value={kind}
                /*
                  DISABLED, not hidden, on an edit — §31 applied to a field rather than to a button.
                  The value is still submitted because the input schema requires it, and the server
                  ignores it. See this file's header for why the transport cannot move.
                */
                disabled={editing}
                onChange={(event) => setKind(event.target.value as NotificationChannelKind)}
                data-testid={`${TestId}.Kind`}
              >
                {NOTIFICATION_CHANNEL_KINDS.map((option) => (
                  <option key={option} value={option} data-testid={`${TestId}.Kind.${option}`}>
                    {notificationChannelKindLabels[option]}
                  </option>
                ))}
              </NativeSelect>
            </Field>

            <Field
              label="Name"
              htmlFor="notification-name"
              error={fieldErrors.name}
              help="Your own label — “#qa-alerts”, “Release leads”. Unique in this project, so two channels posting to the same room are told apart by what somebody called them."
            >
              <Input
                id="notification-name"
                required
                autoFocus
                maxLength={200}
                value={name}
                onChange={(event) => setName(event.target.value)}
                data-testid={`${TestId}.Name`}
              />
            </Field>

            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <p className="text-[13px] text-ink-2">Deliver to this channel</p>
                <p className="text-[12px] text-ink-4">
                  A switched-off channel is skipped when an event fires. Its configuration and its
                  history are kept.
                </p>
              </div>
              <Switch
                checked={enabled}
                onCheckedChange={setEnabled}
                aria-label="Deliver to this channel"
                data-testid={`${TestId}.Enabled`}
              />
            </div>

            {kind === 'EMAIL' ? (
              <Field
                label="Recipients"
                htmlFor="notification-recipients"
                error={fieldErrors.recipients}
                help="Email addresses, separated by commas or new lines. At least one — a channel with none delivers to nobody."
              >
                <Textarea
                  id="notification-recipients"
                  required
                  rows={2}
                  value={recipients}
                  onChange={(event) => setRecipients(event.target.value)}
                  data-testid={`${TestId}.Recipients`}
                />
              </Field>
            ) : (
              <Field
                label={editing ? 'Replace the webhook URL' : 'Webhook URL'}
                htmlFor="notification-secret"
                error={fieldErrors.secret}
                help={
                  editing
                    ? /*
                        The sentence the whole slice rests on. An administrator who reads "leave it
                        empty" and does so must be right — and they are, because the property is
                        absent from the payload rather than sent empty.
                      */
                      'Leave this empty to keep the webhook that is configured. Type a new URL only to replace it.'
                    : notificationChannelCredentialHelp[kind]
                }
              >
                <Input
                  id="notification-secret"
                  type="password"
                  required={!editing}
                  value={secret}
                  onChange={(event) => setSecret(event.target.value)}
                  data-testid={`${TestId}.Secret`}
                />
              </Field>
            )}

            {editing && isWebhook && (
              <div data-testid={`${TestId}.SecretHint`}>
                <Banner tone="neutral">
                  {channel.secretHint ? (
                    <span>
                      The webhook configured now ends with{' '}
                      <Identifier variant="muted" testId={`${TestId}.SecretHint.Value`}>
                        {channel.secretHint}
                      </Identifier>
                      . That tail is all this product will ever show — it is enough to recognise
                      which webhook is set, and far short of enough to post to it.
                    </span>
                  ) : (
                    <span>
                      No webhook is configured for this channel, so nothing can be delivered through
                      it until one is set.
                    </span>
                  )}
                </Banner>
              </div>
            )}

            <div className="border-t border-white/8 pt-4">
              <p className="text-[13px] text-ink-2">What this channel is told about</p>
              <p className="text-[12px] leading-[1.55] text-ink-4">
                {/*
                  Said before the save rather than discovered afterwards. The array is the COMPLETE
                  set and the server reconciles against it, so an unticked box is an unsubscribe —
                  which is the intended behaviour and is invisible unless the form says so.
                */}
                Whatever is ticked here is the complete list. Unticking one stops this channel being
                told about it; a channel with nothing ticked is a legal channel that never fires,
                which is a reasonable state while a webhook is being tested.
              </p>

              <div className="mt-3 flex flex-col gap-3">
                {NOTIFICATION_EVENT_TYPES.map((eventType) => (
                  <EventRow
                    key={eventType}
                    eventType={eventType}
                    draft={subscriptions[eventType]}
                    onChange={(next) =>
                      setSubscriptions((current) => ({ ...current, [eventType]: next }))
                    }
                  />
                ))}
              </div>
            </div>
          </div>

          <DialogFooter>
            <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.Cancel`}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={!submittable}
              data-testid={`${TestId}.Submit`}
            >
              {save.isPending ? 'Saving…' : editing ? 'Save channel' : 'Add channel'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/**
 * One event type, its threshold, and the sentence that says whether the threshold does anything.
 *
 * The threshold renders for every event, including the one that carries no counts, because which
 * events carry counts is the server's fact and a client that hid the field per type would be a
 * second copy of it. `notificationEventTypeHelp` says which is which instead.
 */
function EventRow({
  eventType,
  draft,
  onChange,
}: {
  eventType: NotificationEventType;
  draft: SubscriptionDraft;
  onChange: (next: SubscriptionDraft) => void;
}) {
  const boxId = `notification-event-${eventType}`;
  const thresholdId = `notification-min-failures-${eventType}`;

  return (
    <div>
      <div className="flex items-start gap-2.5">
        {/*
          A native checkbox rather than the kit's, because this is a control the suite clicks on
          the render it arrives at: `@radix-ui/react-checkbox`'s root is a button whose behaviour
          leans on pointer APIs jsdom does not implement, and the platform control needs no
          adapter to be clickable, checkable and labelled.
        */}
        <input
          id={boxId}
          type="checkbox"
          className="mt-0.5 size-4 shrink-0 accent-[color:var(--color-steel)]"
          checked={draft.wanted}
          onChange={(event) => onChange({ ...draft, wanted: event.target.checked })}
          data-testid={`Notifications.Dialog.Event.${eventType}`}
        />
        <label htmlFor={boxId} className="min-w-0">
          <span className="block text-[13px] text-ink-2">{notificationEventTypeLabels[eventType]}</span>
          <span className="block text-[12px] leading-[1.5] text-ink-4">
            {notificationEventTypeHelp[eventType]}
          </span>
        </label>
      </div>
      {draft.wanted && (
        <div className="mt-2 ml-6 max-w-[300px]">
          <Field
            label="Only when this many results failed"
            htmlFor={thresholdId}
            help="0 is every occurrence."
          >
            <Input
              id={thresholdId}
              type="number"
              min={0}
              className="w-[92px] text-right font-mono tabular-nums"
              value={draft.minFailures}
              onChange={(event) =>
                /*
                  `Number('')` is 0, which is the contract's own default and means "every
                  occurrence" — so clearing the field lands on the value the field would have had
                  anyway rather than on a NaN the server refuses.
                */
                onChange({ ...draft, minFailures: Math.max(0, Math.trunc(Number(event.target.value) || 0)) })
              }
              data-testid={`Notifications.Dialog.MinFailures.${eventType}`}
            />
          </Field>
        </div>
      )}
    </div>
  );
}

/**
 * Addresses out of a comma- or newline-separated field.
 *
 * Splits and trims and nothing else — no validation. The server validates each address and lands
 * `errors.recipients` on the field, and a client-side notion of what an address is would be a
 * second opinion whose refusals nobody can appeal.
 */
function parseRecipients(text: string): string[] {
  return text
    .split(/[,\n;]/)
    .map((value) => value.trim())
    .filter((value) => value !== '');
}
