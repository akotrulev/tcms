import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { useConnectAssistant, useProjects } from '../../api/queries';
import type { IssuedAssistant } from '../../api/types';
import { CopyBlock } from '../../components/CopyBlock';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { assistantSetupCommand } from '../../lib/clientSetup';
import { describeError } from '../../lib/describeError';

/**
 * `assistant-connect.html` — name it, scope it, take the snippet.
 *
 * ## The secret exists for one render, and this file is the whole of why that is true
 *
 * `POST /me/assistants` is one of two endpoints in this product that answer with a plaintext
 * credential. The value lives in this component's `useState` and nowhere else: not in the query
 * cache, not in the URL, not in a store. Navigating away loses it, which is what the warning says
 * in those words rather than the softer "store it now" — a reader who believes they can come back
 * for it is the one who will.
 *
 * The step is deliberately **not** a dialog. A modal invites dismissal, and this one carries the
 * only copy of something unrecoverable; a route the reader has to leave on purpose is the shape
 * that matches the consequence. It is also why `AgentTokenPanel` is not reused here despite doing
 * something similar: that panel issues a *company* API token behind `token:issue`, on the guide,
 * and this endpoint costs authentication alone. Two gates, two audiences, one shape in common.
 *
 * ## Scope is the whole form, because reach is not a choice
 *
 * There is no role picker, no expiry field and no permission list, and their absence is the
 * product decision rather than a simplification: the role is `Agent`, the owner is the caller and
 * the expiry is set server-side. What the assistant can do is *whatever its owner can do*,
 * intersected — so the only thing left to decide is which project it may see.
 */
const TEST_ID = 'ConnectAssistant';

/** The wide answer, distinct from an unset select — `AgentTokenPanel`'s constant, same reason. */
const ALL_PROJECTS = 'ALL';

/** `AssistantInput.name`'s `maxLength`, mirrored rather than guessed. */
const MAX_NAME = 120;

