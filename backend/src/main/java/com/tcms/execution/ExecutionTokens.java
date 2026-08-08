package com.tcms.execution;

import com.tcms.identity.ApiToken;
import com.tcms.identity.ApiTokenRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The credential on an execution, resolved to the name an administrator typed —
 * {@link ExecutionPeople}'s shape for the other kind of actor.
 *
 * <p>What is asked of {@code identity} here is <em>what is this credential called</em>, never
 * <em>what may it do</em>: the cap lives in {@code PermissionEvaluator} and nothing in this package
 * asks about it. One query for a whole timeline, never one per row.
 *
 * <h2>The name, and the ambiguity that comes with it</h2>
 *
 * <p>{@code CaseHistoryView.EntryView#executedByToken} is a {@code String} on the wire and the SPA
 * renders it directly, so the id has to become something legible. The name is what the
 * administrator matched against a CI configuration, which makes it the only candidate a reader
 * recognises.
 *
 * <p>It is <strong>not</strong> unique over time. {@code V36}'s index makes a name unique among a
 * company's <em>live</em> tokens only, precisely so a name written into a pipeline can be reused
 * after rotation — so two history entries years apart can show one name for two different
 * credentials. The alternative is the {@code token_prefix}, which is unambiguous and which nobody
 * recognises. Recorded rather than designed away: the ambiguity is between two rotations of the
 * same pipeline's credential, which is the case where a reader is least misled by the name.
 *
 * <p>An id that resolves to no row yields no entry and the caller renders null, which the SPA shows
 * as a bare "ingested". There is no cascade on this column and no reason to invent a name.
 */
@Component
class ExecutionTokens {

    private final ApiTokenRepository tokens;

    ExecutionTokens(ApiTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * @return names by id, missing every id that resolves to nothing. <strong>The caller must not
     *         look up a null key</strong> — the empty case is {@code Map.of()}, whose {@code get}
     *         throws on null where {@code HashMap}'s returns it (§38). Most rows have no token, so
     *         that map is the ordinary answer rather than the corner.
     */
    Map<UUID, String> resolve(Collection<UUID> tokenIds) {
        if (tokenIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (ApiToken token : tokens.findAllByIdIn(tokenIds)) {
            names.put(token.getId(), token.getName());
        }
        return names;
    }
}
