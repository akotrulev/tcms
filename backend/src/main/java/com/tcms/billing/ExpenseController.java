package com.tcms.billing;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /expenses/*} — <strong>endpoints with no screen, deliberately</strong> (019 D-j).
 *
 * <p>Read this before looking for the panel that calls them: there is none, and there will not be
 * one. Scope D24 admits exactly one invoice line shape — approved time at a rate-card rate — and
 * {@code invoice.html} draws no expense row, so an expense is recorded and never invoiced. These
 * three paths exist because <strong>{@code billing:expense} is in the permission catalogue</strong>,
 * and a key with no reachable endpoint is the unreachable-row defect roles spec §43 names. Their
 * callers are the {@code @tcms/cli} and {@code @tcms/mcp} verbs 019 T1 landed.
 *
 * <p>Plan 009's rule is that no module ports a screen it has no endpoint for; this is the other
 * direction, which is allowed, and it is stated here so the absence is not later read as an
 * unfinished task. <strong>Do not build an expenses panel the mockups do not draw.</strong>
 *
 * <p>An AI assistant's usage is a cost and would be recorded through {@link #record}. It is never
 * charged to a client.
 */
@RestController
@RequestMapping("/api/v1")
public class ExpenseController {

    private static final String NO_READ = "You do not have permission to see expenses.";
    private static final String NO_EXPENSE = "You do not have permission to record an expense.";

    private final ExpenseService expenses;

    ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @GetMapping("/expenses")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "projectId") UUID projectId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return BillingResponses.read(expenses.list(caller(jwt), projectId, from, to), NO_READ);
    }

    @PostMapping("/expenses")
    public ResponseEntity<Object> record(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ExpenseWriteRequest body) {

        return BillingResponses.answer(
                expenses.record(caller(jwt), body), HttpStatus.CREATED, NO_EXPENSE);
    }

    @DeleteMapping("/expenses/{expenseId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID expenseId) {

        return BillingResponses.answer(
                expenses.delete(caller(jwt), expenseId), HttpStatus.NO_CONTENT, NO_EXPENSE);
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
