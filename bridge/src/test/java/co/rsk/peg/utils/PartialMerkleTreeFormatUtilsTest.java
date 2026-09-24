package co.rsk.peg.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.bouncycastle.util.encoders.Hex;


class PartialMerkleTreeFormatUtilsTest {

    @Test
    void getHashesCount() {
        String pmtSerializedEncoded = "030000000279e7c0da739df8a00f12c0bff55e5438f530aa5859ff9874258cd7bad3fe709746aff89" +
                "7e6a851faa80120d6ae99db30883699ac0428fc7192d6c3fec0ca6409010d";
        byte[] pmtSerialized = Hex.decode(pmtSerializedEncoded);
        assertEquals(2L, PartialMerkleTreeFormatUtils.getHashesCount(pmtSerialized).value);
    }

    @Test
    void getFlagBitsCount() {
        String pmtSerializedEncoded = "030000000279e7c0da739df8a00f12c0bff55e5438f530aa5859ff9874258cd7bad3fe709746aff89" +
                "7e6a851faa80120d6ae99db30883699ac0428fc7192d6c3fec0ca6409010d";
        byte[] pmtSerialized = Hex.decode(pmtSerializedEncoded);
        assertEquals(1L, PartialMerkleTreeFormatUtils.getFlagBitsCount(pmtSerialized).value);
    }

    @Test
    void hasExpectedSize() {
        String pmtSerializedEncoded = "030000000279e7c0da739df8a00f12c0bff55e5438f530aa5859ff9874258cd7bad3fe709746aff89" +
                "7e6a851faa80120d6ae99db30883699ac0428fc7192d6c3fec0ca6409010d";
        byte[] pmtSerialized = Hex.decode(pmtSerializedEncoded);
        assertEquals(true, PartialMerkleTreeFormatUtils.hasExpectedSize(pmtSerialized));
    }

    @Test
    void doesntHaveExpectedSize() {
        String pmtSerializedEncoded = "030000000279e7c0da739df8a00f12c0bff55e5438f530aa5859ff9874258cd7bad3fe709746aff89" +
                "7e6a851faa80120d6ae99db30883699ac0428fc7192d6c3fec0ca64010d";
        byte[] pmtSerialized = Hex.decode(pmtSerializedEncoded);
        assertEquals(false, PartialMerkleTreeFormatUtils.hasExpectedSize(pmtSerialized));
    }

    @Test
    void overflowSize() {
        String pmtSerializedEncoded = "0300ffffff79e7c0da739df8a00f12c0bff55e5438f530aa5859ff9874258cd7bad3fe709746aff89" +
                "7e6a851faa80120d6ae99db30883699ac0428fc7192d6c3fec0ca6409010d";
        byte[] pmtSerialized = Hex.decode(pmtSerializedEncoded);
        Assertions.assertThrows(ArithmeticException.class, () -> PartialMerkleTreeFormatUtils.getFlagBitsCount(pmtSerialized));
    }
}
