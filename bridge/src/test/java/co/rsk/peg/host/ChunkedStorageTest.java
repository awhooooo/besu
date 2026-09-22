package co.rsk.peg.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.evm.account.MutableAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

class ChunkedStorageTest {

    private final Map<UInt256, UInt256> slots = new HashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    private ChunkedStorage storage;

    @BeforeEach
    void setUp() {
        MutableAccount account = mock(MutableAccount.class);
        when(account.getStorageValue(any())).thenAnswer(inv -> slots.getOrDefault(inv.<UInt256>getArgument(0), UInt256.ZERO));
        doAnswer(inv -> {
            slots.put(inv.getArgument(0), inv.getArgument(1));
            writes.incrementAndGet();
            return null;
        }).when(account).setStorageValue(any(), any());
        storage = new ChunkedStorage(account);
    }

    @Test
    void absentKeyReadsAsNull() {
        assertNull(storage.get(key(1)));
    }

    @Test
    void roundTripsValuesOfEverySize() {
        Random random = new Random(3);
        for (int length : new int[] {1, 31, 32, 33, 64, 100, 1000}) {
            byte[] value = new byte[length];
            random.nextBytes(value);
            storage.put(key(length), value);
            assertArrayEquals(value, storage.get(key(length)), "length " + length);
        }
    }

    @Test
    void unchangedValueTouchesNoSlot() {
        byte[] value = new byte[100];
        new Random(5).nextBytes(value);
        storage.put(key(7), value);
        int after = writes.get();
        storage.put(key(7), value.clone());
        assertEquals(after, writes.get());
    }

    @Test
    void shrinkingClearsLeftoverChunks() {
        byte[] big = new byte[100];
        java.util.Arrays.fill(big, (byte) 0x5a);
        storage.put(key(9), big);
        storage.put(key(9), new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {1, 2, 3}, storage.get(key(9)));
        long nonZero = slots.values().stream().filter(v -> !v.isZero()).count();
        assertEquals(2, nonZero); // the length slot and one chunk
    }

    @Test
    void nullAndEmptyRemove() {
        storage.put(key(4), new byte[] {9, 9, 9});
        storage.put(key(4), null);
        assertNull(storage.get(key(4)));
        storage.put(key(4), new byte[] {9});
        storage.put(key(4), new byte[0]);
        assertNull(storage.get(key(4)));
        assertTrue(slots.values().stream().allMatch(UInt256::isZero));
    }

    @Test
    void keysDoNotCollide() {
        storage.put(key(1), new byte[] {1});
        storage.put(key(2), new byte[] {2});
        assertArrayEquals(new byte[] {1}, storage.get(key(1)));
        assertArrayEquals(new byte[] {2}, storage.get(key(2)));
    }

    private static Bytes32 key(int n) {
        return Bytes32.leftPad(org.apache.tuweni.bytes.Bytes.of((byte) n));
    }
}
