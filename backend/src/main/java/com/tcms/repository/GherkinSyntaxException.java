package com.tcms.repository;

/**
 * Text {@link GherkinScenarios#parse} will not turn into steps — the module's own typed error, so
 * a parser failure is a <strong>400 with a line</strong> and never a 500 (integrations.md X1).
 *
 * <p>It exists because the library's own exception cannot be caught: {@code ParserException} is
 * package-private in {@code io.cucumber.gherkin}, and since v28 the parser does not throw it at the
 * caller anyway — a syntax error arrives as a {@code ParseError} envelope in the message stream.
 * So the boundary this type marks is one this module has to draw for itself.
 *
 * @param reason which of the four refusals this is. The caller maps it to a {@code type} and the
 *               message to {@code errors.text}; the four are distinguished because the remedies
 *               differ — a syntax error is fixed by editing the line named, an Outline by expanding
 *               it, several scenarios by pasting one, and an empty paste by writing something.
 */
class GherkinSyntaxException extends RuntimeException {

    enum Reason {
        /** The parser refused a line. The message carries its line, column and wording. */
        SYNTAX,
        /** A {@code Scenario Outline}, which this product does not expand yet. */
        OUTLINE,
        /** More than one {@code Scenario:} — one paste is one case. */
        MANY_SCENARIOS,
        /** Nothing that parses as a step. */
        EMPTY
    }

    private final Reason reason;

    GherkinSyntaxException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
