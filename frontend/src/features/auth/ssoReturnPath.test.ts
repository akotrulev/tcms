import { afterEach, describe, expect, it } from 'vitest'
import { consumeSsoReturnPath, rememberSsoReturnPath } from './ssoReturnPath'

/**
 * The deep link's ride across the issuer round trip (§222). Small on purpose — the interesting
 * halves live in `LoginPage.test.tsx` (the stash) and `SsoCallbackPage.test.tsx` (the landing);
 * what is pinned here is the storage contract those two lean on.
 */
describe('ssoReturnPath', () => {
  afterEach(() => {
    sessionStorage.clear()
  })

  it('hands back what was remembered, once', () => {
    rememberSsoReturnPath('/projects/p1/runs/r9')

    expect(consumeSsoReturnPath()).toBe('/projects/p1/runs/r9')
    // Consumed, not read: a later visit to the callback must not replay an old destination.
    expect(consumeSsoReturnPath()).toBe('/')
  })

  it('answers the root when nothing was remembered', () => {
    expect(consumeSsoReturnPath()).toBe('/')
  })

  it('refuses anything that is not a path', () => {
    // Storage is writable by anything same-origin; a stored absolute URL fed to a redirect is an
    // open redirect assembled out of a convenience — and `//host` and `/\host` are absolute URLs
    // wearing a leading slash (protocol-relative, and the backslash spelling browsers normalize).
    for (const notAPath of ['https://evil.test/phish', '//evil.test/phish', '/\\evil.test/phish']) {
      sessionStorage.setItem('tcms.sso.return-path', notAPath)
      expect(consumeSsoReturnPath(), notAPath).toBe('/')
    }
  })
})
