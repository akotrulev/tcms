import { describe, expect, it } from 'vitest'
import {
  cliSetupCommands,
  mcpInstallCommand,
  mcpServerConfig,
  MCP_PATH,
  TOKEN_PLACEHOLDER,
} from './clientSetup'

/**
 * The configuration a user pastes into their agent — the one artefact in this product that is
 * *consumed by another program*, and therefore the one where a cosmetic slip is a total failure.
 *
 * Three things are locked here, none of them visible to `tsc`:
 *
 *   1. **The output parses.** A config block is pasted into a file an MCP host reads; a stray
 *      comma is an agent that will not start, reported to the user as a column number.
 *   2. **The transport's contract is the server's.** `packages/mcp/src/http.ts` reads
 *      `Authorization: Bearer …` and serves the single path `/mcp`; `session.ts` reads
 *      `TCMS_URL`, `TCMS_TOKEN` and `TCMS_PROJECT`. A renamed key here is a config that looks
 *      right, copies cleanly, and connects as nobody.
 *   3. **The token is whatever the caller passed.** The help page has no credential and passes a
 *      placeholder; the issue dialog passes the real secret. A default would let a surface print a
 *      config that silently omits the credential — or, worse, one that carries a stale one.
 */

const ORIGIN = 'https://tcms.example.com'
const SECRET = 'tcms_abc123_the_only_time_this_is_ever_shown'

/** Parsed, not string-matched: the assertions below are about the config, not about whitespace. */
function parse(json: string): {
  mcpServers: {
    tcms: {
      type?: string
      url?: string
      headers?: Record<string, string>
      command?: string
      env?: Record<string, string>
    }
  }
} {
  return JSON.parse(json)
}

describe('mcpServerConfig — hosted', () => {
  it('points at this instance and sends the token as the bearer', () => {
    const server = parse(
      mcpServerConfig({ transport: 'hosted', origin: ORIGIN, token: SECRET }),
    ).mcpServers.tcms

    expect(server.type).toBe('http')
    expect(server.url).toBe(`${ORIGIN}${MCP_PATH}`)
    expect(server.headers).toEqual({ Authorization: `Bearer ${SECRET}` })
  })

  it('carries no project, because over HTTP the environment is the operator’s', () => {
    // `TCMS_PROJECT` binds the *server process*, which in the hosted shape is not the user's. A
    // project pinned into a host's config here would be a value nothing reads.
    const server = parse(
      mcpServerConfig({ transport: 'hosted', origin: ORIGIN, token: SECRET, project: 'OLB' }),
    ).mcpServers.tcms

    expect(JSON.stringify(server)).not.toContain('OLB')
  })
})

describe('mcpServerConfig — stdio', () => {
  it('names the installed binary and the three environment variables the server reads', () => {
    const server = parse(
      mcpServerConfig({ transport: 'stdio', origin: ORIGIN, token: SECRET, project: 'OLB' }),
    ).mcpServers.tcms

    expect(server.command).toBe('tcms-mcp')
    expect(server.env).toEqual({
      TCMS_URL: ORIGIN,
      TCMS_TOKEN: SECRET,
      TCMS_PROJECT: 'OLB',
    })
  })

  it('omits the project binding entirely when there is none', () => {
    // Not an empty string: `TCMS_PROJECT=""` is a value the server would try to resolve, and it
    // resolves to nothing — a session bound to a project that does not exist rather than an
    // unbound one.
    const server = parse(
      mcpServerConfig({ transport: 'stdio', origin: ORIGIN, token: SECRET }),
    ).mcpServers.tcms

    expect(server.env).toEqual({ TCMS_URL: ORIGIN, TCMS_TOKEN: SECRET })
    expect(Object.keys(server.env ?? {})).not.toContain('TCMS_PROJECT')
  })
})

describe('the instance address', () => {
  it('never doubles a slash, whatever the origin ends with', () => {
    // `window.location.origin` has no trailing slash, but this function is also handed values from
    // documentation and fixtures, and `https://host//mcp` is a 404 on a Caddy site that routes the
    // exact path.
    const hosted = parse(
      mcpServerConfig({ transport: 'hosted', origin: 'https://tcms.example.com/', token: SECRET }),
    ).mcpServers.tcms

    expect(hosted.url).toBe('https://tcms.example.com/mcp')
    expect(mcpInstallCommand('https://tcms.example.com/')).toBe(
      'npm install -g https://tcms.example.com/downloads/tcms-mcp-latest.tgz',
    )
  })

  it('installs from the instance being configured, never from a registry', () => {
    // Both clients are adapters over one instance's contract — `docs/specs/client-distribution.md`.
    expect(mcpInstallCommand(ORIGIN)).toContain(`${ORIGIN}/downloads/`)
    expect(cliSetupCommands(ORIGIN)).toContain(`${ORIGIN}/downloads/tcms-cli-latest.tgz`)
  })
})

describe('cliSetupCommands', () => {
  it('exports the two variables the CLI needs and nothing else', () => {
    const lines = cliSetupCommands(ORIGIN, SECRET).split('\n')

    expect(lines).toContain(`export TCMS_URL=${ORIGIN}`)
    expect(lines).toContain(`export TCMS_TOKEN=${SECRET}`)
    // The last line is the check, not another export: a setup block that ends without proving the
    // credential works sends the user to the first real command to find out.
    expect(lines.at(-1)).toBe('tcms whoami')
  })

  it('leaves the secret as a placeholder when the caller has none', () => {
    expect(cliSetupCommands(ORIGIN)).toContain(`export TCMS_TOKEN=${TOKEN_PLACEHOLDER}`)
  })
})
