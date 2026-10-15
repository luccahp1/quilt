package quilt;

import java.util.Objects;

/**
 * A single slot holding the most recent write, where "most recent" is decided by a timestamp you
 * supply.
 *
 * <p>Last-write-wins is the bluntest conflict resolution there is: of two concurrent writes, one
 * survives and the other is silently discarded. That is a real data loss, not a merge, and it is
 * the right trade only when the field is genuinely a single value with no meaningful combination -
 * a display name, a status flag, a cursor position. For anything where both edits matter, reach
 * for a set or a sequence instead.
 *
 * <p>Timestamps are passed in rather than read from a clock, so the caller decides between wall
 * time, a Lamport counter, or a hybrid clock, and so tests are deterministic. Wall clocks across
 * machines disagree; a register fed skewed clocks will happily let a slow replica's stale write
 * beat a fresh one. That is a property of the timestamps, not of the merge.
 *
 * <p>Ordering is by timestamp, then writer id, then the value's string form. The first two are the
 * interesting ones. The third exists only so that merge is provably commutative even if a caller
 * manages to produce two different values with an identical timestamp and writer, which would
 * otherwise make {@code a.merge(b)} and {@code b.merge(a)} disagree.
 *
 * @param <V> the value type; must be immutable and have value-based equality
 */
public final class LwwRegister<V> implements Crdt<LwwRegister<V>> {

    private final V value;
    private final long timestamp;
    private final ReplicaId writer;

    private LwwRegister(V value, long timestamp, ReplicaId writer) {
        this.value = value;
        this.timestamp = timestamp;
        this.writer = writer;
    }

    /** A register that has never been written. Merging it with anything yields the other side. */
    public static <V> LwwRegister<V> empty() {
        return new LwwRegister<>(null, 0L, null);
    }

    public static <V> LwwRegister<V> of(V value, long timestamp, ReplicaId writer) {
        return LwwRegister.<V>empty().set(value, timestamp, writer);
    }

    /**
     * Returns a register holding {@code value}, stamped and attributed.
     *
     * <p>The write is recorded unconditionally. Whether it actually wins is decided later, at
     * merge time, against whatever the other replicas did.
     *
     * @param value the new value; may be null to represent a cleared slot
     */
    public LwwRegister<V> set(V value, long timestamp, ReplicaId writer) {
        Objects.requireNonNull(writer, "writer");
        return new LwwRegister<>(value, timestamp, writer);
    }

    /** The current value, or null if never written or explicitly cleared. */
    public V value() {
        return value;
    }

    public long timestamp() {
        return timestamp;
    }

    /** Who wrote the surviving value, or null if never written. */
    public ReplicaId writer() {
        return writer;
    }

    public boolean isEmpty() {
        return writer == null;
    }

    @Override
    public LwwRegister<V> merge(LwwRegister<V> other) {
        Objects.requireNonNull(other, "other");
        return compareWrites(other) >= 0 ? this : other;
    }

    /**
     * This write, if it would beat what {@code since} holds, and the empty register otherwise. A
     * register is one indivisible slot, so the only choices are all of it or nothing.
     */
    @Override
    public LwwRegister<V> delta(LwwRegister<V> since) {
        Objects.requireNonNull(since, "since");
        return compareWrites(since) > 0 ? this : empty();
    }

    /** Positive when this write beats {@code other}'s, negative when it loses, zero when equal. */
    private int compareWrites(LwwRegister<V> other) {
        if (writer == null && other.writer == null) {
            return 0;
        }
        if (writer == null) {
            return -1;
        }
        if (other.writer == null) {
            return 1;
        }
        int byTime = Long.compare(timestamp, other.timestamp);
        if (byTime != 0) {
            return byTime;
        }
        int byWriter = writer.compareTo(other.writer);
        if (byWriter != 0) {
            return byWriter;
        }
        return String.valueOf(value).compareTo(String.valueOf(other.value));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LwwRegister<?> r
                && timestamp == r.timestamp
                && Objects.equals(writer, r.writer)
                && Objects.equals(value, r.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, timestamp, writer);
    }

    @Override
    public String toString() {
        return isEmpty() ? "LwwRegister[empty]" : "LwwRegister[" + value + " @" + timestamp + " by " + writer + "]";
    }
}
