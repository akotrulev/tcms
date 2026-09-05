package com.tcms.billing;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budgets — three money paths and <strong>one that is not</strong>.
 *
 * <p>{@code GET /projects/{projectId}/budget-burn} is gated on {@code project:read} and every other
 * path here on a money key (019 D-h). That asymmetry is the module's sharpest decision and it is
 * safe for one reason only: {@link BudgetBurnView} has no amount member and no currency member, so
 * there is nothing on that response to withhold. {@code BudgetBurnTest} asserts it over the
 * serialized body rather than over the record's components, because the failure it guards against is
 * a field reaching the wire.
 *
 * <p>The two writes are gated at COMPANY scope. A Project Manager sets no budget and reads the burn;
 * Finance sets every budget and holds no project grant, which is why neither write can use a
 * project-scoped gate.
 */
@RestController
@RequestMapping("/api/v1")
public class BudgetController {

    private static final String NO_READ = "You do not have permission to see budgets.";
    private static final String NO_BUDGET =
            "You do not have permission to set this project's budget.";
    private static final String NO_PROJECT = "You do not have permission to see this project.";

    private final BudgetService budgets;

    BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @GetMapping("/budgets")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "clientId", required = false) UUID clientId) {

        return BillingResponses.read(budgets.list(caller(jwt), clientId), NO_READ);
    }

    @PutMapping("/projects/{projectId}/budget")
    public ResponseEntity<Object> set(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody BudgetWriteRequest body) {

        return BillingResponses.answer(
                budgets.set(caller(jwt), projectId, body), HttpStatus.OK, NO_BUDGET);
    }

    @DeleteMapping("/projects/{projectId}/budget")
    public ResponseEntity<Object> clear(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return BillingResponses.answer(
                budgets.clear(caller(jwt), projectId), HttpStatus.NO_CONTENT, NO_BUDGET);
    }

    /**
     * The percentage a Project Manager reads, and the one billing path a Test Engineer may call.
     *
     * <p>Its refusal message names the <em>project</em> and not a budget, deliberately: a caller who
     * cannot reach this has no project visibility, and telling them a budget was withheld would
     * describe a permission they do not lack.
     */
    @GetMapping("/projects/{projectId}/budget-burn")
    public ResponseEntity<Object> burn(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return BillingResponses.read(budgets.burn(caller(jwt), projectId), NO_PROJECT);
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
