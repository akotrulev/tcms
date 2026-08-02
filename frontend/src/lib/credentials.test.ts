import { afterEach, describe, expect, it, vi } from 'vitest'
import { canStoreCredentials, offerToSaveCredentials } from './credentials'

/**
 * The password-manager offer, tested at the seam rather than through a browser.
 *
 * jsdom implements neither `PasswordCredential` nor `navigator.credentials`, which is the same
 * position Safari is in — so the "unsupported" branch here is not a contrivance, it is the branch
 * a real and common browser takes. What has to hold is that the unsupported case and the
 * user-declined case both leave the caller untouched: this runs *after* a successful sign-in, and
 * a rejection escaping it would turn a completed sign-in into an error toast.
 */

const original = Object.getOwnPropertyDescriptor(navigator, 'credentials')

function installCredentialApi(store: (credential: unknown) => Promise<unknown>) {
  // A plain field assignment rather than a parameter property: `erasableSyntaxOnly` is on, and
  // `constructor(public data)` is the one piece of TypeScript that emits runtime code.
  class FakePasswordCredential {
    data: unknown
    constructor(data: unknown) {
      this.data = data
    }
  }
  vi.stubGlobal('PasswordCredential', FakePasswordCredential)
  Object.defineProperty(navigator, 'credentials', {
    value: { store },
    configurable: true,
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  if (original) Object.defineProperty(navigator, 'credentials', original)
  else Reflect.deleteProperty(navigator as object, 'credentials')
})

describe('offerToSaveCredentials', () => {
  it('reports the API as unavailable where the browser has none', () => {
    expect(canStoreCredentials()).toBe(false)
  })

  it('resolves without storing anything where the API is unavailable', async () => {
    // Safari's branch, and the assertion is simply that it is a no-op the caller can await.
    await expect(offerToSaveCredentials({ id: 'a@b.test', password: 'pw' })).resolves.toBeUndefined()
  })

  it('hands the browser the credential that was just used', async () => {
    const store = vi.fn().mockResolvedValue(undefined)
    installCredentialApi(store)

    await offerToSaveCredentials({ id: 'ana@example.com', password: 'hunter2', name: 'Ana' })

    expect(store).toHaveBeenCalledTimes(1)
    // The manager files the entry under the username, and this product's username is the email
    // address — the same string `autoComplete="username"` marks on both forms.
    expect((store.mock.calls[0][0] as { data: unknown }).data).toEqual({
      id: 'ana@example.com',
      password: 'hunter2',
      name: 'Ana',
    })
  })

  it('swallows a refusal, because the user is already signed in by then', async () => {
    // A dismissed prompt, an insecure origin and a permissions-policy block all arrive as a
    // rejection. None of them is something the caller could act on, and letting one propagate
    // would report a successful sign-in as a failure.
    installCredentialApi(vi.fn().mockRejectedValue(new Error('NotAllowedError')))

    await expect(offerToSaveCredentials({ id: 'a@b.test', password: 'pw' })).resolves.toBeUndefined()
  })
})
