package com.tcms.identity;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Identity's own bean registrations, in one place rather than scattered across services. */
@Configuration
public class IdentityConfiguration {

    /**
     * BCrypt at cost 12 — roughly 250 ms per hash on current hardware, which is the point:
     * it is the only thing that makes a stolen {@code password_hash} column expensive to
     * attack offline. Spring's default is 10; 12 is chosen deliberately and is cheap here
     * because hashing happens on activation and login, not per request.
     *
     * <h2>Why a delegating encoder rather than {@code new BCryptPasswordEncoder()}</h2>
     *
     * <p>It stores the algorithm as a {@code {bcrypt}} prefix in the hash itself, so
     * raising the cost — or moving to Argon2 when this codebase gains that dependency — is
     * a change to the <em>encoding</em> id while every existing hash keeps verifying under
     * the id it was written with. A bare {@code BCryptPasswordEncoder} writes no prefix,
     * and the day the algorithm changes there is no way to tell the old rows from the new
     * ones except by parsing the hash format, which is how migrations of this kind stall
     * permanently.
     *
     * <p>{@code V1}'s {@code password_hash} is {@code TEXT}, so the prefix costs nothing.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        String encodingId = "bcrypt";
        return new DelegatingPasswordEncoder(
                encodingId, Map.of(encodingId, new BCryptPasswordEncoder(12)));
    }
}
