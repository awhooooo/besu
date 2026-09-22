package co.rsk.peg.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.rsk.bitcoinj.core.Sha256Hash;
import org.junit.jupiter.api.Test;

/**
 * The keys must be byte-identical to the DataWord-derived keys RSKj used, or state would silently move.
 * The expected values were recorded from RSKj's DataWord while that class was still in the module:
 * {@code DataWord.fromString}, {@code DataWord.fromLongString} and {@code DataWord.valueFromHex}.
 */
class StorageKeysTest {

    @Test
    void nameKeysMatchDataWord() {
        assertEquals("0x000000000000000000000000000000000000006e657746656465726174696f6e", StorageKeys.name("newFederation").toHexString());
        assertEquals("0x0000000000000000000000000000000000000062746354784861736865734150", StorageKeys.name("btcTxHashesAP").toHexString());
        assertEquals("0x0000000000000000000000000072656c65617365526571756573745175657565", StorageKeys.name("releaseRequestQueue").toHexString());
        assertEquals("0x000000000000000000000000000000000072736b54787357616974696e674653", StorageKeys.name("rskTxsWaitingFS").toHexString());
        assertEquals("0x0000000000000000000000000000000000000000000000000000000000000078", StorageKeys.name("x").toHexString());
        assertEquals("0x000000000000000000000000000000006e6578745065676f7574486569676874", StorageKeys.name("nextPegoutHeight").toHexString());
        assertEquals("0x00000000000000000000000000626c6f636b53746f7265436861696e48656164", StorageKeys.name("blockStoreChainHead").toHexString());
    }

    @Test
    void compoundKeysMatchDataWord() {
        assertEquals("0x9ad08b266031d2c79ef2964836fce2b7e09cc60f549cd8746cd56f7945fdf4c8",
            StorageKeys.compound("btcTxHashAP-" + Sha256Hash.ZERO_HASH).toHexString());
        assertEquals("0x005c0ec6e8479cec242d52abd737647523d5a23ac0a899f01b268f7fca45fa0c",
            StorageKeys.compound("btcBlockHeight-700000").toHexString());
        assertEquals("0xe5616a13268349e7019c899f8038287bd486a20bc0ce531b264b504f1f9135d7",
            StorageKeys.compound("pegoutTxSigHash-" + Sha256Hash.of(new byte[] {1, 2, 3})).toHexString());
    }

    @Test
    void hashKeysMatchDataWord() {
        Sha256Hash hash = Sha256Hash.of(new byte[] {1, 2, 3});
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", hash.toString());
        assertEquals("0x039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", StorageKeys.of(hash).toHexString());
    }

    @Test
    void namesLongerThanASlotAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.name("a".repeat(33)));
    }
}
