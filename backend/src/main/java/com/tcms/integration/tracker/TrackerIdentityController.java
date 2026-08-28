package com.tcms.integration.tracker;

import com.tcms.api.Problem;
import java.net.URI;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /me/tracker-identities} — the caller's own tracker accounts.
 *
 * <h2>There is no permission key on this controller, and that is the gate</h2>
 *
 * <p>Every method takes the caller from the token and passes nothing else. No administrator reaches
 * these rows: not a Company Admin, not an {@code integration:manage} holder, not an Instance Admin.
 * A tracker credential is a personal thing somebody gave TCMS for their own use, and 004 D2 makes
 * that the isolation boundary of the whole feature rather than a courtesy.
 *
 * <p><strong>Nothing here answers 403</strong>, and the absence is deliberate: there is no
 * permission to lack. Somebody else's identity id answers a bodiless 404, indistinguishably from an
 * id that names nothing at all — because the only thing a 403 could disclose here is that a
 * colleague has connected to something.
 *
 * <p>This is {@code /me}, so it is not project-scoped and not company-scoped in the path. The
 * company comes from the context, which is what makes a person who works with two clients hold two
 * connections to one Jira site — the papercut {@code V73}'s header records rather than hides.
 */
@RestController
@RequestMapping("/api/v1/me/tracker-identities")
public class TrackerIdentityController {

    private final TrackerIdentityService identities;

    public TrackerIdentityController(TrackerIdentityService identities) {
        this.identities = identities;
    }

    /** The caller's connections, never anybody else's. */
    @GetMapping
    public ResponseEntity<Object> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(identities.list(caller(jwt)));
    }

    /**
     * Connects a tracker account, or rotates the credential of one already connected.
     *
     * <p><strong>An upsert on {@code (provider, baseUrl)}</strong>: 201 for a first connection, 200
     * when the credential of an existing one was replaced. {@link TrackerIdentityInput} carries the
     * argument for why this is not a {@code PUT} and why the secret is required rather than
     * set-separately.
     *
     * <p>Nothing is called on the tracker here — {@code …/{id}/test} does that, so a connection can
     * be recorded while the tracker is down, which is exactly when somebody is reconnecting.
     */
    @PostMapping
    public ResponseEntity<Object> connect(
            @AuthenticationPrincipal Jwt jwt, @RequestBody TrackerIdentityInput input) {

        TrackerIdentityService.Outcome outcome = identities.connect(caller(jwt), input);
        if (outcome instanceof TrackerIdentityService.Outcome.Connected connected) {
            return connected.created()
                    ? ResponseEntity.created(location(connected.view().id())).body(connected.view())
                    : ResponseEntity.ok(connected.view());
        }
        return render(outcome);
    }

    /** Disconnects. Issue links made through this connection survive — 004 D18. */
    @DeleteMapping("/{identityId}")
    public ResponseEntity<Object> disconnect(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID identityId) {

        return render(identities.disconnect(caller(jwt), identityId));
    }

    /**
     * Calls the tracker once with this credential and records the answer.
     *
     * <p>A tracker refusal is a <strong>200 with {@code ok: false}</strong>, not a 4xx: the request
     * to this API succeeded, and the answer is the fact the caller asked for. A 401 here would be
     * actively harmful — it is the status the SPA's session-recovery path watches, so testing an
     * expired Jira token would sign somebody out of TCMS.
     */
    @PostMapping("/{identityId}/test")
    public ResponseEntity<Object> test(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID identityId) {

        return render(identities.test(caller(jwt), identityId));
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static ResponseEntity<Object> render(TrackerIdentityService.Outcome outcome) {
        return switch (outcome) {
            case TrackerIdentityService.Outcome.Connected connected ->
                    ResponseEntity.ok(connected.view());
            case TrackerIdentityService.Outcome.Disconnected ignored ->
                    ResponseEntity.noContent().build();
            case TrackerIdentityService.Outcome.Checked checked ->
                    ResponseEntity.ok(checked.view());
            case TrackerIdentityService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case TrackerIdentityService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
        };
    }

    /** Points at a path that serves no {@code GET} by id — so nothing follows it. See below. */
    private static URI location(UUID identityId) {
        // §119's warning is about a Location naming a resource that 404s. The per-id path here
        // serves DELETE and the test POST, so it resolves; it just is not a document. The list is
        // the readable form, and a client that follows this header gets a 405 rather than a lie.
        return URI.create("/api/v1/me/tracker-identities/" + identityId);
    }
}
