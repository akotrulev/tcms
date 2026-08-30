import { useState } from 'react';
import { useProjects } from '../../api/queries';
import type { IssuedApiToken } from '../../api/types';
import { CopyBlock } from '../../components/CopyBlock';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui';
import { mcpInstallCommand, mcpServerConfig, TOKEN_PLACEHOLDER } from '../../lib/clientSetup';
import { AgentTokenPanel } from './AgentTokenPanel';
import { Code, H, Note, P } from './prose';

/**
 * Connecting an agent, end to end on one screen — issue the credential, then take the finished
 * configuration.
 *
 * ## The instance's own address, not `<your-tcms-url>`
 *
 * The guide used to print a placeholder in every block, on a page that is only ever read *from the
 * instance it describes*. The reader had to know their own base URL, know that the hosted endpoint
 * hangs off it at `/mcp`, and edit three places without typos — for a value the browser already
 * has. `window.location.origin` is that value: the SPA, `/api`, `/mcp` and `/downloads` are one
 * origin behind the same Caddy site (`deploy/shanks/Caddyfile`), so what a user is reading this on
 * is exactly what their agent should point at.
 *
 * The one place that is not true is `npm run dev`, where the SPA is on :5173 and proxies to the
 * backend — a developer reading their own dev server gets :5173 here. That is the correct trade:
 * the deployed product is the audience, and pinning the origin to a build-time variable would put
 * a value in the bundle that is wrong for every instance but one.
 *
 * ## The token is the other placeholder, and it is filled in the same way
 *
 * `AgentTokenPanel` issues one in place, so for a reader who can, the blocks below carry a working
 * credential and the whole setup is *copy this*. Its secret is held here, in this component's
 * state — never in the query cache, never in the URL — so leaving the page loses it, which is what
 * the panel's warning says in those words. A reader who cannot issue tokens gets the steps
 * instead, and the blocks keep {@link TOKEN_PLACEHOLDER}: a config with one word to replace,
 * rather than a control they would be refused.
 *
 * The project binding is derived from the token rather than asked twice — pinning the token to a
 * project *is* the answer to which project the agent works in.
 *
 * ## Hosted first, and both are complete blocks
 *
 * `docs/specs/client-distribution.md` calls the hosted shape the one to prefer, and the guide
 * nonetheless gave it as a bare URL while the local shape got a config block — so the recommended
 * path was the one with more work in it. Both are now the same artefact: an `mcpServers` entry,
 * rendered by `lib/clientSetup.ts`, which the issue-token dialog renders too so the two cannot
 * drift.
 */
export function McpSetup() {
  const origin = window.location.origin;
  const [issued, setIssued] = useState<IssuedApiToken | null>(null);
  const projects = useProjects();

  const token = issued?.secret ?? TOKEN_PLACEHOLDER;
  /*
    The KEY, not the id: `TCMS_PROJECT` is matched on the key a person types
    (`packages/client/src/core/project.ts`), and the issue response carries only the id. A token
    with no binding — or one whose project this list has not answered for — gets no line at all,
    which is right either way: the variable exists for a credential that can see several projects,
    and a pinned one resolves its single project server-side.
  */
  const projectKey = (projects.data ?? []).find(
    (project) => project.id === issued?.token.projectId,
  )?.key;

  return (
    <>
      <H>First, issue a token</H>
      <P>
        An agent connects as a credential of its own — never as your sign-in session — so the setup
        starts with issuing one.
      </P>
      <AgentTokenPanel issued={issued} onIssued={setIssued} />

      <H>Then point your agent at this instance</H>
      <P>
        Two shapes, both filled in with this instance&apos;s own address
        {issued ? ' and the token you just generated' : ''}.{' '}
        <strong>Hosted is the one to prefer</strong> — it installs nothing and is upgraded by your
        operator along with the instance, so it cannot drift from the API it adapts.
      </P>

      <Tabs defaultValue="hosted" data-testid="Help.Mcp.Setup">
        <TabsList className="mb-3">
          <TabsTrigger value="hosted" data-testid="Help.Mcp.Tab.Hosted">
            Hosted — nothing to install
          </TabsTrigger>
          <TabsTrigger value="stdio" data-testid="Help.Mcp.Tab.Stdio">
            Local, over stdio
          </TabsTrigger>
        </TabsList>

        <TabsContent value="hosted">
          <P>
            {issued ? (
              <>Paste this into your MCP host&apos;s configuration — it is complete. </>
            ) : (
              <>
                Paste this into your MCP host&apos;s configuration and replace{' '}
                <Code>{TOKEN_PLACEHOLDER}</Code> with the secret you copied.{' '}
              </>
            )}
            Nothing is installed: the server runs on this instance and is upgraded with it.
          </P>
          <CopyBlock
            label="MCP configuration"
            value={mcpServerConfig({ transport: 'hosted', origin, token })}
            testId="Help.Mcp.Hosted"
          />
          <P>
            The server holds no credential of its own — the whole tool list and the instructions are
            built <em>per session</em> from your token, so what a session can see is exactly what
            that token can reach.
          </P>
          <Note severity="warning">
            The hosted endpoint is off unless your operator turned it on for this instance. If the
            URL does not answer, that is what to ask them about — the local shape in the other tab
            needs nothing from them.
          </Note>
        </TabsContent>

        <TabsContent value="stdio">
          <P>
            For an agent running on your own machine. Install the server from this instance, which
            serves the tarball itself:
          </P>
          <CopyBlock label="Install" value={mcpInstallCommand(origin)} testId="Help.Mcp.Install" />
          <P>
            Then point your MCP host at it
            {issued ? (
              ':'
            ) : (
              <>
                , replacing <Code>{TOKEN_PLACEHOLDER}</Code> with the secret you copied:
              </>
            )}
          </P>
          <CopyBlock
            label="MCP configuration"
            value={mcpServerConfig({ transport: 'stdio', origin, token, project: projectKey })}
            testId="Help.Mcp.Stdio"
          />
          {projectKey ? (
            <P>
              <Code>TCMS_PROJECT</Code> is set to <Code>{projectKey}</Code> because that is the
              project this token is pinned to — a session binds to it and needs nothing else.
            </P>
          ) : (
            <P>
              Add <Code>&quot;TCMS_PROJECT&quot;: &quot;OLB&quot;</Code> to <Code>env</Code> — a
              project key — to bind every session to one project when the token can see several. A
              token pinned to a single project needs no such line: the server finds the one project
              it can reach.
            </P>
          )}
        </TabsContent>
      </Tabs>
    </>
  );
}
