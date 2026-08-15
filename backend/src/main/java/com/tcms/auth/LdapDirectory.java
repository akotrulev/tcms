package com.tcms.auth;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.DefaultTlsDirContextAuthenticationStrategy;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.stereotype.Component;

/**
 * The one class in this codebase that speaks LDAP.
 *
 * <p>It lives in {@code auth} rather than {@code identity} for {@link OidcClient}'s reason and by
 * the same rule: {@code identity} may not reach the network (§6r trap 5, enforced by
 * {@code ArchitectureRulesTest}), so the protocol lives here and
 * {@link com.tcms.identity.FederatedIdentityService} stays pure policy over the database.
 *
 * <h2>Search, then bind — and the search is escaped</h2>
 *
 * <p>The typed address goes into an administrator-supplied filter. Concatenating it gives an
 * attacker {@code *)(objectClass=*} on an unauthenticated surface, whose best case is matching the
 * wrong person and whose worst case is an entry this class then binds against. The substitution is
 * {@link LdapEncoder#filterEncode}, and the filter is refused at save time unless it carries
 * {@code {0}}.
 *
 * <h2>Three refusals that look like successes if you write them naively</h2>
 *
 * <ul>
 *   <li><strong>An empty password is an unauthenticated bind</strong> in the protocol, and most
 *       directories answer it with success. A client that passes it through treats "no password
 *       given" as "signed in". Refused here before a connection is opened.</li>
 *   <li><strong>More than one match is a refusal</strong>, not a "take the first" — a stale
 *       duplicate in a directory would otherwise let result ordering choose a person.</li>
 *   <li><strong>Referrals are not followed.</strong> An AD server may answer with a referral to
 *       another host; following it binds the service credential, and then the person's password,
 *       against a server nominated by the response rather than by configuration.</li>
 * </ul>
 *
 * <h2>Certificate trust is the deployment's, not this class's</h2>
 *
 * <p>The JVM default trust store validates what the directory presents. An internal CA is added to
 * a mounted truststore at the deployment (see {@code deploy/README.md}); there is deliberately no
 * per-provider PEM column, which would be a second trust store editable through a web form, by an
 * administrator, for a host that same form names. {@link #environmentFor} is the seam that makes
 * that decision configurable from outside this class.
 */
@Component
public class LdapDirectory {

    private static final Logger log = LoggerFactory.getLogger(LdapDirectory.class);

    /** JNDI reads these as STRINGS OF MILLISECONDS; a context with neither blocks for ever. */
    private static final String CONNECT_TIMEOUT = "com.sun.jndi.ldap.connect.timeout";
    private static final String READ_TIMEOUT = "com.sun.jndi.ldap.read.timeout";

    /**
     * Two, so "more than one" is detectable without pulling a whole subtree back on a filter an
     * administrator got wrong. The search wants exactly one entry and anything else is a refusal.
     */
    private static final int AMBIGUITY_LIMIT = 2;

    private final LdapProperties properties;

    public LdapDirectory(LdapProperties properties) {
        this.properties = properties;
    }

    /** A provider's directory configuration, flattened — no entity crosses into this package. */
    public record Target(
            String url,
            boolean startTls,
            String bindDn,
            String bindPassword,
            String userSearchBase,
            String userSearchFilter,
            String userIdAttribute,
            String emailAttribute,
            String displayNameAttribute) {
    }

    /** What the directory said about somebody who has just proved they are them. */
    public record Entry(String dn, String subject, String email, String displayName) {
    }

    /**
     * Searches for the address, then binds as the entry it found.
     *
     * @return the entry, or empty for every failure — no entry, several entries, wrong password,
     *         unreachable directory, missing identifier attribute. The caller must not tell them
     *         apart (§6r)
     */
    public Optional<Entry> authenticate(Target target, String identifier, String password) {
        if (password == null || password.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<Entry> found = findEntry(target, identifier);
            if (found.isEmpty()) {
                // A synthetic bind, so a miss and a wrong password cost the same round trips.
                // AuthenticationService's dummy-hash rule, one protocol over: without it, "no such
                // person" answers in milliseconds and "wrong password" in a network round trip, and
                // the difference is readable with a stopwatch by anyone.
                bind(target, "uid=" + UUID.randomUUID() + "," + target.userSearchBase(), password);
                return Optional.empty();
            }
            Entry entry = found.get();
            return bind(target, entry.dn(), password) ? Optional.of(entry) : Optional.empty();
        } catch (RuntimeException failure) {
            // No identifier, no DN, no password — nothing about the credential reaches a log (D7).
            log.info("Directory lookup at {} did not resolve", target.url());
            return Optional.empty();
        }
    }

