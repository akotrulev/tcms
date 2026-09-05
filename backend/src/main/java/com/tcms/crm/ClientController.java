package com.tcms.crm;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company-scope client surface — {@code /clients/*}.
 *
 * <p>Thin, as every controller here is: resolve the caller from the session, delegate, map the
 * sealed outcome. <strong>The company is never a parameter</strong> — it comes from the
 * authenticated session and {@code CompanyAwareTransactionManager} puts it on the connection.
 */
@RestController
@RequestMapping("/api/v1")
public class ClientController {

    private final ClientService clients;

    ClientController(ClientService clients) {
        this.clients = clients;
    }

    @GetMapping("/clients")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "status", required = false, defaultValue = "ACTIVE") String status,
            @RequestParam(name = "accountManagerId", required = false) UUID accountManagerId) {

        /*
          `ALL` is the absence of a filter and is spelled here rather than as a third enum member:
          ClientStatus has exactly the two states a row can be in, and a third that means "do not
          filter" would be a value no column could ever hold. A status the caller invented falls
          through to null too — the query is a filter, not a lookup, so an unknown value narrowing
          to nothing would answer "no clients" for a typo, which is the one answer this module must
          never give wrongly.
        */
        ClientStatus wanted = switch (status) {
            case "ACTIVE" -> ClientStatus.ACTIVE;
            case "ARCHIVED" -> ClientStatus.ARCHIVED;
            default -> null;
        };
        return read(
                clients.list(caller(jwt), wanted, accountManagerId),
                "You do not have permission to see this company's clients.");
    }

    @GetMapping("/clients/{clientId}")
    public ResponseEntity<Object> read(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID clientId) {

        return read(
                clients.read(caller(jwt), clientId),
                "You do not have permission to see this company's clients.");
    }

    @PostMapping("/clients")
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ClientWriteRequest body) {

        return answer(
                clients.create(caller(jwt), body),
                HttpStatus.CREATED,
                "You do not have permission to record a client.");
    }

    @PutMapping("/clients/{clientId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID clientId,
            @RequestBody ClientWriteRequest body) {

        return answer(
                clients.update(caller(jwt), clientId, body),
                HttpStatus.OK,
                "You do not have permission to edit a client.");
    }

    @DeleteMapping("/clients/{clientId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID clientId) {

        return answer(
                clients.delete(caller(jwt), clientId),
                HttpStatus.NO_CONTENT,
                "You do not have permission to delete a client.");
    }

    @PostMapping("/clients/{clientId}/contacts")
    public ResponseEntity<Object> addContact(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID clientId,
            @RequestBody ClientContactWriteRequest body) {

        return answer(
                clients.addContact(caller(jwt), clientId, body),
                HttpStatus.CREATED,
                "You do not have permission to edit a client's contacts.");
    }

    @PutMapping("/clients/{clientId}/contacts/{contactId}")
    public ResponseEntity<Object> updateContact(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID clientId,
            @PathVariable UUID contactId,
            @RequestBody ClientContactWriteRequest body) {

        return answer(
                clients.updateContact(caller(jwt), clientId, contactId, body),
                HttpStatus.OK,
                "You do not have permission to edit a client's contacts.");
    }

    @DeleteMapping("/clients/{clientId}/contacts/{contactId}")
    public ResponseEntity<Object> removeContact(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID clientId,
            @PathVariable UUID contactId) {

        return answer(
                clients.removeContact(caller(jwt), clientId, contactId),
                HttpStatus.NO_CONTENT,
                "You do not have permission to edit a client's contacts.");
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static <T> ResponseEntity<Object> read(GateResult<T> gate, String forbidden) {
        return switch (gate) {
            case GateResult.Unreachable<T> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<T> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<T> allowed -> ResponseEntity.ok(allowed.value());
        };
    }

    /**
     * One mapping for all five writes, because their outcomes are one sealed set.
     *
     * <p>The exhaustive switch is what makes a sixth outcome a compile error here rather than a
     * silent fall-through to a 200 with the wrong body.
     */
    private static <T> ResponseEntity<Object> answer(
            GateResult<ClientService.Outcome<T>> gate, HttpStatus written, String forbidden) {

        return switch (gate) {
            case GateResult.Unreachable<ClientService.Outcome<T>> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<ClientService.Outcome<T>> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<ClientService.Outcome<T>> allowed -> switch (allowed.value()) {
                case ClientService.Outcome.Invalid<T> invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case ClientService.Outcome.Missing<T> ignored -> ResponseEntity.notFound().build();
                case ClientService.Outcome.Conflict<T> clash -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict(clash.title(), clash.detail()));
                case ClientService.Outcome.Written<T> stored ->
                        ResponseEntity.status(written).body(stored.value());
                case ClientService.Outcome.Removed<T> ignored -> ResponseEntity.noContent().build();
            };
        };
    }
}
