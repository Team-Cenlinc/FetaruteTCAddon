# Deep diagnostic pass: FetaruteTCAddon dynamic dispatch

You are auditing a Minecraft TrainCarts railway **dispatch/interlocking** plugin for
latent defects. This is safety-critical scheduling code: a wrong permissive change
causes real collisions and overruns on a live server. Throughput matters, but never
at the cost of a safety invariant.

## Start here

Load these skills before doing anything else:

- `diagnosing-bugs` — use its loop for every hypothesis you form. Do not skip the
  "reproduce / isolate" steps just because the evidence is a log rather than a test.
- `codebase-design` — for the structural half of the task (see Task 4).

Read `/Users/acatine/.claude/projects/-Users-acatine-Documents-CodeLib-FetaruteTCAddon/memory/`
in full before forming any hypothesis, in this order:
`dispatch-disproven-hypotheses.md`, `dispatch-fix-invariants.md`,
`dynamic-dispatch-stabilization.md`, `dispatch-debug-log.md`.
Roughly 27 rounds of live-server debugging are recorded there. **Several plausible
hypotheses are already disproven with measurements.** Re-deriving one costs a full
round on a live server.

## Evidence sources

- Code: this repo. Current HEAD is the audit target.
- **Live server logs: `../fetarute_experimental/logs/`** — `latest.log` plus ~485
  rotated `.gz`. The `test/data/debug` directory inside this repo is a stale copy;
  do not use it.
- The instrumentation trace vocabulary is `SMART_*`. Log volume is ~1500 lines/min.

## The six failure shapes that have actually burned this project

Every one of these cost at least one full debugging round. Check your own reasoning
against this list before you report anything.

1. **Absence of a trace read as absence of the state.** A trace not on the
   `RuntimeDispatchDiagnosticGate` must-keep list gets dropped by the diagnostic
   budget. One round dropped **572,957** lines as `OTHER_DIAGNOSTIC`. For such a
   trace, `count == 0` means *nothing*. Always check the must-keep list before
   interpreting a zero. This mistake was made four separate times.

2. **An empty collection read as "nothing exists."** `blockedBy=[]` in
   `SMART_BLOCKING_SNAPSHOT` comes from `RuntimeStopState.blockers()`, which is
   *natively empty* for several stop classes. It was twice misread as "nothing is
   blocking this train"; the second time it hid a 45-minute interlock. Ask: is this
   field *natively* empty on this path, and is it recomputed or stored?

3. **Comparing two quantities that are not the same quantity.** Six occurrences.
   Most recent: median inter-arrival interval was used as "terminal capacity" — it
   measures *demand*. Capacity is bounded by the *minimum* achievable interval.
   Watch for `X.orElse(someOtherQuantity)`: downstream will compare the substitute
   against the real X.

4. **A value stored at entry read as current state.** `SMART_BLOCKING_SNAPSHOT`'s
   `detail=` is `RuntimeStopState.detail()`, written when the stop began. Measured:
   **100 of 102** long holds (98%) never changed it over a median 153 seconds. The
   field named the reason a train *first* stopped, never why it was *still* stopped.
   A `liveCause=` field was added to answer the second question; the two disagreeing
   is normal, not a contradiction.

5. **A guard whose condition never intersects reality.** Recurring. Instances:
   a candidate selector gated on `kind == CONFLICT` while live blockers were
   NODE 1012 / EDGE 331 / CONFLICT 0; `smartPlannerForwardDirection` unconditionally
   returning `UNKNOWN`, so the deadlock breaker selected a candidate **0 times out of
   1035 detected cycles**; `RuntimeStopState.retryTrigger()` — whose values include
   `PERIODIC_RECHECK` and `LAYOVER_RECHECK` — being read in exactly two places, a log
   line and a CLI display, and **driving nothing**. The code path exists and looks
   like it works.

6. **An event stream treated as a complete ledger.** `SMART_RESOURCE_LIFECYCLE` is
   on the must-keep list and looks authoritative, but **does not emit a release event
   on every claim removal**. "No release seen" does *not* imply "still held". The
   reverse direction (acquires) appears complete.

## Standing constraints — do not violate

- **Fail-closed.** Missing or ambiguous evidence must produce the safe-side block,
  never a permissive default. Absence of evidence is never evidence of absence.
