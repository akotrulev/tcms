package com.tcms.staffing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.api.PatchBody;
import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The project-scope staffing surface — {@code /projects/{projectId}/staffing} and its allocations.
 *
 * <p>Thin, like its sibling. The project is the path's and the company is the session's; neither is
 * ever read from a body.
 */
@RestController
@RequestMapping("/api/v1")
public class AllocationController {

    private final AllocationService allocations;
    private final ObjectMapper mapper;

    public AllocationController(AllocationService allocations, ObjectMapper mapper) {
        this.allocations = allocations;
        this.mapper = mapper;
    }

    @GetMapping("/projects/{projectId}/staffing")
    public ResponseEntity<Object> staffing(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        GateResult<ProjectStaffingView> gate =
                allocations.staffingOf(caller(jwt), projectId, from, to);

        return switch (gate) {
            case GateResult.Unreachable<ProjectStaffingView> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<ProjectStaffingView> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(
                            "You do not have permission to see this project's staffing."));
            case GateResult.Allowed<ProjectStaffingView> allowed ->
                    ResponseEntity.ok(allowed.value());
        };
    }

    @PostMapping("/projects/{projectId}/allocations")
    public ResponseEntity<Object> allocate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody AllocationInput submitted) {

        return answer(
                allocations.create(caller(jwt), projectId, submitted),
                HttpStatus.CREATED,
                "You do not have permission to allocate people to this project.");
    }

    /**
     * Bound as a node <em>and</em> as the record, so the service can ask what was actually sent.
     *
     * <p>The record alone cannot answer it, which is why a delta edit against an endpoint without
     * this used to empty four columns and answer 200 (B1). A body that cannot be read as the record
     * is caught here rather than by a global handler, because the answer has to be this API's
     * {@code Problem} — a 400 whose body is a different shape is one no client parses.
     */
    @PatchMapping("/projects/{projectId}/allocations/{allocationId}")
    public ResponseEntity<Object> amend(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID allocationId,
            @RequestBody JsonNode body) {

        PatchBody patch = new PatchBody(body);
        AllocationPatch submitted;
        try {
            submitted = patch.as(mapper, AllocationPatch.class);
        } catch (PatchBody.MalformedBody unreadable) {
            return ResponseEntity.badRequest().body(Problem.validation(Map.of(
                    "body", List.of("The request body could not be read as an allocation."))));
        }

        return answer(
                allocations.update(caller(jwt), projectId, allocationId, submitted, patch),
                HttpStatus.OK,
                "You do not have permission to change this project's allocations.");
    }

    @DeleteMapping("/projects/{projectId}/allocations/{allocationId}")
    public ResponseEntity<Object> remove(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID allocationId) {

        return answer(
                allocations.delete(caller(jwt), projectId, allocationId),
                HttpStatus.NO_CONTENT,
                "You do not have permission to change this project's allocations.");
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * One mapping for all three writes, because their outcomes are one sealed set.
     *
     * <p>{@link AllocationService.Outcome.Overlaps} is 017 D-n's 409 and it <strong>names the range
     * it collided with</strong>: "already allocated here" with no dates is a refusal the caller
     * cannot act on, and the whole reason the server refuses rather than merging is that somebody
     * typed a number and is owed the chance to move it.
     */
    private static ResponseEntity<Object> answer(
            GateResult<AllocationService.Outcome> gate, HttpStatus written, String forbidden) {

        return switch (gate) {
            case GateResult.Unreachable<AllocationService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<AllocationService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<AllocationService.Outcome> allowed -> switch (allowed.value()) {
                case AllocationService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case AllocationService.Outcome.Overlaps clash -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("Already allocated over those days",
                                "This person is already on this project from "
                                        + clash.startsOn() + " to " + clash.endsOn()
                                        + ". One person has one bar per project, so change that "
                                        + "allocation instead of adding a second one — merging "
                                        + "them silently would change a number somebody typed."));
                case AllocationService.Outcome.NoSuchAllocation ignored ->
                        ResponseEntity.notFound().build();
                case AllocationService.Outcome.Written stored ->
                        ResponseEntity.status(written).body(stored.view());
                case AllocationService.Outcome.Removed ignored ->
                        ResponseEntity.noContent().build();
            };
        };
    }
}
