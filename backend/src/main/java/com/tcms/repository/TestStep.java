package com.tcms.repository;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One step of one case — the thing a tester actually reads while executing.
 *
 * <h2>{@link #id} is stable, and that is the most expensive property in this class</h2>
 *
 * <p>{@code step_result} FKs here, so evidence is anchored to the step it was recorded against.
 * Insert a step at position 2, or drag step 5 above step 3, and every past result still points at
 * the step it was actually about — because {@link #position} moves and the id does not (plan §6m,
 * handoff §3 lesson 2). The alternative shape, where a step is identified by its ordinal, silently
 * reattributes every screenshot in the project's history the first time anyone reorders anything.
 *
 * <p>It is why {@code position} is a plain mutable column and the id is {@code updatable = false},
 * and why the editor's step reordering will be an update of positions rather than a delete and
 * re-insert. Nothing writes steps yet; the constraint is recorded here because the writer that
 * takes the cheap route is the one that destroys the history.
 *
 * <h2>Not mapped as a {@code @OneToMany} from {@link TestCase}</h2>
 *
 * <p>Same choice as every other edge in this module. A mapped collection would make
 * {@code testCase.getSteps()} a field access and would load the steps of every case in a 200-row
 * page the moment anything touched it — the N+1 that costs nothing to write and everything to
 * find. The detail endpoint reads one case, so it asks for one case's steps, by id.
 *
 * <h2>The two block arguments, mapped at last</h2>
 *
 * <p>{@link #docstring} and {@link #datatable} are Gherkin's block arguments. Both existed in
 * {@code V1} and neither had a field on the contract's {@code TestStep}, so mapping them would have
 * put values on an entity nothing could serialize and no writer could set — which is why they were
 * left off until §255 gave them somewhere to go. That slice is also what makes {@code stepFormat} a
 * <em>lens</em> rather than a storage switch: a Gherkin case is step rows now, so its doc strings
 * and tables have to live on the rows.
 *
 * <p><strong>At most one of {@link #testData}, {@link #docstring} and {@link #datatable} is
 * non-null</strong>, which {@code ck_test_step_one_data} enforces and {@link CaseEdit} answers with
 * a field error first. A step holding two leaves the execution screen to pick which one the tester
 * follows.
 */
@Entity
@Table(name = "test_step")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TestStep {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    /** Sibling order within one case. Moves on reorder; {@link #id} does not. */
    @Column(name = "position", nullable = false)
    private int position;

    /** Null ⇒ a structured step. See {@link StepKeyword} — the absence is the format. */
    @Enumerated(EnumType.STRING)
    @Column(name = "keyword")
    private StepKeyword keyword;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "test_data")
    private String testData;

    @Column(name = "expected_result")
    private String expectedResult;

    /** Gherkin's {@code """} block — a multi-line string argument to this one step. */
    @Column(name = "docstring")
    private String docstring;

    /**
     * Gherkin's {@code |}-table as rows of cells.
     *
     * <p>{@code @JdbcTypeCode(JSON)} rather than {@code columnDefinition = "jsonb"}, which is
     * {@link Condition#getBody()}'s mapping one level of nesting deeper and for its reason:
     * {@code columnDefinition} satisfies {@code ddl-auto: validate} while the parameter is still
     * bound as {@code varchar}, and Postgres does not implicitly cast {@code varchar} to
     * {@code jsonb}. Rows may be ragged — nothing here requires a rectangle.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datatable")
    private List<List<String>> datatable;

    /** JPA only. */
    protected TestStep() {
    }

    /**
     * A new step on a case.
     *
     * <p>The id is generated here and is <strong>the value every future result will reference</strong>
     * — so it is assigned once, at creation, and never reassigned. {@code companyId} comes from
     * {@link CompanyContext} rather than from an argument, per ERD §0.1 point 2: a company taken from
     * a parameter is one a future call site can pass wrong.
     *
     * @param position the caller's index in the submitted list, not a value from the request — see
     *                 {@link CaseSteps}
     */
    static TestStep of(UUID caseId, int position, CaseEdit.StepEdit edit) {
        TestStep step = new TestStep();
        step.id = UUID.randomUUID();
        step.companyId = CompanyContext.require();
        step.caseId = caseId;
        step.applyEdit(position, edit);
        return step;
    }

    /**
     * Updates this step in place, <strong>keeping its id</strong>.
     *
     * <p>That is the whole point, and it is why {@link CaseSteps} updates rather than replaces:
     * {@code step_result} references {@link #id}, so an edited step keeps every result recorded
     * against it and a reordered one keeps them attached to the right action. {@code caseId} is
     * {@code updatable = false} at the mapping level, so no edit can move a step between cases.
     */
    void applyEdit(int position, CaseEdit.StepEdit edit) {
        this.position = position;
        this.keyword = edit.keyword();
        this.action = edit.action();
        this.testData = edit.testData();
        this.expectedResult = edit.expectedResult();
        this.docstring = edit.docString();
        this.datatable = edit.dataTable();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public int getPosition() {
        return position;
    }

    public StepKeyword getKeyword() {
        return keyword;
    }

    public String getAction() {
        return action;
    }

    public String getTestData() {
        return testData;
    }

    public String getExpectedResult() {
        return expectedResult;
    }

    public String getDocstring() {
        return docstring;
    }

    public List<List<String>> getDatatable() {
        return datatable;
    }
}