- **`deadlock-destroy-enabled` and `stuck-cleanup-enabled` stay `false`.** The goal
  is to *unjam* trains, not to delete them on a timeout. Do not propose raising a
  timeout as a fix.
- **General solutions only.** No special-casing a named station, line, or track
  segment. If a fix only works at one location it is the wrong fix.
- **Never weaken** the opposite-direction / unknown-direction single-region hard
  barrier, and never turn an advisory or preview signal into a hard release.
- **`OccupancyRequest.unresolvedDirectionKeys` is a safety redline.** A key in that
  set means direction evidence is contradictory; no fallback inference may supply a
  direction. Ignoring it lets a train that has just reversed reclaim its old
  direction claim and bypass the opposing-direction barrier — a collision, not a jam.
- **`RuntimeDispatchService` is at 997 methods; SpotBugs skips an entire class above
  1000**, silently. Verify with
  `javap -p build/classes/java/main/.../RuntimeDispatchService.class | grep -cE '\(.*\);$'`.
  Put new pure predicates in a collaborator (see `OccupancyClaimEvidence`).
- **Do not edit `../fetarute_experimental/plugins/FetaruteTCAddon/config.yml`.** A
  live experiment may be running. Propose config changes; do not apply them.
- Do not commit or push. Report findings; leave the working tree clean.

## Verification bar

- Never trust "the tests passed." Re-run and parse `build/test-results/test/*.xml`
  yourself, counting failures and errors.
- **Every new test must be mutation-verified**: break the specific logic it covers,
  confirm *that* test fails, restore. Two tests in this project have already been
  found vacuous this way. Confirm the mutation lands on the site you intend — one
  earlier "the test doesn't catch this" conclusion was itself wrong because the
  mutation was applied to the wrong predicate.
- Gradle needs the sandbox disabled to fork its daemon.

## Tasks, in priority order

1. **Hunt for more instances of failure shape 5.** The strongest open lead:
   `RuntimeStopState.retryTrigger()` drives nothing, yet several stop classes declare
   a retry trigger and depend on being re-evaluated. One instance
   (`OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK`) was fixed by giving the signal monitor a
   recheck path; that fix raised network throughput 38%. **`LAYOVER_RECHECK` looks
   like an unfixed copy of the same defect** — a train in the layover registry gets an
   unconditional STOP every signal tick, and one such train held a single-platform
   dead-end turnback for 1019 seconds. Determine whether each declared release
   condition and retry trigger is actually implemented, or is documentation.

2. **Audit every other predicate that can only return one value in practice.**
   Shape 5 generalizes: find guards, resolvers, and candidate selectors whose
   real-world input domain cannot satisfy them. Prefer measuring against the live
   logs over reading alone.

3. **Signal aspect laddering.** Measured: 82% of transitions to STOP came directly
   from PROCEED with no intermediate aspect; `CAUTION` appeared once in an entire
   round. The main path `stageSignalAspectForAuthorityAndAdvisory` is binary
   (hard-blocked → STOP, else advisory risk → PROCEED_WITH_CAUTION, else PROCEED);
   the graduated ladder in `deriveBlockedAspect` only serves a progress-trigger
   branch. A probe now records `advisorySignal` / `advisoryBlockers` on the
   hard-blocked branch. Determine whether the lookahead genuinely sees nothing, or
   sees it and discards the warning. Do not widen the lookahead as a first move —
   there is a repo-wide lesson that "in the planning horizon" is not the same
   quantity as "should start braking," and that error has been made twice.

4. **Structural.** `RuntimeDispatchService` is ~30k lines and 997/1000 methods.
   Using `codebase-design`, propose a seam-by-seam split (signal tick / authorization
   construction / recovery actions / diagnostics) with the deep-module reasoning made
   explicit. Do not perform the refactor. Rank seams by how much each would have
   *prevented* one of the six failure shapes above.

## What to hand back

A ranked list of findings. For each: the concrete failure scenario (inputs and state
→ wrong behavior), the evidence that distinguishes it from the alternatives you
considered, and the narrowest fail-closed change that addresses it.

State your confidence, and say plainly which findings you could **not** settle from
the available evidence and what single measurement would settle each. An honest
"this needs one more instrumented round, here is the exact probe" is worth more than
a confident guess — guesses have cost this project entire rounds.
