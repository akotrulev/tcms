import { CopyBlock } from '../../components/CopyBlock';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui';
import {
  mcpInstallCommand,
  mcpServerConfig,
  TOKEN_PLACEHOLDER,
} from '../../lib/clientSetup';
import { Code, Note, P } from './prose';

/**
 * The two ways to connect an agent, as configuration a reader can take rather than assemble.
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
 * backend — a developer reading their own dev server gets :5173 here. That is the correct
 * trade: the deployed product is the audience, and pinning the origin to a build-time variable
 * would put a value in the bundle that is wrong for every instance but one.
 *
 * ## Hosted first, and both are complete blocks
 *
 * `docs/specs/client-distribution.md` calls the hosted shape the one to prefer, and the guide
 * nonetheless gave it as a bare URL while the local shape got a config block — so the recommended
 * path was the one with more work in it. Both are now the same artefact: an `mcpServers` entry,
 * rendered by `lib/clientSetup.ts`, which the issue-token dialog renders too so the two cannot drift.
 *
 * The secret is the only thing left to fill in, and the fastest way to avoid even that is named
 * below: the dialog that issues the token hands over the finished configuration with the token
 * already in it, at the one moment the plaintext exists.
 */
export function McpSetup() {
  const origin = window.location.origin;

  return (
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
          Paste this into your MCP host&apos;s configuration and replace{' '}
          <Code>{TOKEN_PLACEHOLDER}</Code> with the secret you copied. Nothing is installed: the
          server runs on this instance and is upgraded with it.
        </P>
        <CopyBlock
          label="MCP configuration"
          value={mcpServerConfig({ transport: 'hosted', origin, token: TOKEN_PLACEHOLDER })}
          testId="Help.Mcp.Hosted"
        />
        <P>
          The server holds no credential of its own — the whole tool list and the instructions are
          built <em>per session</em> from your token, so what a session can see is exactly what that
          token can reach.
        </P>
        <Note severity="warning">
          The hosted endpoint is off unless your operator turned it on for this instance. If the URL
          does not answer, that is what to ask them about — the local shape in the other tab needs
          nothing from them.
        </Note>
      </TabsContent>

      <TabsContent value="stdio">
        <P>
          For an agent running on your own machine. Install the server from this instance, which
          serves the tarball itself:
        </P>
        <CopyBlock
          label="Install"
          value={mcpInstallCommand(origin)}
          testId="Help.Mcp.Install"
        />
        <P>
          Then point your MCP host at it, replacing <Code>{TOKEN_PLACEHOLDER}</Code> with the secret
          you copied:
        </P>
        <CopyBlock
          label="MCP configuration"
          value={mcpServerConfig({ transport: 'stdio', origin, token: TOKEN_PLACEHOLDER })}
          testId="Help.Mcp.Stdio"
        />
        <P>
          Add <Code>&quot;TCMS_PROJECT&quot;: &quot;OLB&quot;</Code> to <Code>env</Code> — a project
          key — to bind every session to one project when the token can see several. A token pinned
          to a single project needs no such line: the server finds the one project it can reach.
        </P>
      </TabsContent>
    </Tabs>
  );
}
