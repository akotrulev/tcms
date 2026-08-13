# §191 — the node that was not there any more, and the flake five sections called half-typed

**One phase, `frontend/` only, and test code at that: no migration, no contract change, no
`schema.d.ts` regeneration, nothing in `src` outside `CaseEditorPage.test.tsx`.** Not a slice off
anybody's list — CI run
[31731166477](https://github.com/akotrulev/tcms/actions/runs/31731166477/job/94551772583) went red
on `master` and this is what was under it.

## The flake had been narrated four times and diagnosed zero

`CaseEditorPage.test.tsx` has been failing intermittently since §115, and every section that met it
recorded the same two things: it only fails **under full-suite load**, and it passes when the file
runs alone. §136 measured 5 failures in a 342 s run and 30/30 in 125 s alone. §149 recorded six, all
in that file, *"every one a 30 s timeout and never an assertion"*. §163 filed it as *"not fixed and
not this slice's"*. §186 measured it **against a stashed tree at the previous commit** — the right
instinct, because "probably flaky" is how a real regression ships — confirmed it failed identically
without the slice's changes, and wrote it into `CLAUDE.md` as *NOT fixed*. §187 saw it pass once and
said so, correctly, as *one green run does not disprove a load-dependent flake*.

Every one of those is accurate. None of them asked **why load would change the answer**, and the
standing description that came out of it — *"one test asserts a half-typed value under full-suite
load"* — is wrong in the way that mattered: it names the **input** as the thing load corrupts, which
makes the failure sound like `userEvent` losing keystrokes, i.e. nobody's bug and nothing to fix.
That reading is why it survived five sections. It is also §108's shape at a smaller scale: a
plausible sentence, repeated by each reader from the previous reader, standing in for a check.

## What load actually changes

CI's report is the whole diagnosis once the assertion is read as a **timing** statement rather than
a typing one:

```
expect(element).toHaveValue(Refund it twice)   // the suggestion the test clicked
Received: Refund it                            // what the author typed, complete and correct
```

The received value is not half-typed. It is the *fully* typed fragment — the state the field is in
when the click on the suggestion **does nothing at all**.

`StepActionField` debounces the corpus read by **250 ms of real time**, and `useStepSuggestions`
keys its query on the debounced fragment. On an idle machine `user.type(field, 'Refund it')` runs
faster than the debounce and produces one round. Under full-suite load the gaps between keystrokes
exceed 250 ms, so the same call produces **several** rounds — `'Refund'`, `'Refund i'`,
`'Refund it'` — and each is a new query key, whose `data` is `undefined` until it resolves. The
component reads `suggestions.data ?? []`, so between rounds the option list is **empty**: MUI
unmounts the rows and mounts fresh ones when the next round answers.

The test did what every reader would write:

```ts
const options = await screen.findAllByTestId('CaseEditor.Steps.Suggestion')
await user.click(options[options.length - 1])
```

`findAllByTestId` resolves on whichever round happens to be on screen. By the time the click runs,
a later round has replaced those `<li>`s, and `options[…]` is a **detached node**. Clicking one
dispatches events into a document that no longer contains it, MUI's `onChange` never fires, the
field keeps its typed text, and the assertion reports a value that looks like truncated input.

**Finding a node and clicking it are two awaited steps, and nothing holds the DOM still between
them.** That is the defect, it is in the test, and load is not the cause — it is the only thing slow
enough to open the window.

## The fix is to make capture-and-click one retried step

```ts
async function pickSuggestion(user, action) {
  await waitFor(async () => {
    const row = screen
      .queryAllByTestId('CaseEditor.Steps.Suggestion')
      .find((option) => option.textContent?.includes(action))
    expect(row).toBeDefined()
    await user.click(row!)
    expect(actionFields()[0]).toHaveValue(action)
  })
}
```

Three properties are load-bearing:

- **The query is inside the retry.** A row found on a stale round is never carried across an await
  boundary; each attempt re-reads the DOM it is about to click.
- **The row is chosen by its text, not its index.** `options[options.length - 1]` assumes the list
  is the one the final fragment produced; a round for `'Refund'` and a round for `'Refund it'` can
  hold different rows in different orders, so an index picks a different suggestion rather than
  failing.
- **A pick is idempotent**, which is what makes clicking inside `waitFor` safe: a retry after a
  click that did land re-selects the same suggestion and the assertion passes.

Both corpus tests use it — the sibling (`fills action, test data AND expected result from one pick`)
had the identical two-step pattern and is the test §187 watched pass once. It was never fixed; it
was lucky. **Any test that finds a node in a debounce-fed popup and clicks it on the next line has
this defect**, whatever the popup is; the helper's javadoc says so where the next author will read
it.

## Gates

`frontend` build green, `lint` 0 warnings, and the file alone **36/36 in 96 s** locally — which is
the run that never failed, so it proves nothing on its own. The measurement that counts is on
`shanks`, the loaded Linux observer that reddens it: **1285 tests / 97 files / 0 failures in
198 s**. That is the shape the flake needs and it did not recur.

One loaded green run is a weaker claim than the diagnosis is — but unlike §187's, it is not the
evidence the fix rests on. The mechanism is named, it is visible in the source, and the code path
that produced the detached node no longer exists in either test.

`CLAUDE.md`'s note is struck through rather than deleted, with the real mechanism in its place: the
half-typed reading is the thing that cost five sections, and a reader who finds only *"fixed"* would
have no way to recognise the pattern in the next popup.

**No verification rows.** This slice changes no product behaviour, drives no screen and mutates
nothing — there is nothing for either queue to hold.
