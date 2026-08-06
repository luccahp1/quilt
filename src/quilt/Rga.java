package quilt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A replicated sequence: the structure behind collaborative text editors.
 *
 * <p>Sequences are the hard case. Indices are useless as coordinates, because "insert at position
 * 4" means something different the moment anyone else inserts at position 2. So positions are not
 * used at all. Every element instead records <em>which element it was typed after</em>, by
 * {@link Dot}. That anchor is stable no matter what else arrives, so an edit never lands in the
 * wrong place.
 *
 * <p>The elements therefore form a tree, not a list: the root is the start of the document, and
 * each element is a child of whatever it followed. Reading the sequence is a preorder walk of that
 * tree, where siblings - elements typed after the same anchor, which is exactly what concurrent
 * inserts at the same spot produce - are ordered by descending dot. Every replica sorts them the
 * same way, so every replica reads the same string without exchanging a word about it.
 *
 * <p>Because the state is a set of nodes plus a set of tombstones and merge is union on both, the
 * lattice laws hold trivially. All the difficulty is pushed into the traversal, which is a pure
 * function of the node set. That is the whole design: make the merge boring and the read clever.
 *
 * <p>Deleted elements stay as tombstones, since later inserts may be anchored to them and losing
 * an anchor would orphan text.
 *
 * <pre>{@code
 * ReplicaId alice = ReplicaId.of("alice");
 * ReplicaId bob = ReplicaId.of("bob");
 *
 * Rga<Character> a = Rga.<Character>empty().insertAt(alice, 0, 'h').insertAt(alice, 1, 'i');
 * Rga<Character> b = a.insertAt(bob, 2, '!');
 * a = a.insertAt(alice, 2, '?');
 *
 * a.merge(b).toList();   // same order as b.merge(a).toList(), always
 * }</pre>
 *
 * @param <E> the element type; must be immutable and have value-based equality
 */
public final class Rga<E> implements Crdt<Rga<E>> {

    /**
     * One element and the anchor it was inserted after.
     *
     * @param parent the dot this element follows, or null for the head of the sequence
     */
    private record Node<E>(Dot id, E value, Dot parent) {}

    private static final Comparator<Node<?>> SIBLING_ORDER =
            Comparator.comparing(Node::id, Comparator.reverseOrder());

    private final Map<Dot, Node<E>> nodes;
    private final Set<Dot> tombstones;

    private Rga(Map<Dot, Node<E>> nodes, Set<Dot> tombstones) {
        this.nodes = Collections.unmodifiableMap(nodes);
        this.tombstones = Collections.unmodifiableSet(tombstones);
    }

    public static <E> Rga<E> empty() {
        return new Rga<>(new TreeMap<>(), new TreeSet<>());
    }

    /**
     * Inserts {@code value} directly after the element identified by {@code anchor}.
     *
     * <p>This is the primitive operation and the one to use if you are holding dots rather than
     * indices, since it is immune to concurrent edits elsewhere in the sequence.
     *
     * @param anchor the dot to insert after, or null to insert at the head
     * @throws IllegalArgumentException if {@code anchor} is not an element of this sequence
     */
    public Rga<E> insertAfter(ReplicaId replica, Dot anchor, E value) {
        Objects.requireNonNull(replica, "replica");
        Objects.requireNonNull(value, "value");
        if (anchor != null && !nodes.containsKey(anchor)) {
            throw new IllegalArgumentException("anchor " + anchor + " is not in this sequence");
        }

        Dot id = nextDot(replica);
        Map<Dot, Node<E>> next = new TreeMap<>(nodes);
        next.put(id, new Node<>(id, value, anchor));
        return new Rga<>(next, new TreeSet<>(tombstones));
    }

