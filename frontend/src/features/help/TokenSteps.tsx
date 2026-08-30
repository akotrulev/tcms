import { Link as RouterLink } from 'react-router-dom';
import { Code, List, Note } from './prose';

/**
 * How to obtain an API token — the step the guide told users to have already taken.
 *
 * Both the CLI section and the MCP section began with "issue a token" and neither said how: one
 * gave the menu path in half a sentence, the other did not mention it at all. That is the first
 * thing a reader has to do and the only one the guide left them to work out, so it is written once
 * here and used by both — a second hand-copy of these steps would drift from the dialog the moment
 * a field changed, and this file is next to the section that quotes it.
 *
 * **The link is rendered for everybody, including readers who cannot follow it.** `/admin/tokens`
 * is gated on `token:issue` and its nav entry is hidden without it, which is right; a guide that
 * hid the step to match would leave a Test Engineer with no way to learn that the thing they need
 * exists or who to ask for it. So the step is shown and the permission is named — the help page's
 * own rule that this is the last screen that should hide a capability from the person missing it.
 */
export function TokenSteps({ purpose }: { purpose: 'agent' | 'ci' }) {
  return (
    <>
      <List
        ordered
        items={[
          <>
            Open{' '}
            <RouterLink to="/admin/tokens" className="text-steel no-underline hover:underline">
              Company → API tokens
            </RouterLink>{' '}
            and press <strong>Issue token</strong>.
          </>,
          <>
            <strong>Name</strong> it after where it will live —{' '}
            {purpose === 'agent' ? '"Cursor on my laptop"' : '"Nightly regression, staging"'}. This
            is the sentence you will read when deciding which credential to revoke.
          </>,
          purpose === 'agent' ? (
            <>
              <strong>Role: Agent.</strong> It is the role built for this and it is never granted to
              a person — it caps what the credential can do regardless of who issued it.
            </>
          ) : (
            <>
              <strong>Role: Automation Service.</strong> It is the role built for a pipeline.
            </>
          ),
          <>
            <strong>Where it works:</strong> pick the single project it should reach. A pinned token
            is inert on every other project, which is a hard ceiling rather than a preference.
          </>,
          <>
            <strong>Expires:</strong>{' '}
            {purpose === 'agent'
              ? 'required for an agent token and pre-filled with 90 days, because a credential that lives on somebody’s machine is the one nobody remembers to withdraw.'
              : 'optional. Left empty, it lasts until it is revoked.'}
          </>,
          <>
            Press <strong>Issue token</strong> and copy the secret.{' '}
            <strong>It is shown once</strong> and nothing can show it again — if you lose it, revoke
            that token and issue another.
          </>,
        ]}
      />
      <Note>
        Issuing costs <Code>token:issue</Code>, which Company Admins hold. If{' '}
        <strong>API tokens</strong> is not in your sidebar, that is the thing to ask one for — and a
        token never grants more than its owner has anyway, so the person issuing it cannot hand you
        more access than they hold themselves.
      </Note>
    </>
  );
}
