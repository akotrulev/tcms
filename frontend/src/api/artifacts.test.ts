import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchArtifact, fetchArtifactJson } from './client'

/**
 * The same-origin guard on the artifact readers.
 *
 * **§89 shipped this guard with no test and recorded the gap as a row rather than implying
 * coverage.** This file closes it, and it is the one part of the download path worth a unit test:
 * the URL is *data from the server* and these two functions attach the signed-in user's bearer
 * token to it, so an absolute URL — a misconfigured `tcms.public-url`, or a deployment setting
 * somebody typed wrong — hands that token to another host. Nothing else in the app would notice;
 * the download would simply work or simply fail.
 *
 * The two readers are asserted **as a pair** for the reason §84 gives about pairs: F2 split the
 * shared half into `artifactResponse`, and a guard that stayed on only one of them would leave the
 * newer reader — the on-screen view's — unprotected, which is precisely the copy that drifts.
 */

const originalFetch = globalThis.fetch

const respond = (body: string, headers: Record<string, string> = {}) =>
  new Response(body, { status: 200, headers })

beforeEach(() => {
  globalThis.fetch = vi.fn(async () =>
    respond('{"header":{},"report":{}}', {
      'Content-Type': 'application/json',
      'Content-Disposition': 'attachment; filename="run_summary-2026-08-04.json"',
    }),
  ) as unknown as typeof fetch
})

afterEach(() => {
  globalThis.fetch = originalFetch
  vi.restoreAllMocks()
})

describe.each([
  ['fetchArtifact', (url: string) => fetchArtifact(url)],
  ['fetchArtifactJson', (url: string) => fetchArtifactJson(url)],
])('%s', (_name, read) => {
  it.each([
    ['an absolute URL on another host', 'https://evil.example.com/api/v1/projects/p/reports/x'],
    ['an absolute URL on this host', 'https://tcms.example.com/api/v1/projects/p/reports/x'],
    ['a protocol-relative URL', '//evil.example.com/api/v1/projects/p/reports/x'],
    ['a path outside the API base', '/uploads/report.json'],
    ['the base itself with no path', '/api/v1'],
  ])('refuses %s without making a request', async (_case, url) => {
    await expect(read(url)).rejects.toThrow(/Refusing to fetch a report artifact/)
    // The assertion that matters: it threw *before* the token went anywhere.
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('sends a root-relative URL under the API base, verbatim', async () => {
    await read('/api/v1/projects/p1/reports/artifacts/a1/content')

    // Verbatim, because the SERVER composed this URL — a client that rebuilt or re-prefixed it
    // would hold a second copy of the routing (§67), and `/api/v1/api/v1/…` is the 404 §89 found.
    expect(globalThis.fetch).toHaveBeenCalledWith(
      '/api/v1/projects/p1/reports/artifacts/a1/content',
      expect.anything(),
    )
  })
})

describe('the artifact response', () => {
  it('reads the filename the server named the file', async () => {
    const { filename } = await fetchArtifact('/api/v1/projects/p1/reports/artifacts/a1/content')

    expect(filename).toBe('run_summary-2026-08-04.json')
  })

  it('parses JSON on the model reader without going through a blob', async () => {
    await expect(
      fetchArtifactJson('/api/v1/projects/p1/reports/artifacts/a1/content'),
    ).resolves.toEqual({ header: {}, report: {} })
  })

  /*
    A 403 on this route is bodiless by design — the response is typed to a binary resource, so
    there is no problem document to read. It must still arrive as an `ApiError` carrying the status,
    because that status is what the on-screen view branches on to explain that reading a report
    costs the export permission.
  */
  it('raises a bodiless refusal as an ApiError carrying its status', async () => {
    globalThis.fetch = vi.fn(async () => new Response(null, { status: 403 })) as unknown as typeof fetch

    await expect(
      fetchArtifactJson('/api/v1/projects/p1/reports/artifacts/a1/content'),
    ).rejects.toMatchObject({ status: 403 })
    await expect(
      fetchArtifactJson('/api/v1/projects/p1/reports/artifacts/a1/content'),
    ).rejects.toBeInstanceOf(ApiError)
  })
})
