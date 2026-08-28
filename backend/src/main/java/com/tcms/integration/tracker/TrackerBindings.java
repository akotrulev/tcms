package com.tcms.integration.tracker;

import com.tcms.project.TrackerBindingDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one implementation of {@link TrackerBindingDirectory} — {@code integration} supplying what
 * {@code project} declares, so {@code execution} and {@code documentation} can resolve a binding
 * without an import the architecture forbids.
 *
 * <p>{@code IdentityPeopleDirectory} is the pattern: the declaring module is one both sides already
 * depend on, and the answering module is the one that owns the table. The arrow points one way.
 *
 * <p>It answers a <em>factual</em> question and gates nothing — the callers have already resolved
 * the project through their own permission. Scoping by {@code projectId} is not a gate either; it is
 * what stops a binding id from a sibling project resolving through a path naming this one, which is
 * the same rule {@code findByIdAndExecutionId} follows two modules over.
 */
@Component
class TrackerBindings implements TrackerBindingDirectory {

    private final TrackerBindingRepository bindings;

    TrackerBindings(TrackerBindingRepository bindings) {
        this.bindings = bindings;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TrackerTarget> resolve(UUID projectId, UUID bindingId) {
        return bindings.findByIdAndProjectId(bindingId, projectId)
                .map(binding -> new TrackerTarget(
                        binding.getId(),
                        binding.getProvider().name(),
                        binding.getBaseUrl()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TrackerTarget> forHost(UUID projectId, String provider, String host) {
        if (provider == null || host == null) {
            return Optional.empty();
        }
        return bindings.findByProjectIdOrderByDefaultBindingDescCreatedAtAsc(projectId).stream()
                .filter(binding -> binding.getProvider().name().equals(provider)
                        && binding.getBaseUrl().equals(host))
                .findFirst()
                .map(binding -> new TrackerTarget(
                        binding.getId(), binding.getProvider().name(), binding.getBaseUrl()));
    }
}
