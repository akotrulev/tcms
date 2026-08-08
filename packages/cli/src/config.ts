/**
 * Where the CLI gets its instance and its credential.
 *
 * Two sources, in one order, and no third: an explicit flag, then the environment. There is
 * deliberately **no config file and no keychain** yet — both are real features, and both are
 * decisions about where a credential rests on disk that this phase is not the place to make. An
 * environment variable is the one form that is already how CI holds a token and how an MCP host
 * injects one, so it is the form that needs no invention.
 */
import { TcmsClientError, createTcmsClient, type TcmsClient } from '@tcms/client';

export const ENV_URL = 'TCMS_URL';
export const ENV_TOKEN = 'TCMS_TOKEN';

export interface ConnectionFlags {
  url?: string | undefined;
  timeout?: number | undefined;
}

/** The version this build reports, sent as `User-Agent`. */
export const CLI_VERSION = '0.1.0';

/**
 * The `User-Agent` every request carries.
 *
 * It is not decoration: `audit_event` has no `via` column (surface spec §0.6), so this string
 * plus `actor_token_id` is the whole of how a CLI edit is told from an MCP one after the fact.
 * It must therefore be stable and it must name the client — not the machine, not the user, both
 * of which would put an identity in an audit field that already has one.
 */
export const USER_AGENT = `tcms-cli/${CLI_VERSION}`;

export function connect(flags: ConnectionFlags, env: NodeJS.ProcessEnv = process.env): TcmsClient {
  const url = flags.url ?? env[ENV_URL];
  if (!url) {
    throw new TcmsClientError(
      `No TCMS instance. Set ${ENV_URL}=https://tcms.example.com, or pass --url.`,
    );
  }

  const token = env[ENV_TOKEN];
  if (!token) {
    throw new TcmsClientError(
      `No API token. Set ${ENV_TOKEN} to a token issued from Settings → API tokens.`,
    );
  }

  return createTcmsClient({
    baseUrl: url,
    // Read per request rather than captured, so a long-running invocation picks up a rotated
    // file. Cheap: it is an environment lookup.
    token: () => env[ENV_TOKEN] ?? '',
    userAgent: USER_AGENT,
  });
}

/**
 * The per-request options a command passes through, built once from the global flags.
 *
 * `--timeout` is in seconds on the command line and milliseconds everywhere below it: nobody
 * types a timeout in milliseconds, and nobody wants a unit conversion in six commands.
 */
export function requestOptions(flags: ConnectionFlags, signal?: AbortSignal) {
  return {
    ...(flags.timeout === undefined ? {} : { timeoutMs: Math.round(flags.timeout * 1000) }),
    ...(signal ? { signal } : {}),
  };
}
