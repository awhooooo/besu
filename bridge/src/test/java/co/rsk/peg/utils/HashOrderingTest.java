package co.rsk.peg.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Sorted maps of transaction hashes must iterate exactly as RSKj's Keccak256 ordering iterated them.
 * The expected results were recorded from {@code Keccak256.compareTo} while that class was still in the module.
 */
class HashOrderingTest {

    /** Keccak256 compared from the last byte backwards, unsigned. Each pair pins one of those two facts. */
    @Test
    void comparesFromTheLastByteUnsigned() {
        Hash lastByteOne = hash(b -> b[31] = 1);
        Hash allOnesExceptLastByte = hash(b -> {
            Arrays.fill(b, (byte) 0xff);
            b[31] = 0;
        });
        Hash firstByte0x80 = hash(b -> b[0] = (byte) 0x80);
        Hash firstByte0x7f = hash(b -> b[0] = 0x7f);

        assertEquals(1, HashOrdering.RSK.compare(lastByteOne, allOnesExceptLastByte));
        assertEquals(-1, HashOrdering.RSK.compare(allOnesExceptLastByte, lastByteOne));
        assertEquals(1, HashOrdering.RSK.compare(firstByte0x80, firstByte0x7f));
        assertEquals(-1, HashOrdering.RSK.compare(firstByte0x7f, firstByte0x80));
        assertEquals(0, HashOrdering.RSK.compare(lastByteOne, hash(b -> b[31] = 1)));
    }

    /** Forty hashes from a seeded generator, in the order a TreeMap keyed by Keccak256 returned them. */
    @Test
    void treeMapIterationMatchesKeccak256() {
        int[] recordedOrder = {
            10, 28, 15, 19, 9, 23, 36, 33, 4, 13, 6, 17, 16, 21, 27, 37, 38, 11, 18, 34,
            39, 3, 32, 5, 24, 12, 29, 35, 31, 30, 8, 26, 2, 0, 25, 22, 14, 20, 1, 7};

        Random random = new Random(11);
        List<Hash> hashes = new ArrayList<>();
        TreeMap<Hash, Integer> ported = new TreeMap<>(HashOrdering.RSK);
        for (int i = 0; i < recordedOrder.length; i++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            Hash hash = Hash.wrap(Bytes32.wrap(bytes));
            hashes.add(hash);
            ported.put(hash, i);
        }

        // The generator sequence is part of the recording; this guards it.
        assertEquals("0xe59bf7ba9862f4b62fa0666d872ec93622df26a1366d3b66f8f8c106fbcaf7e8", hashes.get(0).getBytes().toHexString());
        assertEquals(Arrays.stream(recordedOrder).boxed().toList(), new ArrayList<>(ported.values()));
    }

    private static Hash hash(Consumer<byte[]> fill) {
        byte[] bytes = new byte[32];
        fill.accept(bytes);
        return Hash.wrap(Bytes32.wrap(bytes));
    }
}
