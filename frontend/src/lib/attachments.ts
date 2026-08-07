/**
 * What every upload control in this product agrees about, in one place.
 *
 * There are four owners of an `attachment` row now — an execution's evidence (§67), a case, a
 * documentation page and a `FILE` artifact (#49) — and exactly **one** server-side limit behind all
 * of them: `spring.servlet.multipart.max-file-size`, enforced by the servlet container before any
 * handler runs. Four constants named after four owners would be four copies of one deployment
 * setting, free to drift the day somebody raises it.
 */
import { formatBytes } from './format';

/**
 * The instance's upload ceiling, mirroring `spring.servlet.multipart.max-file-size` (10MB in
 * `application.yml`; `max-request-size` is 12MB so the multipart envelope has room).
 *
 * **Checked client-side as copy, not as security.** The server is the gate and answers **413** — a
 * status refused *before* the handler runs, so there is no field to hang a message on and nothing
 * the SPA could render beside a control. This exists so the user learns before pushing 10MB over a
 * slow link, which is the difference between a rule and a punishment.
 */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

/**
 * Why this file cannot be uploaded, or `null` when it can.
 *
 * A function rather than a boolean, because **the refusal has to name both numbers**: "that file is
 * too big" leaves the user to guess by halving it. Both go through `formatBytes`, which is the one
 * place this app decides whether 1024 reads as "1 KB" or "1 KiB" — the limit therefore reads as
 * **10 MB**, matching `application.yml`'s `10MB` by arithmetic rather than by a second literal.
 *
 * An **empty** file is refused too, and it is a genuinely separate failure: the server answers a
 * zero-byte part with a `400` naming the field (`ArtifactFileController.register`) while the case
 * and page endpoints store an attachment whose download is a zero-length file. A control that let
 * it through would produce a row that looks attached and opens to nothing.
 */
export function uploadRefusal(file: { name: string; size: number }): string | null {
  if (file.size === 0) {
    return `${file.name} is empty. Pick a file with something in it.`;
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return (
      `${file.name} is ${formatBytes(file.size)}. This server accepts files up to `
      + `${formatBytes(MAX_UPLOAD_BYTES)}.`
    );
  }
  return null;
}
