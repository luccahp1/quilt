# Quilt

[![build](https://github.com/luccahp1/quilt/actions/workflows/ci.yml/badge.svg)](https://github.com/luccahp1/quilt/actions/workflows/ci.yml)

Eight CRDTs in Java. Zero dependencies, in the library and in its tests.

```java
ReplicaId laptop = ReplicaId.of("laptop");

OrSet<String> onPhone  = cart.remove("milk");        // deleted on the phone
OrSet<String> onLaptop = cart.add(laptop, "milk");   // re-added on the laptop, offline

onPhone.merge(onLaptop).contains("milk");            // true - and true the other way round too
```

Two replicas edit the same data with no coordinator, no locks and no consensus, exchange states
over a network that reorders and duplicates whatever it likes, and end up byte-identical.

No dependencies, no build tool, no annotations. `javac` and `java` are the entire toolchain.

## What's in it

| Type | What it is | Reach for it when |
|---|---|---|
| `GCounter` | Counter that only goes up | Page views, likes, anything monotonic |
| `PNCounter` | Counter that goes both ways | Inventory, votes, balances |
| `GSet` | Set you can only add to | Append-only collections, observed tags |
| `TwoPhaseSet` | Add once, remove once, permanently | Retired ids, revoked keys |
| `LwwRegister` | One slot, last write wins | Display name, status flag, cursor |
| `OrSet` | Add-wins set | **The default set.** Carts, tags, presence |
| `LwwMap` | Map of independent registers | User profiles, settings, documents |
| `Rga` | Replicated sequence | Collaborative text, ordered lists |

All eight implement one interface:

```java
public interface Crdt<S extends Crdt<S>> {
    S merge(S other);
}
```

Everything is immutable. Updates return a new state, so a state you have already handed to a peer
can never change underneath you.

## Why merge order does not matter

Every type here is a join-semilattice, and `merge` computes a least upper bound. A least upper
bound is necessarily commutative, associative and idempotent, and those three properties are
exactly the three things a real network breaks:

| Property | The network problem it survives |
|---|---|
| Commutative | Messages arrive out of order |
| Associative | Peers merge partial results, not a neat left fold |
| Idempotent | A retry delivers the same state twice |

That is the entire theory. Everything else in this repo is the work of making useful data
structures that still merge that way.

The tests check all three exhaustively over generated states rather than trusting the claim:

```
PASS  gcounter obeys the lattice laws
PASS  orset converges under any delivery order
PASS  rga converges across a gossiping cluster
```

## Counters merge by maximum, not by sum

The single most common way to get a distributed counter wrong is to add the two numbers together.

```java
GCounter shared = GCounter.empty().increment(alice, 5);
GCounter a = shared.increment(alice, 2);   // alice: 7
GCounter b = shared.increment(bob, 3);     // alice: 5, bob: 3

a.merge(b).value();   // 10, not 15
```

Alice's first five exist on both sides. Summing double-counts it, and worse, makes merge
non-idempotent, so a redelivered message inflates the number every time it arrives. Each replica
owns one slot, nobody else writes to it, and merge takes the maximum per slot. Receiving the same
state twice is then a no-op, which is the only reason gossip over an unreliable network is safe.

`PNCounter` needs two of these, one for increments and one for decrements, because a single
max-merged slot would swallow one of two concurrent decrements.

## Add-wins, and why you usually want it

`TwoPhaseSet` and `OrSet` look similar and behave very differently under concurrency.

```java
// TwoPhaseSet: remove is permanent
set.add("a").remove("a").add("a").contains("a");                 // false, forever

// OrSet: the re-add is a new event, so it stands
set.add(alice, "a").remove("a").add(alice, "a").contains("a");   // true
```

The difference is that `OrSet` tracks *events*, not elements. Every add mints a unique
[dot](src/quilt/Dot.java) - a `(replica, counter)` pair - and a remove retires only the dots that
replica has actually seen. So when Alice deletes "milk" while Bob concurrently adds it, Bob's add
carries a dot Alice never observed and therefore never retired, and "milk" survives.

That asymmetry is deliberate. Losing an item the user just typed is a worse failure than keeping
one they just deleted, so add-wins is the right default for carts, tags and presence lists. Use
`TwoPhaseSet` only when "gone" genuinely means gone.

**The cost is real:** retired dots are kept forever. Merge is a pure union in both halves, which
is what makes convergence provable, but a set that has churned a million times carries a million
dots even when empty. Bounding that needs causal context - a version vector summarising "every dot
from replica A up to 400" rather than listing them - which is a genuine optimisation and a genuine
increase in subtlety. This implementation takes the simple version and pays the space.
`retiredDotCount()` makes the bill visible.

## The sequence is the interesting one

`Rga` is the structure behind collaborative text editors, and it is where index-based thinking
falls apart. "Insert at position 4" means something different the moment somebody else inserts at
position 2, so positions are not used as coordinates at all. Each element instead records **which
element it was typed after**, by dot. That anchor is stable no matter what else arrives.

The elements therefore form a tree, not a list. Reading the sequence is a preorder walk where
siblings - elements typed after the same anchor, which is exactly what concurrent inserts at the
same spot produce - are ordered by descending dot. Every replica sorts identically, so every
replica reads the same text without exchanging a word about it.

```java
Rga<Character> alice = type("hello");   // typed on one replica
Rga<Character> bob   = type("world");   // typed on another, concurrently

alice.merge(bob).text();   // "worldhello" - and bob.merge(alice) gives the same
```

Note what did **not** happen: `hwoerllodl`. Character-level merging interleaves; a causal tree
keeps each typed run contiguous, because every character is anchored to the one before it.

Deleted elements stay as tombstones, because later inserts may be anchored to them:

```java
Dot b = doc.dotAt(1);                                  // doc is "abc"
Rga<Character> deleted = doc.removeAt(1);              // alice deletes 'b'
Rga<Character> typed = doc.insertAfter(bob, b, 'X');   // bob types after it, not knowing

deleted.merge(typed).text();   // "aXc" - X lands exactly where bob meant it to
```

### The bug this design invites

Dots carry a counter, and it is tempting to make that counter a per-replica sequence number.
`OrSet` does exactly that, correctly, because there a dot is only ever an identity.

In `Rga` it is wrong, and quietly so. Dots get compared *across* replicas to order concurrent
inserts, so a replica joining a mature document would mint dot 1, sort below everything, and have
its edits slide to the end of every run it inserted into. The first version of this file had that
bug and passed most of the suite; two tests caught it.

`Rga` advances a Lamport clock instead - one past the highest counter anywhere in the state - so a
dot's counter is at least as large as everything its author had seen. Then "greater counter"
implies "not causally earlier", which is the guarantee the sibling ordering actually needs. Equal
counters mean genuine concurrency and break on replica id.

## Testing

```bash
./build.sh          # or .\build.ps1 on Windows
```

```
  49/49 passed
```

Two kinds of test. Example tests pin down behaviour worth documenting - add-wins really does win,
a 2P-set really cannot resurrect. Then [Laws.java](test/quilt/Laws.java) checks the algebra on
generated states, because convergence bugs hide in the orderings nobody thought to write down:

- **`lattice`** - commutativity, associativity and idempotence over every pair and triple, checked
  exhaustively rather than sampled
- **`monotonic`** - merging never loses information from either side
- **`converges`** - the same states merged in random permutations *and* random binary groupings,
  since a real network merges partial results into partial results
- **`gossip`** - a simulated cluster where replicas apply local operations and sync with random
  peers, so edits routinely land on states that are missing edits they will learn about later

Every randomised test is seeded. A failure reproduces exactly, which is the difference between a
useful randomised test and a flaky one.

### The tests were checked against deliberate bugs

A suite that passes proves nothing on its own. Eleven realistic bugs were introduced one at a time
and the suite had to go red for each:

| Mutation | Result |
|---|---|
| `GCounter` merges by sum instead of max | killed, 44/49 |
| `PNCounter` merges increments into decrements | killed, 47/49 |
| `GSet` merges by intersection instead of union | killed, 47/49 |
| `TwoPhaseSet` ignores its tombstones | killed, 46/49 |
| `LwwRegister` drops the replica tiebreak on equal timestamps | killed, 46/49 |
| `OrSet` ignores retired dots | killed, 47/49 |
| `OrSet.remove` retires every dot, not just observed ones | killed, 48/49 |
| `LwwMap` overwrites entries instead of merging registers | killed, 47/49 |
| `Rga` orders concurrent siblings ascending | killed, 46/49 |
| `Rga` uses a per-replica counter instead of a Lamport clock | killed, 47/49 |
| `Rga` renders tombstoned elements | killed, 47/49 |

All eleven were caught. The narrowest margin was one test, for the mutation where `OrSet.remove`
retires dots it has not observed - which is precisely the add-wins property, and precisely the
thing an example-based test would be least likely to cover.

## What this does not do

- **No garbage collection.** Tombstones and retired dots accumulate forever. Every type that keeps
  them exposes a count so the growth is at least visible.
- **No delta sync.** Merging ships whole states. Delta-state CRDTs send only what changed and are
  what you want over a real link.
- **No serialisation.** No JSON, no protobuf, no opinion about your wire format.
- **No thread safety needed, and none provided.** Instances are immutable, so sharing one is safe;
  a mutable reference to "the current state" is yours to guard.
- **Wall clocks are your problem.** `LwwRegister` and `LwwMap` take timestamps as arguments rather
  than reading a clock. Feed them skewed clocks and a stale write will beat a fresh one. That is a
  property of the timestamps, not of the merge.

## Requirements

JDK 17 or newer. CI runs 17, 21 and 24.

## License

MIT
