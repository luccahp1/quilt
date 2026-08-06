package quilt;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A set you can only add to. Merge is union.
 *
 * <p>The simplest lattice in the library, and worth reading first: union is obviously commutative,
 * associative and idempotent, so a grow-only set is convergent almost by accident. Every other
 * type here is a variation on making something useful out of structures that merge this cleanly.
 *
 * <p>Useful on its own for append-only collections - tags observed, nodes seen, files uploaded -
 * and as the two halves of {@link TwoPhaseSet}.
 *
 * @param <E> the element type; must be immutable and have value-based equality
 */
public final class GSet<E> implements Crdt<GSet<E>> {

    private final Set<E> elements;

    private GSet(Set<E> elements) {
        this.elements = Collections.unmodifiableSet(elements);
    }

    public static <E> GSet<E> empty() {
        return new GSet<>(new LinkedHashSet<>());
    }

    @SafeVarargs
    public static <E> GSet<E> of(E... elements) {
        GSet<E> set = empty();
        for (E e : elements) {
            set = set.add(e);
        }
        return set;
    }

    public GSet<E> add(E element) {
        Objects.requireNonNull(element, "element");
        if (elements.contains(element)) {
            return this;
        }
        Set<E> next = new LinkedHashSet<>(elements);
        next.add(element);
        return new GSet<>(next);
    }

    public boolean contains(E element) {
        return elements.contains(element);
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /** The elements, unmodifiable. */
    public Set<E> elements() {
        return elements;
    }

    @Override
    public GSet<E> merge(GSet<E> other) {
        Objects.requireNonNull(other, "other");
        Set<E> next = new LinkedHashSet<>(elements);
        next.addAll(other.elements);
        return new GSet<>(next);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GSet<?> g && elements.equals(g.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public String toString() {
        return "GSet" + elements;
    }
}
