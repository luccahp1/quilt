package quilt;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The whole test suite, with its own runner.
 *
 * <p>No JUnit, no assertion library, no build tool. That is the point: the library has zero
 * dependencies and so does its test suite, so {@code javac} plus {@code java} is the entire
 * toolchain and CI needs nothing but a JDK.
 *
 * <p>Two kinds of test live here. The example tests pin down behaviour a reader would want
 * documented - add-wins really does win, a 2P-set really cannot resurrect. The {@link Laws} tests
 * check the algebra on generated states, because convergence bugs hide in orderings nobody thought
 * to write down. Every randomised test is seeded, so a failure reproduces exactly.
 */
public final class Tests {

    private static final ReplicaId ALICE = ReplicaId.of("alice");
    private static final ReplicaId BOB = ReplicaId.of("bob");
    private static final ReplicaId CAROL = ReplicaId.of("carol");

    private static final long SEED = 20260806L;
    private static final int TRIALS = 40;

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {

        // --- grow-only counter ---
        check("gcounter starts at zero", () -> {
            assertEquals(0L, GCounter.empty().value(), "an untouched counter is zero");
        });

        check("gcounter accumulates a replica's own increments", () -> {
            GCounter c = GCounter.empty().increment(ALICE, 3).increment(ALICE, 4);
            assertEquals(7L, c.value(), "3 + 4");
            assertEquals(7L, c.valueOf(ALICE), "all of it is alice's");
        });

        check("gcounter refuses a non-positive increment", () -> {
            assertThrows(IllegalArgumentException.class,
                    () -> GCounter.empty().increment(ALICE, 0), "zero is not an increment");
            assertThrows(IllegalArgumentException.class,
                    () -> GCounter.empty().increment(ALICE, -1), "a grow-only counter cannot shrink");
        });

        check("gcounter merges slots by maximum, not by sum", () -> {
            // Both replicas start from the same state, so alice's 5 is present in both. Summing
            // would count it twice; taking the max is what makes redelivery safe.
            GCounter shared = GCounter.empty().increment(ALICE, 5);
            GCounter a = shared.increment(ALICE, 2);
            GCounter b = shared.increment(BOB, 3);
            assertEquals(10L, a.merge(b).value(), "7 from alice plus 3 from bob, not 15");
        });

        check("gcounter is unmoved by redelivery", () -> {
            GCounter a = GCounter.empty().increment(ALICE, 5);
            GCounter b = GCounter.empty().increment(BOB, 3);
            GCounter once = a.merge(b);
            assertEquals(once, once.merge(b).merge(b).merge(a), "extra copies change nothing");
        });

        check("gcounter obeys the lattice laws", () -> {
            Laws.lattice("GCounter", gcounters());
            Laws.monotonic("GCounter", gcounters());
        });

        // --- positive/negative counter ---
        check("pncounter goes down as well as up", () -> {
            PNCounter c = PNCounter.empty().increment(ALICE, 10).decrement(ALICE, 4);
            assertEquals(6L, c.value(), "10 - 4");
        });

        check("pncounter can go negative", () -> {
            assertEquals(-3L, PNCounter.empty().decrement(ALICE, 3).value(), "nothing clamps at zero");
        });

        check("pncounter keeps both concurrent decrements", () -> {
            // The reason decrements need their own grow-only counter: merged by maximum in a
            // single slot, one of these two would be swallowed.
            PNCounter shared = PNCounter.empty().increment(ALICE, 10);
            PNCounter a = shared.decrement(ALICE, 1);
            PNCounter b = shared.decrement(BOB, 1);
            assertEquals(8L, a.merge(b).value(), "both decrements must survive");
        });

        check("pncounter obeys the lattice laws", () -> {
            Laws.lattice("PNCounter", pncounters());
            Laws.monotonic("PNCounter", pncounters());
        });

        // --- grow-only set ---
        check("gset holds what it is given", () -> {
            GSet<String> s = GSet.of("a", "b");
            assertTrue(s.contains("a"), "a was added");
            assertFalse(s.contains("z"), "z was not");
            assertEquals(2, s.size(), "two elements");
        });

        check("gset merges by union", () -> {
            assertEquals(Set.of("a", "b", "c"),
                    GSet.of("a", "b").merge(GSet.of("b", "c")).elements(), "union, deduplicated");
        });

        check("gset obeys the lattice laws", () -> {
            Laws.lattice("GSet", gsets());
            Laws.monotonic("GSet", gsets());
        });

        // --- two-phase set ---
        check("twophaseset removes", () -> {
            TwoPhaseSet<String> s = TwoPhaseSet.<String>empty().add("a").add("b").remove("a");
            assertFalse(s.contains("a"), "a was removed");
            assertTrue(s.contains("b"), "b was not");
        });

        check("twophaseset cannot resurrect a removed element", () -> {
            // Documented behaviour, not a bug: this is the whole difference from an OrSet.
            TwoPhaseSet<String> s = TwoPhaseSet.<String>empty().add("a").remove("a").add("a");
            assertFalse(s.contains("a"), "a tombstone is permanent");
            assertTrue(s.isTombstoned("a"), "and it is visible as one");
        });

        check("twophaseset lets remove beat a concurrent add", () -> {
            TwoPhaseSet<String> shared = TwoPhaseSet.<String>empty().add("milk");
            TwoPhaseSet<String> a = shared.remove("milk");
            TwoPhaseSet<String> b = shared.add("milk");
            assertFalse(a.merge(b).contains("milk"), "remove-wins is the contract here");
        });

        check("twophaseset obeys the lattice laws", () -> {
            Laws.lattice("TwoPhaseSet", twoPhaseSets());
            Laws.monotonic("TwoPhaseSet", twoPhaseSets());
        });

        // --- last-write-wins register ---
        check("lwwregister keeps the later write", () -> {
            LwwRegister<String> r = LwwRegister.<String>empty()
                    .set("first", 1, ALICE)
                    .set("second", 2, ALICE);
            assertEquals("second", r.value(), "the later timestamp");
        });

        check("lwwregister lets a higher timestamp win regardless of merge direction", () -> {
            LwwRegister<String> early = LwwRegister.of("early", 1, ALICE);
            LwwRegister<String> late = LwwRegister.of("late", 9, BOB);
            assertEquals("late", early.merge(late).value(), "merged one way");
            assertEquals("late", late.merge(early).value(), "and the other");
        });

        check("lwwregister breaks a timestamp tie by replica, deterministically", () -> {
            LwwRegister<String> a = LwwRegister.of("from alice", 5, ALICE);
            LwwRegister<String> b = LwwRegister.of("from bob", 5, BOB);
            assertEquals("from bob", a.merge(b).value(), "bob sorts above alice");
            assertEquals(a.merge(b), b.merge(a), "and the tiebreak does not depend on order");
        });

        check("lwwregister treats an unwritten register as losing to everything", () -> {
            LwwRegister<String> empty = LwwRegister.empty();
            LwwRegister<String> written = LwwRegister.of("x", 1, ALICE);
            assertTrue(empty.isEmpty(), "starts empty");
            assertEquals("x", empty.merge(written).value(), "empty loses");
            assertEquals("x", written.merge(empty).value(), "in both directions");
        });

        check("lwwregister obeys the lattice laws", () -> {
            Laws.lattice("LwwRegister", registers());
            Laws.monotonic("LwwRegister", registers());
        });

        // --- observed-remove set ---
        check("orset adds and removes", () -> {
            OrSet<String> s = OrSet.<String>empty().add(ALICE, "a").add(ALICE, "b").remove("a");
            assertFalse(s.contains("a"), "a was removed");
            assertTrue(s.contains("b"), "b was not");
        });

        check("orset can re-add what it removed", () -> {
            // The contrast with TwoPhaseSet. The re-add is a new event with a new dot, so there is
            // nothing for the old tombstone to suppress.
            OrSet<String> s = OrSet.<String>empty().add(ALICE, "a").remove("a").add(ALICE, "a");
            assertTrue(s.contains("a"), "a is back");
        });

        check("orset lets a concurrent add beat a remove", () -> {
            // Alice deletes milk; Bob, who has not heard, adds it again. Bob's add carries a dot
            // alice never observed and so never retired.
            OrSet<String> shared = OrSet.<String>empty().add(ALICE, "milk");
            OrSet<String> aliceRemoves = shared.remove("milk");
            OrSet<String> bobAdds = shared.add(BOB, "milk");
            assertTrue(aliceRemoves.merge(bobAdds).contains("milk"), "add wins");
            assertTrue(bobAdds.merge(aliceRemoves).contains("milk"), "in both directions");
        });

        check("orset removes only the dots the remover had observed", () -> {
            OrSet<String> shared = OrSet.<String>empty().add(ALICE, "x");
            OrSet<String> bobAddsAgain = shared.add(BOB, "x");
            // Alice removes while holding only her own dot, so bob's is untouched.
            OrSet<String> merged = shared.remove("x").merge(bobAddsAgain);
            assertTrue(merged.contains("x"), "bob's dot survives");
            assertEquals(1, merged.retiredDotCount(), "exactly one dot was retired");
        });

        check("orset removing a fully observed element does remove it", () -> {
            OrSet<String> shared = OrSet.<String>empty().add(ALICE, "x");
            OrSet<String> both = shared.add(BOB, "x");
            assertFalse(both.remove("x").contains("x"), "a remover that has seen both dots wins");
        });

        check("orset obeys the lattice laws", () -> {
            Laws.lattice("OrSet", orSets());
            Laws.monotonic("OrSet", orSets());
        });

        check("orset converges under any delivery order", () -> {
            Laws.converges("OrSet", orSets(), new Random(SEED), TRIALS, s -> ((OrSet<?>) s).elements());
        });

        check("orset converges across a gossiping cluster", () -> {
            Random random = new Random(SEED);
            Laws.gossip(
                    "OrSet",
                    List.of(ALICE, BOB, CAROL),
                    OrSet.<String>empty(),
                    (state, replica, step) -> {
                        String item = "item" + random.nextInt(5);
                        return random.nextInt(3) == 0 ? state.remove(item) : state.add(replica, item);
                    },
                    random,
                    300,
                    s -> s.elements());
        });

        // --- last-write-wins map ---
        check("lwwmap puts and gets", () -> {
            LwwMap<String, String> m = LwwMap.<String, String>empty().put("k", "v", 1, ALICE);
            assertEquals("v", m.get("k"), "the value went in");
            assertTrue(m.containsKey("k"), "and the key is live");
        });

        check("lwwmap deletes with a tombstone", () -> {
            LwwMap<String, String> m = LwwMap.<String, String>empty()
                    .put("k", "v", 1, ALICE)
                    .remove("k", 2, ALICE);
            assertEquals(null, m.get("k"), "gone");
            assertEquals(0, m.size(), "and not counted");
            assertEquals(1, m.tombstoneCount(), "but still tracked");
        });

        check("lwwmap keeps concurrent edits to different keys", () -> {
            // The common case, and the reason a map of registers beats one big register.
            LwwMap<String, String> a = LwwMap.<String, String>empty().put("name", "ada", 1, ALICE);
            LwwMap<String, String> b = LwwMap.<String, String>empty().put("city", "london", 1, BOB);
            LwwMap<String, String> merged = a.merge(b);
            assertEquals("ada", merged.get("name"), "alice's key survived");
            assertEquals("london", merged.get("city"), "and so did bob's");
        });

        check("lwwmap resolves concurrent edits to the same key by timestamp", () -> {
            LwwMap<String, String> a = LwwMap.<String, String>empty().put("k", "early", 1, ALICE);
            LwwMap<String, String> b = LwwMap.<String, String>empty().put("k", "late", 5, BOB);
            assertEquals("late", a.merge(b).get("k"), "later wins");
            assertEquals(a.merge(b), b.merge(a), "and it does not depend on merge order");
        });

        check("lwwmap lets a delete lose to a later write", () -> {
            LwwMap<String, String> base = LwwMap.<String, String>empty().put("k", "v", 1, ALICE);
            LwwMap<String, String> deleted = base.remove("k", 2, ALICE);
            LwwMap<String, String> rewritten = base.put("k", "again", 3, BOB);
            assertEquals("again", deleted.merge(rewritten).get("k"), "the newer write beats the tombstone");
        });

        check("lwwmap obeys the lattice laws", () -> {
            Laws.lattice("LwwMap", lwwMaps());
            Laws.monotonic("LwwMap", lwwMaps());
        });

        // --- replicated sequence ---
        check("rga preserves the order things were typed in", () -> {
            Rga<Character> r = type(Rga.empty(), ALICE, "hello");
            assertEquals("hello", r.text(), "typed left to right");
        });

        check("rga inserts in the middle", () -> {
            Rga<Character> r = type(Rga.empty(), ALICE, "hi").insertAt(ALICE, 1, 'e');
            assertEquals("hei", r.text(), "e landed at index 1");
        });

        check("rga appends at the end", () -> {
            Rga<Character> r = type(Rga.empty(), ALICE, "hi").insertAt(ALICE, 2, '!');
            assertEquals("hi!", r.text(), "index == size appends");
        });

        check("rga rejects an out-of-range index", () -> {
            Rga<Character> r = type(Rga.empty(), ALICE, "hi");
            assertThrows(IndexOutOfBoundsException.class, () -> r.insertAt(ALICE, 3, 'x'), "past the end");
            assertThrows(IndexOutOfBoundsException.class, () -> r.insertAt(ALICE, -1, 'x'), "before the start");
        });

        check("rga removes by index", () -> {
            Rga<Character> r = type(Rga.empty(), ALICE, "cart").removeAt(1);
            assertEquals("crt", r.text(), "the a is gone");
            assertEquals(3, r.size(), "and not counted");
            assertEquals(1, r.tombstoneCount(), "but kept as an anchor");
        });

        check("rga keeps both concurrent inserts and orders them identically everywhere", () -> {
            // The property that matters. Not just "nothing was lost" but "both replicas agree
            // on the order", which is what a naive last-write-wins on position gets wrong.
            Rga<Character> shared = type(Rga.empty(), ALICE, "ac");
            Rga<Character> a = shared.insertAt(ALICE, 1, 'b');
            Rga<Character> b = shared.insertAt(BOB, 1, 'x');

            assertEquals(a.merge(b).text(), b.merge(a).text(), "both replicas read the same thing");
            assertEquals(4, a.merge(b).size(), "and nothing was dropped");
            assertTrue(a.merge(b).text().startsWith("a"), "anchors still hold");
            assertTrue(a.merge(b).text().endsWith("c"), "at both ends");
        });

        check("rga does not interleave two words typed at the same spot", () -> {
            // Character-by-character merging would produce "hwoerllodl". A causal tree keeps each
            // run contiguous because every character anchors to the one before it.
            Rga<Character> alice = type(Rga.empty(), ALICE, "hello");
            Rga<Character> bob = type(Rga.empty(), BOB, "world");
            String merged = alice.merge(bob).text();
            assertTrue(merged.equals("worldhello") || merged.equals("helloworld"),
                    "one whole word then the other, got: " + merged);
            assertEquals(merged, bob.merge(alice).text(), "and the same either way round");
        });

        check("rga can anchor an insert to a deleted element", () -> {
            // Bob types after a character alice has already deleted. The tombstone is still there
            // to hang it on, so the text lands where bob meant it to.
            Rga<Character> shared = type(Rga.empty(), ALICE, "abc");
            Dot b = shared.dotAt(1);
            Rga<Character> aliceDeletes = shared.removeAt(1);
            Rga<Character> bobTypes = shared.insertAfter(BOB, b, 'X');
            assertEquals("aXc", aliceDeletes.merge(bobTypes).text(), "X sits where b was");
        });

        check("rga refuses an anchor it has never seen", () -> {
            Rga<Character> r = type(Rga.empty(), ALICE, "a");
            assertThrows(IllegalArgumentException.class,
                    () -> r.insertAfter(BOB, Dot.of(CAROL, 99), 'x'), "unknown anchor");
        });

        check("rga handles a deep sequence without blowing the stack", () -> {
            // Typing one character after another makes a 2000-deep tree, which a recursive
            // traversal would not survive.
            Rga<Character> r = Rga.empty();
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                char c = (char) ('a' + (i % 26));
                r = r.insertAt(ALICE, i, c);
                expected.append(c);
            }
            assertEquals(expected.toString(), r.text(), "2000 characters, in order");
        });

