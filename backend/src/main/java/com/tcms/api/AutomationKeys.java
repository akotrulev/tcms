package com.tcms.api;

/**
 * How an {@code automationKey} is spelled — the one speller, for the two modules that must agree.
 *
 * <h2>Why this is in {@code api} and not in either module that uses it</h2>
 *
 * <p>{@code repository} writes {@code test_case.automation_key} when a {@code .feature} file is
 * imported; {@code execution} builds the same string out of a Cucumber report and looks the case up
 * by it ({@code IngestedResult#fullName()}). Neither module may import the other — that is
 * {@code execution}'s whole design (plan §3) — so before this class the format existed twice, in
 * prose, with the older copy's own javadoc saying <em>"changing it silently breaks every stored key
 * at once, with no error anywhere — every test simply stops matching"</em>. That sentence is the
 * argument for a single speller and it was written at the copy.
 *
 * <p>The charter in {@code package-info} refuses <em>"a helper two modules happen to share"</em>,
 * and this is not one. {@code automationKey} is a <strong>contract field</strong>
 * ({@code TestCase.automationKey}), its format is the thing the contract means by it, and two
 * modules <em>serve</em> that one field from opposite ends. The precedent inside this package is
 * {@link ProblemEnvelope}, which is here as <em>rendering</em> rather than as a shape on the same
 * reasoning: whether every refusal in this API announces itself the same way is a property of the
 * API, not of any module. Whether the key the import writes and the key the ingestion matches are
 * the same string is a property of the API too, and there is no module that could hold it.
 *
 * <p>It depends on nothing, which is the property {@code ArchitectureRulesTest.apiDependsOnNothing}
 * pins and the reason this module may hold anything at all.
 */
public final class AutomationKeys {

    /**
     * The separator, and it is a shared convention rather than any one caller's choice.
     *
     * <p>Plan §6c names {@code automationKey} as {@code "FQCN#method"}, so a key a human typed while
     * reading their own test source and a key a report parser assembled have to join the two halves
     * identically or the case never matches.
     */
    public static final String SEPARATOR = "#";

    private AutomationKeys() {
    }

    /**
     * {@code "<Feature name>#<Scenario name>"} — the key a Cucumber scenario is found by.
     *
     * <p>Both halves verbatim, including their spaces: {@code Orders#Cancel an order}. The feature
     * name is what makes it unique — two scenarios in different features may share a name, which is
     * ordinary Gherkin, and a key without the feature would bind both to one case.
     */
    public static String cucumber(String featureName, String scenarioName) {
        return join(featureName, scenarioName);
    }

    /**
     * The general form: {@code owner#test}, or the test alone when the dialect has no owner.
     *
     * <p>{@link #cucumber} is this with the two halves named. The dialects that are not Cucumber
     * reach it through here — a JUnit class, a TestNG class, an Allure {@code testClass} label —
     * and the point of one method is that all four produce keys a single stored value can match.
     */
    public static String join(String owner, String test) {
        return owner == null || owner.isBlank() ? test : owner + SEPARATOR + test;
    }
}
