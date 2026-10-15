# Handoff - quilt

_Last updated: 2026-10-15_

Current state and the reasoning that is not obvious from the code. For what the
library is and how to use it, see [README.md](README.md).

## State

**Complete and green.** Eight CRDTs, 58 tests, zero dependencies, zero compiler
warnings at `-Xlint:all`. CI runs JDK 17, 21 and 24 on every push.

**Delta sync (2026-10-15).** `Crdt.delta(since)` on all eight types returns only
what a peer holding `since` is missing, as an ordinary state of the same type.
`Laws.delta` checks every pair for complete / honest / lean / quiet, and
`Laws.deltaGossip` checks a cluster converges on deltas alone. Eight delta
mutations, one per type, were all killed (README table). The quiet and lean
checks are the only ones that can catch a redundant delta, since shipping too
much never breaks convergence.

```powershell
.\build.ps1        # or ./build.sh
```

Eleven deliberate bugs were introduced one at a time to confirm the suite
catches them; all eleven were killed. The table is in the README. The harness
that did it was a throwaway script, not committed - if you change the library
substantially it is worth redoing rather than trusting the old numbers.

## Layout

```
src/quilt/Crdt.java          the one interface: S merge(S)
src/quilt/ReplicaId.java     who is editing
src/quilt/Dot.java           (replica, counter) - a unique event tag
src/quilt/GCounter.java      grow-only counter, merges by per-slot max
src/quilt/PNCounter.java     two GCounters, one for each direction
src/quilt/GSet.java          grow-only set, merges by union
src/quilt/TwoPhaseSet.java   two GSets: added, retired. Remove is permanent
src/quilt/LwwRegister.java   one slot, ordered by (timestamp, writer, value)
src/quilt/OrSet.java         add-wins set, dots per add, tombstoned dots
src/quilt/LwwMap.java        map of independent registers, null = tombstone
src/quilt/Rga.java           sequence as a causal tree. The hard one
test/quilt/Laws.java         lattice/monotonicity/convergence/gossip harness
test/quilt/Tests.java        examples + the runner
```

## The decisions worth knowing

### Dots mean different things in OrSet and Rga

This is the trap in the whole library, and it already bit once.

In `OrSet` a dot is **only an identity**. It exists so a remove can name exactly
which add events it retires. The counter is a per-replica sequence number and
comparing counters across replicas would be meaningless - which is fine,
because nothing does.

In `Rga` a dot is **also a timestamp**. Concurrent inserts under the same anchor
are ordered by descending dot, so counters get compared across replicas. A
per-replica counter breaks this quietly: a replica joining a mature document
mints dot 1, sorts below everything, and its edits slide to the end of whatever
run they were inserted into. `Rga.nextDot` therefore advances a **Lamport
clock** - one past the highest counter anywhere in the state, regardless of
replica.

The first version had the per-replica bug. Two tests caught it:
`rga can anchor an insert to a deleted element` and
`rga keeps both concurrent inserts and orders them identically everywhere`.
Do not "simplify" the two into a shared helper.

### Merge is deliberately boring

Every merge is a union or a pointwise max. That is what makes the lattice laws
hold by construction rather than by argument. All the cleverness lives in the
read path - `Rga.walk()` in particular - which is a pure function of the state,
so it cannot break convergence no matter how it changes.

If you ever find yourself wanting merge to make a decision, that is the signal
you are about to break something.

### Nothing is garbage collected

Retired dots in `OrSet`, tombstones in `TwoPhaseSet`, `LwwMap` and `Rga`: all
kept forever. This is a real limitation, stated plainly in the README rather
than hidden. Fixing it properly means causal context (version vectors
summarising contiguous dot ranges), which turns provably-correct union merges
into merges that need an argument. Worth doing, worth doing carefully.

`Rga` tombstones are load-bearing in a way the others are not - later inserts
anchor to them - so they can never be dropped outright, only compacted.

### LwwRegister has a third tiebreak on purpose

Ordering is timestamp, then writer, then `String.valueOf(value)`. The third one
looks like a hack and is: it exists only so `merge` is provably commutative even
if a caller manages to produce two different values with an identical timestamp
and writer. Without it, `a.merge(b)` and `b.merge(a)` would disagree in that
case and the law test would fail intermittently depending on generated data.

### Rga.walk is iterative

Typing one character after another builds a tree as deep as the document is
long. A recursive traversal dies somewhere in the low thousands of characters.
There is a test at 2000 characters specifically to keep it that way.

## Next, if anyone wants it

- **Delta-state sync.** Ship what changed rather than whole states. The single
  biggest practical gap.
- **Causal-context garbage collection** for `OrSet`, per above.
- **A `Json`-shaped composite** - a map whose values can themselves be CRDTs -
  which is what most applications actually reach for.
- **`Rga.text()` is O(n) per call** and `insertAt` walks the tree to resolve an
  index, so building a document character by character is O(n squared). Fine for
  the test suite, not fine for a real editor. A cached index would fix it.