        check("rga obeys the lattice laws", () -> {
            Laws.lattice("Rga", sequences());
            Laws.monotonic("Rga", sequences());
        });

        check("rga converges under any delivery order", () -> {
            Laws.converges("Rga", sequences(), new Random(SEED), TRIALS, s -> ((Rga<?>) s).toList());
        });

        check("rga converges across a gossiping cluster", () -> {
            Random random = new Random(SEED);
            Laws.gossip(
                    "Rga",
                    List.of(ALICE, BOB, CAROL),
                    Rga.<Character>empty(),
                    (state, replica, step) -> {
                        if (state.size() > 2 && random.nextInt(4) == 0) {
                            return state.removeAt(random.nextInt(state.size()));
                        }
                        char c = (char) ('a' + (step % 26));
                        return state.insertAt(replica, random.nextInt(state.size() + 1), c);
                    },
                    random,
                    300,
                    s -> s.toList());
        });

        // --- deltas: ship what changed, not the whole state ---
        check("every type's delta is complete, honest, lean and quiet", () -> {
            Laws.delta("GCounter", gcounters(), GCounter.empty());
            Laws.delta("PNCounter", pncounters(), PNCounter.empty());
            Laws.delta("GSet", gsets(), GSet.empty());
            Laws.delta("TwoPhaseSet", twoPhaseSets(), TwoPhaseSet.empty());
            Laws.delta("LwwRegister", registers(), LwwRegister.empty());
            Laws.delta("OrSet", orSets(), OrSet.empty());
            Laws.delta("LwwMap", lwwMaps(), LwwMap.empty());
            Laws.delta("Rga", sequences(), Rga.empty());
        });