export function ConnectAssistantPage() {
  const projects = useProjects();
  const connect = useConnectAssistant();

  const [name, setName] = useState('claude-code');
  const [scope, setScope] = useState(ALL_PROJECTS);
  const [nameError, setNameError] = useState<string | undefined>();
  const [formError, setFormError] = useState<string | undefined>();
  const [issued, setIssued] = useState<IssuedAssistant | null>(null);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setNameError(undefined);
    setFormError(undefined);

    try {
      setIssued(
        await connect.mutateAsync({
          name: name.trim(),
          projectId: scope === ALL_PROJECTS ? null : scope,
        }),
      );
    } catch (error) {
      // `name` is the one field the reader can fix here — assistant names are unique per owner —
      // so it lands on the input. Everything else is a sentence about the request, and
      // `describeError` is the one place that copy is written.
      if (error instanceof ApiError && error.fieldErrors?.name) {
        setNameError(error.fieldErrors.name.join(' '));
        return;
      }
      setFormError(describeError(error).body);
    }
  };

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Connect an AI assistant"
        documentTitle="Connect an assistant"
        crumbs={[
          { label: 'AI assistants', to: '/account/assistants' },
          { label: 'Connect' },
        ]}
        subtitle="Name it, scope it, take the snippet. Everything after that happens on your own machine."
        actions={
          <Button asChild data-testid={`${TEST_ID}.Cancel`}>
            <RouterLink to="/account/assistants">
              {issued ? 'Done — I have it' : 'Cancel'}
            </RouterLink>
          </Button>
        }
      />

      <div className="grid max-w-[1180px] gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex flex-col gap-3.5">
          {issued ? (
            <Panel title="Your setup snippet" testId={`${TEST_ID}.Snippet`}>
              <div className="mb-3" data-testid={`${TEST_ID}.Warning`}>
                <Banner tone="warn">
                  <span role="alert">
                    <strong>Shown once.</strong> The token carries your identity and your
                    permissions — treat it like a password. Leaving this page loses it, and nothing
                    can show it again; connect another assistant if that happens.
                  </span>
                </Banner>
              </div>

              <CopyBlock
                label="Setup command"
                value={assistantSetupCommand(window.location.origin, issued.secret)}
                testId={`${TEST_ID}.Command`}
              />

              <p className="m-0 mt-3 text-[12.5px] leading-[1.55] text-ink-3">
                Connected as <b className="font-medium text-ink">{issued.assistant.name}</b>,
                scoped to{' '}
                <b className="font-medium text-ink">
                  {issued.assistant.projectKey ?? 'every project you can reach'}
                </b>
                . Revoke it whenever you like from{' '}
                <RouterLink
                  to="/account/assistants"
                  className="text-steel no-underline hover:underline"
                >
                  your assistants
                </RouterLink>
                .
              </p>
            </Panel>
          ) : (
            <Panel title="The assistant" testId={`${TEST_ID}.Form.Panel`}>
              <form
                onSubmit={(event) => void submit(event)}
                noValidate
                aria-busy={connect.isPending}
                className="flex max-w-[560px] flex-col gap-3"
                data-testid={`${TEST_ID}.Form`}
              >
                {formError && (
                  <Banner tone="bad" data-testid={`${TEST_ID}.Error`}>
                    <span role="alert">{formError}</span>
                  </Banner>
                )}

                <Field
                  label="Name"
                  htmlFor="assistant-name"
                  error={nameError}
                  help="Appears in every history line this assistant writes — “Dana Petrov, via claude-code”."
                >
                  <Input
                    id="assistant-name"
                    required
                    maxLength={MAX_NAME}
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                    data-testid={`${TEST_ID}.Name`}
                  />
                </Field>

                <Field
                  label="Scope"
                  htmlFor="assistant-scope"
                  help={
                    scope === ALL_PROJECTS
                      ? 'Every project you can reach, including ones created later.'
                      : 'Scoped to one project, it receives nothing at all from any other — not an empty list, nothing.'
                  }
                >
                  <NativeSelect
                    id="assistant-scope"
                    value={scope}
                    onChange={(event) => setScope(event.target.value)}
                    data-testid={`${TEST_ID}.Scope`}
                  >
                    <option value={ALL_PROJECTS}>Every project I can see</option>
                    {(projects.data ?? []).map((project) => (
                      <option key={project.id} value={project.id}>
                        {project.name}
                      </option>
                    ))}
                  </NativeSelect>
                </Field>

                <Banner tone="neutral" data-testid={`${TEST_ID}.Reach`}>
                  <span>
                    <strong>Reach:</strong> exactly what you can do, minus closing work you are
                    assigned to on a project that requires a review to close. There is no separate
                    permission list to keep in step, and nothing here can grant it more than you
                    hold.
                  </span>
                </Banner>

                <div>
                  <Button
                    type="submit"
                    variant="primary"
                    disabled={connect.isPending || !name.trim()}
                    data-testid={`${TEST_ID}.Submit`}
                  >
                    {connect.isPending ? 'Connecting…' : 'Create and show the snippet'}
                  </Button>
                </div>
              </form>
            </Panel>
          )}
        </div>

        <Panel title="Then, in your own tool" testId={`${TEST_ID}.Next`}>
          <div className="flex flex-col gap-3 text-[12.5px]">
            <div>
              <p className="m-0 text-ink">&ldquo;What&rsquo;s on my queue?&rdquo;</p>
              <p className="m-0 mt-1 text-[11.5px] leading-[1.55] text-ink-3">
                Returns your AI-eligible tasks, each with its story, acceptance criteria, epic goal,
                target release, linked spec excerpts, and the repository and build command the
                project declares.
              </p>
            </div>
            <div>
              <p className="m-0 text-ink">&ldquo;Pick up ACME-124.&rdquo;</p>
              <p className="m-0 mt-1 text-[11.5px] leading-[1.55] text-ink-3">
                It works in your local checkout, opens a pull request with your git, reports the
                link back and moves the task to In Review. You review it and close it.
              </p>
            </div>
            <p className="m-0 border-t border-line pt-2.5 text-[11.5px] leading-[1.55] text-ink-4">
              Everything above happens on your machine. Nothing about it is hosted here.
            </p>
          </div>
        </Panel>
      </div>
    </div>
  );
}
