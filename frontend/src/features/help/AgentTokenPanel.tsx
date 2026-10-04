import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { useIssueApiToken, useProjects, useRoles } from '../../api/queries';
import type { IssuedApiToken } from '../../api/types';
import { useCanInCompany } from '../../auth/useCan';
import { CopyField } from '../../components/CopyField';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import { TokenSteps } from './TokenSteps';

/**
 * Issuing the agent token **from the guide**, so the configuration below it can be complete.
 *
 * ## Why a form on a help page, which is not where features go
 *
 * The setup was three screens: read the guide, cross the app to `/admin/tokens`, come back with a
 * secret and paste it into a block. The middle step is the one that loses people — it is a
 * different screen, behind a permission whose absence is only discovered there, and it hands back
 * a value that cannot be recovered if the return journey goes wrong. What the reader wants is the
 * finished configuration, and the token is an input to it, so it is asked for here.
 *
 * This is deliberately **not** a second copy of `IssueTokenDialog`. That screen administers
 * credentials — every role, any project, an optional expiry, a name somebody will later read while
 * deciding what to revoke. This one issues exactly one shape: an `Agent` token, ninety days,
 * pinned where the reader says. Anything else is the dialog's job and the panel links to it.
 *
 * ## What it does with the secret
 *
 * The same as the dialog, for the same reasons: the plaintext lives in component state, is never
 * written to the query cache (`useIssueApiToken` says why), and dies when the reader leaves the
 * page. The difference is that a page is easier to navigate away from than a modal is to dismiss,
 * so the warning says so in those words rather than "store it now".
 *
 * ## The three permission states are three different pages
 *
 * `token:issue` is company-scope and most readers of this guide do not hold it. A generator
 * rendered for them would be a control that 403s, and hiding the whole subject would leave them
 * with no idea what to ask for — so a caller without the key gets {@link TokenSteps}, which names
 * the permission and the fields somebody else will fill in on their behalf. While the verdict is
 * still loading neither is shown: `Gate.can` is fail-closed while pending, which is right for
 * disabling a button and would here mean flashing "ask an administrator" at a Company Admin on
 * every visit.
 */
const TestId = 'Help.Mcp.Token';

/** The wide answer, distinct from an unset select — `IssueTokenDialog`'s constant, same reason. */
const ALL_PROJECTS = 'ALL';

/** `ApiTokenService.MAX_NAME`, mirrored rather than guessed. */
const MAX_NAME = 120;

/** What an agent token is issued for. The dialog defaults to the same ninety days, and says why. */
const NINETY_DAYS_MS = 90 * 24 * 60 * 60 * 1000;

export function AgentTokenPanel({
  issued,
  onIssued,
}: {
  issued: IssuedApiToken | null;
  onIssued: (token: IssuedApiToken) => void;
}) {
  const gate = useCanInCompany();

  if (gate.pending) {
    return (
      <p className="mb-4 text-[13px] text-ink-3" data-testid={`${TestId}.Pending`}>
        Checking what you can do here…
      </p>
    );
  }

  if (!gate.can('token:issue')) return <TokenSteps purpose="agent" />;

  return <Generator issued={issued} onIssued={onIssued} />;
}

/**
 * The name a token is born with.
 *
 * A name is required and must be unique among the company's live tokens, so a one-press flow needs
 * one that is both — hence the timestamp, which also answers the question the tokens screen asks
 * months later: *what is this and can I revoke it*. "Agent" alone would be every row on it.
 *
 * **Short, because the column is narrow.** The first draft read "Agent set up from the guide, …"
 * and the tokens table wrapped it over eight lines, making the row this flow creates the ugliest
 * one on that screen. The field is editable and the reader can say more if they want to.
 */
function defaultName(now: Date = new Date()): string {
  return `Agent — ${formatDateTime(now.toISOString())}`.slice(0, MAX_NAME);
}

