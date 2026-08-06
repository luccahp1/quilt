package quilt;

/**
 * A state-based convergent replicated data type.
 *
 * <p>Every implementation in this library is a <em>join-semilattice</em>: states are partially
 * ordered, and {@link #merge} computes the least upper bound of two of them. That single property
 * is what makes replicas converge, because a least upper bound is necessarily
 *
 * <ul>
 *   <li><b>commutative</b> - {@code a.merge(b)} equals {@code b.merge(a)}, so delivery order does
 *       not matter;
 *   <li><b>associative</b> - {@code a.merge(b).merge(c)} equals {@code a.merge(b.merge(c))}, so
 *       grouping does not matter;
 *   <li><b>idempotent</b> - {@code a.merge(a)} equals {@code a}, so duplicate delivery does not
 *       matter.
 * </ul>
 *
 * <p>Together those three mean replicas can gossip states over an unreliable network that
 * reorders, regroups and redelivers messages, and still end up byte-identical as long as each
 * update eventually reaches everyone. No coordination, no consensus, no locks.
 *
 * <p>{@code Tests} checks all three laws against randomly generated states for every type here,
 * because "I believe this is a lattice" is not the same as knowing it.
 *
 * <p>Implementations are immutable. Update methods return a new state rather than mutating, so a
 * state you have already sent to a peer can never change underneath you.
 *
 * @param <S> the implementing type itself, so {@code merge} stays type-safe without casting
 */
public interface Crdt<S extends Crdt<S>> {

    /**
     * Returns the least upper bound of this state and {@code other}, mutating neither.
     *
     * @param other the state to join with; must not be null
     * @return a state at least as advanced as both inputs
     */
    S merge(S other);
}
