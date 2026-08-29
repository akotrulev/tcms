package com.tcms;

import com.tcms.repository.AutomationStatus;
import com.tcms.repository.CaseUpdateRequest;
import com.tcms.repository.Priority;
import com.tcms.repository.StepFormat;
import java.util.List;

/**
 * The prose the fixture is made of — suite names, case titles, steps, documentation.
 *
 * <p>Written as if somebody testing a retail storefront had written it, not as {@code Case 1},
 * {@code Case 2}, {@code Case 3}. That is not presentation: a screen full of placeholder text
 * reads as correct whatever it does, because there is nothing on it a reader knows the right
 * answer to. Real titles of realistic lengths are what makes a truncation, a wrong sort, a
 * mis-labelled enum or a column that does not wrap visible at a glance — which is the entire job
 * of this fixture.
 *
 * <p>Lengths vary on purpose, including one title long enough to wrap and one deliberately terse.
 */
final class DemoContent {

    private DemoContent() {
    }

    /** A case before it becomes a {@link CaseUpdateRequest} — the fields the fixture varies. */
    record CaseSpec(
            String suite,
            String title,
            String objective,
            Priority priority,
            Priority severity,
            AutomationStatus automation,
            String automationKey,
            List<String> tags,
            List<Step> steps) {
    }

    /** One structured step: what the tester does, with what, and what should happen. */
    record Step(String action, String testData, String expected) {
    }

    /** The suites of the full project, with the tag rules that resolve their third arm. */
    record SuiteSpec(String name, String description, List<String> tagRules) {
    }

    static final List<SuiteSpec> WEB_SUITES = List.of(
            new SuiteSpec("Checkout",
                    "Everything from the cart page to the order confirmation, card and wallet.",
                    List.of("checkout")),
            new SuiteSpec("Catalogue & Search",
                    "Browsing, faceted search, sorting and the product detail page.",
                    List.of("search")),
            new SuiteSpec("Account",
                    "Registration, sign-in, password reset, addresses and order history.",
                    List.of()),
            new SuiteSpec("Smoke",
                    "The twenty minutes that decide whether a build is worth testing further.",
                    List.of("smoke")));

    /**
     * The feature tree of the full project. Two levels, because {@code feature} is the one
     * hierarchical dimension and a flat list would never exercise the path column.
     */
    static final List<String> WEB_FEATURES = List.of(
            "Storefront",
            "Storefront/Cart",
            "Storefront/Checkout",
            "Storefront/Search",
            "Accounts",
            "Accounts/Sign-in",
            "Accounts/Profile",
            "Fulfilment");

    /** Reusable preconditions and postconditions, so the condition library is not empty. */
    record ConditionSpec(String title, boolean precondition, List<String> body) {
    }

    static final List<ConditionSpec> WEB_CONDITIONS = List.of(
            new ConditionSpec("A signed-in shopper with a saved card", true, List.of(
                    "Sign in as a customer whose account has at least one saved payment card.",
                    "Confirm the account has no incomplete order in progress.")),
            new ConditionSpec("Catalogue seeded with the reference product set", true, List.of(
                    "Load the 240-product reference catalogue.",
                    "Confirm search indexing has caught up — the admin page reports Idle.")),
            new ConditionSpec("Leave no order behind", false, List.of(
                    "Cancel any order this test placed.",
                    "Empty the cart.")));

