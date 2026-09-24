package co.rsk.peg.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.fluent.SimpleAccount;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * The counter states what a piece of bridge code costs in slots, so its arithmetic has to be the arithmetic
 * {@link ChunkedStorage} actually performs. Each case runs the same operation twice: once against an account
 * that counts the slot calls it receives, which is the truth, and once through the counter, and the two must
 * agree. A counter that models the layout wrongly would be worse than no counter at all.
 */
class SlotCountingBridgeHostTest {

    private static final Bytes32 KEY = Bytes32.rightPad(org.apache.tuweni.bytes.Bytes.fromHexString("0xfeed"));

    /** Records what ChunkedStorage really asks of an account. */
    private static final class CountingAccount extends SimpleAccount {
        private int reads;
        private int writes;

        private CountingAccount() {
            super(Address.ZERO, 0, Wei.ZERO);
        }

        @Override
        public UInt256 getStorageValue(final UInt256 key) {
            reads++;
            return super.getStorageValue(key);
        }

        @Override
        public void setStorageValue(final UInt256 key, final UInt256 value) {
            writes++;
            super.setStorageValue(key, value);
        }
    }

    private static byte[] bytes(int length, int fill) {
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, (byte) fill);
        return out;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 31, 32, 33, 116, 593, 5903})
    void countsWhatReadingCosts(int length) {
        byte[] value = length == 0 ? null : bytes(length, 7);

        CountingAccount account = new CountingAccount();
        ChunkedStorage chunked = new ChunkedStorage(account);
        chunked.put(KEY, value);
        account.reads = 0;
        account.writes = 0;
        chunked.get(KEY);

        SlotCountingBridgeHost counter = SlotCountingBridgeHost.over(new InMemoryBridgeHost());
        counter.putStorage(KEY, value);
        counter.reset();
        counter.getStorage(KEY);

        assertEquals(account.reads, counter.slotReads(), "slot reads while reading " + length + " bytes");
        assertEquals(account.writes, counter.slotWrites(), "slot writes while reading " + length + " bytes");
    }

    @ParameterizedTest
    @CsvSource({
        "0, 32",      // a value appears
        "32, 0",      // a value is removed
        "116, 116",   // a stored block replaced by another of the same size
        "593, 653",   // a list grows by one entry
        "5903, 5963", // a long list grows by one entry
        "5903, 5903", // written back unchanged
        "5903, 100",  // a list shrinks, freeing slots that have to be zeroed
        "33, 34"      // the length changes without changing the chunk count
    })
    void countsWhatWritingCosts(int oldLength, int newLength) {
        byte[] before = oldLength == 0 ? null : bytes(oldLength, 7);
        byte[] after = newLength == 0 ? null : bytes(newLength, oldLength == newLength ? 7 : 9);

        CountingAccount account = new CountingAccount();
        ChunkedStorage chunked = new ChunkedStorage(account);
        chunked.put(KEY, before);
        account.reads = 0;
        account.writes = 0;
        chunked.put(KEY, after);

        SlotCountingBridgeHost counter = SlotCountingBridgeHost.over(new InMemoryBridgeHost());
        counter.putStorage(KEY, before);
        counter.reset();
        counter.putStorage(KEY, after);

        String what = oldLength + " bytes becoming " + newLength;
        assertEquals(account.reads, counter.slotReads(), "slot reads while writing " + what);
        assertEquals(account.writes, counter.slotWrites(), "slot writes while writing " + what);
    }

    @Test
    void countsRawSlotsAsRecordLayoutsUseThem() {
        SlotCountingBridgeHost counter = SlotCountingBridgeHost.over(new InMemoryBridgeHost());
        UInt256 slot = UInt256.valueOf(3);

        counter.getSlot(slot);
        assertEquals(1, counter.slotReads());
        assertEquals(0, counter.slotWrites());

        counter.reset();
        counter.putSlot(slot, UInt256.ONE);
        assertEquals(1, counter.slotReads(), "a raw write compares before it writes");
        assertEquals(1, counter.slotWrites());

        counter.reset();
        counter.putSlot(slot, UInt256.ONE);
        assertEquals(1, counter.slotReads());
        assertEquals(0, counter.slotWrites(), "writing an unchanged slot touches nothing");
    }
}
