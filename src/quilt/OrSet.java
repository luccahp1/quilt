package quilt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * An observed-remove set: the set you actually want most of the time.
 *
 * <p>{@link TwoPhaseSet} cannot un-retire an element, and a naive "adds minus removes" set loses
 * to concurrency in the other direction. The fix is to stop tracking elements and start tracking
 * <em>events</em>. Every add mints a fresh {@link Dot}. Removing an element retires only the dots
 * this replica has actually observed for it. An element is present when at least one of its dots
 * is still standing.
 *
 * <p>That gives add-wins semantics for free. If Alice removes "milk" while Bob concurrently adds
 * "milk", Bob's add carries a dot Alice never saw and so never retired, and "milk" survives the
 * merge. Losing an item you just typed is worse than keeping one you just deleted, which is why
 * add-wins is the usual default for shopping carts, tag sets and presence lists.
 *
 * <p>Removing and re-adding works exactly as you would hope, because the re-add is a new event
 * with a new dot rather than a resurrection of the old one.
 *
 * <p><b>The cost:</b> retired dots are kept forever. Merge is a pure union in both halves, which
 * is what makes convergence provable, but it also means a set that has churned a million times
 * carries a million dots even if it is empty. Bounding that needs causal context - a version
 * vector summarising "every dot from replica A up to 400" instead of listing them - which is a
 * real optimisation and a real increase in subtlety. This implementation takes the simple, easy
 * to verify version and pays the space.
 *
 * @param <E> the element type; must be immutable and have value-based equality
 */
public final class OrSet<E> implements Crdt<OrSet<E>> {

    private final Map<E, Set<Dot>> adds;
    private final Set<Dot> retired;

    private OrSet(Map<E, Set<Dot>> adds, Set<Dot> retired) {
        this.adds = Collections.unmodifiableMap(adds);
        this.retired = Collections.unmodifiableSet(retired);
    }

    public static <E> OrSet<E> empty() {
        return new OrSet<>(new LinkedHashMap<>(), new TreeSet<>());
    }

    /**
     * Adds {@code element} on behalf of {@code replica}, minting a fresh dot for the event.
     *
     * <p>Adding something already present is still a real event with its own dot, which is what
     * makes a concurrent remove-then-add resolve to present.
     */
    public OrSet<E> add(ReplicaId replica, E element) {
        Objects.requireNonNull(replica, "replica");
        Objects.requireNonNull(element, "element");

        Map<E, Set<Dot>> nextAdds = copyAdds();
        nextAdds.computeIfAbsent(element, k -> new TreeSet<>()).add(nextDot(replica));
        return new OrSet<>(nextAdds, new TreeSet<>(retired));
    }

    /**
     * Removes {@code element} by retiring every dot this replica has observed for it.
     *
     * <p>Dots minted concurrently elsewhere are invisible here and survive, which is the add-wins
     * behaviour. Removing something absent is a no-op.
     */
    public OrSet<E> remove(E element) {
        Objects.requireNonNull(element, "element");

        Set<Dot> observed = adds.get(element);
        if (observed == null || observed.isEmpty()) {
            return this;
        }
        Set<Dot> nextRetired = new TreeSet<>(retired);
        nextRetired.addAll(observed);
        return new OrSet<>(copyAdds(), nextRetired);
    }

    public boolean contains(E element) {
        Set<Dot> dots = adds.get(element);
        if (dots == null) {
            return false;
        }
        for (Dot dot : dots) {
            if (!retired.contains(dot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The elements currently present.
     *
     * <p>Iteration order is unspecified and may differ between replicas that hold equal states;
     * only the set contents are guaranteed to converge.
     */
    public Set<E> elements() {
        Set<E> live = new LinkedHashSet<>();
        for (E element : adds.keySet()) {
            if (contains(element)) {
                live.add(element);
            }
        }
        return live;
    }

    public int size() {
        return elements().size();
    }

    public boolean isEmpty() {
        return elements().isEmpty();
    }

    /** How many retired dots the state is carrying. The price of add-wins, made visible. */
    public int retiredDotCount() {
        return retired.size();
    }

    @Override
    public OrSet<E> merge(OrSet<E> other) {
        Objects.requireNonNull(other, "other");

        Map<E, Set<Dot>> nextAdds = copyAdds();
        for (Map.Entry<E, Set<Dot>> entry : other.adds.entrySet()) {
            nextAdds.computeIfAbsent(entry.getKey(), k -> new TreeSet<>()).addAll(entry.getValue());
        }
        Set<Dot> nextRetired = new TreeSet<>(retired);
        nextRetired.addAll(other.retired);
        return new OrSet<>(nextAdds, nextRetired);
    }

    private Map<E, Set<Dot>> copyAdds() {
        Map<E, Set<Dot>> copy = new LinkedHashMap<>();
        for (Map.Entry<E, Set<Dot>> entry : adds.entrySet()) {
            copy.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * The next unused dot for {@code replica}, one past the highest counter this state has ever
     * seen from it - including retired dots, so a counter is never reused after a removal.
     */
    private Dot nextDot(ReplicaId replica) {
        long highest = 0;
        for (Set<Dot> dots : adds.values()) {
            highest = highestFor(replica, dots, highest);
        }
        highest = highestFor(replica, retired, highest);
        return new Dot(replica, highest + 1);
    }

    private static long highestFor(ReplicaId replica, Set<Dot> dots, long soFar) {
        long highest = soFar;
        for (Dot dot : dots) {
            if (dot.replica().equals(replica) && dot.counter() > highest) {
                highest = dot.counter();
            }
        }
        return highest;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OrSet<?> s && adds.equals(s.adds) && retired.equals(s.retired);
    }

    @Override
    public int hashCode() {
        return 31 * adds.hashCode() + retired.hashCode();
    }

    @Override
    public String toString() {
        return "OrSet" + elements();
    }
}
