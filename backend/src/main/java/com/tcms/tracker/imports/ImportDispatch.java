package com.tcms.tracker.imports;

import java.util.List;
import java.util.UUID;

import com.tcms.tenancy.CompanyScopedEvent;

/**
 * Everything the dispatched run needs, handed to it as a <strong>method parameter</strong>.
 *
 * <h2>It is a parameter and never an outbox payload, and that is D-a's whole point</h2>
 *
 * <p>It carries {@link ImportConnection}, which carries the credential. The outbox is a
 * <em>table</em>: a payload written there is a token at rest, in a JSONB column, in a row that
 * survives the run and is read back by a dispatcher — which is precisely the thing
 * {@code import-connect.html} promises does not happen. So this crosses the {@code @Async}
 * boundary in memory or not at all, and a server restart mid-run cannot resume it. That cost is
 * paid by {@link ImportReaper} rather than by storing the token.
 *
 * <h2>It is a {@link CompanyScopedEvent} because the async boundary clears the company</h2>
 *
 * <p>{@code CompanyContextTaskDecorator} runs every task submitted to an application executor with
 * {@code CompanyContext} <strong>cleared</strong>, so the run starts with no company and every
 * RLS-protected read would return zero rows, silently. {@link ImportWriter#run} opens
 * {@code CompanyContext.openFor(this)} as its first statement, and this interface is what makes
 * forgetting the company a compile error rather than an empty import.
 *
 * <p>There is no {@code toString} override here and none is needed: {@link ImportConnection}
 * overrides its own, so interpolating this record prints the site and the provider and never the
 * token.
 */
record ImportDispatch(
        UUID companyId,
        UUID runId,
        UUID projectId,
        UUID actorId,
        ImportConnection connection,
        String sourceProjectKey,
        List<StatusMappingEntry> statusMapping,
        ImportOptions options) implements CompanyScopedEvent {
}
