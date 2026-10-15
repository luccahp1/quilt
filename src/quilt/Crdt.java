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

    /**
     * Returns only the part of this state that {@code since} is missing.
     *
     * <p>Shipping a whole state on every sync is correct but wasteful: a counter with a thousand
     * replicas sends a thousand slots to report one increment. A delta is the alternative. It is
     * an ordinary state of the same type, built from just the pieces of this one that are not
     * already in {@code since}, so a peer known to hold {@code since} can catch up with it alone:
     *
     * <pre>{@code
     * since.merge(this.delta(since)).equals(since.merge(this))   // always
     * }</pre>
     *
     * <p>Because a delta is a state, everything that makes merge safe carries over. Deltas can be
     * batched by merging them together, delivered out of order, or delivered twice. The one
     * requirement is on the sender: {@code since} must be something the receiver really holds,
     * such as the last state it acknowledged. When in doubt, sending the whole state is always
     * correct, and {@code delta(empty())} is exactly that.
     *
     * <p>A delta never contains anything this state does not, and never contains anything
     * {@code since} already has. It is a message to merge, not a value to read: a sequence delta,
     * for instance, can hold an insert whose anchor the receiver already has and the delta does
     * not.
     *
     * @param since a state the receiver is known to hold; must not be null
     * @return the smallest state that brings {@code since} up to date with this one, which is the
     *     empty state when there is nothing to send
     */
    S delta(S since);
}
