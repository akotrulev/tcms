import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  publishSession,
  resetSessionChannelForTests,
  subscribeSession,
  type SessionBroadcast,
} from './sessionChannel'

/**
 * The cross-tab transport, tested against a **real** `BroadcastChannel` rather than a mock.
 *
 * That choice is the point of the file. The two properties that make this module correct are
 * both properties of the browser API — a channel object does not receive its own posts, but it
 * does receive posts from a sibling object in the same document — and a hand-written fake would
 * be free to get either of them backwards and stay green. jsdom implements the real thing, so
 * "the other tab" here is a second channel object with the same name, which is exactly what a
 * second tab is.
 */

const CHANNEL_NAME = 'tcms.session'

/** Stands in for another tab: a second channel object on the same name. */
function otherTab() {
  const channel = new BroadcastChannel(CHANNEL_NAME)
  return {
    post: (message: SessionBroadcast | unknown) => channel.postMessage(message),
    close: () => channel.close(),
  }
}

/**
 * Delivery is queued as a task, so nothing is observable in the same tick as the post.
 *
 * Two turns of the macrotask queue rather than a fixed sleep: a timeout that is long enough on
 * an idle machine is a flake on a loaded one, and this file's negative assertions ("was NOT
 * delivered") cannot use the observe-it-directly trick the behaviour tests use.
 */
const delivered = async () => {
  for (let turn = 0; turn < 3; turn++) await new Promise((resolve) => setTimeout(resolve, 0))
}

afterEach(() => {
  resetSessionChannelForTests()
})

describe('sessionChannel', () => {
  it('delivers another tab’s message to every subscriber', async () => {
    const first = vi.fn()
    const second = vi.fn()
    subscribeSession(first)
    subscribeSession(second)

    const tab = otherTab()
    tab.post({ type: 'signed-out' })
    await delivered()
    tab.close()

    expect(first).toHaveBeenCalledWith({ type: 'signed-out' })
    expect(second).toHaveBeenCalledWith({ type: 'signed-out' })
  })

  /**
   * The bug this exists to prevent: two providers each opening their own channel would hear
   * their *own* tab's sign-out and act on it, so signing out would clear the session twice and a
   * sign-in would re-adopt the session it had just written. One channel object per tab is what
   * makes that impossible, and it is invisible in any test that mocks the API away.
   */
  it('never delivers a message back to the tab that published it', async () => {
    const listener = vi.fn()
    subscribeSession(listener)

    publishSession({ type: 'signed-in', email: 'a@acme.test', token: 'tok-broadcast' })
    await delivered()

    expect(listener).not.toHaveBeenCalled()
  })

  it('reaches another tab’s subscriber when published', async () => {
    const received: unknown[] = []
    const channel = new BroadcastChannel(CHANNEL_NAME)
    channel.onmessage = (event) => received.push(event.data)

    publishSession({ type: 'signed-in', email: 'a@acme.test', token: 'tok-broadcast' })
    await delivered()
    channel.close()

    expect(received).toEqual([{ type: 'signed-in', email: 'a@acme.test', token: 'tok-broadcast' }])
  })

  it('stops delivering after unsubscribe', async () => {
    const listener = vi.fn()
    const unsubscribe = subscribeSession(listener)
    unsubscribe()

    const tab = otherTab()
    tab.post({ type: 'signed-out' })
    await delivered()
    tab.close()

    expect(listener).not.toHaveBeenCalled()
  })

  /**
   * Another application on this origin, or a future build with a shape this one does not know,
   * must not be able to sign a user out — or throw inside the handler and take the tab down.
   */
  it('ignores a message it does not recognize', async () => {
    const listener = vi.fn()
    subscribeSession(listener)

    const tab = otherTab()
    tab.post({ type: 'something-else' })
    tab.post(null)
    tab.post('signed-out')
    await delivered()
    tab.close()

    expect(listener).not.toHaveBeenCalled()
  })

  /**
   * A message is delivered to everyone who was subscribed when it was posted, even if an earlier
   * listener tears the later one down while it runs — which is the ordinary case here, not an
   * edge one: `AuthProvider` receives a sign-out, clears the session, and `RequireAuth` unmounts
   * the subtree containing `SessionReplayBridge`'s subscription before the bridge has been told.
   * Iterating the live set would silently skip it, so the cache of the session that just ended
   * would never be cleared — a bug with no symptom until the *next* user signs in.
   */
  it('delivers to a subscriber that an earlier listener removed mid-delivery', async () => {
    const second = vi.fn()
    let unsubscribeSecond = () => {}
    subscribeSession(() => unsubscribeSecond())
    unsubscribeSecond = subscribeSession(second)

    const tab = otherTab()
    tab.post({ type: 'signed-out' })
    await delivered()
    tab.close()

    expect(second).toHaveBeenCalledOnce()
  })

  /**
   * Where the API is absent the app must be exactly as correct as it was before this module
   * existed — single-tab — and must not throw on the way there.
   */
  it('degrades to a silent no-op where BroadcastChannel is unavailable', () => {
    const original = globalThis.BroadcastChannel
    // @ts-expect-error — deleting a DOM global for the duration of one assertion.
    delete globalThis.BroadcastChannel
    resetSessionChannelForTests()

    try {
      const listener = vi.fn()
      expect(() => subscribeSession(listener)).not.toThrow()
      expect(() => publishSession({ type: 'signed-out' })).not.toThrow()
      expect(listener).not.toHaveBeenCalled()
    } finally {
      globalThis.BroadcastChannel = original
    }
  })
})
