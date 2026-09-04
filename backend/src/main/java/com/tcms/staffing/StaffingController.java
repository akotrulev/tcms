package com.tcms.staffing;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company-scope staffing surface — {@code /staffing/*}.
 *
 * <p>Thin, as every controller here is: resolve the caller from the session, delegate, map the
 * sealed outcome. <strong>The company is never a parameter</strong> — it comes from the
 * authenticated session and {@code CompanyAwareTransactionManager} puts it on the connection.
 *
 * <p>{@code userId} in a path segment is the <em>subject</em> of the read or write, never the
 * caller: the caller is {@code jwt.getSubject()} and nothing else. The services compare the two
 * where the rule is "somebody else's needs a key, your own does not" (017 D-h).
 */
@RestController
@RequestMapping("/api/v1")
public class StaffingController {

    private final StaffingService staffing;
    private final ProfileService profiles;

    public StaffingController(StaffingService staffing, ProfileService profiles) {
        this.staffing = staffing;
        this.profiles = profiles;
    }

    @GetMapping("/staffing/people")
    public ResponseEntity<Object> people(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "skill", required = false) List<UUID> skill,
            @RequestParam(name = "freeFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate freeFrom) {

        return read(
                staffing.directory(caller(jwt), from, to, skill, freeFrom),
                "You do not have permission to see this company's skills directory.");
    }

    @GetMapping("/staffing/people/{userId}")
    public ResponseEntity<Object> person(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return read(
                staffing.person(caller(jwt), userId, from, to),
                "You do not have permission to read somebody else's staffing.");
    }

    @PutMapping("/staffing/people/{userId}/profile")
    public ResponseEntity<Object> putProfile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @RequestBody PersonProfileInput submitted) {

        return answer(
                profiles.putProfile(caller(jwt), userId, submitted),
                HttpStatus.OK,
                "You do not have permission to maintain staffing profiles.");
    }

    @PutMapping("/staffing/people/{userId}/skills")
    public ResponseEntity<Object> putSkills(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @RequestBody PersonSkillsInput submitted) {

        return answer(
                profiles.putSkills(caller(jwt), userId, submitted),
                HttpStatus.OK,
                "You do not have permission to maintain people's skills.");
    }

    @GetMapping("/staffing/people/{userId}/absences")
    public ResponseEntity<Object> absences(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return read(
                profiles.listAbsences(caller(jwt), userId, from, to),
                "You do not have permission to read somebody else's absences.");
    }

    @PostMapping("/staffing/people/{userId}/absences")
    public ResponseEntity<Object> recordAbsence(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @RequestBody AbsenceInput submitted) {

        return answer(
                profiles.createAbsence(caller(jwt), userId, submitted),
                HttpStatus.CREATED,
                "You do not have permission to record absence on somebody else's behalf.");
    }

    @DeleteMapping("/staffing/people/{userId}/absences/{absenceId}")
    public ResponseEntity<Object> removeAbsence(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @PathVariable UUID absenceId) {

        return answer(
                profiles.deleteAbsence(caller(jwt), userId, absenceId),
                HttpStatus.NO_CONTENT,
                "You do not have permission to remove somebody else's absence.");
    }

    @GetMapping("/staffing/skills")
    public ResponseEntity<Object> skills(@AuthenticationPrincipal Jwt jwt) {
        return read(
                profiles.listSkills(caller(jwt)),
                "You do not have permission to see this company's skills.");
    }

    @PostMapping("/staffing/skills")
    public ResponseEntity<Object> addSkill(
            @AuthenticationPrincipal Jwt jwt, @RequestBody SkillInput submitted) {

        return answer(
                profiles.createSkill(caller(jwt), submitted),
                HttpStatus.CREATED,
                "You do not have permission to add to this company's skill vocabulary.");
    }

    @GetMapping("/staffing/capacity")
    public ResponseEntity<Object> capacity(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return read(
                staffing.capacity(caller(jwt), from, to),
                "You do not have permission to see this company's capacity.");
    }

    /**
     * {@code GET /staffing/capacity/me} — no permission at all, so no gate and no 403.
     *
     * <p>The only validation is the one the contract declares a {@code 400} for: a range that runs
     * backwards. Everything else about the window is decided by {@link StaffingWindow}, including
     * the ceiling — the other three reads declare no 400 and therefore have no honest way to refuse
     * an enormous range, so all four clamp rather than one of them refusing.
     */
    @GetMapping("/staffing/capacity/me")
    public ResponseEntity<Object> myCapacity(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "from")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (StaffingWindow.isBackwards(from, to)) {
            return ResponseEntity.badRequest().body(Problem.validation(Map.of(
                    "to", List.of("The end of the window cannot be before its start."))));
        }
        return ResponseEntity.ok(staffing.myCapacity(caller(jwt), from, to));
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
     * <p>The exhaustive switch is what makes a new outcome a compile error here rather than a silent
     * fall-through to a 200 with the wrong body.
     */
    private static <T> ResponseEntity<Object> answer(
            GateResult<ProfileService.Outcome<T>> gate, HttpStatus written, String forbidden) {

        return switch (gate) {
            case GateResult.Unreachable<ProfileService.Outcome<T>> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<ProfileService.Outcome<T>> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<ProfileService.Outcome<T>> allowed -> switch (allowed.value()) {
                case ProfileService.Outcome.Invalid<T> invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case ProfileService.Outcome.Conflict<T> clash -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict(clash.title(), clash.detail()));
                case ProfileService.Outcome.Written<T> stored ->
                        ResponseEntity.status(written).body(stored.value());
                case ProfileService.Outcome.Removed<T> ignored -> ResponseEntity.noContent().build();
            };
        };
    }
}
