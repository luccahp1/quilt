package quilt;

import java.util.Objects;

/**
 * A globally unique tag for a single event: which replica caused it, and a counter that replica
 * chose.
 *
 * <p>Dots are what let an add-wins set tell "the element you removed" apart from "an element with
 * the same value that somebody added concurrently". Comparing values cannot do that; comparing
 * identities can.
 *
 * <p>The natural ordering is by counter, then by replica. It is a total order and every replica
 * computes it identically, which is what {@link Rga} uses to linearize concurrent inserts without
 * talking to anyone.
 *
 * <p>How the counter is chosen is up to the owner, and the two users here choose differently.
 * {@link OrSet} numbers each replica's dots independently, because it only ever needs them to be
 * distinct. {@link Rga} advances a Lamport clock across all replicas, because it compares dots to
 * decide order and a per-replica counter would make that comparison meaningless. Both satisfy the
 * only rule that matters: a given replica never mints the same counter twice.
 *
 * @param replica who minted this dot
 * @param counter that replica's chosen counter, at least 1 and never reused by the same replica
 */
public record Dot(ReplicaId replica, long counter) implements Comparable<Dot> {

    public Dot {
        Objects.requireNonNull(replica, "replica");
        if (counter < 1) {
            throw new IllegalArgumentException("counter must be >= 1, got " + counter);
        }
    }

    public static Dot of(ReplicaId replica, long counter) {
        return new Dot(replica, counter);
    }

    @Override
    public int compareTo(Dot other) {
        int byCounter = Long.compare(counter, other.counter);
        return byCounter != 0 ? byCounter : replica.compareTo(other.replica);
    }

    @Override
    public String toString() {
        return replica + ":" + counter;
    }
}
