package co.rsk.peg.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.rsk.peg.BridgeAddresses;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.core.MessageFrameTestFixture;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;
import org.junit.jupiter.api.Test;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

class FrameBridgeHostTest {

    private static final Address SENDER = Address.fromHexString("0x00000000000000000000000000000000000000aa");

    @Test
    void readsContextStateAndLogsFromARealFrame() {
        MutableWorldState world = InMemoryKeyValueStorageProvider.createInMemoryWorldState();
        WorldUpdater updater = world.updater();
        updater.getOrCreate(BridgeAddresses.BRIDGE).setBalance(Wei.of(1000));
        MessageFrame frame = new MessageFrameTestFixture()
            .worldUpdater(updater)
            .sender(SENDER)
            .address(BridgeAddresses.BRIDGE)
            .contract(BridgeAddresses.BRIDGE)
            .value(Wei.of(7))
            .blockHeader(new BlockHeaderTestFixture().number(42).timestamp(1234).buildHeader())
            .build();
        FrameBridgeHost host = new FrameBridgeHost(frame);

        assertEquals(42, host.blockNumber());
        assertEquals(1234, host.blockTimestamp());
        assertEquals(MessageFrameTestFixture.DEFAULT_ADDRESS, host.origin());
        assertEquals(SENDER, host.caller());
        assertEquals(Wei.of(7), host.callValue());
        assertEquals(CallKind.CALL, host.callKind());
        assertFalse(host.callerIsContract());
        assertTrue(host.originPublicKey().isEmpty());
        assertThrows(IllegalStateException.class, host::transactionHash);
        assertThrows(IllegalStateException.class, host::isLocalCall);

        Bytes32 key = Bytes32.leftPad(Bytes.of((byte) 1));
        byte[] value = new byte[70];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) i;
        }
        assertNull(host.getStorage(key));
        host.putStorage(key, value);
        assertArrayEquals(value, host.getStorage(key));
        host.putStorage(key, null);
        assertNull(host.getStorage(key));

        assertEquals(Wei.of(1000), host.balanceOf(BridgeAddresses.BRIDGE));
        assertEquals(Wei.ZERO, host.balanceOf(SENDER));
        host.transfer(BridgeAddresses.BRIDGE, SENDER, Wei.of(10));
        assertEquals(Wei.of(990), host.balanceOf(BridgeAddresses.BRIDGE));
        assertEquals(Wei.of(10), host.balanceOf(SENDER));
        assertThrows(IllegalStateException.class, () -> host.transfer(SENDER, BridgeAddresses.BRIDGE, Wei.of(11)));

        Log log = new Log(BridgeAddresses.BRIDGE, Bytes.of(1, 2, 3), List.of(LogTopic.wrap(Bytes32.ZERO)));
        host.emitLog(log);
        assertEquals(List.of(log), frame.getLogs());

        UInt256 slot = UInt256.valueOf(77);
        assertEquals(UInt256.ZERO, host.getSlot(slot));
        host.putSlot(slot, UInt256.valueOf(5));
        assertEquals(UInt256.valueOf(5), host.getSlot(slot));
        assertEquals(UInt256.valueOf(5), updater.get(BridgeAddresses.BRIDGE).getStorageValue(slot));
        host.putSlot(slot, UInt256.ZERO);
        assertEquals(UInt256.ZERO, host.getSlot(slot));
    }

    @Test
    void unchangedSlotValuesAreNotWritten() {
        MessageFrame frame = mock(MessageFrame.class);
        WorldUpdater updater = mock(WorldUpdater.class);
        MutableAccount account = mock(MutableAccount.class);
        when(frame.getWorldUpdater()).thenReturn(updater);
        when(updater.getOrCreate(BridgeAddresses.BRIDGE)).thenReturn(account);
        when(account.getStorageValue(UInt256.ONE)).thenReturn(UInt256.valueOf(9));

        new FrameBridgeHost(frame).putSlot(UInt256.ONE, UInt256.valueOf(9));
        verify(account, never()).setStorageValue(UInt256.ONE, UInt256.valueOf(9));

        new FrameBridgeHost(frame).putSlot(UInt256.ONE, UInt256.valueOf(10));
        verify(account).setStorageValue(UInt256.ONE, UInt256.valueOf(10));
    }

    @Test
    void classifiesCallKinds() {
        MessageFrame frame = mock(MessageFrame.class);
        when(frame.isStatic()).thenReturn(true);
        assertEquals(CallKind.STATICCALL, new FrameBridgeHost(frame).callKind());

        MessageFrame delegate = mock(MessageFrame.class);
        when(delegate.isStatic()).thenReturn(false);
        when(delegate.getRecipientAddress()).thenReturn(SENDER);
        when(delegate.getContractAddress()).thenReturn(BridgeAddresses.BRIDGE);
        assertEquals(CallKind.DELEGATECALL, new FrameBridgeHost(delegate).callKind());

        MessageFrame call = mock(MessageFrame.class);
        when(call.getRecipientAddress()).thenReturn(BridgeAddresses.BRIDGE);
        when(call.getContractAddress()).thenReturn(BridgeAddresses.BRIDGE);
        assertEquals(CallKind.CALL, new FrameBridgeHost(call).callKind());

        MessageFrame nested = mock(MessageFrame.class);
        when(nested.getDepth()).thenReturn(1);
        assertTrue(new FrameBridgeHost(nested).callerIsContract());
    }

    @Test
    void readsNodeSuppliedContextVariables() {
        Hash hash = Hash.fromHexString("0x2222222222222222222222222222222222222222222222222222222222222222");
        byte[] xy = new byte[64];
        for (int i = 0; i < xy.length; i++) {
            xy[i] = (byte) (i + 1);
        }
        MessageFrame frame = mock(MessageFrame.class);
        when(frame.<Hash>getContextVariable(FrameBridgeHost.TRANSACTION_HASH)).thenReturn(hash);
        when(frame.<Boolean>getContextVariable(FrameBridgeHost.LOCAL_CALL)).thenReturn(true);
        when(frame.<Bytes>getContextVariable(FrameBridgeHost.ORIGIN_PUBLIC_KEY)).thenReturn(Bytes.wrap(xy));
        FrameBridgeHost host = new FrameBridgeHost(frame);

        assertEquals(hash, host.transactionHash());
        assertTrue(host.isLocalCall());
        byte[] key = host.originPublicKey().orElseThrow();
        assertEquals(65, key.length);
        assertEquals(0x04, key[0]);
        assertArrayEquals(xy, java.util.Arrays.copyOfRange(key, 1, 65));
    }
}
