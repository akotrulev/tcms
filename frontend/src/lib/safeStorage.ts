/**
 * Guarded storage wrapper (state-management skill).
 *
 * localStorage THROWS on quota exceeded, in Safari private mode, and in blocked
 * third-party contexts — every one of those is a write that could otherwise take down a
 * render that had nothing to do with persistence. Everything routes through here, falls
 * back to in-memory, and logs once.
 */

const memory = new Map<string, string>();
let warned = false;

function warnOnce(err: unknown) {
  if (warned) return;
  warned = true;
  console.warn('[tcms] Browser storage unavailable — falling back to in-memory session.', err);
}

export const safeStorage = {
  get(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch (err) {
      warnOnce(err);
      return memory.get(key) ?? null;
    }
  },

  set(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch (err) {
      warnOnce(err);
      memory.set(key, value);
    }
  },

  remove(key: string): void {
    try {
      localStorage.removeItem(key);
    } catch (err) {
      warnOnce(err);
    }
    memory.delete(key);
  },

  getJSON<T>(key: string): T | null {
    const raw = this.get(key);
    if (raw === null) return null;
    try {
      return JSON.parse(raw) as T;
    } catch {
      // A parse failure is a corrupt entry — discard it rather than throwing on every
      // subsequent read.
      this.remove(key);
      return null;
    }
  },

  setJSON(key: string, value: unknown): void {
    this.set(key, JSON.stringify(value));
  },
};
