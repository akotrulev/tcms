/**
 * Is this module the process's entry point?
 *
 * Both binaries guard their top-level `main()` on this so their tests can import the module
 * without running it. It lives here, once, because the obvious spelling is wrong in a way that
 * only shows up in the shape users are told to use.
 *
 * `import.meta.url === \`file://${process.argv[1]}\`` is that spelling, and it was in both
 * packages until 2026-08-23. It compares a URL Node has already resolved through symlinks
 * against a raw argv path — and `npm install -g` installs the bin as a SYMLINK into the prefix's
 * bin directory. So the comparison is false for every globally installed copy: `tcms help`
 * ran nothing, printed nothing, and exited 0. A silent success is the worst available failure
 * here, because there is no message to search for and the install itself looks fine.
 *
 * `realpathSync` on both sides is what makes the two comparable. `fileURLToPath` rather than
 * slicing the URL, because a path containing a space or a `#` is percent-encoded in the URL and
 * the string form silently stops matching.
 */
import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export function isProcessEntryPoint(importMetaUrl: string): boolean {
  const invoked = process.argv[1];
  if (!invoked) return false;
  try {
    return realpathSync(invoked) === realpathSync(fileURLToPath(importMetaUrl));
  } catch {
    // argv[1] can name something unstattable (a deleted file, a `-e` script). Not the entry
    // point is the right answer, and it must not be an exception at module load.
    return false;
  }
}
