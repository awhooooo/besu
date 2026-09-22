package co.rsk.peg.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.rsk.crypto.Keccak256;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes32;

/** Sorted maps of transaction hashes must iterate exactly as RSKj's Keccak256 ordering iterated them. */
class HashOrderingTest {

    @Test
    void comparatorMatchesKeccak256() {
        Random random = new Random(7);
        for (int i = 0; i < 2_000; i++) {
            byte[] a = new byte[32];
            byte[] b = new byte[32];
            random.nextBytes(a);
            random.nextBytes(b);
            if (random.nextInt(4) == 0) {
                System.arraycopy(a, 0, b, 0, 32);
                b[random.nextInt(32)] ^= 1;
            }
            int expected = Integer.signum(new Keccak256(a).compareTo(new Keccak256(b)));
            int actual = Integer.signum(HashOrdering.RSK.compare(Hash.wrap(Bytes32.wrap(a)), Hash.wrap(Bytes32.wrap(b))));
            assertEquals(expected, actual);
        }
    }

    @Test
    void treeMapIterationMatchesKeccak256() {
        Random random = new Random(11);
        TreeMap<Keccak256, Integer> legacy = new TreeMap<>();
        TreeMap<Hash, Integer> ported = new TreeMap<>(HashOrdering.RSK);
        for (int i = 0; i < 200; i++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            legacy.put(new Keccak256(bytes), i);
            ported.put(Hash.wrap(Bytes32.wrap(bytes)), i);
        }
        List<Integer> expected = new ArrayList<>(legacy.values());
        List<Integer> actual = new ArrayList<>(ported.values());
        assertEquals(expected, actual);
    }
}