    static final List<CaseSpec> WEB_CASES = List.of(
            new CaseSpec("Checkout",
                    "A shopper pays with a saved card and reaches the confirmation page",
                    "Prove the shortest paid path through checkout still works end to end.",
                    Priority.CRITICAL, Priority.CRITICAL, AutomationStatus.AUTOMATED,
                    "checkout.saved_card.happy_path",
                    List.of("checkout", "smoke", "payments"),
                    List.of(
                            new Step("Open the cart with one in-stock item", "SKU NW-1042",
                                    "The cart shows one line and a subtotal of 34.00."),
                            new Step("Continue to checkout", null,
                                    "The delivery address step opens with the saved address selected."),
                            new Step("Accept the saved address and continue", null,
                                    "The payment step opens with the saved card selected."),
                            new Step("Place the order", null,
                                    "The confirmation page shows an order number and the card's last four digits."))),

            new CaseSpec("Checkout",
                    "A declined card leaves the basket intact and says why",
                    "A failed payment must not cost the shopper their basket — the single most "
                            + "expensive checkout defect we have shipped.",
                    Priority.CRITICAL, Priority.HIGH, AutomationStatus.AUTOMATED,
                    "checkout.decline.basket_preserved",
                    List.of("checkout", "payments", "negative"),
                    List.of(
                            new Step("Reach the payment step with two items in the basket", null,
                                    "The order summary lists both items."),
                            new Step("Pay with the always-declined test card",
                                    "4000 0000 0000 0002",
                                    "The page stays on the payment step and explains the card was declined."),
                            new Step("Open the cart", null,
                                    "Both items are still there, at the same quantities."))),

            new CaseSpec("Checkout",
                    "Applying an expired discount code is refused without clearing the basket",
                    "Discount handling is the part of checkout customers complain about most.",
                    Priority.HIGH, Priority.MEDIUM, AutomationStatus.TO_BE_AUTOMATED, null,
                    List.of("checkout", "promotions"),
                    List.of(
                            new Step("Enter the expired code at the cart", "SPRING24",
                                    "The cart says the code has expired."),
                            new Step("Check the totals", null,
                                    "The subtotal is unchanged and no discount line appears."))),

            new CaseSpec("Catalogue & Search",
                    "Search results honour the price filter and the sort together",
                    "The filter and the sort are applied by different services; the bug is always "
                            + "in the handover.",
                    Priority.HIGH, Priority.MEDIUM, AutomationStatus.AUTOMATED,
                    "search.filter_and_sort",
                    List.of("search", "regression"),
                    List.of(
                            new Step("Search for a term with more than 50 matches", "jacket",
                                    "The results page reports the match count."),
                            new Step("Filter to 20–60 and sort by price ascending", null,
                                    "Every result is within the range and the prices ascend."),
                            new Step("Move to the second page", null,
                                    "The filter and sort are still applied."))),

            new CaseSpec("Catalogue & Search",
                    "A product with no stock shows as unavailable rather than disappearing",
                    "Removing the page loses the SEO ranking, which is why this is a test and not "
                            + "an implementation preference.",
                    Priority.MEDIUM, Priority.LOW, AutomationStatus.MANUAL, null,
                    List.of("search"),
                    List.of(
                            new Step("Open a product whose stock is zero", "SKU NW-0007",
                                    "The page loads and the buy button is replaced by a notify-me form."))),

            new CaseSpec("Account",
                    "Password reset link works once and then does not",
                    "Single-use is the whole security property of the link.",
                    Priority.HIGH, Priority.CRITICAL, AutomationStatus.AUTOMATED,
                    "account.reset.single_use",
                    List.of("account", "security", "smoke"),
                    List.of(
                            new Step("Request a reset for a known address", "shopper@example.test",
                                    "The confirmation page appears and one mail is sent."),
                            new Step("Open the link and set a new password", null,
                                    "The password is changed and the shopper is signed in."),
                            new Step("Open the same link a second time", null,
                                    "The link is refused as already used."))),

            new CaseSpec("Account",
                    "Sign-in refuses a wrong password without revealing whether the account exists",
                    "The error copy must not distinguish the two cases.",
                    Priority.MEDIUM, Priority.HIGH, AutomationStatus.AUTOMATED,
                    "account.signin.no_enumeration",
                    List.of("account", "security"),
                    List.of(
                            new Step("Sign in with a real address and a wrong password", null,
                                    "The generic failure message appears."),
                            new Step("Sign in with an address that has no account", null,
                                    "The same message appears, word for word."))),

            new CaseSpec("Account",
                    "Order history paginates",
                    "Thin on purpose — a case whose value is that it is short.",
                    Priority.LOW, Priority.LOW, AutomationStatus.NOT_AUTOMATABLE, null,
                    List.of("account"),
                    List.of(
                            new Step("Open order history for an account with 30 orders", null,
                                    "Ten are listed and the pager offers three pages."))),

            new CaseSpec("Smoke",
                    "The home page renders and its primary navigation works",
                    "The first thing to check and the first thing to break.",
                    Priority.CRITICAL, Priority.HIGH, AutomationStatus.AUTOMATED,
                    "smoke.home",
                    List.of("smoke"),
                    List.of(
                            new Step("Open the home page", null,
                                    "It renders inside two seconds with no console error."),
                            new Step("Follow each primary navigation link", null,
                                    "Each lands on its section and none answers 404."))));

    /** The Gherkin case, so both step formats are represented on the case screens. */
    static final String WEB_GHERKIN_TITLE =
            "Free delivery is applied above the threshold and removed below it";

    static final List<Step> WEB_GHERKIN_STEPS = List.of(
            new Step("Given the free-delivery threshold is 50.00", null, null),
            new Step("When the basket subtotal reaches 52.00", null, null),
            new Step("Then the delivery line reads Free", null, null),
            new Step("When an item is removed and the subtotal falls to 38.00", null, null),
            new Step("Then the delivery line reads 4.99", null, null));

