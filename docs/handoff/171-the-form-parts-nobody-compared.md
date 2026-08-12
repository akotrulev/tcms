# §171 — the form parts nobody compared, and the note that named its own closer

**One phase, `backend/` only, and test code at that: no migration, no contract change, no frontend
or `packages/` source, and nothing in `src/main` changed.** §170's named next slice, quoted
verbatim:

> **the multipart forms** — `BOUND_AS_REQUEST_PARAMETERS` is four entries, and the check they forgo
> is real: nothing compares a form's declared parts to the `@RequestParam` names the handler binds,
> which is the shape `ContractParameterBindingTest` already fixes for query parameters one media
> type over.

Both of those are now false, and the second one was false when it was written — the parts are
bound with `@RequestPart`, not `@RequestParam`, on all four forms. That is the smaller of the two
findings but it is the one that shaped the code, so it is below.

## The gap was recorded honestly and it was still a gap

`ContractConventionTest` pairs every object schema in the contract with the record that serves it —
129 named ones since §170, 25 inline ones by location. Four positions are exempt, and the exemption
is correct: a `multipart/form-data` body has no record behind it, because Spring binds a form to a
handler's *parameter list*. There is no type to compare components against, and inventing one would
be a type that exists to satisfy a rule.

What the exemption then cost was named in its own javadoc — *"nothing compares a form's declared
parts to the parameters the handler binds"* — and named the class where the check belonged. This
slice is that paragraph being cashed. It is the cheapest kind of slice this repo has, and the
reason it is worth doing rather than leaving recorded is that **a recorded gap and a closed one
look identical from every green build**.

Three rules, in `ContractParameterBindingTest`, which is where the note said to put them:

| Rule | What it catches | How it fails today, unchecked |
|---|---|---|
| `everyDeclaredFormPartIsBound` | a part the contract declares and no parameter binds | Spring **drops** it. The upload succeeds and the 201 describes a record missing whatever the part carried — an evidence file attached with no caption, against a contract that promised one |
| `noHandlerBindsAnUndeclaredFormPart` | a part the handler binds and the contract never declared | the field is absent from both copies of `schema.d.ts`, so no generated client can send it; the argument has been null on every request ever made |
| `formPartRequirednessAgrees` | the two sides disagreeing about `required` | contract-optional / handler-required is **a 400 on a request the document says is legal**; the reverse is a null on a path written for a value, invisible because every test sends the part |

They are the same three questions the named lane already asks of a record's components, which is
the argument for the shape as much as for the rules: a form is not a special case of contract
drift, it is the one position where the comparison had no type to hang off.

**All four forms pass all three rules today.** The slice ships no product fix and that is the
expected outcome for a check written after the code it checks — §154, §156 and §161 each shipped
red and this one does not. What it buys is the next rename, which is exactly how this failure
arrives: somebody edits one side.

## `@RequestParam` cannot tell a form field from a query key, and the rule has to

`@RequestParam("caption")` binds the query string key `caption` and the text form field `caption`
identically — the annotation carries nothing that distinguishes them, and neither does the handler.
So on a multipart operation the *only* thing separating the two populations is which side of the
contract declares the name, and `noHandlerBindsAnUndeclaredFormPart` subtracts the operation's
declared query parameters before reporting.

Today that subtraction removes nothing: none of the four multipart paths declares a query
parameter. It is there so that the day one does, the name is reported by the rule that owns it
instead of by this one — and so the next reader meets the ambiguity as a written decision rather
than discovering it from a false positive.

The mirror of it stays open on purpose. `everyDeclaredQueryParameterIsBound` is one-directional
(declared ⊆ bound) and its counterpart — *bound and undeclared* — is not written, because on these
four operations it would report `caption` and `stepId`, which are form fields. A bound-and-undeclared
query rule is a real check and it needs the media type in hand; it is a slice, not a line, and
recording that here is the same move the paragraph this slice closed made.

