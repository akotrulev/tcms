import { useContext } from 'react';

import { ColorSchemeContext, type ColorSchemeValue } from './colorSchemeContextValue';

/**
 * Non-throwing outside the provider, deliberately: the top bar is the only caller, and a test
 * that renders it without the provider should see the default scheme rather than an exception
 * about a concern it is not testing.
 */
export function useColorScheme(): ColorSchemeValue {
  const value = useContext(ColorSchemeContext);
  return (
    value ?? {
      mode: 'system',
      systemMode: 'light',
      resolved: 'light',
      setMode: () => {},
    }
  );
}