        check("gcounter delta after one increment is one slot, however many replicas", () -> {
            GCounter big = GCounter.empty();
            for (int i = 0; i < 1000; i++) {
                big = big.increment(ReplicaId.of("replica-" + i), i + 1);
            }
            GCounter next = big.increment(ALICE, 2);
            GCounter d = next.delta(big);
            assertEquals(1, d.slots().size(), "only alice's slot moved");
            assertEquals(next, big.merge(d), "and that is enough to catch up");
        });

        check("orset delta after one add is one dot, and carries no tombstones", () -> {
            OrSet<String> big = OrSet.empty();
            for (int i = 0; i < 500; i++) {
                big = big.add(ALICE, "item" + i).remove("item" + i);
            }
            OrSet<String> next = big.add(BOB, "milk");
            OrSet<String> d = next.delta(big);
            assertEquals(Set.of("milk"), d.elements(), "just the new element");
            assertEquals(0, d.retiredDotCount(), "none of the 500 retired dots go back over the wire");
            assertEquals(next, big.merge(d), "and the receiver lands on the sender's state");
        });

        check("rga delta after one keystroke is one node", () -> {
            Rga<Character> doc = type(Rga.empty(), ALICE, "the quick brown fox jumps over the lazy dog");
            Rga<Character> next = doc.insertAt(BOB, 4, 'Q');
            Rga<Character> d = next.delta(doc);
            assertEquals(1, d.size(), "one inserted element");
            assertEquals(next.text(), doc.merge(d).text(), "it lands where bob typed it");
        });

