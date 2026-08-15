package com.tcms.identity;

import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuring a company's external issuers — <strong>{@code company:idp}'s first consumer, five
 * migrations and eighteen months after {@code V3} seeded it.</strong>
 *
 * <p>That makes it the sixth permission to have been right in the catalogue and inert in the
 * product, after {@code company:teams} (§54), {@code case:approve} (§62), {@code library:manage}
 * (§64), {@code company:vocabulary} (§69) and {@code token:issue} (§128). The gate is the
 * permission and never a role name, per §6b, and there is no wildcard.
 *
 * <h2>Two credentials leave by exactly one door</h2>
 *
 * <p>{@link IdentityProviderInput#clientSecret} and {@link IdentityProviderInput#bindPassword} are
 * encrypted by {@link ProviderSecrets} before anything else touches them, and are decrypted only by
 * the token exchange and the directory bind respectively. Neither is in a view, an audit document, a
 * log line or an error message (§6r) — {@link IdentityProviderView} carries
 * {@code clientSecretSet} and {@code bindPasswordSet} instead, so a form can still say "leave blank
 * to keep" without either value existing outside the database.
 *
 * <p>The bind password is the one this instance did not choose and cannot rotate: it is a service
 * account in the <em>customer's</em> directory, which is why it gets the stricter of the two
 * treatments wherever they could differ.
 *
 * <h2>Registering and enabling are two acts</h2>
 *
 * <p>{@link IdentityProvider#register} forces {@code enabled = false} whatever the caller sends. An
 * issuer typed wrong is a sign-in route that refuses everybody, and the moment it becomes live
 * should be a decision somebody made after checking, not a side effect of filling in a form.
 *
 * <h2>Email domains are a sub-resource here rather than their own surface</h2>
 *
 * <p>They are company-level rather than provider-level — {@code uq_company_email_domain} is
 * {@code (company, domain)}, so two providers in one company cannot each claim the same domain —
 * but they exist only to make providers discoverable, they are gated on the same permission, and
 * they are edited on the same screen. A separate controller would be a second surface for one
 * decision.
 */
@Service
public class IdentityProviderService {

    /**
     * Roles spec §A3, Company table: <em>"Configure identity providers and claim-to-role
     * mappings"</em>. COMPANY scope, so Company Admin and Instance Admin.
     */
    static final String MANAGE_IDP = "company:idp";

    /** Long enough for "Acme Corporate SSO"; short enough to render on a button. */
    private static final int MAX_DISPLAY_NAME = 120;

    private static final int MAX_KEY = 60;

    private static final int MAX_URI = 500;

    /** Lowercase, digits, hyphens — what a URL-safe stable identifier is everywhere else here. */
    private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{0,58}[a-z0-9]$");

    /** Already encrypted, so StartTLS is neither needed nor used. */
    private static final Pattern LDAPS_URL = Pattern.compile("^ldaps://.+");

    private static final Pattern LDAP_URL = Pattern.compile("^ldaps?://.+");

    /**
     * Deliberately permissive, like {@code MembershipInviteService.EMAIL} one label over: at least
     * two dot-separated labels, no whitespace, no {@code @}. It exists to catch a pasted full
     * address and a typo, not to adjudicate the public suffix list.
     */
    private static final Pattern DOMAIN = Pattern.compile("^[^@\\s.]+(\\.[^@\\s.]+)+$");

    private static final String AUDIT_REGISTERED = "identity_provider.registered";
    private static final String AUDIT_UPDATED = "identity_provider.updated";
    private static final String AUDIT_DOMAINS = "company_email_domain.replaced";
    private static final String AUDIT_TARGET = "identity_provider";
    private static final String AUDIT_DOMAIN_TARGET = "company_email_domain";

    private final AdminDirectoryService directory;
    private final IdentityProviderRepository providers;
    private final CompanyEmailDomainRepository domains;
    private final ProviderSecrets secrets;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public IdentityProviderService(
            AdminDirectoryService directory,
            IdentityProviderRepository providers,
            CompanyEmailDomainRepository domains,
            ProviderSecrets secrets,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.directory = directory;
        this.providers = providers;
        this.domains = domains;
        this.secrets = secrets;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** The caller does not hold {@code company:idp} in this company. */
        record Forbidden() implements Outcome {
        }

        /** No such provider in this company. */
        record NotFound() implements Outcome {
        }

        /** Field name to messages, for the form to land them on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Registered — 201. */
        record Registered(IdentityProviderView view) implements Outcome {
        }

        /** Saved — 200. */
        record Saved(IdentityProviderView view) implements Outcome {
        }

        /** The company's providers — 200. */
        record Listed(List<IdentityProviderView> views) implements Outcome {
        }

        /** The company's claimed email domains — 200. */
        record Domains(List<String> domains) implements Outcome {
        }
    }

    /** The company's providers, disabled ones included: a list that hid them could not enable one. */
    @Transactional(readOnly = true)
    public Outcome list(UUID callerId) {
        if (!mayManage(callerId)) {
            return new Outcome.Forbidden();
        }
        return new Outcome.Listed(providers.findAll().stream()
                .sorted((left, right) -> left.getDisplayName().compareToIgnoreCase(right.getDisplayName()))
                .map(IdentityProviderView::of)
                .toList());
    }

    /** Registers a provider, always disabled — see the class header. */
    @Transactional
    public Outcome register(UUID callerId, IdentityProviderInput input) {
        if (!mayManage(callerId)) {
            return new Outcome.Forbidden();
        }
        Map<String, List<String>> errors = validate(input, true);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();

        IdentityProvider provider = providers.saveAndFlush(
                input.kind() == IdentityProviderKind.LDAP
                        ? IdentityProvider.registerDirectory(
                                id,
                                companyId,
                                input.key().trim(),
                                input.displayName().trim(),
                                input.issuerUri().trim(),
                                blankToNull(input.bindDn()),
                                secrets.encrypt(input.bindPassword()),
                                Boolean.TRUE.equals(input.startTls()),
                                input.userSearchBase().trim(),
                                input.userSearchFilter().trim(),
                                input.userIdAttribute().trim(),
                                blankToNull(input.emailAttribute()),
                                blankToNull(input.displayNameAttribute()),
                                callerId,
                                now)
                        : IdentityProvider.register(
                                id,
                                companyId,
                                input.key().trim(),
                                input.kind(),
                                input.displayName().trim(),
                                input.issuerUri().trim(),
                                input.clientId().trim(),
                                secrets.encrypt(input.clientSecret()),
                                callerId,
                                now));

        auditEvents.save(AuditEvent.record(
                null, callerId, AUDIT_REGISTERED, AUDIT_TARGET, List.of(id), changes(provider), now));
        return new Outcome.Registered(IdentityProviderView.of(provider));
    }

    /**
     * Applies a whole edit, {@code key} and {@code kind} excepted.
     *
     * <p>One mutator for one PUT, per {@link IdentityProvider#applyEdit}: two would each stamp
     * {@code updatedAt} and could leave a half-applied configuration behind a 200 saying otherwise.
     */
    @Transactional
    public Outcome update(UUID callerId, UUID providerId, IdentityProviderInput input) {
        Optional<IdentityProvider> found = providers.findById(providerId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        if (!mayManage(callerId)) {
            return new Outcome.Forbidden();
        }
        Map<String, List<String>> errors = validate(input, false);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        IdentityProvider provider = found.get();
        Instant now = clock.instant();
        boolean directory = provider.getKind() == IdentityProviderKind.LDAP;
        provider.applyEdit(
                input.displayName().trim(),
                input.issuerUri().trim(),
                // ck_identity_provider_shape requires it null on a directory and present on an
                // issuer, so the kind of the STORED row decides — `kind` is set once and an edit
                // neither requires nor reads what the caller sent.
                directory ? null : trimmed(input.clientId()),
                secrets.encrypt(input.clientSecret()),
                blankToNull(input.bindDn()),
                secrets.encrypt(input.bindPassword()),
                Boolean.TRUE.equals(input.startTls()),
                directory ? blankToNull(input.userSearchBase()) : null,
                directory ? blankToNull(input.userSearchFilter()) : null,
                directory ? blankToNull(input.userIdAttribute()) : null,
                blankToNull(input.emailAttribute()),
                blankToNull(input.displayNameAttribute()),
                Boolean.TRUE.equals(input.jitProvisioning()),
                Boolean.TRUE.equals(input.autoLinkVerifiedEmail()),
                blankToNull(input.groupClaim()),
                Boolean.TRUE.equals(input.enabled()),
                callerId,
                now);
        providers.saveAndFlush(provider);

        auditEvents.save(AuditEvent.record(
                null, callerId, AUDIT_UPDATED, AUDIT_TARGET, List.of(providerId),
                changes(provider), now));
        return new Outcome.Saved(IdentityProviderView.of(provider));
    }

    /** The domains this company claims, for the screen that edits them. */
    @Transactional(readOnly = true)
    public Outcome listDomains(UUID callerId) {
        if (!mayManage(callerId)) {
            return new Outcome.Forbidden();
        }
        UUID companyId = CompanyContext.require();
        return new Outcome.Domains(domains.findByCompanyIdOrderByDomainAsc(companyId).stream()
                .map(CompanyEmailDomain::getDomain)
                .toList());
    }

    /**
     * Replaces the whole claimed set.
     *
     * <p>A replace rather than add/remove endpoints because the set is the unit an administrator
     * thinks in and there is no lifecycle on a row here — {@link CompanyEmailDomain} has no status,
     * no author and nothing referencing it, so a removed claim leaves nothing behind. Existing rows
     * are kept rather than deleted and reinserted, so {@code created_at} still records when the
     * claim was first made.
     */
    @Transactional
    public Outcome replaceDomains(UUID callerId, List<String> submitted) {
        if (!mayManage(callerId)) {
            return new Outcome.Forbidden();
        }
        UUID companyId = CompanyContext.require();

        Set<String> wanted = new LinkedHashSet<>();
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (String raw : submitted == null ? List.<String>of() : submitted) {
            String domain = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!DOMAIN.matcher(domain).matches()) {
                errors.computeIfAbsent("domains", key -> new ArrayList<>())
                        .add("'" + raw + "' is not a domain — enter 'acme.test', not an address.");
                continue;
            }
            wanted.add(domain);
        }
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        Instant now = clock.instant();
        List<CompanyEmailDomain> existing = domains.findByCompanyIdOrderByDomainAsc(companyId);
        Set<String> held = new LinkedHashSet<>();
        for (CompanyEmailDomain claim : existing) {
            String domain = claim.getDomain().toLowerCase(Locale.ROOT);
            if (wanted.contains(domain)) {
                held.add(domain);
            } else {
                domains.delete(claim);
            }
        }
        domains.flush();
        for (String domain : wanted) {
            if (!held.contains(domain)) {
                domains.save(CompanyEmailDomain.claim(UUID.randomUUID(), companyId, domain, now));
            }
        }

        auditEvents.save(AuditEvent.record(
                null, callerId, AUDIT_DOMAINS, AUDIT_DOMAIN_TARGET, List.of(),
                "{\"domains\":" + jsonArray(wanted) + "}", now));
        return new Outcome.Domains(List.copyOf(wanted));
    }

    private boolean mayManage(UUID callerId) {
        return directory.reachOf(callerId).manageIdentityProviders();
    }

    /**
     * @param registering whether {@code key} and {@code kind} are being set. They are immutable
     *                    afterwards, so an edit neither requires nor reads them
     */
    private Map<String, List<String>> validate(IdentityProviderInput input, boolean registering) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (registering) {
            String key = input.key() == null ? "" : input.key().trim();
            if (!KEY.matcher(key).matches() || key.length() > MAX_KEY) {
                errors.put("key", List.of(
                        "A key is lowercase letters, digits and hyphens, up to " + MAX_KEY
                                + " characters."));
            }
            if (input.kind() == null) {
                errors.put("kind", List.of("Choose what kind of provider this is."));
            }
        }

        String displayName = input.displayName() == null ? "" : input.displayName().trim();
        if (displayName.isEmpty() || displayName.length() > MAX_DISPLAY_NAME) {
            errors.put("displayName", List.of(
                    "A display name is required, up to " + MAX_DISPLAY_NAME + " characters."));
        }

        if (input.kind() == IdentityProviderKind.LDAP) {
            validateDirectory(input, errors);
        } else {
            validateIssuer(input, errors);
        }

        return errors;
    }

    private static void validateIssuer(IdentityProviderInput input,
                                       Map<String, List<String>> errors) {
        String issuerUri = trimmed(input.issuerUri());
        if (!issuerUri.startsWith("https://") || issuerUri.length() > MAX_URI) {
            // Not a style rule. The discovery document, and therefore every signing key a signature
            // is verified against, is fetched from this URL — over http, anyone on the path
            // nominates the keys that decide who signs in.
            errors.put("issuerUri", List.of("The issuer must be an https:// URL."));
        }
        String clientId = trimmed(input.clientId());
        if (clientId.isEmpty() || clientId.length() > MAX_URI) {
            errors.put("clientId", List.of("A client id is required."));
        }
    }

    /**
     * The directory arm. <strong>Two of these are the security-relevant ones</strong> and both are
     * refusals with no escape hatch: an unencrypted bind puts the person's own directory password on
     * the wire, and a filter without the placeholder is an injection point on a surface an anonymous
     * caller reaches.
     */
    private static void validateDirectory(IdentityProviderInput input,
                                          Map<String, List<String>> errors) {
        String url = trimmed(input.issuerUri());
        boolean startTls = Boolean.TRUE.equals(input.startTls());
        if (!LDAP_URL.matcher(url).matches() || url.length() > MAX_URI
                || (!LDAPS_URL.matcher(url).matches() && !startTls)) {
            errors.put("issuerUri", List.of(
                    "A directory URL must be ldaps://, or ldap:// with StartTLS — a plain bind puts "
                            + "your people's directory passwords on the wire."));
        }
        if (trimmed(input.userSearchBase()).isEmpty()) {
            errors.put("userSearchBase", List.of(
                    "A search base is required — 'ou=people,dc=acme,dc=test'."));
        }
        String filter = trimmed(input.userSearchFilter());
        if (filter.isEmpty() || !filter.contains("{0}")) {
            errors.put("userSearchFilter", List.of(
                    "A search filter is required and must contain {0}, where the address is "
                            + "substituted — '(|(userPrincipalName={0})(mail={0}))'."));
        }
        if (trimmed(input.userIdAttribute()).isEmpty()) {
            errors.put("userIdAttribute", List.of(
                    "Name the attribute holding the directory's own identifier — 'objectGUID' on "
                            + "Active Directory, 'entryUUID' on OpenLDAP. Not a DN: a DN changes "
                            + "when somebody moves between OUs."));
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * The audit document. <strong>No secret, and not even a placeholder for one</strong> —
     * {@code AuditEvent.record}'s rule: the client's own administrators can read this table, so a
     * credential in it is disclosed to everyone the audit screen is for.
     */
    private static String changes(IdentityProvider provider) {
        return new StringBuilder("{")
                .append("\"key\":").append(AuditEvent.jsonString(provider.getKey()))
                .append(",\"displayName\":").append(AuditEvent.jsonString(provider.getDisplayName()))
                .append(",\"issuerUri\":").append(AuditEvent.jsonString(provider.getIssuerUri()))
                .append(",\"clientId\":").append(orNull(provider.getClientId()))
                .append(",\"bindDn\":").append(orNull(provider.getBindDn()))
                .append(",\"startTls\":").append(provider.isStartTls())
                .append(",\"userSearchBase\":").append(orNull(provider.getUserSearchBase()))
                .append(",\"userSearchFilter\":").append(orNull(provider.getUserSearchFilter()))
                .append(",\"userIdAttribute\":").append(orNull(provider.getUserIdAttribute()))
                .append(",\"jitProvisioning\":").append(provider.isJitProvisioning())
                .append(",\"autoLinkVerifiedEmail\":").append(provider.isAutoLinkVerifiedEmail())
                .append(",\"enabled\":").append(provider.isEnabled())
                .append("}")
                .toString();
    }

    /**
     * The JSON {@code null} literal for an absent value, rather than the quoted string
     * {@link AuditEvent#jsonString} would build.
     *
     * <p>Needed since V57 and not before: every field this document carried used to be
     * {@code NOT NULL}, and half of them are now null by design — {@code clientId} on a directory,
     * the five directory fields on an issuer. {@code jsonString} dereferences its argument, so
     * handing it one of those is an NPE inside a 201, which is what this exists to stop.
     */
    private static String orNull(String value) {
        return value == null ? "null" : AuditEvent.jsonString(value);
    }

    private static String jsonArray(Set<String> values) {
        return values.stream()
                .map(AuditEvent::jsonString)
                .reduce((left, right) -> left + "," + right)
                .map(joined -> "[" + joined + "]")
                .orElse("[]");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
