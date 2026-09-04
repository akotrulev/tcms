import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { usePutProfile } from '../../api/queries';
import type { PersonProfile } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input } from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { formatDate, formatHours, joinMeta } from '../../lib/format';
import { NO_PROFILE } from './capacityCopy';

/**
 * Somebody's contracted hours, job title, location and experience — `directory.html`'s strapline,
 * with the record behind it.
 *
 * <h2>"6 years" is DERIVED and there is no years field on this form (017 D-b)</h2>
 *
 * The column is `experience_since DATE` and `experienceYears` is computed server-side at read time,
 * so this panel **shows** a count of years and **edits** a date. An integer somebody types is
 * correct on the day it is typed and wrong every day after, silently, and nothing in the product
 * would ever tell anybody — which is why the form must not offer the field the strapline displays.
 *
 * <h2>Absent is a state, not a zero</h2>
 *
 * A person with no profile row reads {@link NO_PROFILE} and an em dash for the hours, never `0h`
 * and never a default of forty (017, correction 4). The panel then offers the create, which is the
 * same `PUT` as the edit — a profile is one row per person and the caller has no id to address it
 * by, which is why there is no separate create.
 *
 * <h2>Editing costs `staffing:profile`, which is COMPANY scope and is not yours by being you</h2>
 *
 * Unlike absence (017 D-h), maintaining a profile is an administrative act on both your own row and
 * anybody else's: contracted hours are what every capacity figure in the company is denominated
 * over, so a person editing their own would be a person moving their own denominator. The control
 * is disabled with the reason rather than hidden (§31).
 */
export interface ProfilePanelProps {
  userId: string;
  profile: PersonProfile | null;
  canEdit: boolean;
  /** The gate's own sentence, so the tooltip and the server's refusal agree. */
  reason: string | undefined;
  testId: string;
}

/** Mirrors `PersonProfileInput`'s bounds — the ones a browser can actually answer. */
const ProfileSchema = z.object({
  weeklyHours: z.coerce
    .number()
    .min(0.5, 'At least half an hour a week.')
    .max(168, 'A week has 168 hours.'),
  jobTitle: z.string().max(200).optional(),
  location: z.string().max(200).optional(),
  experienceSince: z.string().optional(),
});

type ProfileFormInput = z.input<typeof ProfileSchema>;
type ProfileFormValues = z.output<typeof ProfileSchema>;

type FormField = 'weeklyHours' | 'jobTitle' | 'location' | 'experienceSince';

const FIELDS: readonly FormField[] = ['weeklyHours', 'jobTitle', 'location', 'experienceSince'];

export function ProfilePanel({ userId, profile, canEdit, reason, testId }: ProfilePanelProps) {
  const [editing, setEditing] = useState(false);

  return (
    <Panel
      testId={testId}
      title="Profile"
      actions={
        editing ? null : (
          <span title={canEdit ? undefined : reason}>
            <Button
              size="sm"
              disabled={!canEdit}
              onClick={() => setEditing(true)}
              data-testid={`${testId}.Edit`}
            >
              {profile ? 'Edit' : 'Add a profile'}
            </Button>
          </span>
        )
      }
    >
      {editing ?
        <ProfileForm
          userId={userId}
          profile={profile}
          onDone={() => setEditing(false)}
          testId={testId}
        />
      : <div className="flex flex-col gap-2">
          <p className="m-0 text-[20px] font-semibold text-ink" data-testid={`${testId}.Hours`}>
            {profile ? `${formatHours(profile.weeklyHours)} a week` : '—'}
          </p>
          <p className="m-0 text-[12.5px] text-ink-3" data-testid={`${testId}.Meta`}>
            {profile ?
              joinMeta(
                profile.jobTitle,
                profile.location,
                /*
                  Derived, never stored (017 D-b). `experienceYears` arrives beside
                  `experienceSince` and this line does no subtraction of its own — a client that did
                  would be the second answer, and the one nobody could correct.
                */
                profile.experienceYears !== null ?
                  `${profile.experienceYears} ${profile.experienceYears === 1 ? 'year' : 'years'}`
                : null,
              ) || 'Contracted hours only — no title, location or start date recorded.'
            : NO_PROFILE}
          </p>
          {profile?.experienceSince && (
            <p className="m-0 text-[11.5px] text-ink-4" data-testid={`${testId}.Since`}>
              Working since {formatDate(profile.experienceSince)}. The years above are counted from
              that date every time this page is opened, so they never go stale.
            </p>
          )}
          {!profile && (
            <p className="m-0 text-[11.5px] leading-[1.55] text-ink-4">
              Without contracted hours there is no capacity figure for this person anywhere —
              staffing, utilisation and the timesheet all read an em dash rather than assuming a
              week.
            </p>
          )}
        </div>
      }
    </Panel>
  );
}