    /** The thinner project's cases. Two suites, four cases — navigable, and quick to read. */
    static final List<SuiteSpec> MOBILE_SUITES = List.of(
            new SuiteSpec("Onboarding", "First launch, permissions and sign-in.", List.of()),
            new SuiteSpec("Offline", "What the app does with no network.", List.of("offline")));

    static final List<CaseSpec> MOBILE_CASES = List.of(
            new CaseSpec("Onboarding",
                    "First launch asks for notification permission once",
                    "Asking twice is a store review problem.",
                    Priority.HIGH, Priority.MEDIUM, AutomationStatus.MANUAL, null,
                    List.of("onboarding"),
                    List.of(
                            new Step("Install and launch the app", null,
                                    "The permission prompt appears once."),
                            new Step("Deny, then relaunch", null,
                                    "No prompt appears; the settings hint is shown instead."))),

            new CaseSpec("Onboarding",
                    "Sign-in survives being backgrounded mid-flow",
                    "iOS reclaims the web view; the token must not be lost with it.",
                    Priority.MEDIUM, Priority.MEDIUM, AutomationStatus.TO_BE_AUTOMATED, null,
                    List.of("onboarding", "regression"),
                    List.of(
                            new Step("Start sign-in and background the app", null,
                                    "The app suspends."),
                            new Step("Return to the app", null,
                                    "The sign-in flow resumes where it was."))),

            new CaseSpec("Offline",
                    "The basket survives a flight-mode round trip",
                    "The basket is the one thing that must be local.",
                    Priority.CRITICAL, Priority.HIGH, AutomationStatus.AUTOMATED,
                    "offline.basket.persist",
                    List.of("offline", "smoke"),
                    List.of(
                            new Step("Add two items, then enable flight mode", null,
                                    "An offline banner appears."),
                            new Step("Force-quit and relaunch, still offline", null,
                                    "Both items are still in the basket."),
                            new Step("Restore the network", null,
                                    "The banner clears and the basket syncs without duplicating."))),

            new CaseSpec("Offline",
                    "A queued order is sent once when the network returns",
                    "Duplicate orders are the expensive failure of any offline queue.",
                    Priority.CRITICAL, Priority.CRITICAL, AutomationStatus.TO_BE_AUTOMATED, null,
                    List.of("offline", "payments"),
                    List.of(
                            new Step("Place an order while offline", null,
                                    "The app accepts it and marks it pending."),
                            new Step("Restore the network and wait", null,
                                    "Exactly one order appears in history."))));

    /** The second company's cases — a different domain, so the two are not confusable. */
    static final List<SuiteSpec> EHR_SUITES = List.of(
            new SuiteSpec("Patient Record", "Creating, amending and merging patient records.",
                    List.of()),
            new SuiteSpec("Access & Audit", "Who may read what, and the trail it leaves.",
                    List.of("audit")));

    static final List<CaseSpec> EHR_CASES = List.of(
            new CaseSpec("Patient Record",
                    "An amended record keeps its previous value in the history",
                    "Regulated: an amendment that overwrites is a reportable event.",
                    Priority.CRITICAL, Priority.CRITICAL, AutomationStatus.MANUAL, null,
                    List.of("regulated"),
                    List.of(
                            new Step("Open a record and amend the allergy list", null,
                                    "The amendment is saved."),
                            new Step("Open the record's history", null,
                                    "The previous list is shown with who changed it and when."))),

            new CaseSpec("Access & Audit",
                    "Reading a record outside your own caseload is permitted and logged",
                    "Break-glass access is allowed; unlogged break-glass access is not.",
                    Priority.CRITICAL, Priority.CRITICAL, AutomationStatus.MANUAL, null,
                    List.of("audit", "regulated"),
                    List.of(
                            new Step("Open a record belonging to another clinician's caseload", null,
                                    "The record opens after a reason is given."),
                            new Step("Check the audit trail", null,
                                    "The read is recorded with the reason and the reader."))),

            new CaseSpec("Access & Audit",
                    "A discharged patient's record becomes read-only after 30 days",
                    "Short, and the one case here that is about time passing.",
                    Priority.MEDIUM, Priority.MEDIUM, AutomationStatus.TO_BE_AUTOMATED, null,
                    List.of("audit"),
                    List.of(
                            new Step("Open a record discharged 31 days ago", null,
                                    "Every field is read-only and the reason is shown."))));

    static StepFormat structured() {
        return StepFormat.STRUCTURED;
    }
}
