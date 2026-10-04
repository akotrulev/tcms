import type { UserEvent } from '@testing-library/user-event';
import { screen } from '@testing-library/react';

export async function openFilters(user: UserEvent, testId: string): Promise<HTMLElement> {
  await user.click(screen.getByTestId(`${testId}.Open`));
  return screen.findByTestId(`${testId}.Popover`);
}