function ProfileForm({
  userId,
  profile,
  onDone,
  testId,
}: {
  userId: string;
  profile: PersonProfile | null;
  onDone: () => void;
  testId: string;
}) {
  const save = usePutProfile(userId);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ProfileFormInput, unknown, ProfileFormValues>({
    resolver: zodResolver(ProfileSchema),
    defaultValues: {
      weeklyHours: profile?.weeklyHours ?? 40,
      jobTitle: profile?.jobTitle ?? '',
      location: profile?.location ?? '',
      experienceSince: profile?.experienceSince ?? '',
    },
    mode: 'onBlur',
  });

  const submit = handleSubmit(async (values) => {
    try {
      await save.mutateAsync({
        weeklyHours: values.weeklyHours,
        jobTitle: values.jobTitle?.trim() || null,
        location: values.location?.trim() || null,
        // An empty date input submits `''`, which is not a date. Null is what "nobody recorded a
        // start date" means in the column and in the contract — and it is what makes
        // `experienceYears` come back null rather than as a count from the epoch.
        experienceSince: values.experienceSince?.trim() || null,
      });
      onDone();
    } catch (error) {
      if (error instanceof ApiError && error.fieldErrors) {
        let landed = false;
        for (const [field, messages] of Object.entries(error.fieldErrors)) {
          if ((FIELDS as readonly string[]).includes(field)) {
            setError(field as FormField, { type: 'server', message: messages.join(' ') });
            landed = true;
          }
        }
        if (landed) return;
      }
      setError('root', { type: 'server', message: describeError(error).body });
    }
  });

  return (
    <form onSubmit={submit} noValidate aria-busy={save.isPending} data-testid={`${testId}.Form`}>
      <div className="flex flex-col gap-4">
        {errors.root && (
          <div data-testid={`${testId}.Error`}>
            <Banner tone="bad">
              <span role="alert">{errors.root.message}</span>
            </Banner>
          </div>
        )}

        <Field
          label="Contracted hours a week"
          htmlFor="profile-hours"
          error={errors.weeklyHours?.message}
          help="A day is a fifth of this — there is no per-day contract in this product (017 D-c)."
        >
          <Input
            id="profile-hours"
            type="number"
            step="0.5"
            min={0.5}
            max={168}
            required
            className="w-[110px] text-right font-mono tabular-nums"
            aria-invalid={!!errors.weeklyHours}
            data-testid={`${testId}.Hours.Input`}
            {...register('weeklyHours')}
          />
        </Field>

        <div className="flex gap-4">
          <Field label="Job title" htmlFor="profile-title" error={errors.jobTitle?.message} help="Optional.">
            <Input
              id="profile-title"
              autoComplete="off"
              maxLength={200}
              aria-invalid={!!errors.jobTitle}
              data-testid={`${testId}.JobTitle`}
              {...register('jobTitle')}
            />
          </Field>
          <Field label="Location" htmlFor="profile-location" error={errors.location?.message} help="Optional.">
            <Input
              id="profile-location"
              autoComplete="off"
              maxLength={200}
              aria-invalid={!!errors.location}
              data-testid={`${testId}.Location`}
              {...register('location')}
            />
          </Field>
        </div>

        <Field
          label="Working since"
          htmlFor="profile-since"
          error={errors.experienceSince?.message}
          /*
            THE sentence 017 D-b is about, and it belongs on the control rather than in a comment: a
            reader who expected a "years of experience" box needs to be told why there isn't one.
          */
          help="A date, not a number of years. The years shown on this page are counted from it on every read, so they are never a figure somebody has to remember to update."
        >
          <Input
            id="profile-since"
            type="date"
            aria-invalid={!!errors.experienceSince}
            data-testid={`${testId}.Since.Input`}
            {...register('experienceSince')}
          />
        </Field>

        <div className="flex justify-end gap-2">
          <Button variant="quiet" onClick={onDone} data-testid={`${testId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={save.isPending}
            data-testid={`${testId}.Save`}
          >
            {save.isPending ? 'Saving…' : 'Save profile'}
          </Button>
        </div>
      </div>
    </form>
  );
}