        check("lwwregister sends nothing when its write would lose", () -> {
            LwwRegister<String> newer = LwwRegister.of("new", 9, BOB);
            LwwRegister<String> older = LwwRegister.of("old", 3, ALICE);
            assertTrue(older.delta(newer).isEmpty(), "the receiver already has a later write");
            assertEquals(older, older.delta(LwwRegister.empty()), "an empty receiver gets the write");
        });

        check("deltas can be batched, reordered and redelivered", () -> {
            GCounter base = GCounter.empty().increment(ALICE, 4);
            GCounter one = base.increment(BOB, 1);
            GCounter two = one.increment(CAROL, 2);
            GCounter d1 = one.delta(base);
            GCounter d2 = two.delta(one);
            GCounter batch = d2.merge(d1);
            assertEquals(two, base.merge(batch), "one merged batch");
            assertEquals(two, base.merge(d2).merge(d1).merge(d2), "out of order, d2 twice");
        });

        check("gcounter converges on deltas alone", () -> {
            Random random = new Random(SEED);
            Laws.deltaGossip(
                    "GCounter",
                    List.of(ALICE, BOB, CAROL),
                    GCounter.empty(),
                    (state, replica, step) -> state.increment(replica, 1 + random.nextInt(3)),
                    random,
                    300,
                    s -> s.value());
        });

