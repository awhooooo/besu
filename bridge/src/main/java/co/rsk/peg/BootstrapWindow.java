package co.rsk.peg;

/**
 * The blocks during which a chain is still bringing its peg to life.
 *
 * <p>At genesis the bridge holds every coin and nobody else holds any, so the federation cannot pay
 * for the very transactions that would release the first of them: no fee, no headers; no headers, no
 * peg-in; no peg-in, no coins. RSK cut that knot by letting the peg's own operators send bridge
 * transactions for nothing until a named hardfork, and by asking less bitcoin of the peg-in that
 * seeds the chain. This is that window.
 *
 * <p>It ends at a block chosen in the genesis file rather than at an observed event. A peg-in credits
 * the account that sent the bitcoin, not the federation, so "the first peg-in has landed" would leave
 * the federation exactly as broke as before and close the window on an unfinished job. When bootstrap
 * is over is a judgement its operator makes, which is why RSK made it a fork and not a predicate.
 *
 * @param bridgeTxsPaidBlock the first block at which bridge transactions are paid for like any other
 */
public record BootstrapWindow(long bridgeTxsPaidBlock) {

    /** For a chain that never had a window, or whose window has no business being open. */
    public static final BootstrapWindow CLOSED = new BootstrapWindow(0);

    public BootstrapWindow {
        if (bridgeTxsPaidBlock < 0) {
            throw new IllegalArgumentException("A bootstrap window cannot end before genesis");
        }
    }

    public boolean isOpenAt(long blockNumber) {
        return blockNumber < bridgeTxsPaidBlock;
    }
}
