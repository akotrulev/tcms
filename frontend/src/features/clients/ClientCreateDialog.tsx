import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCreateClient } from '../../api/queries';
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
  Textarea,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';

/**
 * `client-new.html` — and its whole argument is what the form does **not** require.
 *
 * <h2>A name is the only required field, and the form says so</h2>
 *
 * Story C1: *"when I save with a name only, then the client exists — contacts, rates and contracts
 * are optional."* That is the product's rule rather than this dialog's convenience, so the help
 * text under the name states it and no other field is marked required. A client with a name and
 * nothing else is a complete client.
 *
 * <h2>Three blocks the mockup draws as sections are not inputs here</h2>
 *
 * `client-new.html` shows Contacts, Rate card and Contracts under an "Optional, and separate"
 * heading. None of them is a field on this form and only one of them exists at all: contacts are
 * added on the client's own page, a rate card is M8's, and contracts are built by no module (018
 * D-h). The footer says what happens next instead of implying a form with sections it does not
 * have.
 *
 * <h2>Account manager is absent, and that is a missing read rather than a decision against it</h2>
 *
 * The contract takes `accountManagerId` and the mockup draws a picker. Populating it needs the
 * company's member list, which is `staffing:directory`'s — a key a Company Admin holding
 * `client:create` need not hold. Rendering an empty picker, or one that 403s, is worse than
 * leaving the field to the day a company-member read exists on this gate.
 *
 * <h2>A dialog rather than a route</h2>
 *
 * Short, self-contained, and the reader wants the list behind it — `ui-rules` §7's first two
 * questions. `ConnectAssistantPage` is a route for the opposite reason: it shows a secret that
 * cannot be recovered after a dismissal, and nothing here is unrecoverable.
 */

const MAX_NAME = 200;
const MAX_LOCATION = 200;
const MAX_INDUSTRY = 120;
const MAX_NOTES = 4000;

const ClientSchema = z.object({
  name: z
    .string()
    .min(1, 'Enter the client’s name.')
    .max(MAX_NAME, `Keep the name under ${MAX_NAME} characters.`),
  location: z.string().max(MAX_LOCATION).optional(),
  industry: z.string().max(MAX_INDUSTRY).optional(),
  notes: z.string().max(MAX_NOTES).optional(),
});

type ClientFormInput = z.infer<typeof ClientSchema>;

const TestId = 'Clients.Create';

type FormField = 'name' | 'location' | 'industry' | 'notes';

const FIELDS: readonly FormField[] = ['name', 'location', 'industry', 'notes'];

/**
 * Maps a rejected create onto the form.
 *
 * The server keys `errors` by the *input schema's* field names, so a duplicate name lands on the
 * name input — where the reader's next action is. A 403 has no field to land on and goes to the
 * form-level alert through `describeError`, the one place an error becomes user-facing copy.
 */
function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      const known = FIELDS.find((candidate) => candidate === field);
      if (known) {
        setError(known, messages.join(' '));
        landed = true;
      }
    }
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

export function ClientCreateDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const create = useCreateClient();
  const navigate = useNavigate();
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<ClientFormInput>({
    resolver: zodResolver(ClientSchema),
    defaultValues: { name: '', location: '', industry: '', notes: '' },
    mode: 'onBlur',
  });

  useEffect(() => {
    if (open) setFocus('name');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const created = await create.mutateAsync({
        name: values.name.trim(),
        location: values.location?.trim() || null,
        industry: values.industry?.trim() || null,
        notes: values.notes?.trim() || null,
      });
      onClose();
      /*
        Straight to the new client, because the next thing a person does after recording one is add
        a contact or attach a project — and both live there. Returning to a list where the only
        change is one more row makes the reader find what they just made.
      */
      navigate(`/clients/${created.id}`);
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TestId}>
        <form onSubmit={submit} noValidate aria-busy={create.isPending}>
          <DialogHeader>
            <DialogTitle>Add client</DialogTitle>
          </DialogHeader>
          <div className="mt-4 flex flex-col gap-4">
            {errors.root && (
              <div data-testid={`${TestId}.Error`}>
                <Banner tone="bad">
                  <span role="alert">{errors.root.message}</span>
                </Banner>
              </div>
            )}
            <Field
              label="Client name"
              htmlFor="client-name"
              error={errors.name?.message}
              help="The only required field on this form."
            >
              <Input
                id="client-name"
                required
                autoComplete="off"
                aria-invalid={!!errors.name}
                maxLength={MAX_NAME}
                data-testid={`${TestId}.Name`}
                {...register('name')}
              />
            </Field>
            <Field label="Location" htmlFor="client-location" error={errors.location?.message} help="Optional.">
              <Input
                id="client-location"
                autoComplete="off"
                aria-invalid={!!errors.location}
                maxLength={MAX_LOCATION}
                data-testid={`${TestId}.Location`}
                {...register('location')}
              />
            </Field>
            <Field label="Industry" htmlFor="client-industry" error={errors.industry?.message} help="Optional.">
              <Input
                id="client-industry"
                autoComplete="off"
                aria-invalid={!!errors.industry}
                maxLength={MAX_INDUSTRY}
                data-testid={`${TestId}.Industry`}
                {...register('industry')}
              />
            </Field>
            <Field label="Notes" htmlFor="client-notes" error={errors.notes?.message} help="Optional.">
              <Textarea
                id="client-notes"
                rows={2}
                aria-invalid={!!errors.notes}
                data-testid={`${TestId}.Notes`}
                {...register('notes')}
              />
            </Field>
            <p className="m-0 max-w-[62ch] text-[12px] leading-[1.55] text-ink-4" data-testid={`${TestId}.NotAnAccount`}>
              A client is a <strong>record</strong>, not an account. Nobody at this organisation can
              sign in, see a board, or be granted a permission by creating it. Contacts are added on
              the client’s own page, and adding one creates no account either.
            </p>
          </div>
          <DialogFooter>
            <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={create.isPending}
              data-testid={`${TestId}.Submit`}
            >
              {create.isPending ? 'Saving…' : 'Save client'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
