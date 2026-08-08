/**
 * Output budgets — surface spec §4.6.
 *
 * The MCP side is the constraint: a tool result warns at 10K tokens and is **capped at 25K**,
 * with an oversized result written to disk and replaced by a file reference. So a list tool that
 * does not paginate does not fail — it succeeds and hands the model a pointer instead of the
 * answer, which is worse, because nothing about the call looks wrong.
 *
 * The CLI shares the default for a different reason: a person who asked for "the cases" and got
 * 4,000 lines has to re-run with a filter anyway.
 */

/** Every list tool and command, unless the caller says otherwise. */
export const DEFAULT_PAGE_SIZE = 25;

/**
 * The ceiling a caller may raise a single page to.
 *
 * Not unbounded: the point of the cap is that one call cannot silently become the whole
 * repository. A caller wanting everything pages, and `eachPage` below is how.
 */
export const MAX_PAGE_SIZE = 200;

export interface PageMeta {
  page: number;
  size: number;
  total: number;
}

export interface Page<T> {
  items: T[];
  meta: PageMeta;
}

export function clampPageSize(size: number | undefined): number {
  if (size === undefined) return DEFAULT_PAGE_SIZE;
  if (!Number.isInteger(size) || size < 1) return DEFAULT_PAGE_SIZE;
  return Math.min(size, MAX_PAGE_SIZE);
}

/** True when `meta` describes a page with more after it. */
export function hasMore(meta: PageMeta): boolean {
  return (meta.page + 1) * meta.size < meta.total;
}

/**
 * Walks a paginated endpoint, yielding items until they run out or `limit` is reached.
 *
 * **`limit` is required, and that is the design.** An unbounded generator over a project's cases
 * is one `for await` away from being the 25K-token failure above, or from being a client-side
 * table scan nobody meant to write. Every caller states its ceiling, and the one that genuinely
 * wants everything says so in a number somebody can read.
 */
export async function* eachItem<T>(
  fetchPage: (page: number, size: number) => Promise<Page<T>>,
  limit: number,
  size: number = DEFAULT_PAGE_SIZE,
): AsyncGenerator<T> {
  const pageSize = clampPageSize(size);
  let page = 0;
  let yielded = 0;

  for (;;) {
    const result = await fetchPage(page, pageSize);

    for (const item of result.items) {
      if (yielded >= limit) return;
      yield item;
      yielded += 1;
    }

    // Two exits, and the second is not redundant. `hasMore` trusts `total`; an endpoint that
    // answered an empty page while claiming a larger total would otherwise loop forever, and a
    // client that loops forever against a server it cannot fix is worse than one that stops.
    if (!hasMore(result.meta) || result.items.length === 0) return;
    page += 1;
  }
}