    /**
     * Inserts {@code value} at a visible index, resolving that index to a stable anchor first.
     *
     * <p>Convenient, and what a UI actually has, but remember the index is interpreted against
     * <em>this</em> replica's current view. That is the correct behaviour - it is where the user
     * was looking when they typed - not a limitation.
     *
     * @param index 0 to insert at the head, {@link #size()} to append
     */
    public Rga<E> insertAt(ReplicaId replica, int index, E value) {
        int size = size();
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index " + index + " outside 0.." + size);
        }
        return insertAfter(replica, index == 0 ? null : dotAt(index - 1), value);
    }

    /** Tombstones the element at a visible index. The node itself stays as an anchor. */
    public Rga<E> removeAt(int index) {
        Dot target = dotAt(index);
        Set<Dot> next = new TreeSet<>(tombstones);
        next.add(target);
        return new Rga<>(new TreeMap<>(nodes), next);
    }

    /** Tombstones {@code dot}. A no-op if it is unknown or already removed. */
    public Rga<E> remove(Dot dot) {
        Objects.requireNonNull(dot, "dot");
        if (!nodes.containsKey(dot) || tombstones.contains(dot)) {
            return this;
        }
        Set<Dot> next = new TreeSet<>(tombstones);
        next.add(dot);
        return new Rga<>(new TreeMap<>(nodes), next);
    }

    /** The visible elements, in sequence order. */
    public List<E> toList() {
        List<E> out = new ArrayList<>();
        for (Node<E> node : walk()) {
            if (!tombstones.contains(node.id())) {
                out.add(node.value());
            }
        }
        return out;
    }

    /** The dot of the visible element at {@code index}. */
    public Dot dotAt(int index) {
        int seen = 0;
        for (Node<E> node : walk()) {
            if (tombstones.contains(node.id())) {
                continue;
            }
            if (seen == index) {
                return node.id();
            }
            seen++;
        }
        throw new IndexOutOfBoundsException("index " + index + " outside 0.." + (seen - 1));
    }

    public E get(int index) {
        List<E> visible = toList();
        return visible.get(index);
    }

    /** The number of visible elements, excluding tombstones. */
    public int size() {
        int count = 0;
        for (Dot id : nodes.keySet()) {
            if (!tombstones.contains(id)) {
                count++;
            }
        }
        return count;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** How many tombstoned elements the state is still carrying as anchors. */
    public int tombstoneCount() {
        return tombstones.size();
    }

    /** Renders a sequence of characters as a string. A convenience for text documents. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (E value : toList()) {
            sb.append(value);
        }
        return sb.toString();
    }

    @Override
    public Rga<E> merge(Rga<E> other) {
        Objects.requireNonNull(other, "other");
        Map<Dot, Node<E>> nextNodes = new TreeMap<>(nodes);
        nextNodes.putAll(other.nodes);
        Set<Dot> nextTombstones = new TreeSet<>(tombstones);
        nextTombstones.addAll(other.tombstones);
        return new Rga<>(nextNodes, nextTombstones);
    }

    /**
     * Preorder walk of the causal tree, including tombstoned nodes so they can still anchor their
     * children.
     *
     * <p>Iterative rather than recursive: a document is a deep tree when it has been typed one
     * character after another, which is the normal case, and a thousand-character paragraph would
     * be a thousand stack frames.
     */
    private List<Node<E>> walk() {
        Map<Dot, List<Node<E>>> children = new LinkedHashMap<>();
        List<Node<E>> roots = new ArrayList<>();
        for (Node<E> node : nodes.values()) {
            if (node.parent() == null) {
                roots.add(node);
            } else {
                children.computeIfAbsent(node.parent(), k -> new ArrayList<>()).add(node);
            }
        }
        roots.sort(SIBLING_ORDER);
        for (List<Node<E>> siblings : children.values()) {
            siblings.sort(SIBLING_ORDER);
        }

        List<Node<E>> ordered = new ArrayList<>(nodes.size());
        Deque<Node<E>> stack = new ArrayDeque<>();
        pushInReverse(stack, roots);
        while (!stack.isEmpty()) {
            Node<E> node = stack.pop();
            ordered.add(node);
            pushInReverse(stack, children.getOrDefault(node.id(), List.of()));
        }
        return ordered;
    }

    private static <E> void pushInReverse(Deque<Node<E>> stack, List<Node<E>> siblings) {
        for (int i = siblings.size() - 1; i >= 0; i--) {
            stack.push(siblings.get(i));
        }
    }

    /**
     * One past the highest counter anywhere in this state - a Lamport clock, deliberately not a
     * per-replica sequence number.
     *
     * <p>This is the subtle part of the whole file. {@link OrSet} numbers each replica's dots
     * independently, because there a dot is only ever an identity and never a time. Here dots are
     * compared across replicas to order concurrent inserts, and a per-replica counter makes that
     * comparison meaningless: a replica joining a mature document would mint dot 1 and sort below
     * everything, so its edits would slide to the end of every run they were inserted into.
     *
     * <p>Taking the maximum over all replicas instead means a dot's counter is at least as large
     * as everything its author had seen, so "greater counter" implies "not causally earlier",
     * which is exactly the guarantee the sibling ordering needs. Equal counters mean genuine
     * concurrency, and those break on replica id.
     */
    private Dot nextDot(ReplicaId replica) {
        long highest = 0;
        for (Dot id : nodes.keySet()) {
            if (id.counter() > highest) {
                highest = id.counter();
            }
        }
        return new Dot(replica, highest + 1);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rga<?> r && nodes.equals(r.nodes) && tombstones.equals(r.tombstones);
    }

    @Override
    public int hashCode() {
        return 31 * nodes.hashCode() + tombstones.hashCode();
    }

    @Override
    public String toString() {
        return "Rga" + toList();
    }
}