function Generator({
  issued,
  onIssued,
}: {
  issued: IssuedApiToken | null;
  onIssued: (token: IssuedApiToken) => void;
}) {
  const roles = useRoles();
  const projects = useProjects();
  const issue = useIssueApiToken();

  const [name, setName] = useState(defaultName);
  /*
    `null` is "the reader has not chosen", which is not the same as ALL — that distinction is what
    lets the default below be computed rather than written in by an effect. A token for somebody
    who can see exactly one project has one sensible ceiling and no decision in it, which is what
    makes this a single press for the common case.
  */
  const [chosen, setChosen] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | undefined>();
  const [formError, setFormError] = useState<string | undefined>();

  const onlyProject = projects.data?.length === 1 ? projects.data[0]!.id : undefined;
  const projectId = chosen ?? onlyProject ?? ALL_PROJECTS;

  /*
    Matched on the name, because role ids are seeded data this client does not hold — the same
    resolution `IssueTokenDialog` makes for the expiry rule. `Agent` carries `assignable: false`,
    so it is deliberately absent from every grant picker and present here.
  */
  const agentRole = (roles.data ?? []).find((role) => role.name === 'Agent');

  if (issued) {
    return (
      <div className="mb-4" data-testid={`${TestId}.Issued`}>
        <div className="mb-3" data-testid={`${TestId}.Warning`}>
          <Banner tone="warn">
            <span role="alert">
              This is the only time this token is shown. It is in the configuration below — copy
              that now, because leaving this page loses the secret and nothing can show it again.
            </span>
          </Banner>
        </div>
        <CopyField label="Token" value={issued.secret} testId={`${TestId}.Secret`} />
        <p className="mt-2 text-[12.5px] leading-[1.55] text-ink-3">
          Issued as <b className="font-medium text-ink">{issued.token.name}</b>, carrying the{' '}
          <b className="font-medium text-ink">Agent</b> role
          {issued.token.expiresAt ? ` until ${formatDateTime(issued.token.expiresAt)}` : ''}. Revoke
          it whenever you like from{' '}
          <RouterLink to="/admin/tokens" className="text-steel no-underline hover:underline">
            Company → API tokens
          </RouterLink>
          .
        </p>
      </div>
    );
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setNameError(undefined);
    setFormError(undefined);

    if (!agentRole) {
      // Rather than posting a roleId this client invented. The role is seeded, so its absence
      // means the roles read has not answered — or that this instance's catalogue is not the one
      // this guide describes, which is worth saying instead of a 400 the reader cannot act on.
      setFormError(
        roles.isPending
          ? 'Still reading this instance’s roles — try again in a moment.'
          : 'This instance has no Agent role, so a token for an agent cannot be issued from here.',
      );
      return;
    }

    try {
      onIssued(
        await issue.mutateAsync({
          name: name.trim(),
          roleId: agentRole.id,
          projectId: projectId === ALL_PROJECTS ? null : projectId,
          // Required for an Agent token, and computed rather than asked: a credential that lives
          // on somebody's machine is the one nobody remembers to withdraw. The dialog is where a
          // different date is chosen.
          expiresAt: new Date(Date.now() + NINETY_DAYS_MS).toISOString(),
        }),
      );
    } catch (error) {
      // `name` is the one field this form can be refused on that the reader can fix here — the
      // company's live token names are unique — so it lands on the input. Everything else is a
      // sentence about the request, and `describeError` is the one place that copy is written.
      if (error instanceof ApiError && error.fieldErrors?.name) {
        setNameError(error.fieldErrors.name.join(' '));
        return;
      }
      setFormError(describeError(error).body);
    }
  };

  return (
    <form
      onSubmit={(event) => void submit(event)}
      noValidate
      aria-busy={issue.isPending}
      className="mb-4 rounded-lg border border-line bg-wash-1 p-4"
      data-testid={TestId}
    >
      <h4 className="mb-1 text-[14px] font-semibold text-ink">Generate one now</h4>
      <p className="mb-3 text-[12.5px] leading-[1.55] text-ink-3">
        An <b className="font-medium text-ink">Agent</b> token, expiring in 90 days, dropped
        straight into the configuration below. It never grants more than you can already do.
      </p>

      {formError && (
        <div className="mb-3" data-testid={`${TestId}.Error`}>
          <Banner tone="bad">
            <span role="alert">{formError}</span>
          </Banner>
        </div>
      )}

      <div className="flex flex-col gap-3">
        <Field
          label="Name"
          htmlFor="help-token-name"
          error={nameError}
          help="What you will read on the tokens screen when deciding whether to revoke it."
        >
          <Input
            id="help-token-name"
            required
            maxLength={MAX_NAME}
            value={name}
            onChange={(event) => setName(event.target.value)}
            data-testid={`${TestId}.Name`}
          />
        </Field>

        <Field
          label="Where it works"
          htmlFor="help-token-project"
          help={
            projectId === ALL_PROJECTS
              ? 'Every project you can reach, including ones created later.'
              : 'A hard limit: the token is inert on every other project, and the agent needs no project setting of its own.'
          }
        >
          <NativeSelect
            id="help-token-project"
            value={projectId}
            onChange={(event) => setChosen(event.target.value)}
            data-testid={`${TestId}.Project`}
          >
            <option value={ALL_PROJECTS}>Every project I can reach</option>
            {(projects.data ?? []).map((project) => (
              <option key={project.id} value={project.id}>
                {project.name}
              </option>
            ))}
          </NativeSelect>
        </Field>

        <div className="flex items-center gap-3">
          <Button
            type="submit"
            variant="primary"
            disabled={issue.isPending || !name.trim()}
            data-testid={`${TestId}.Submit`}
          >
            {issue.isPending ? 'Generating…' : 'Generate token'}
          </Button>
          <span className="text-[12.5px] text-ink-3">
            Or issue one with every option from{' '}
            <RouterLink to="/admin/tokens" className="text-steel no-underline hover:underline">
              Company → API tokens
            </RouterLink>
            .
          </span>
        </div>
      </div>
    </form>
  );
}
