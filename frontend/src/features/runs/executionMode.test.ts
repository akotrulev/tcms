import { describe, expect, it } from 'vitest'
import {
  DEFAULT_EXECUTION_MODE,
  executionModeParam,
  parseExecutionMode,
} from './executionMode'

/**
 * The mode is read from a query parameter, which means it is read from user-supplied text — a
 * pasted link from a colleague on an older deployment, a typo, a mode that got renamed. Every one
 * of those must land on a working screen, because an unrecognised value reaching the layout
 * renders neither the queue nor the detail: a blank page produced by a query string.
 */
describe('parseExecutionMode', () => {
  it('accepts the three modes', () => {
    expect(parseExecutionMode('focus')).toBe('focus')
    expect(parseExecutionMode('split')).toBe('split')
    expect(parseExecutionMode('grid')).toBe('grid')
  })

  it('falls back for anything else, including an absent parameter', () => {
    expect(parseExecutionMode(null)).toBe(DEFAULT_EXECUTION_MODE)
    expect(parseExecutionMode('')).toBe(DEFAULT_EXECUTION_MODE)
    expect(parseExecutionMode('FOCUS')).toBe(DEFAULT_EXECUTION_MODE)
    expect(parseExecutionMode('kanban')).toBe(DEFAULT_EXECUTION_MODE)
  })

  /*
    `['focus','split','grid'].includes(value)` on a nullish value is the shape that goes wrong
    quietly: `includes(undefined)` is false, so it lands on the default — but a prototype key
    like `constructor` reaching an object-keyed lookup would not. Pinned as a property of the
    parser rather than trusted to the array literal staying an array.
  */
  it('is not fooled by an inherited property name', () => {
    expect(parseExecutionMode('constructor')).toBe(DEFAULT_EXECUTION_MODE)
    expect(parseExecutionMode('toString')).toBe(DEFAULT_EXECUTION_MODE)
  })
})

describe('executionModeParam', () => {
  /**
   * The default drops the parameter, so two links to the same screen are the same string. A
   * reader comparing a pasted `?mode=split` against a bare URL should not have to know they are
   * the same view.
   */
  it('omits the parameter for the default mode and names the others', () => {
    expect(executionModeParam(DEFAULT_EXECUTION_MODE)).toBeNull()
    expect(executionModeParam('focus')).toBe('focus')
    expect(executionModeParam('grid')).toBe('grid')
  })

  it('round-trips every mode through the parser', () => {
    for (const mode of ['focus', 'split', 'grid'] as const) {
      expect(parseExecutionMode(executionModeParam(mode))).toBe(mode)
    }
  })
})
