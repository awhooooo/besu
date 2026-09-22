package co.rsk.peg.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.rsk.bitcoinj.core.Sha256Hash;
import org.ethereum.vm.DataWord;
import org.junit.jupiter.api.Test;

/** The new keys must be byte-identical to the DataWord-derived keys RSKj used, or state would silently move. */
class StorageKeysTest {

    @Test
    void nameKeysMatchDataWord() {
        for (String name : new String[] {"newFederation", "btcTxHashesAP", "releaseRequestQueue", "rskTxsWaitingFS", "x", "nextPegoutHeight"}) {
            assertArrayEquals(DataWord.fromString(name).getData(), StorageKeys.name(name).toArray(), name);
        }
    }

    @Test
    void compoundKeysMatchDataWord() {
        String value = "btcTxHashAP-" + Sha256Hash.ZERO_HASH;
        assertArrayEquals(DataWord.fromLongString(value).getData(), StorageKeys.compound(value).toArray());
    }

    @Test
    void hashKeysMatchDataWord() {
        Sha256Hash hash = Sha256Hash.of(new byte[] {1, 2, 3});
        assertArrayEquals(DataWord.valueFromHex(hash.toString()).getData(), StorageKeys.of(hash).toArray());
    }

    @Test
    void namesLongerThanASlotAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.name("a".repeat(33)));
    }
}
