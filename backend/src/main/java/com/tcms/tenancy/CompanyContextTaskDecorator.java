package com.tcms.tenancy;

import org.springframework.core.task.TaskDecorator;

/**
 * Makes "the handler forgot to open a company" fail <em>closed</em> instead of reading
 * whatever the worker thread happened to be carrying.
 *
 * <p>Every task submitted to an application executor or scheduler runs with
 * {@link CompanyContext} <strong>cleared</strong>, and the submitting thread's own
 * company is restored when the task returns.
 *
 * <h2>It clears {@link TokenContext} too, and that is newer than the class</h2>
 *
 * <p>The token could not be cleared here while it lived in {@code identity}, which this module may
 * not import — an omission {@code TokenContext.clear()} recorded and argued was survivable, because
 * an inherited token only ever <em>narrows</em> what background work may do. That argument named
 * its own expiry and {@link AuditEvent#record} reached it: the audit trail now reads the token to
 * attribute, so an inherited one no longer makes a task do less, it makes the task's audit row name
 * a credential that had nothing to do with it. Both contexts are cleared in one place, so the two
 * cannot drift.
 *
 * <h2>Why this is not redundant with the non-inheritable ThreadLocal</h2>
 *
 * <p>{@code CompanyContext} is not an {@code InheritableThreadLocal}, so a <em>fresh</em>
 * thread already starts empty. The gap is a <strong>recycled</strong> one. A pooled
 * worker that ran a task which opened a company and never closed it — one missing
 * try-with-resources, or a throw past the close — hands that company to the next task on
 * the same thread. That task then reads a foreign client's rows while looking entirely
 * correct: it has a company, the filter applies, RLS agrees, nothing errors.
 *
 * <p>This deployment runs virtual threads
 * ({@code spring.threads.virtual.enabled=true}), which are never reused, so today the
 * gap is theoretical. It stops being theoretical the moment that property is switched
 * off for a measured reason — and that is a one-line change in a YAML file, made by
 * someone who is not thinking about tenancy. The guard is wired to both the pooled and
 * the virtual-thread executor kinds so the switch cannot quietly remove it.
 *
 * <h2>Restore, don't just clear</h2>
 *
 * <p>The previous value is restored rather than dropped, because a task decorator is not
 * only invoked on a worker thread: a {@code CallerRunsPolicy} rejection, a
 * {@code SyncTaskExecutor}, and an inline invocation all run the task on the
 * <em>submitting</em> thread. Clearing without restoring would silently strip a live
 * request's company mid-flight, and the next statement in that request would read zero
 * rows for no visible reason.
 *
 * <p>Restoring also cleans up after the leaking task above: the company it failed to
 * close is dropped when the decorator's own scope closes.
 *
 * @see CompanyScopedEvent the only thing a handler may re-establish context from
 */
public class CompanyContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return () -> {
            try (CompanyContext.Scope ignoredCompany = CompanyContext.clear();
                    TokenContext.Scope ignoredToken = TokenContext.clear()) {
                runnable.run();
            }
        };
    }
}
