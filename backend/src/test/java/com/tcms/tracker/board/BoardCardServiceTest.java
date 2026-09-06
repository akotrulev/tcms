package com.tcms.tracker.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.Priority;
import com.tcms.tracker.TrackerEndpointTestBase;
import com.tcms.tracker.WorkflowService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * The board's contents and the backlog — the lanes, the filter, and the two rows that are not rows.
 *
 * <p>The filter semantics are Trello's and are the reason this class exists rather than an endpoint
 * smoke test: <strong>OR within a category, AND across categories</strong> (§12.2). Getting that
 * backwards produces a board that answers plausibly and wrongly, and nothing fails.
 */
@AutoConfigureMockMvc
@DisplayName("Board contents and the backlog — lanes, the Trello filter, and what is not a row")
class BoardCardServiceTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("bf200000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("bf201000-0000-4000-8000-000000000001");

    /** Project Manager — the tier holding {@code board:configure} (§A3). */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID MANAGER = UUID.fromString("bf202000-0000-4000-8000-000000000001");
    private static final UUID DEV = UUID.fromString("bf202000-0000-4000-8000-000000000002");
    private static final UUID MANAGER_GRANT = UUID.fromString("bf203000-0000-4000-8000-000000000001");
    private static final UUID DEV_GRANT = UUID.fromString("bf203000-0000-4000-8000-000000000002");

    @Autowired
    private BoardCardService cards;

    @Autowired
    private LabelService labels;

    @Autowired
    private BoardConfigService boardConfig;

    @Autowired
    private WorkflowService workflow;

    private int nextSeq;
    private int nextCase;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "board-cards-acme", "Acme", now);
            activeUser(MANAGER, "pm@board-cards.test", "Paula Manager", now);
            activeUser(DEV, "dev@board-cards.test", "Dana Dev", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "CARD", "Board Cards Project", now);
            member(ACME, MANAGER, "Paula Manager", now);
            member(ACME, DEV, "Dana Dev", now);
        }));

        grantOnProject(MANAGER_GRANT, ACME, PM_ROLE, MANAGER, PROJECT);
        grantOnProject(DEV_GRANT, ACME, PM_ROLE, DEV, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearLabels();
        clearIssues(List.of(ACME));
        clearCases();
        restoreBoards();
        nextSeq = 1;
        nextCase = 1;
    }

    /** The board rows go FIRST, for D-p's reason — the seed function's board block skips silently. */
    @AfterEach
    void putEverythingBack() throws SQLException {
        clearLabels();
        clearIssues(List.of(ACME));
        clearCases();
        restoreBoards();
    }

    @Test
    @DisplayName("NONE puts every card in one lane, in rank order")
    void oneLaneWhenTheBoardHasNoSwimlaneAxis() throws SQLException {
        String first = story("first", null, null, Priority.MEDIUM);
        String second = story("second", null, null, Priority.MEDIUM);

        BoardContentsView contents = contents(defaultBoard(), BoardCardService.Filter.none());

        assertThat(contents.lanes()).hasSize(1);
        assertThat(contents.lanes().get(0).key()).isNull();
        assertThat(keysOf(contents)).containsExactly(first, second);
    }

    @Test
    @DisplayName("the epic lanes come first and the No-epic lane is last")
    void theCatchAllLaneIsLast() throws SQLException {
        // A story without an epic is a normal story rather than an error, which is what the screen
        // says in words — so its lane is a lane and not a warning at the top.
        String epic = epic("Payments");
        story("under the epic", epic, null, Priority.MEDIUM);
        story("no epic at all", null, null, Priority.MEDIUM);

        BoardContentsView contents = contents(swimlaned(SwimlaneBy.EPIC), BoardCardService.Filter.none());

        assertThat(contents.lanes()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(contents.lanes().get(contents.lanes().size() - 1).key()).isNull();
        assertThat(contents.lanes().get(contents.lanes().size() - 1).label()).isEqualTo("No epic");
    }

    @Test
    @DisplayName("a lane's completion is D8's number, over the lane's own cards")
    void aLaneCarriesItsRollUp() throws SQLException {
        String epic = epic("Payments");
        String done = story("shipped", epic, null, Priority.MEDIUM);
        story("still going", epic, null, Priority.MEDIUM);
        moveToDone(done);

        BoardContentsView contents =
                contents(swimlaned(SwimlaneBy.EPIC), BoardCardService.Filter.none());

        BoardLaneView lane = contents.lanes().stream()
                .filter(row -> "Payments".equals(row.label()))
                .findFirst()
                .orElseThrow();
        assertThat(lane.completion().total()).isEqualTo(2);
        assertThat(lane.completion().done()).isEqualTo(1);
        assertThat(lane.completion().percent()).isEqualTo(50);
        assertThat(lane.completion().basis())
                .as("the basis travels beside the number, always (D8) — never inferred by a client")
                .isEqualTo("COUNT");
    }

    @Test
    @DisplayName("two labels and one assignee is (a or b) and c")
    void filtersAreOrWithinACategoryAndAndAcrossThem() throws SQLException {
        // §12.2, verbatim, and worth copying precisely because it is learnable.
        UUID red = label("red");
        UUID blue = label("blue");
        UUID green = label("green");

        String redAndMine = story("red, mine", null, DEV, Priority.MEDIUM);
        String blueAndMine = story("blue, mine", null, DEV, Priority.MEDIUM);
        String greenAndMine = story("green, mine", null, DEV, Priority.MEDIUM);
        String redAndTheirs = story("red, theirs", null, MANAGER, Priority.MEDIUM);
        put(redAndMine, red);
        put(blueAndMine, blue);
        put(greenAndMine, green);
        put(redAndTheirs, red);

        BoardContentsView contents = contents(defaultBoard(), new BoardCardService.Filter(
                List.of(red, blue), List.of(DEV), null, null, List.of(), false, null, false));

        assertThat(keysOf(contents)).containsExactlyInAnyOrder(redAndMine, blueAndMine);
    }

    @Test
    @DisplayName("a card carries its labels, its sub-task counts and its legal moves")
    void theCardFaceCarriesWhatTheMockupReads() throws SQLException {
        UUID red = label("red");
        String parent = story("a story with sub-tasks", null, DEV, Priority.HIGH);
        String childDone = task("done bit", parent);
        task("open bit", parent);
        moveToDone(childDone);
        put(parent, red);

        BoardIssueCardView card = cardFor(defaultBoard(), parent);

        assertThat(card.labels()).extracting(LabelView::name).containsExactly("red");
        assertThat(card.subtaskTotal()).isEqualTo(2);
        assertThat(card.subtaskDone()).isEqualTo(1);
        assertThat(card.priority()).isEqualTo(Priority.HIGH);
        assertThat(card.assignee().displayName()).isEqualTo("Dana Dev");
        assertThat(card.allowedTransitions())
                .as("""
                        §12.4: the legal set must be known client-side BEFORE the drag starts, so a \
                        forbidden drop is refused at the drag with the reason rather than accepted \
                        and reverted.""")
                .extracting(IssueStatusView::name)
                .contains("Refining");
    }

    @Test
    @DisplayName("an Epic's card shows its STORED status and offers the workflow's moves (021 R10)")
    void anEpicCardIsStoredAndMovable() throws SQLException {
        // ~~D31: an epic reads its derived status and offers no move.~~ → inverted by 021 R10. The
        // sentence that survives is the one about agreement — an epic reading one thing on the
        // board and another on its detail page is the disagreement, whichever value is right — and
        // there is now ONE value, so the two cannot differ at all.
        String epic = epic("Payments");
        String only = story("the only story", epic, null, Priority.MEDIUM);
        moveToDone(only);
        moveTo(epic, "Ready to develop");

        BoardIssueCardView card = cardFor(defaultBoard(), epic);

        assertThat(card.typeLevel()).isEqualTo(IssueLevel.EPIC);
        assertThat(card.status().name())
                .as("where the PO left it, not where its stories are")
                .isEqualTo("Ready to develop");
        assertThat(card.allowedTransitions())
                .as("the workflow's set, like any other card's — the PO drags it to ship it")
                .isNotEmpty();
    }

    @Test
    @DisplayName("an epic whose stories are all done is NUDGED, never moved (021 D-d)")
    void anEpicWhoseStoriesAreAllDoneIsNudged() throws SQLException {
        // §11: the product automates no transition. "All the stories are done" and "this body of
        // work is finished" are different claims and only a person can make the second, so the
        // flag is advice on the card and the epic stays where the PO left it.
        //
        // The flag only became EXPRESSIBLE with R10. It used to compare the epic's derived status
        // against the very stories the status was derived FROM, so "all done and not itself done"
        // was arithmetically unreachable and the value could only ever be false.
        String epic = epic("Payments");
        String only = story("the only story", epic, null, Priority.MEDIUM);
        moveToDone(only);
        moveTo(epic, "Ready to develop");

        BoardIssueCardView card = cardFor(defaultBoard(), epic);

        assertThat(card.allStoriesDone()).isTrue();
        assertThat(card.status().name()).isEqualTo("Ready to develop");
    }

    @Test
    @DisplayName("an epic that has been shipped is not nudged to ship again")
    void aDoneEpicIsNotNudged() throws SQLException {
        String epic = epic("Payments");
        moveToDone(story("the only story", epic, null, Priority.MEDIUM));
        moveToDone(epic);

        assertThat(cardFor(defaultBoard(), epic).allStoriesDone()).isFalse();
    }

    @Test
    @DisplayName("an epic with no stories at all is not nudged")
    void anEmptyEpicIsNotNudged() throws SQLException {
        // The vacuous truth, from the board's side: allMatch is true of an empty list, so a naive
        // read puts "ready to close" on every epic the moment somebody creates one.
        assertThat(cardFor(defaultBoard(), epic("Payments")).allStoriesDone()).isFalse();
    }

    @Test
    @DisplayName("the backlog is STORY and TASK level, not done, numbered from one in rank order")
    void theBacklogLeavesOutEpicsAndDoneWork() throws SQLException {
        // An Epic has nothing to be ranked AGAINST (D31), and a list carrying both an epic and the
        // stories under it double-counts the same work in the one view whose job is "what is next".
        String epic = epic("Payments");
        String first = story("first", epic, null, Priority.MEDIUM);
        String second = story("second", epic, null, Priority.MEDIUM);
        String finished = story("finished", epic, null, Priority.MEDIUM);
        moveToDone(finished);

        List<BacklogRowView> rows = backlog(BoardCardService.Filter.none());

        assertThat(rows).extracting(BacklogRowView::key).containsExactly(first, second);
        assertThat(rows).extracting(BacklogRowView::position).containsExactly(1, 2);
        assertThat(rows).extracting(BacklogRowView::epicKey).containsOnly(epic);
    }

    @Test
    @DisplayName("the WIP breach is counted over the project, not over the filtered view")
    void theBreachIgnoresTheFilter() throws SQLException {
        // A WIP limit is a fact about how much is in flight. Narrowing the view does not take work
        // out of a column, so a filter that hid the breach would hide the thing the header exists
        // to shout about.
        UUID board = boardWithNewLimit(1);
        UUID red = label("red");
        String tagged = story("tagged", null, null, Priority.MEDIUM);
        story("untagged", null, null, Priority.MEDIUM);
        put(tagged, red);

        BoardContentsView filtered = contents(board, new BoardCardService.Filter(
                List.of(red), List.of(), null, null, List.of(), false, null, false));

        assertThat(keysOf(filtered)).containsExactly(tagged);
        assertThat(filtered.breaches()).hasSize(1);
        assertThat(filtered.breaches().get(0).count()).isEqualTo(2);
        assertThat(filtered.breaches().get(0).limit()).isEqualTo(1);
    }

    @Test
    @DisplayName("a task card carries its story's key as parentKey, and a story its epic's")
    void aCardCarriesItsImmediateParentsKey() throws SQLException {
        // parentKey is the IMMEDIATE parent and not epicKey, which is the lane (021 §2.5): the row
        // a card belongs under is its parent's, one level up, and for a task that is its story
        // rather than the epic two above it. An implementation that answered epicKey for both would
        // be green on every story and wrong on every task, which is the half the row header needs.
        String epic = epic("Payments");
        String story = story("Guest checkout", epic, null, Priority.MEDIUM);
        String task = task("the form", story);

        assertThat(cardFor(defaultBoard(), task).parentKey()).isEqualTo(story);
        assertThat(cardFor(defaultBoard(), story).parentKey()).isEqualTo(epic);
        assertThat(cardFor(defaultBoard(), epic).parentKey())
                .as("an epic is top level; there is no level above one")
                .isNull();
    }

    @Test
    @DisplayName("a story row counts its not-shown-here tasks under elsewhere, never total minus shown")
    void elsewhereCountsTheNotShownHereSetAndNotTheDifference() throws SQLException {
        // 021 §2.5's arithmetic point, and the one an implementation gets wrong by subtracting.
        // Ready for test is claimed by BOTH the Dev and QA boards — that is what a hand-off column
        // is — so on the Dev board a task in it is SHOWN HERE and also on the QA board. Counting
        // "total minus shown" would report that task as being somewhere else; counting the tasks
        // this board does not claim reports only the one that really is.
        String story = story("Guest checkout", null, null, Priority.MEDIUM);
        String shownOnBoth = task("shown on Dev and QA", story);
        String onlyOnQa = task("only on QA", story);
        // The story itself has to be in a status the Dev board claims, or it is not on that board
        // at all — a board claims a subset (021 R7), and New is the PM board's.
        moveTo(story, "In progress");
        moveTo(shownOnBoth, "Ready for test");
        moveTo(onlyOnQa, "In test");

        BoardIssueCardView onDev = cardFor(boardNamed("Dev board"), story);

        assertThat(onDev.subtaskTotal())
                .as("two tasks in total, one of which the Dev board shows")
                .isEqualTo(2);
        assertThat(onDev.elsewhere()).hasSize(1);
        assertThat(onDev.elsewhere().get(0).boardName()).isEqualTo("QA board");
        assertThat(onDev.elsewhere().get(0).count())
                .as("""
                        ONE, not two. Total minus shown would say one as well here only by \
                        accident; make the story's story-row card itself invisible and the two \
                        diverge. The set counted is the tasks in a status this board does not \
                        claim — In test — and never the difference of two totals.""")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a card whose every task is shown here carries an empty elsewhere")
    void elsewhereIsEmptyWhenEveryDescendantIsShownHere() throws SQLException {
        // The ordinary case, and the one a row header draws no link for. Asserting it is what stops
        // an implementation that lists every other board with a zero count from passing.
        String story = story("Guest checkout", null, null, Priority.MEDIUM);
        task("still to be picked up", story);

        assertThat(cardFor(defaultBoard(), story).elsewhere()).isEmpty();
    }

    @Test
    @DisplayName("a task carries its story's linked cases, and an epic the union of its stories'")
    void aTaskBorrowsItsStorysSummaryAndAnEpicRollsTheSubtreeUp() throws SQLException {
        // 021 D-k, and the two halves an implementation gets wrong separately.
        //
        // THE TASK. Linking is done on the story (H7) and the QA board is made of task cards, so a
        // task computing only its OWN links would leave the one board that needs the summary
        // showing nothing at all. It borrows its parent's.
        //
        // THE EPIC. `theShared` verifies BOTH stories, which is ordinary — one case can prove two
        // slices of the same feature. An epic that SUMMED its stories' summaries would report five
        // cases where four exist, and a reader could not reconcile the epic against the stories it
        // came from. The roll-up is a union of case ids.
        String epic = epic("Payments");
        String first = story("Guest checkout", epic, null, Priority.MEDIUM);
        String second = story("Saved cards", epic, null, Priority.MEDIUM);
        // TASK level is `Sub-task` and NOT the type called `Task`, which V13 D29 seeds at STORY
        // level so a solo project never needs an Epic and a Story before "a thing to do". Both are
        // here because the borrow is keyed on the LEVEL: `Sub-task` takes its story's summary and
        // `Task` — a story-level unit of work that carries its own links — does not.
        String subtask = subtask("the form", first);
        String storyLevelTask = task("a Task, which is STORY level", first);

        UUID onlyFirst = testCase("the guest reaches the card form");
        UUID shared = testCase("the total is right");
        UUID onlySecond = testCase("a saved card is offered");
        UUID neverRun = testCase("nobody has run this one");
        lastResult(onlyFirst, "PASSED");
        lastResult(shared, "FAILED");
        lastResult(onlySecond, "BLOCKED");

        linkCase(first, onlyFirst);
        linkCase(first, shared);
        linkCase(second, shared);
        linkCase(second, onlySecond);
        linkCase(second, neverRun);

        UUID board = defaultBoard();
        assertThat(cardFor(board, first).linkedCases())
                .isEqualTo(new LinkedCasesSummaryView(2, 1, 1, 0, 0));
        assertThat(cardFor(board, second).linkedCases())
                .isEqualTo(new LinkedCasesSummaryView(3, 0, 1, 1, 1));
        assertThat(cardFor(board, subtask).linkedCases())
                .as("a sub-task has no links of its own and shows its parent story's")
                .isEqualTo(cardFor(board, first).linkedCases());
        assertThat(cardFor(board, storyLevelTask).linkedCases())
                .as("""
                        A STORY-level Task borrows nothing: it is a unit of work in its own right \
                        and links go on it directly, so an empty one is empty. Keying the borrow \
                        on "has a parent" rather than on the level would make this the story's \
                        summary and put a coverage figure on a card nobody linked a case to.""")
                .isNull();
        assertThat(cardFor(board, epic).linkedCases())
                .as("""
                        FOUR distinct cases, not the five that summing the two stories would \
                        report: the shared case is one case however many stories claim it.""")
                .isEqualTo(new LinkedCasesSummaryView(4, 1, 1, 1, 1));
    }

    @Test
    @DisplayName("only PASSED, FAILED and BLOCKED are verdicts; every other kind is notRun")
    void aKindThatIsNotAVerdictLandsInNotRun() throws SQLException {
        // `documentation.BlockCoverageView`'s rule over the same case_health rows, and §46's: a
        // roll-up that could go either way must not go the way that makes the report look better
        // than the truth. SKIPPED, RETEST, UNTESTED and WIP are not verdicts, so they sit with the
        // case nobody has ever run rather than with the ones that passed.
        String story = story("Guest checkout", null, null, Priority.MEDIUM);
        int linked = 0;
        for (String kind : List.of("PASSED", "FAILED", "BLOCKED",
                "SKIPPED", "RETEST", "UNTESTED", "WIP")) {
            UUID id = testCase("last " + kind);
            lastResult(id, kind);
            linkCase(story, id);
            linked++;
        }
        // The eighth has no case_health row at all — the state of every case in a project the
        // recompute has not reached, and the same fact as a null column.
        linkCase(story, testCase("no health row at all"));
        linked++;

        LinkedCasesSummaryView summary = cardFor(defaultBoard(), story).linkedCases();

        assertThat(summary).isEqualTo(new LinkedCasesSummaryView(8, 1, 1, 1, 5));
        assertThat(summary.passing() + summary.failing() + summary.blocked() + summary.notRun())
                .as("the parts must sum to the total, or a reader cannot subtract with them")
                .isEqualTo(summary.total())
                .isEqualTo(linked);
    }

    @Test
    @DisplayName("a card with nothing linked carries null, not a zeroed summary")
    void nothingLinkedIsNullAndNeverZero() throws SQLException {
        // The contract's key is always present. A card reading "0 of 0 passing" claims coverage was
        // measured where none was ever asked for, and the whole point of the nullable key is that a
        // consumer can tell the two apart.
        String story = story("nothing linked", null, null, Priority.MEDIUM);

        assertThat(cardFor(defaultBoard(), story).linkedCases()).isNull();
    }

    @Test
    @DisplayName("a link naming a case that no longer exists is not counted")
    void aDanglingLinkIsNotCounted() throws SQLException {
        // issue_link.target_id carries no foreign key (scope §3), so a link can outlive the case it
        // names. Counting it would tell a card it has two cases when it has one, and the phantom
        // would be permanently not-run — a coverage figure that can only ever get worse.
        String story = story("Guest checkout", null, null, Priority.MEDIUM);
        UUID live = testCase("a case that exists");
        lastResult(live, "PASSED");
        linkCase(story, live);
        linkCase(story, UUID.fromString("bf209000-0000-4000-8000-0000000000ff"));

        assertThat(cardFor(defaultBoard(), story).linkedCases())
                .isEqualTo(new LinkedCasesSummaryView(1, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("hasAcceptanceCriteria is the readiness signal, and the criteria stay off the card")
    void theCardSaysWhetherItHasAcceptanceCriteria() throws SQLException {
        // 021 §2.6 — the PM board's readiness signal. The array itself stays off the card: it is
        // the detail page's, and a board that shipped every criterion of every card would ship a
        // paragraph per card to render a dot.
        String without = story("nothing agreed yet", null, null, Priority.MEDIUM);
        String with = story("agreed", null, null, Priority.MEDIUM);
        giveAcceptanceCriteria(with);

        assertThat(cardFor(defaultBoard(), without).hasAcceptanceCriteria()).isFalse();
        assertThat(cardFor(defaultBoard(), with).hasAcceptanceCriteria()).isTrue();
    }

    // =====================================================================

    /**
     * The PM board — the first of the three {@code tcms_seed_project_defaults} makes (021 §2.4).
     *
     * <p>It is the one that matters for these fixtures because every issue below is created in
     * {@code New}, and {@code New} is a PM-board status. A board claims a SUBSET now, so "the
     * seeded board" is no longer a phrase that picks out a board showing every card.
     */
    private UUID defaultBoard() {
        return scoped(() -> boardConfig.list(MANAGER, PROJECT).orElseThrow()).get(0).id();
    }

    private UUID boardNamed(String name) {
        return scoped(() -> boardConfig.list(MANAGER, PROJECT).orElseThrow()).stream()
                .filter(board -> board.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no board called " + name))
                .id();
    }

    private UUID swimlaned(SwimlaneBy axis) {
        List<IssueStatusView> statuses = statuses();
        List<BoardColumnInput> columns = new ArrayList<>();
        statuses.forEach(status -> columns.add(new BoardColumnInput(
                null, status.name(), null, List.of(status.id()), status.id())));
        return written(new BoardInput("Lanes", axis, columns));
    }

    private UUID boardWithNewLimit(int limit) {
        List<IssueStatusView> statuses = statuses();
        List<BoardColumnInput> columns = new ArrayList<>();
        statuses.forEach(status -> columns.add(new BoardColumnInput(
                null, status.name(), status.name().equals("New") ? limit : null,
                List.of(status.id()), status.id())));
        return written(new BoardInput("Limited", SwimlaneBy.NONE, columns));
    }

    private UUID written(BoardInput input) {
        GateResult<BoardConfigService.Outcome> gate =
                scoped(() -> boardConfig.create(MANAGER, PROJECT, input));
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        BoardConfigService.Outcome outcome =
                ((GateResult.Allowed<BoardConfigService.Outcome>) gate).value();
        assertThat(outcome).isInstanceOf(BoardConfigService.Outcome.Written.class);
        return ((BoardConfigService.Outcome.Written) outcome).view().id();
    }

    private BoardContentsView contents(UUID boardId, BoardCardService.Filter filter) {
        return scoped(() -> cards.contents(MANAGER, boardId, filter).orElseThrow());
    }

    private List<BacklogRowView> backlog(BoardCardService.Filter filter) {
        return scoped(() -> cards.backlog(MANAGER, PROJECT, filter).orElseThrow());
    }

    private BoardIssueCardView cardFor(UUID boardId, String key) {
        return contents(boardId, BoardCardService.Filter.none()).lanes().stream()
                .flatMap(lane -> lane.cards().stream())
                .filter(card -> card.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no card for " + key));
    }

    private static List<String> keysOf(BoardContentsView contents) {
        return contents.lanes().stream()
                .flatMap(lane -> lane.cards().stream())
                .map(BoardIssueCardView::key)
                .toList();
    }

    private UUID label(String name) {
        GateResult<LabelService.Outcome> gate =
                scoped(() -> labels.create(MANAGER, PROJECT, new LabelInput(name, "label-1")));
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        LabelService.Outcome outcome = ((GateResult.Allowed<LabelService.Outcome>) gate).value();
        assertThat(outcome).isInstanceOf(LabelService.Outcome.Written.class);
        return ((LabelService.Outcome.Written) outcome).view().id();
    }

    private void put(String key, UUID... labelIds) {
        GateResult<LabelService.Outcome> gate = scoped(() -> labels.putIssueLabels(
                MANAGER, key, new IssueLabelsInput(List.of(labelIds))));
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        assertThat(((GateResult.Allowed<LabelService.Outcome>) gate).value())
                .isInstanceOf(LabelService.Outcome.Replaced.class);
    }

    private List<IssueStatusView> statuses() {
        return scoped(() -> workflow.workflow(MANAGER, PROJECT).orElseThrow().statuses());
    }

    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(ACME)) {
            return call.get();
        }
    }

    private String epic(String title) throws SQLException {
        return insert(title, "Epic", null);
    }

    private String story(String title, String parentKey, UUID assignee, Priority priority)
            throws SQLException {

        String key = insert(title, "Story", parentKey);
        if (assignee != null || priority != Priority.MEDIUM) {
            try (Connection connection = appConnection(ACME);
                    PreparedStatement statement = connection.prepareStatement(
                            "UPDATE issue SET assignee_id = CAST(? AS uuid), priority = ? "
                                    + "WHERE issue_key = ?")) {
                statement.setObject(1, assignee);
                statement.setString(2, priority.name());
                statement.setString(3, key);
                statement.executeUpdate();
            }
        }
        return key;
    }

    /**
     * A {@code Task} — which is a <strong>STORY</strong>-level type (V13 D29), not a TASK-level one.
     *
     * <p>The name has been misleading since V13 seeded six types across three levels; {@link
     * #subtask} is what makes a TASK-level issue.
     */
    private String task(String title, String parentKey) throws SQLException {
        return insert(title, "Task", parentKey);
    }

    /** The TASK-level type, and the only seeded one — {@code Sub-task}. */
    private String subtask(String title, String parentKey) throws SQLException {
        return insert(title, "Sub-task", parentKey);
    }

    private String insert(String title, String typeName, String parentKey) throws SQLException {
        int seq = nextSeq++;
        String key = "CARD-" + seq;
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO issue (id, company_id, project_id, key_seq, issue_key,
                                           type_id, status_id, parent_id, title)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                                (SELECT id FROM issue_type
                                  WHERE project_id = CAST(? AS uuid) AND name = ?),
                                (SELECT id FROM issue_status
                                  WHERE project_id = CAST(? AS uuid) AND name = 'New'),
                                (SELECT id FROM issue WHERE issue_key = ?),
                                ?)
                        """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, ACME);
            statement.setObject(3, PROJECT);
            statement.setInt(4, seq);
            statement.setString(5, key);
            statement.setObject(6, PROJECT);
            statement.setString(7, typeName);
            statement.setObject(8, PROJECT);
            statement.setString(9, parentKey);
            statement.setString(10, title);
            statement.executeUpdate();
        }
        return key;
    }

    /**
     * Sets the status directly rather than through {@code TransitionService}.
     *
     * <p>New to Done is not a legal move in the seeded workflow and walking the eight steps would
     * make every fixture here about the workflow instead of about the board.
     */
    private void moveToDone(String key) throws SQLException {
        moveTo(key, "Done");
    }

    /** The same direct write, for a status that is not Done — {@code done_at} stays null. */
    private void moveTo(String key, String statusName) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE issue SET status_id = (SELECT id FROM issue_status
                                                       WHERE project_id = CAST(? AS uuid)
                                                         AND name = ?),
                                         done_at = CASE WHEN ? = 'Done' THEN now() ELSE NULL END
                         WHERE issue_key = ?
                        """)) {
            statement.setObject(1, PROJECT);
            statement.setString(2, statusName);
            statement.setString(3, statusName);
            statement.setString(4, key);
            statement.executeUpdate();
        }
    }

    /** One criterion, written as the stored shape — {@code AcceptanceCriteria} is tracker's codec. */
    private void giveAcceptanceCriteria(String key) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE issue SET acceptance_criteria = CAST(? AS jsonb) "
                                + "WHERE issue_key = ?")) {
            statement.setString(1, """
                    [{"id":"3f1c8a20-0000-4000-8000-000000000001",\
                    "text":"The guest can pay without an account.","done":false,"position":0}]""");
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }

    /**
     * A minimal {@code test_case} row, written straight through the app connection.
     *
     * <p>{@code CaseWriteService} would pull {@code repository}'s whole validation surface into a
     * fixture about board cards; the summary reads three columns of this table, and this writes
     * those plus what the schema requires. {@code ReleaseReadinessTest} makes the same choice one
     * package over.
     */
    private UUID testCase(String title) throws SQLException {
        int seq = nextCase++;
        UUID id = UUID.fromString(String.format("bf209000-0000-4000-8000-%012d", seq));
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO test_case (id, company_id, project_id, key, key_seq, title)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, ACME);
            statement.setObject(3, PROJECT);
            statement.setString(4, "CARDTC-" + seq);
            statement.setLong(5, seq);
            statement.setString(6, title);
            statement.executeUpdate();
        }
        return id;
    }

    /** One {@code TEST_CASE} link — the row {@code PUT /issues/&#123;key&#125;/test-cases} writes. */
    private void linkCase(String issueKey, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO issue_link (company_id, issue_id, target_type, target_id)
                        VALUES (CAST(? AS uuid),
                                (SELECT id FROM issue WHERE issue_key = ?),
                                'TEST_CASE', CAST(? AS uuid))
                        """)) {
            statement.setObject(1, ACME);
            statement.setString(2, issueKey);
            statement.setObject(3, caseId);
            statement.executeUpdate();
        }
    }

    /**
     * One {@code case_health} row, written directly.
     *
     * <p>{@code repository} recomputes that table from executions on a tick, and driving a run
     * through {@code execution} to produce one would make this class a test of the ingestion path.
     * What the card summary reads is the read model, so the read model is what the fixture writes.
     */
    private void lastResult(UUID caseId, String kind) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO case_health (case_id, company_id, project_id,
                                                 last_status_kind, last_executed_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, now())
                        """)) {
            statement.setObject(1, caseId);
            statement.setObject(2, ACME);
            statement.setObject(3, PROJECT);
            statement.setString(4, kind);
            statement.executeUpdate();
        }
    }

    /** {@code case_health} first: it is keyed on a case id, and the cases go with it. */
    private void clearCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            for (String table : List.of("case_health", "test_case")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE project_id = CAST(? AS uuid)")) {
                    statement.setObject(1, PROJECT);
                    statement.executeUpdate();
                }
            }
        }
    }

    private void clearLabels() throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM issue_label WHERE project_id = CAST(? AS uuid)")) {
            statement.setObject(1, PROJECT);
            statement.executeUpdate();
        }
    }

    private void restoreBoards() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM board WHERE project_id = CAST(? AS uuid)")) {
                statement.setObject(1, PROJECT);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setObject(1, ACME);
                statement.setObject(2, PROJECT);
                statement.execute();
            }
        }
    }

}
