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
 *
 * <h2>2026-08-28 (003 C4, 005 S1) — the line is a field, not a substring of the message</h2>
 *
 * <p>Every refusal but {@code EMPTY} is <em>about</em> a line, and until {@code Problem.line} existed
 * the only spelling of that was the {@code "Line 4, column 3: …"} prose {@link GherkinScenarios} puts
 * in front of the message. Under 003's D1 the editor has no row form to fall back to, so the sentence
 * has to land <strong>on</strong> the line it names — and a client regexing a number out of English
 * is a parser for a message this server is free to reword. So the line travels beside the message,
 * {@link CaseController} copies it into {@code Problem.line}, and the prose stays exactly as it was
 * for the readers that only print it (@tcms/cli, the import's REFUSE rows).
 *
 * <p>{@code EMPTY} carries <strong>null</strong>, and that is the honest answer rather than a gap: a
 * paste with nothing that parses as a step has no line to point at — the remedy is to write
 * something, anywhere.
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

    private final Integer line;

    GherkinSyntaxException(Reason reason, String message) {
        this(reason, message, null);
    }

    GherkinSyntaxException(Reason reason, String message, Integer line) {
        super(message);
        this.reason = reason;
        this.line = line;
    }

    Reason reason() {
        return reason;
    }

    /** The 1-based line the refusal is about, or null when there is none to point at. */
    Integer line() {
        return line;
    }
}
