package quilt;

import java.util.Objects;

/**
 * A counter that goes both ways, built from two counters that only go up.
 *
 * <p>You cannot merge a decrementable counter by taking a maximum: two replicas that each
 * decrement once would merge to a single decrement and one of them would vanish. So the standard
 * move is to keep increments and decrements in separate {@link GCounter}s, merge each of those by
 * maximum, and subtract only at read time.
 *
 * <p>The cost is that the state never shrinks. A slot that has gone up and back down still carries
 * both totals forever, because forgetting either one would break idempotence.
 */
public final class PNCounter implements Crdt<PNCounter> {

    private final GCounter increments;
    private final GCounter decrements;

    private PNCounter(GCounter increments, GCounter decrements) {
        this.increments = increments;
        this.decrements = decrements;
    }

    public static PNCounter empty() {
        return new PNCounter(GCounter.empty(), GCounter.empty());
    }

    public PNCounter increment(ReplicaId replica, long by) {
        return new PNCounter(increments.increment(replica, by), decrements);
    }

    public PNCounter increment(ReplicaId replica) {
        return increment(replica, 1);
    }

    public PNCounter decrement(ReplicaId replica, long by) {
        return new PNCounter(increments, decrements.increment(replica, by));
    }

    public PNCounter decrement(ReplicaId replica) {
        return decrement(replica, 1);
    }

    /** Total increments minus total decrements. May be negative. */
    public long value() {
        return Math.subtractExact(increments.value(), decrements.value());
    }

    @Override
    public PNCounter merge(PNCounter other) {
        Objects.requireNonNull(other, "other");
        return new PNCounter(
                increments.merge(other.increments), decrements.merge(other.decrements));
    }

    @Override
    public PNCounter delta(PNCounter since) {
        Objects.requireNonNull(since, "since");
        return new PNCounter(
                increments.delta(since.increments), decrements.delta(since.decrements));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PNCounter p
                && increments.equals(p.increments)
                && decrements.equals(p.decrements);
    }

    @Override
    public int hashCode() {
        return 31 * increments.hashCode() + decrements.hashCode();
    }

    @Override
    public String toString() {
        return "PNCounter[+" + increments.value() + " -" + decrements.value() + "]=" + value();
    }
}
