/**
 * Every monetary figure in this feature goes through here, and there is deliberately **no
 * `sum(Money[])`** (019 D-l).
 *
 * <h2>The missing function is the point</h2>
 *
 * `billing.html` draws `€52.4k` as one tile while its own rate-card table carries a EUR client and
 * a CHF client two rows apart. That is a single-currency render of a multi-currency company, and a
 * helper that added two currencies would be the one place this feature could produce a number that
 * is wrong in *both*. The tiles render {@link formatMoneyList}'s one line per currency instead —
 * recorded in plan 019 D-l as a disagreement with the mockup rather than resolved silently, per
 * plan 009's precedence rule: on what a screen shows the mockup wins, on what the system does the
 * plan wins, and *"what is this company's invoiced total"* is a question about the system.
 *
 * Any figure this feature renders is a `Money` — an amount **and** its currency, always together
 * — because a bare number is the field that eventually gets summed across two of them.
 *
 * <h2>Why this is here rather than in `lib/format.ts`</h2>
 *
 * `lib/format.ts` is the one canonical format per data type, and on the formatting alone this
 * would belong there. What keeps it here is the paragraph above: the module's rule is a
 * *prohibition*, and a prohibition is only legible beside the thing it forbids. In the shared
 * layer `formatMoney` would read as an ordinary formatter, and the absence of a `sumMoney` beside
 * it as an oversight somebody would helpfully correct. `features/staffing/capacityCopy.ts` is the
 * precedent for a shared-layer-shaped module that lives with the decision it encodes, and it is
 * imported across features exactly as this one is (T6's client table reads it).
 *
 * <h2>Two decimals, always — and the mockup's whole euros are the mockup's data</h2>
 *
 * `Money.amount` is a `double`, so an invoice can total `€6 096.42`. Rendering it as `€6 096`
 * would be a *wrong number* on the one screen in the product whose whole subject is what a client
 * owes, and "the mockup shows no cents" is a fact about its example figures rather than a rounding
 * rule. One precision, everywhere, is `lib/format.ts`'s own rule applied to a type it does not own.
 */
import type { Money } from '../../api/types';

const EM_DASH = '—';

/**
 * `Intl.NumberFormat` per currency code, built once.
 *
 * A formatter is comparatively expensive to construct and this feature renders one per table cell;
 * the cache is keyed on the code alone because the locale is fixed, for `lib/format.ts`' reason —
 * a per-viewer locale would make two people reading the same invoice disagree about the separator
 * and, in some locales, the decimal point.
 */
const FORMATTERS = new Map<string, Intl.NumberFormat>();

/**
 * The formatter for a currency code, or `null` for one `Intl` will not accept.
 *
 * **A code the runtime rejects must not throw here.** `Intl.NumberFormat` raises a `RangeError` on
 * anything that is not a well-formed ISO-4217 code, and a formatter that threw would trip the
 * region's error boundary and blank a panel — reporting a rendering failure for what is really a
 * bad three letters in one row. The caller falls back to `CODE 1234.00`, which is legible, honest,
 * and visibly not the house format.
 */
function formatterFor(currency: string): Intl.NumberFormat | null {
  const cached = FORMATTERS.get(currency);
  if (cached) return cached;
  try {
    const built = new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    FORMATTERS.set(currency, built);
    return built;
  } catch {
    return null;
  }
}

/** `€6,096.00`, `CHF 12,705.00`. One amount and its currency, never one without the other. */
export function formatMoney(money: Money | null | undefined): string {
  if (!money) return EM_DASH;
  const formatter = formatterFor(money.currency);
  if (!formatter) return `${money.currency} ${money.amount.toFixed(2)}`;
  return formatter.format(money.amount);
}

/**
 * A company-level total: **one line per currency, and never one number**.
 *
 * Returns an array so the caller renders each figure on its own row — the tiles do, and so does
 * the invoice list's footer. An empty array is a real answer (nothing issued this year, nothing
 * draftable now) and the caller renders its own em dash for it rather than being handed a string
 * that looks like a figure.
 *
 * The order is the server's. Sorting by amount here would rank a company's currencies by size,
 * which is a claim this module has no basis for making.
 */
export function formatMoneyList(figures: readonly Money[]): string[] {
  return figures.map((money) => formatMoney(money));
}

/**
 * `€110.00 / h` — a rate-card entry's rate, and an invoice line's.
 *
 * The rate arrives as a bare number beside the card's currency (on `RateCardEntry`) or beside the
 * line's own `amount.currency` (on `InvoiceLine`), which is the one place in this feature a number
 * legitimately travels without its currency attached — it is an input to a product, not a total.
 * It gets one here rather than at four call sites.
 */
export function formatRate(rate: number, currency: string): string {
  return `${formatMoney({ amount: rate, currency })} / h`;
}