## Three spellings of a bound part, and the third one is not defensive

`formPartsBoundBy` reads `@RequestPart`, `@RequestParam`, **and a bare `MultipartFile` parameter
with no annotation at all**, which Spring binds by parameter name. Nothing in this codebase is
written that third way, so the arm reads like belt-and-braces. It is not, and mutation `§171 FP-4`
is why it is in the queue as an expected-**green** row: strip the `@RequestPart` off
`CaseAttachmentController` and the handler still works, so without the third arm both membership
rules would report the endpoint as binding *nothing* — no, worse: `everyDeclaredFormPartIsBound`
would redden with a misleading message and `noHandlerBindsAnUndeclaredFormPart` would go quiet, on
the one endpoint somebody had just rewritten. The rule is about what the running server binds, the
same argument `EndpointAccessTest` and `RepositoryTransactionTest` make for reading the live
handler mapping rather than scanning source.

`defaultValue` is folded into the requiredness answer for the same reason: Spring treats a
parameter with a default as optional whatever its `required` flag says, so reading the flag alone
would report a disagreement that the server does not have.

## The map's name was wrong about all four of its entries

`BOUND_AS_REQUEST_PARAMETERS` → **`BOUND_AS_FORM_PARTS`**. Every `file` part is a `@RequestPart`, so
is `artifact`; only `caption` and `stepId` are `@RequestParam`s. Three of the four descriptions said
`MultipartFile file` without naming how it was bound, and the map's *name* asserted the wrong
mechanism for all four.

It cost nothing while nothing read it. It stopped being free the moment a rule had to read both
annotations, because a reader coming to this map for the answer would have looked for one — and
this is the smallest instance of the pattern this repo keeps paying for: **a note that was accurate
about the decision and inaccurate about the code, surviving because no test reads prose.** Renamed
and corrected in the same commit, with the old name struck through rather than deleted.

## Mutation-tested, in-slice, and one of the four was a claim rather than a check

Four mutations, all run, recorded at `§171 FP-1`…`FP-4` in
[`docs/verification/backend-mutation-checks.md`](../verification/backend-mutation-checks.md). Every
one is a check over a check — none exercises the product — so running them in-slice is §165's and
§170's precedent and not the §65 exception §72–§76 wrongly took. Two of them mutate a file in
`src/main`, because the handler's signature is the only side of this comparison that lives there;
both are reverted in the same command.

`FP-3` is the row to read: renaming the bound part to `legend` while the contract keeps `caption`
reddens **both** membership rules at once, and before this commit it was a 201 with a silently
dropped caption that nothing in 2528 tests could see. `FP-4` is the expected-green one, above.

## Gates

`./gradlew build` **SUCCESSFUL** — **2531 tests, 0 failures, 0 skipped** (+3; 515 containers
unchanged, no new file). No frontend or `packages/` change, so neither of those gates was in scope.
Nothing was added to the browser queue: there is no product behaviour here to drive, which is the
same reason §170 added none.

## The next slice

§170's list, minus the entry this one took:

- the **browser queue**, still never run since §110 — and it has now been the first item on this
  list for three consecutive sections;
- **`TeamRef`** — three types, two of them a contract change before they are a Java one; §170
  narrowed it to *should the three be one*, not *do they agree*;
- **the bound-and-undeclared query parameter**, this slice's own recorded gap — the direction
  `everyDeclaredQueryParameterIsBound` does not check, which needs the media type in hand to avoid
  reporting a form field;
- **Git traceability (M4)** — two properties, two columns, two components, one commit, with §170's
  deletion of `IngestionRequest.branch`/`.commitSha` as the starting point;
- **`OUTDATED_SPEC_CHANGED` / `UNLINKED`**, derivable from `case_doc_link`;
- the **read-only-archive** decision §159 deliberately did not take;
- **`useLinkBlock`'s missing screen** — a hook with no caller, §169's second recorded gap.
