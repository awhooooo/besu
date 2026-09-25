package co.rsk.peg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import org.junit.jupiter.api.Test;

/** What the bootstrap window is, and what the peg asks of a peg-in while it is open. */
class BootstrapWindowTest {

    @Test
    void aWindowIsOpenUntilTheBlockThatClosesIt() {
        BootstrapWindow window = new BootstrapWindow(100);

        assertTrue(window.isOpenAt(0));
        assertTrue(window.isOpenAt(99));
        assertFalse(window.isOpenAt(100));
        assertFalse(window.isOpenAt(101));
    }

    @Test
    void aClosedWindowWasNeverOpen() {
        assertFalse(BootstrapWindow.CLOSED.isOpenAt(0));
        assertEquals(0, BootstrapWindow.CLOSED.bridgeTxsPaidBlock());
    }

    @Test
    void aWindowCannotEndBeforeGenesis() {
        assertThrows(IllegalArgumentException.class, () -> new BootstrapWindow(-1));
    }

    @Test
    void thePegAsksLessOfThePeginThatSeedsTheChain() {
        // The chain it will later carry asks 500; the one that starts it asks one, so that the peg
        // can be seeded without first owning what the peg is for.
        assertEquals(Coin.FIFTY_COINS.multiply(10), BridgeMainNetConstants.getInstance().getMinimumPeginTxValue());
        assertEquals(Coin.COIN, BridgeMainNetConstants.getInstance().getBootstrapMinimumPeginTxValue());
        assertEquals(Coin.COIN, new BridgeRegTestConstants().getBootstrapMinimumPeginTxValue());
    }
}
