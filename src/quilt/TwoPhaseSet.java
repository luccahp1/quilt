package quilt;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A set where each element can be added and then removed exactly once. Removal is permanent.
 *
 * <p>Two grow-only sets: things added, and things retired. An element is present when it is in the
 * first and not the second. Both halves merge by union, so the pair merges cleanly too.
 *
 * <p>The catch, and the reason {@link OrSet} exists, is that <b>remove wins forever</b>. Once an
 * element is in the tombstone set no future add can bring it back, on any replica, ever. That is
 * genuinely what you want for identifiers that are retired for good - a revoked key, a deleted
 * account id - and genuinely wrong for anything a user might re-add, like a tag or a shopping
 * cart line.
 *
 * <p>Pick this when "gone" means gone. Pick {@link OrSet} otherwise.
 *
 * @param <E> the element type; must be immutable and have value-based equality
 */
public final class TwoPhaseSet<E> implements Crdt<TwoPhaseSet<E>> {

    private final GSet<E> added;
    private final GSet<E> removed;

    private TwoPhaseSet(GSet<E> added, GSet<E> removed) {
        this.added = added;
        this.removed = removed;
    }

    public static <E> TwoPhaseSet<E> empty() {
        return new TwoPhaseSet<>(GSet.empty(), GSet.empty());
    }

    public TwoPhaseSet<E> add(E element) {
        return new TwoPhaseSet<>(added.add(element), removed);
    }

    /**
     * Retires {@code element} permanently. Removing something that is not present is a no-op
     * rather than an error, since on another replica it may well have been present.
     */
    public TwoPhaseSet<E> remove(E element) {
        Objects.requireNonNull(element, "element");
        if (!added.contains(element)) {
            return this;
        }
        return new TwoPhaseSet<>(added, removed.add(element));
    }

    public boolean contains(E element) {
        return added.contains(element) && !removed.contains(element);
    }

    /** True once {@code element} has been retired, whether or not it was ever added here. */
    public boolean isTombstoned(E element) {
        return removed.contains(element);
    }

    public Set<E> elements() {
        Set<E> live = new LinkedHashSet<>(added.elements());
        live.removeAll(removed.elements());
        return live;
    }

    public int size() {
        return elements().size();
    }

    public boolean isEmpty() {
        return elements().isEmpty();
    }

    @Override
    public TwoPhaseSet<E> merge(TwoPhaseSet<E> other) {
        Objects.requireNonNull(other, "other");
        return new TwoPhaseSet<>(added.merge(other.added), removed.merge(other.removed));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TwoPhaseSet<?> t && added.equals(t.added) && removed.equals(t.removed);
    }

    @Override
    public int hashCode() {
        return 31 * added.hashCode() + removed.hashCode();
    }

    @Override
    public String toString() {
        return "TwoPhaseSet" + elements();
    }
}
