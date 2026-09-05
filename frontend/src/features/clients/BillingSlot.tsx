import { Receipt } from 'lucide-react';
import { Panel } from '../../components/PageHeader';

const TEST_ID = 'Client.Billing';

/**
 * The region M8 fills — `client.html`'s *"Hours and invoices to date"* table, the two tiles above
 * it, and the rate card panel beside it.
 *
 * <h2>It draws no data and reads no endpoint</h2>
 *
 * Which is what keeps it inside plan 009's rule that no module ports a screen it has no endpoint
 * for. What it is instead is the **seam**: M8 lands its table here, and this component's existence
 * is what let 018's close-out hand that module a defined slot rather than an unresolved layout
 * decision three modules later.
 *
 * <h2>Why not half the table now</h2>
 *
 * M6 landed, so approved hours per project are reachable — the columns are
 * `Period · Project · Approved hours · Invoice · Status` and **M8 owns three of the five**.
 * Building two of them now and rebuilding the table in M8 costs more than M8 building it once, and
 * leaves a half-table that reads as a defect rather than as a boundary (018 D-g). Story C3 words
 * the deferral itself: *"its projects, contacts, and — once time and billing exist — hours and
 * invoices to date."*
 *
 * <h2>The rate card panel is not here even as a refusal</h2>
 *
 * The mockup renders it for an Account Manager as *"Not visible to you"*. That string is the AM's
 * view of a panel that exists for Finance — and with no rate card anywhere in the product it would
 * be a promise about a screen nobody can reach. An explanation of a boundary is worth rendering; a
 * placeholder for a feature is not.
 */
export function BillingSlot() {
  return (
    <Panel title="Hours and invoices" testId={TEST_ID}>
      <div className="flex items-start gap-3 px-3.5 py-3" data-testid={`${TEST_ID}.Reserved`}>
        <Receipt aria-hidden className="mt-0.5 size-4 shrink-0 text-ink-4" />
        <p className="m-0 max-w-[74ch] text-[12.5px] leading-[1.6] text-ink-3">
          Approved hours and the invoices raised against them will appear here, per period and per
          project. Hours are recorded today — a project’s Time panel shows them — but nothing prices
          them yet, and a table that showed the hours beside an empty Invoice column would read as
          broken rather than as unfinished.
        </p>
      </div>
    </Panel>
  );
}
