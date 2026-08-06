package quilt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A map whose every value is an independent {@link LwwRegister}.
 *
 * <p>Merging is per key, so two replicas editing different keys never conflict at all - the usual
 * case, and the reason this type is so much more useful than a single register. Only edits to the
 * same key race, and there last-write-wins decides.
 *
 * <p>Deletion is a write of null rather than dropping the entry. Dropping it would make removal
 * lose to any concurrent write from a replica that still had it, and worse, would make merge
 * non-idempotent: the entry would come back from the other side. A null tombstone is a normal
 * write that competes on timestamp like any other, so a delete can win or lose on its merits.
 *
 * <p>Tombstones are kept forever, same trade as {@link OrSet}.
 *
 * @param <K> the key type; must be immutable and have value-based equality
 * @param <V> the value type; must be immutable and have value-based equality
 */
public final class LwwMap<K, V> implements Crdt<LwwMap<K, V>> {

    private final Map<K, LwwRegister<V>> entries;

    private LwwMap(Map<K, LwwRegister<V>> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    public static <K, V> LwwMap<K, V> empty() {
        return new LwwMap<>(new LinkedHashMap<>());
    }

    public LwwMap<K, V> put(K key, V value, long timestamp, ReplicaId writer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        return write(key, value, timestamp, writer);
    }

    /** Deletes {@code key} by writing a null-valued tombstone that competes on timestamp. */
    public LwwMap<K, V> remove(K key, long timestamp, ReplicaId writer) {
        Objects.requireNonNull(key, "key");
        return write(key, null, timestamp, writer);
    }

    private LwwMap<K, V> write(K key, V value, long timestamp, ReplicaId writer) {
        Objects.requireNonNull(writer, "writer");
        Map<K, LwwRegister<V>> next = new LinkedHashMap<>(entries);
        LwwRegister<V> current = next.getOrDefault(key, LwwRegister.empty());
        next.put(key, current.set(value, timestamp, writer));
        return new LwwMap<>(next);
    }

    /** The value for {@code key}, or null if absent or deleted. */
    public V get(K key) {
        LwwRegister<V> register = entries.get(key);
        return register == null ? null : register.value();
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    /** The live keys, excluding tombstoned ones. Iteration order is unspecified. */
    public Set<K> keys() {
        Set<K> live = new LinkedHashSet<>();
        for (Map.Entry<K, LwwRegister<V>> entry : entries.entrySet()) {
            if (entry.getValue().value() != null) {
                live.add(entry.getKey());
            }
        }
        return live;
    }

    public int size() {
        return keys().size();
    }

    public boolean isEmpty() {
        return keys().isEmpty();
    }

    /** How many keys are carrying a delete tombstone. */
    public int tombstoneCount() {
        int count = 0;
        for (LwwRegister<V> register : entries.values()) {
            if (!register.isEmpty() && register.value() == null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public LwwMap<K, V> merge(LwwMap<K, V> other) {
        Objects.requireNonNull(other, "other");
        Map<K, LwwRegister<V>> next = new LinkedHashMap<>(entries);
        for (Map.Entry<K, LwwRegister<V>> entry : other.entries.entrySet()) {
            next.merge(entry.getKey(), entry.getValue(), LwwRegister::merge);
        }
        return new LwwMap<>(next);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LwwMap<?, ?> m && entries.equals(m.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        Map<K, V> visible = new LinkedHashMap<>();
        for (K key : keys()) {
            visible.put(key, get(key));
        }
        return "LwwMap" + visible;
    }
}