        check("orset converges on deltas alone", () -> {
            Random random = new Random(SEED);
            Laws.deltaGossip(
                    "OrSet",
                    List.of(ALICE, BOB, CAROL),
                    OrSet.<String>empty(),
                    (state, replica, step) -> {
                        String item = "item" + random.nextInt(5);
                        return random.nextInt(3) == 0 ? state.remove(item) : state.add(replica, item);
                    },
                    random,
                    300,
                    s -> s.elements());
        });

        check("rga converges on deltas alone", () -> {
            Random random = new Random(SEED);
            Laws.deltaGossip(
                    "Rga",
                    List.of(ALICE, BOB, CAROL),
                    Rga.<Character>empty(),
                    (state, replica, step) -> {
                        if (state.size() > 2 && random.nextInt(4) == 0) {
                            return state.removeAt(random.nextInt(state.size()));
                        }
                        char c = (char) ('a' + (step % 26));
                        return state.insertAt(replica, random.nextInt(state.size() + 1), c);
                    },
                    random,
                    300,
                    s -> s.toList());
        });

        report();
    }

    // --- state generators for the law checks ---
    //
    // Each returns states that overlap partially: a shared prefix of history plus divergent
    // edits, which is the shape that actually catches merge bugs. Wholly unrelated states merge
    // too easily to prove much.

    private static List<GCounter> gcounters() {
        GCounter shared = GCounter.empty().increment(ALICE, 2);
        return List.of(
                GCounter.empty(),
                shared,
                shared.increment(ALICE, 5),
                shared.increment(BOB, 3),
                shared.increment(BOB, 3).increment(CAROL, 1));
    }

    private static List<PNCounter> pncounters() {
        PNCounter shared = PNCounter.empty().increment(ALICE, 10);
        return List.of(
                PNCounter.empty(),
                shared,
                shared.decrement(ALICE, 4),
                shared.decrement(BOB, 2).increment(BOB, 7),
                shared.increment(CAROL, 1).decrement(CAROL, 9));
    }

    private static List<GSet<String>> gsets() {
        GSet<String> shared = GSet.of("a");
        return List.of(shared, shared.add("b"), shared.add("c"), GSet.of("b", "c", "d"), GSet.empty());
    }

    private static List<TwoPhaseSet<String>> twoPhaseSets() {
        TwoPhaseSet<String> shared = TwoPhaseSet.<String>empty().add("a").add("b");
        return List.of(
                shared,
                shared.remove("a"),
                shared.add("c"),
                shared.remove("b").add("c"),
                TwoPhaseSet.<String>empty().add("a").remove("a"));
    }

    private static List<LwwRegister<String>> registers() {
        return List.of(
                LwwRegister.empty(),
                LwwRegister.of("a", 1, ALICE),
                LwwRegister.of("b", 2, BOB),
                LwwRegister.of("c", 2, CAROL),
                LwwRegister.of("tie", 2, ALICE));
    }

    private static List<OrSet<String>> orSets() {
        OrSet<String> shared = OrSet.<String>empty().add(ALICE, "x").add(ALICE, "y");
        return List.of(
                shared,
                shared.remove("x"),
                shared.add(BOB, "x"),
                shared.remove("y").add(BOB, "z"),
                shared.add(CAROL, "y").remove("z"));
    }

    private static List<LwwMap<String, String>> lwwMaps() {
        LwwMap<String, String> shared = LwwMap.<String, String>empty().put("k", "base", 1, ALICE);
        return List.of(
                LwwMap.empty(),
                shared,
                shared.put("k", "alice", 5, ALICE),
                shared.put("k", "bob", 5, BOB),
                shared.remove("k", 4, CAROL).put("other", "v", 2, CAROL));
    }

    private static List<Rga<Character>> sequences() {
        Rga<Character> shared = type(Rga.empty(), ALICE, "abc");
        return List.of(
                Rga.empty(),
                shared,
                shared.insertAt(ALICE, 1, 'X'),
                shared.insertAt(BOB, 1, 'Y').insertAt(BOB, 0, 'Z'),
                shared.removeAt(2).insertAt(CAROL, 0, 'Q'));
    }

    private static Rga<Character> type(Rga<Character> start, ReplicaId replica, String text) {
        Rga<Character> r = start;
        for (int i = 0; i < text.length(); i++) {
            r = r.insertAt(replica, r.size(), text.charAt(i));
        }
        return r;
    }

    // --- runner ---

    private static void check(String name, Runnable body) {
        try {
            body.run();
            passed++;
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            failures.add(name + "\n      " + message.replace("\n", "\n      "));
        }
    }

    private static void report() {
        System.out.println();
        for (String failure : failures) {
            System.out.println("  FAIL  " + failure);
        }
        int total = passed + failures.size();
        System.out.printf("  %d/%d passed%n%n", passed, total);
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void assertTrue(boolean condition, String why) {
        if (!condition) {
            throw new AssertionError("expected true: " + why);
        }
    }

    private static void assertFalse(boolean condition, String why) {
        if (condition) {
            throw new AssertionError("expected false: " + why);
        }
    }

    private static void assertEquals(Object expected, Object actual, String why) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(why + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, Runnable body, String why) {
        try {
            body.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError(
                    why + "\n  expected: " + expected.getSimpleName() + "\n  actual:   " + actual);
        }
        throw new AssertionError(why + "\n  expected " + expected.getSimpleName() + ", nothing was thrown");
    }

    private Tests() {}
}
