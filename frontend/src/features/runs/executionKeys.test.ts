import { describe, expect, it } from 'vitest'
import {
  EXECUTION_SHORTCUTS,
  isTypingTarget,
  matchNotesShortcut,
  matchShortcut,
} from './executionKeys'

/**
 * The key map's own arms (§248).
 *
 * The first two describe the defect this feature ships broken everywhere: a single-key shortcut
 * that fires while the tester is typing a sentence into the notes box. Everything else here is a
 * property of the map rather than of one binding, which is the schema-wide-invariant rule applied
 * to a catalogue small enough that reading it looks sufficient — and is not.
 */

/** A keydown as the matchers read it, with the target the arm is about. */
function press(
  key: string,
  target: EventTarget | null,
  modifiers: Partial<Record<'ctrlKey' | 'metaKey' | 'altKey', boolean>> = {},
) {
  return {
    key,
    target,
    ctrlKey: modifiers.ctrlKey ?? false,
    metaKey: modifiers.metaKey ?? false,
    altKey: modifiers.altKey ?? false,
  }
}

describe('isTypingTarget', () => {
  it('answers true for every surface a person types into', () => {
    const input = document.createElement('input')
    const textarea = document.createElement('textarea')
    const select = document.createElement('select')
    const editable = document.createElement('div')
    editable.setAttribute('contenteditable', 'true')

    expect(isTypingTarget(input)).toBe(true)
    expect(isTypingTarget(textarea)).toBe(true)
    expect(isTypingTarget(select)).toBe(true)
    expect(isTypingTarget(editable)).toBe(true)
  })

  it('answers true for a node INSIDE a contenteditable, not only the host', () => {
    const editable = document.createElement('div')
    editable.setAttribute('contenteditable', 'true')
    const span = document.createElement('span')
    editable.append(span)
    expect(isTypingTarget(span)).toBe(true)
  })

  it('answers false for the body, a button and nothing at all', () => {
    expect(isTypingTarget(document.body)).toBe(false)
    expect(isTypingTarget(document.createElement('button'))).toBe(false)
    expect(isTypingTarget(null)).toBe(false)
  })

  it('answers false for a disabled or read-only input — nobody is typing into it', () => {
    const disabled = document.createElement('input')
    disabled.disabled = true
    const readOnly = document.createElement('textarea')
    readOnly.readOnly = true
    expect(isTypingTarget(disabled)).toBe(false)
    expect(isTypingTarget(readOnly)).toBe(false)
  })
})

describe('matchShortcut', () => {
  it('resolves the verdict keys', () => {
    expect(matchShortcut(press('p', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'PASSED' })
    expect(matchShortcut(press('f', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'FAILED' })
    expect(matchShortcut(press('b', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'BLOCKED' })
    expect(matchShortcut(press('s', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'SKIPPED' })
  })

  it('resolves the movement and the notes keys', () => {
    expect(matchShortcut(press('j', document.body))?.action).toEqual({ kind: 'move', direction: 1 })
    expect(matchShortcut(press('k', document.body))?.action).toEqual({ kind: 'move', direction: -1 })
    expect(matchShortcut(press('n', document.body))?.action).toEqual({ kind: 'focusNotes' })
    expect(matchShortcut(press('?', document.body))?.action).toEqual({ kind: 'toggleHelp' })
  })

  it('is case-insensitive — caps lock is not a different keyboard', () => {
    expect(matchShortcut(press('P', document.body))?.action).toEqual({ kind: 'verdict', verdict: 'PASSED' })
    expect(matchShortcut(press('J', document.body))?.action).toEqual({ kind: 'move', direction: 1 })
  })

  it('THE ARM THIS FEATURE SHIPS BROKEN: no shortcut fires while a text field has focus', () => {
    const notes = document.createElement('textarea')
    const duration = document.createElement('input')
    for (const shortcut of EXECUTION_SHORTCUTS.filter((s) => s.scope === 'screen')) {
      expect(matchShortcut(press(shortcut.key, notes))).toBeNull()
      expect(matchShortcut(press(shortcut.key, duration))).toBeNull()
    }
  })

  it('ignores a keypress carrying a modifier — Cmd-P is print, not Passed', () => {
    expect(matchShortcut(press('p', document.body, { metaKey: true }))).toBeNull()
    expect(matchShortcut(press('p', document.body, { ctrlKey: true }))).toBeNull()
    expect(matchShortcut(press('p', document.body, { altKey: true }))).toBeNull()
  })

  it('does not treat shift as a modifier — `?` needs it', () => {
    // `shiftKey` is deliberately absent from `ShortcutEvent`: a shifted key arrives as its shifted
    // `key` value, and a guard on it would remove the help binding on every layout where `?` is a
    // shifted `/`.
    expect(matchShortcut(press('?', document.body))?.action).toEqual({ kind: 'toggleHelp' })
  })

  it('answers null for a key nobody bound, and never throws on one', () => {
    expect(matchShortcut(press('z', document.body))).toBeNull()
    expect(matchShortcut(press('Enter', document.body))).toBeNull()
    // Escape is bound in the NOTES scope only, so the screen matcher must not claim it — otherwise
    // it would swallow the Escape that closes a dialog.
    expect(matchShortcut(press('Escape', document.body))).toBeNull()
  })
})

describe('matchNotesShortcut', () => {
  it('fires INSIDE a text field, which is the whole point of the scope', () => {
    const notes = document.createElement('textarea')
    expect(matchNotesShortcut(press('Escape', notes))?.action).toEqual({ kind: 'leaveNotes' })
  })

  it('does not answer a screen-scope key', () => {
    const notes = document.createElement('textarea')
    expect(matchNotesShortcut(press('p', notes))).toBeNull()
    expect(matchNotesShortcut(press('j', notes))).toBeNull()
  })
})

describe('the map itself', () => {
  it('binds each key at most once per scope — computed, not read', () => {
    for (const scope of ['screen', 'notes'] as const) {
      const keys = EXECUTION_SHORTCUTS.filter((s) => s.scope === scope).map((s) => s.key.toLowerCase())
      expect(new Set(keys).size).toBe(keys.length)
    }
  })

  it('gives every binding something the overlay can render', () => {
    for (const shortcut of EXECUTION_SHORTCUTS) {
      expect(shortcut.display.trim()).not.toBe('')
      expect(shortcut.description.trim()).not.toBe('')
    }
  })
})
