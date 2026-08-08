import { defineConfig } from 'vitest/config';

/**
 * Node environment, not jsdom. Everything in this package runs in a CLI process or an MCP
 * server; a test that passed only because jsdom supplied a browser global would be testing a
 * runtime this code never sees.
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
