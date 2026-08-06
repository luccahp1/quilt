package quilt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * The properties every type in this library claims, checked against generated states rather than
 * hand-picked ones.
 *
 * <p>Example-based tests are good at catching "this method returns the wrong thing" and bad at
 * catching "this converges for the four orderings I happened to think of". Convergence bugs hide
 * in the orderings you did not try, so these checks enumerate all pairs and triples for the
 * algebraic laws, then hammer random permutations and random pairwise gossip for the behaviour
 * that actually matters.
 *
 * <p>Everything here is seeded. A failure reproduces exactly, which is the difference between a
 * useful randomised test and a flaky one.
 */
final class Laws {

    private Laws() {}

    /**
     * Checks that merge really is a join: commutative, associative and idempotent over every
     * combination drawn from {@code states}.
     *
     * <p>These three are checked exhaustively rather than sampled, because the state list is small
     * and a missed pair is a missed bug.
     */
    static <S extends Crdt<S>> void lattice(String label, List<S> states) {
        if (states.size() < 3) {
            throw new AssertionError(label + ": need at least 3 states to check associativity");
        }

        for (S a : states) {
            if (!a.merge(a).equals(a)) {
                throw new AssertionError(
                        label + ": not idempotent. a.merge(a) = " + a.merge(a) + ", a = " + a);
            }
        }

        for (S a : states) {
            for (S b : states) {
                if (!a.merge(b).equals(b.merge(a))) {
                    throw new AssertionError(
                            label
                                    + ": not commutative.\n  a.merge(b) = "
                                    + a.merge(b)
                                    + "\n  b.merge(a) = "
                                    + b.merge(a));
                }
            }
        }

        for (S a : states) {
            for (S b : states) {
                for (S c : states) {
                    S left = a.merge(b).merge(c);
                    S right = a.merge(b.merge(c));
                    if (!left.equals(right)) {
                        throw new AssertionError(
                                label
                                        + ": not associative.\n  (a+b)+c = "
                                        + left
                                        + "\n  a+(b+c) = "
                                        + right);
                    }
                }
            }
        }
    }

    /**
     * Checks that merging is monotonic: the result is never behind either input.
     *
     * <p>Expressed as absorption, which is the lattice identity that says exactly this without
     * needing a separate "is at least as advanced as" relation: if the join of a and b is at least
     * a, then joining it with a again changes nothing.
     */
    static <S extends Crdt<S>> void monotonic(String label, List<S> states) {
        for (S a : states) {
            for (S b : states) {
                S joined = a.merge(b);
                if (!joined.merge(a).equals(joined)) {
                    throw new AssertionError(
                            label + ": merging lost information from a. joined = " + joined);
                }
                if (!joined.merge(b).equals(joined)) {
                    throw new AssertionError(
                            label + ": merging lost information from b. joined = " + joined);
                }
            }
        }
    }

    /**
     * Checks that the same set of replica states reaches the same place regardless of the order
     * they are merged in, and regardless of how the merges are grouped.
     *
     * <p>Random permutations cover reordering. Random binary groupings cover the fact that a real
     * network merges partial results into partial results, not a neat left fold.
     */
    static <S extends Crdt<S>> void converges(
            String label, List<S> replicas, Random random, int trials, Function<S, Object> view) {

        S expected = foldLeft(replicas);
        Object expectedView = view.apply(expected);

        for (int trial = 0; trial < trials; trial++) {
            List<S> shuffled = new ArrayList<>(replicas);
            Collections.shuffle(shuffled, random);

            S folded = foldLeft(shuffled);
            if (!folded.equals(expected)) {
                throw new AssertionError(
                        label
                                + ": permutation "
                                + trial
                                + " diverged.\n  expected = "
                                + expected
                                + "\n  got      = "
                                + folded);
            }

            S grouped = mergeRandomlyGrouped(shuffled, random);
            if (!grouped.equals(expected)) {
                throw new AssertionError(
                        label
                                + ": grouping "
                                + trial
                                + " diverged.\n  expected = "
                                + expected
                                + "\n  got      = "
                                + grouped);
            }
            if (!view.apply(grouped).equals(expectedView)) {
                throw new AssertionError(
                        label
                                + ": states matched but the visible value did not.\n  expected = "
                                + expectedView
                                + "\n  got      = "
                                + view.apply(grouped));
            }
        }
    }

    /**
     * Simulates a gossiping cluster: replicas take turns applying local operations and syncing
     * with a random peer, and at the end everyone syncs with everyone.
     *
     * <p>This is the test that looks like production. Operations interleave with partial,
     * out-of-order sync, so a replica routinely applies a local edit on top of a state that is
     * missing edits it will only learn about later - the exact situation that breaks anything
     * relying on a global order.
     *
     * @param apply given a replica's state, its id and the step number, returns its next state
     */
    static <S extends Crdt<S>> void gossip(
            String label,
            List<ReplicaId> ids,
            S initial,
            Operation<S> apply,
            Random random,
            int steps,
            Function<S, Object> view) {

        List<S> states = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            states.add(initial);
        }

        for (int step = 0; step < steps; step++) {
            int actor = random.nextInt(states.size());
            states.set(actor, apply.apply(states.get(actor), ids.get(actor), step));

            int peer = random.nextInt(states.size());
            if (peer != actor) {
                // A real sync is one-directional: the peer learns what the actor knows.
                states.set(peer, states.get(peer).merge(states.get(actor)));
            }
        }

        S all = foldLeft(states);
        List<S> settled = new ArrayList<>();
        for (S state : states) {
            settled.add(state.merge(all));
        }

        S first = settled.get(0);
        for (int i = 1; i < settled.size(); i++) {
            if (!settled.get(i).equals(first)) {
                throw new AssertionError(
                        label
                                + ": replica "
                                + ids.get(i)
                                + " did not converge.\n  replica 0 = "
                                + view.apply(first)
                                + "\n  replica "
                                + i
                                + " = "
                                + view.apply(settled.get(i)));
            }
        }
        lattice(label + " (gossiped states)", states.subList(0, Math.min(4, states.size())));
    }

    /** A local operation performed by one replica at one step of a gossip simulation. */
    interface Operation<S> {
        S apply(S state, ReplicaId replica, int step);
    }

    private static <S extends Crdt<S>> S foldLeft(List<S> states) {
        S acc = states.get(0);
        for (int i = 1; i < states.size(); i++) {
            acc = acc.merge(states.get(i));
        }
        return acc;
    }

    /** Merges by repeatedly collapsing two random entries, producing an arbitrary merge tree. */
    private static <S extends Crdt<S>> S mergeRandomlyGrouped(List<S> states, Random random) {
        List<S> pool = new ArrayList<>(states);
        while (pool.size() > 1) {
            int i = random.nextInt(pool.size());
            int j = random.nextInt(pool.size());
            if (i == j) {
                continue;
            }
            S merged = pool.get(i).merge(pool.get(j));
            pool.remove(Math.max(i, j));
            pool.remove(Math.min(i, j));
            pool.add(merged);
        }
        return pool.get(0);
    }
}
