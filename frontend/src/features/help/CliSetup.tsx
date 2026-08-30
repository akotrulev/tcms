import { CopyBlock } from '../../components/CopyBlock';
import { cliSetupCommands, TOKEN_PLACEHOLDER } from '../../lib/clientSetup';
import { Code, P } from './prose';

/**
 * Installing and configuring `tcms`, against the instance the reader is looking at.
 *
 * The same change as `McpSetup` and for the same reason: the block carried `<your-tcms-url>` twice
 * on a page that is only ever read from the instance it describes, so the reader was asked to
 * supply a value the browser already has. See that file's header for why `window.location.origin`
 * is the right source and what the `npm run dev` caveat is.
 */
export function CliSetup() {
  const origin = window.location.origin;

  return (
    <>
      <CopyBlock
        label="Install and configure"
        value={cliSetupCommands(origin)}
        testId="Help.Cli.Setup"
      />
      <P>
        Replace <Code>{TOKEN_PLACEHOLDER}</Code> with the secret you copied. There is nothing else
        to configure — no file, no keychain — so a pipeline needs only these two variables from its
        secret store.
      </P>
    </>
  );
}