    private Optional<Entry> findEntry(Target target, String identifier) {
        LdapTemplate template = templateFor(target, target.bindDn(), target.bindPassword());
        String filter = target.userSearchFilter()
                .replace("{0}", LdapEncoder.filterEncode(identifier));

        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(AMBIGUITY_LIMIT);
        controls.setReturningAttributes(new String[] {
                target.userIdAttribute(), target.emailAttribute(), target.displayNameAttribute()});

        ContextMapper<Entry> mapper = context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            Attributes attributes = adapter.getAttributes();
            return new Entry(
                    adapter.getNameInNamespace(),
                    subjectOf(attributes.get(target.userIdAttribute())),
                    stringOf(attributes.get(target.emailAttribute())),
                    stringOf(attributes.get(target.displayNameAttribute())));
        };

        List<Entry> matches =
                template.search(target.userSearchBase(), filter, controls, mapper);

        if (matches.size() != 1) {
            return Optional.empty();
        }
        Entry only = matches.getFirst();
        // No identifier attribute is a refusal too: a subject this class cannot key on would
        // otherwise become a null in user_identity.subject.
        return only.subject() == null ? Optional.empty() : Optional.of(only);
    }

    private boolean bind(Target target, String dn, String password) {
        try {
            templateFor(target, dn, password).lookup(dn);
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    private LdapTemplate templateFor(Target target, String userDn, String password) {
        LdapContextSource source = new LdapContextSource();
        source.setUrl(target.url());
        if (userDn != null && !userDn.isBlank()) {
            source.setUserDn(userDn);
            source.setPassword(password);
        } else {
            source.setAnonymousReadOnly(true);
        }
        // D7: a referral is a server the RESPONSE chose, and following it would bind there.
        source.setReferral("ignore");
        if (target.startTls()) {
            source.setAuthenticationStrategy(new DefaultTlsDirContextAuthenticationStrategy());
        }
        source.setBaseEnvironmentProperties(environmentFor(target));
        source.afterPropertiesSet();

        LdapTemplate template = new LdapTemplate(source);
        template.setIgnorePartialResultException(true);
        return template;
    }

    /**
     * The JNDI environment for one connection.
     *
     * <p>Overridable so a deployment — or a test standing up its own directory — can decide how the
     * server's certificate is trusted, which is a deployment concern and not a per-provider column.
     * The two timeouts are not optional: a directory that accepts a connection and never answers
     * holds a request thread for ever without them.
     */
    protected Map<String, Object> environmentFor(Target target) {
        Map<String, Object> environment = new HashMap<>();
        environment.put(CONNECT_TIMEOUT, String.valueOf(properties.connectTimeout().toMillis()));
        environment.put(READ_TIMEOUT, String.valueOf(properties.readTimeout().toMillis()));
        return environment;
    }

    /**
     * The identifier, in the form the customer's administrator can look up.
     *
     * <p><strong>{@code objectGUID} is a 16-byte octet string whose first three fields are
     * little-endian</strong> — the canonical GUID a Windows administrator sees is a byte-swapped
     * rendering of what the wire carries. Two ways to get this wrong and both are permanent once
     * rows exist: {@code new String(bytes)} produces platform-dependent mojibake, so the key stops
     * being stable and every affected person forks a second account on their next sign-in; and
     * rendering in wire order gives a GUID that matches nothing anyone can search for.
     * {@code entryUUID} arrives as text and is used as-is.
     */
    private static String subjectOf(Attribute attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            Object value = attribute.get();
            if (value instanceof byte[] bytes && bytes.length == 16) {
                return guidOf(bytes);
            }
            return value == null ? null : value.toString();
        } catch (NamingException unreadable) {
            return null;
        }
    }

    private static String guidOf(byte[] bytes) {
        int[] order = {3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15};
        StringBuilder guid = new StringBuilder(36);
        for (int index = 0; index < order.length; index++) {
            if (index == 4 || index == 6 || index == 8 || index == 10) {
                guid.append('-');
            }
            guid.append(String.format("%02x", bytes[order[index]]));
        }
        return guid.toString();
    }

    private static String stringOf(Attribute attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            Object value = attribute.get();
            if (value == null) {
                return null;
            }
            return value instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : value.toString();
        } catch (NamingException unreadable) {
            return null;
        }
    }
}
