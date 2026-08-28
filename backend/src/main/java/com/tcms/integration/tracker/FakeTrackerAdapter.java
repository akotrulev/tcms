package com.tcms.integration.tracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A tracker that is not one — behind the {@code tracker-fake} profile.
 *
 * <h2>Built before the feature it proves, per the working agreement</h2>
 *
 * <p><em>"Build the fault switch before the feature it proves. A resilience behaviour you cannot
 * trigger is one you have not tested."</em> Every failure this integration has to render — a tracker
 * that is down, a token that expired, an issue the viewer cannot see, an account that was never
 * connected — is unreachable through the product by using it. This adapter is where each of them is
 * one call away, and it is why the UI batch can build the degraded states against something rather
 * than against a description of something.
 *
 * <h2>THE ENTITLEMENT MAP IS THE POINT, AND IT IS KEYED PER USER</h2>
 *
 * <p>A fake that answers the same thing to everybody cannot prove D15. The one defect this feature
 * is most likely to ship is a {@link TrackerCache} keyed by binding rather than by person — user A
 * previews an issue, user B with no Jira access opens the same case and is served A's copy — and
 * that is invisible unless the fake can be told that <em>B is not entitled to that issue</em>. So
 * {@link #entitle} takes a user id, {@link #search} and {@link #get} consult it, and the two-user
 * leak test is writable.
 *
 * <p>{@link #countCalls} is the other half: the cache's whole reason for existing is that it stops
 * calls, and "the second read did not reach the tracker" is not observable from a response body.
 *
 * <h2>It is a profile and not a property</h2>
 *
 * <p>A boolean would leave this bean in the production context, one misconfiguration away from
 * being the thing that answers a real user's preview. {@code @Profile("tracker-fake")} means the
 * class is not instantiated at all in any deployment that does not ask for it by name, which is the
 * same bar {@code import.meta.env.DEV} sets on the SPA's fault switch.
 */
@Component
@Profile("tracker-fake")
public class FakeTrackerAdapter implements TrackerPort {

    /** What the fake will answer with, per host, when a person is entitled to the issue. */
    private final Map<String, TrackerIssue> issues = new ConcurrentHashMap<>();

    /**
     * {@code accountId + "|" + issueId} → may that person see it. Absent means no.
     *
     * <p>Keyed on the credential's {@code accountId} rather than on a TCMS user id, because that is
     * what an adapter actually has: a real tracker answers according to <em>whose token this is</em>
     * and knows nothing about TCMS. Since each person connects with their own account, it is a
     * per-person key — which is the property the leak test needs.
     */
    private final Map<String, Boolean> entitlements = new ConcurrentHashMap<>();

    /** Hosts the fake refuses outright, so "the tracker is down" is drivable. */
    private final Map<String, String> outages = new ConcurrentHashMap<>();

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public boolean supports(TrackerProvider provider) {
        // Every provider. The fake stands in for whichever one a test's binding names, so a test
        // does not have to know which adapter would otherwise have been chosen.
        return true;
    }

    /** Registers an issue this fake knows about. Nobody can see it until {@link #entitle} says so. */
    public void register(TrackerIssue issue) {
        issues.put(issue.id(), issue);
    }

    /**
     * Says whether one person may see one issue.
     *
     * <p>Per <em>person</em>, which is the whole reason this class exists rather than a static map
     * of issues: the leak this integration must not have is one viewer's answer served to another.
     */
    public void entitle(String accountId, String issueId, boolean allowed) {
        entitlements.put(accountId + "|" + issueId, allowed);
    }

    /** Makes a host answer {@link TrackerUnavailableException} — "the tracker is down". */
    public void breakHost(String baseUrl, String message) {
        outages.put(baseUrl, message);
    }

    /** How many calls reached this adapter — the cache's effect, which no response body shows. */
    public int countCalls() {
        return calls.get();
    }

    public void reset() {
        issues.clear();
        entitlements.clear();
        outages.clear();
        calls.set(0);
    }

    @Override
    public List<TrackerIssue> search(
            TrackerCredential credential, TrackerBindingTarget target, String query, int limit) {

        refuseIfDown(target.baseUrl());
        calls.incrementAndGet();
        String needle = query.toLowerCase(Locale.ROOT);
        List<TrackerIssue> matches = new ArrayList<>();
        for (TrackerIssue issue : issues.values()) {
            if (!visible(credential, issue.id())) {
                continue;
            }
            if (issue.title().toLowerCase(Locale.ROOT).contains(needle)
                    || issue.key().toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(rebind(issue, target));
            }
            if (matches.size() >= limit) {
                break;
            }
        }
        return matches;
    }

    @Override
    public Optional<TrackerIssue> get(
            TrackerCredential credential, TrackerBindingTarget target, String issueId) {

        refuseIfDown(target.baseUrl());
        calls.incrementAndGet();
        if (!visible(credential, issueId)) {
            // Empty rather than an exception, so the caller renders UNAVAILABLE — which is exactly
            // what a real tracker's 404-for-an-invisible-issue produces, and is the state the
            // two-user leak test asserts user B gets.
            return Optional.empty();
        }
        return Optional.ofNullable(issues.get(issueId)).map(issue -> rebind(issue, target));
    }

    @Override
    public TrackerIssue create(
            TrackerCredential credential, TrackerBindingTarget target, TrackerDraft draft) {

        refuseIfDown(target.baseUrl());
        calls.incrementAndGet();
        String id = String.valueOf(10000 + issues.size());
        TrackerIssue created = new TrackerIssue(
                target.provider(),
                id,
                target.externalProject() + "-" + (issues.size() + 1),
                draft.title(),
                TrackerIssueStatusCategory.TODO,
                "To Do",
                target.baseUrl() + "/browse/" + target.externalProject() + "-" + (issues.size() + 1),
                target.bindingId());
        issues.put(id, created);
        // The filer can always see what they just filed, which is true of every real tracker and is
        // what makes the create-then-preview path testable without a second entitle call.
        entitlements.put(credential.accountId() + "|" + id, true);
        return created;
    }

    @Override
    public void verify(TrackerCredential credential) {
        refuseIfDown(credential.baseUrl());
        calls.incrementAndGet();
    }

    /**
     * The entitlement lookup, keyed on {@code accountId}.
     *
     * <p>{@code TrackerIssueService} builds a {@link TrackerCredential} per request from the
     * caller's <em>own</em> identity row, so "whose credential is this" and "who is asking" are the
     * same fact — which is exactly the property the real adapters get from the tracker itself, and
     * exactly what a fake answering everybody the same could not reproduce.
     */
    private boolean visible(TrackerCredential credential, String issueId) {
        return entitlements.getOrDefault(credential.accountId() + "|" + issueId, false);
    }

    private void refuseIfDown(String baseUrl) {
        String outage = outages.get(baseUrl);
        if (outage != null) {
            throw new TrackerUnavailableException(outage);
        }
    }

    /** The issue as this binding would have answered it — the binding id is the caller's, not ours. */
    private static TrackerIssue rebind(TrackerIssue issue, TrackerBindingTarget target) {
        return new TrackerIssue(
                target.provider(),
                issue.id(),
                issue.key(),
                issue.title(),
                issue.statusCategory(),
                issue.statusName(),
                issue.url(),
                target.bindingId());
    }
}
