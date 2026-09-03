import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AiEligibleMarker } from './AiEligibleMarker'

/**
 * The one behaviour worth pinning is the absence. A marker that rendered a greyed "not eligible"
 * on every unflagged row would be noise on the ninety per cent of issues nobody flagged, and it is
 * the kind of thing a later edit adds back without noticing.
 */
describe('The AI-eligible marker', () => {
  it('renders nothing at all when the flag is off', () => {
    const { container } = render(<AiEligibleMarker eligible={false} data-testid="M" />)
    expect(screen.queryByTestId('M')).toBeNull()
    expect(container).toBeEmptyDOMElement()
  })

  it('says AI-ELIGIBLE where there is room and AI where there is not', () => {
    const { rerender } = render(<AiEligibleMarker eligible size="full" data-testid="M" />)
    expect(screen.getByTestId('M')).toHaveTextContent('AI-ELIGIBLE')

    rerender(<AiEligibleMarker eligible data-testid="M" />)
    expect(screen.getByTestId('M')).toHaveTextContent(/^AI$/)
  })
})
