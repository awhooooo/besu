package co.rsk.peg.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.rsk.peg.BridgeAddresses;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.Test;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

class InMemoryBridgeHostTest {

    @Test
    void behavesLikeAHost() {
        InMemoryBridgeHost host = new InMemoryBridgeHost().balance(BridgeAddresses.BRIDGE, Wei.of(5));
        Bytes32 key = Bytes32.leftPad(Bytes.of((byte) 9));
        host.putStorage(key, new byte[] {1, 2});
        assertArrayEquals(new byte[] {1, 2}, host.getStorage(key));
        host.putStorage(key, new byte[0]);
        assertNull(host.getStorage(key));

        Address to = Address.fromHexString("0x00000000000000000000000000000000000000bb");
        host.transfer(BridgeAddresses.BRIDGE, to, Wei.of(2));
        assertEquals(Wei.of(3), host.balanceOf(BridgeAddresses.BRIDGE));
        assertEquals(Wei.of(2), host.balanceOf(to));
        assertThrows(IllegalStateException.class, () -> host.transfer(to, BridgeAddresses.BRIDGE, Wei.of(3)));

        Log log = new Log(BridgeAddresses.BRIDGE, Bytes.EMPTY, List.of());
        host.emitLog(log);
        assertEquals(List.of(log), host.logs());
        assertEquals(host.origin(), CallContext.of(host).getSender());
    }
}
