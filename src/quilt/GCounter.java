package quilt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A counter that only goes up.
 *
 * <p>The trick is that there is no single number. Each replica owns one slot and is the only
 * writer to it, so its slot never conflicts with anyone. Merging takes the maximum per slot, which
 * is a least upper bound, and the value everybody reads is the sum of the slots.
 *
 * <p>Taking the maximum rather than the sum is the whole reason redelivery is harmless: receiving
 * the same state twice cannot double-count, because the second max is a no-op.
 *
 * <p>This is the building block for {@link PNCounter}, which is the one you usually want.
 */
public final class GCounter implements Crdt<GCounter> {

    private final Map<ReplicaId, Long> counts;

    private GCounter(Map<ReplicaId, Long> counts) {
        this.counts = Collections.unmodifiableMap(counts);
    }

    public static GCounter empty() {
        return new GCounter(new TreeMap<>());
    }

    /**
     * Returns a counter with {@code by} added to {@code replica}'s slot.
     *
     * @param by how much to add; must be positive, since a grow-only counter that can shrink is
     *     not grow-only and would break the merge law
     */
    public GCounter increment(ReplicaId replica, long by) {
        Objects.requireNonNull(replica, "replica");
        if (by <= 0) {
            throw new IllegalArgumentException("increment must be positive, got " + by);
        }
        Map<ReplicaId, Long> next = new TreeMap<>(counts);
        next.merge(replica, by, Math::addExact);
        return new GCounter(next);
    }

    public GCounter increment(ReplicaId replica) {
        return increment(replica, 1);
    }

    /** The sum of every replica's slot. */
    public long value() {
        long total = 0;
        for (long slot : counts.values()) {
            total = Math.addExact(total, slot);
        }
        return total;
    }

    /** What {@code replica} has contributed, or zero if it has never incremented. */
    public long valueOf(ReplicaId replica) {
        return counts.getOrDefault(replica, 0L);
    }

    /** The per-replica slots, unmodifiable. Exposed because {@link PNCounter} needs to merge them. */
    public Map<ReplicaId, Long> slots() {
        return counts;
    }

    @Override
    public GCounter merge(GCounter other) {
        Objects.requireNonNull(other, "other");
        Map<ReplicaId, Long> next = new TreeMap<>(counts);
        for (Map.Entry<ReplicaId, Long> e : other.counts.entrySet()) {
            next.merge(e.getKey(), e.getValue(), Math::max);
        }
        return new GCounter(next);
    }

    /** The slots that have moved past where {@code since} has them, at their current values. */
    @Override
    public GCounter delta(GCounter since) {
        Objects.requireNonNull(since, "since");
        Map<ReplicaId, Long> ahead = new TreeMap<>();
        for (Map.Entry<ReplicaId, Long> e : counts.entrySet()) {
            if (e.getValue() > since.valueOf(e.getKey())) {
                ahead.put(e.getKey(), e.getValue());
            }
        }
        return new GCounter(ahead);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GCounter g && counts.equals(g.counts);
    }

    @Override
    public int hashCode() {
        return counts.hashCode();
    }

    @Override
    public String toString() {
        return "GCounter" + new LinkedHashMap<>(counts) + "=" + value();
    }
}
