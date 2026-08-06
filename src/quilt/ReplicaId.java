package quilt;

import java.util.Objects;

/**
 * The identity of one replica: a laptop, a browser tab, a server, a phone that has been in a
 * tunnel for an hour.
 *
 * <p>The only hard requirement is uniqueness. Two live replicas sharing an id will mint colliding
 * {@link Dot}s and silently lose writes, which is the one failure mode in this library that the
 * merge laws cannot protect you from. A UUID per process is the usual answer.
 *
 * <p>Ordering is lexicographic. It carries no meaning beyond being a deterministic tiebreak that
 * every replica computes the same way.
 */
public record ReplicaId(String value) implements Comparable<ReplicaId> {

    public ReplicaId {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("replica id must not be empty");
        }
    }

    public static ReplicaId of(String value) {
        return new ReplicaId(value);
    }

    @Override
    public int compareTo(ReplicaId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
