package co.rsk.peg.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;

/** The RSKj conventions layered over Besu's RLP. */
class RskRlpTest {

    @Test
    void nullEmptyAndZeroAllEncodeAsTheEmptyString() {
        assertEquals("0x80", hex(RskRlp.encodeElement(null)));
        assertEquals("0x80", hex(RskRlp.encodeElement(new byte[0])));
        assertEquals("0x80", hex(RskRlp.encode(out -> RskRlp.writeUnsigned(out, 0))));
        assertEquals("0x80", hex(RskRlp.encode(out -> RskRlp.writeElement(out, null))));
    }

    @Test
    void scalarsAreMinimalBigEndian() {
        assertEquals("0x01", hex(RskRlp.encode(out -> RskRlp.writeUnsigned(out, 1))));
        assertEquals("0x7f", hex(RskRlp.encode(out -> RskRlp.writeUnsigned(out, 127))));
        assertEquals("0x8180", hex(RskRlp.encode(out -> RskRlp.writeUnsigned(out, 128))));
        assertEquals("0x820100", hex(RskRlp.encode(out -> RskRlp.writeUnsigned(out, BigInteger.valueOf(256)))));
        assertEquals("0x887fffffffffffffff", hex(RskRlp.encode(out -> RskRlp.writeUnsigned(out, Long.MAX_VALUE))));
    }

    @Test
    void emptyListIsC0() {
        assertEquals("0xc0", hex(RskRlp.encodedEmptyList()));
    }

    @Test
    void readDataReturnsPayloadNullForEmptyAndWholeEncodingForLists() {
        // [ "abcd", "", [ "01" ] ]
        byte[] encoded = Bytes.fromHexString("0xc682abcd80c101").toArrayUnsafe();
        RLPInput in = RskRlp.input(encoded);
        assertEquals(3, in.enterList());
        assertArrayEquals(new byte[] {(byte) 0xab, (byte) 0xcd}, RskRlp.readData(in));
        assertNull(RskRlp.readData(in));
        assertArrayEquals(new byte[] {(byte) 0xc1, 0x01}, RskRlp.readData(in));
        in.leaveList();
    }

    @Test
    void readRawDataReturnsAnEmptyArrayForTheEmptyString() {
        RLPInput in = RskRlp.input(Bytes.fromHexString("0xc180").toArrayUnsafe());
        in.enterList();
        assertArrayEquals(new byte[0], RskRlp.readRawData(in));
        in.leaveList();
    }

    @Test
    void readUnsignedAcceptsLeadingZerosAndReadsEmptyAsZero() {
        // [ "002a", "", "ff" ]
        RLPInput in = RskRlp.input(Bytes.fromHexString("0xc682002a8081ff").toArrayUnsafe());
        in.enterList();
        assertEquals(BigInteger.valueOf(42), RskRlp.readUnsigned(in));
        assertEquals(BigInteger.ZERO, RskRlp.readUnsigned(in));
        assertEquals(BigInteger.valueOf(255), RskRlp.readUnsigned(in));
        in.leaveList();
    }

    private static String hex(byte[] bytes) {
        return Bytes.wrap(bytes).toHexString();
    }
}
