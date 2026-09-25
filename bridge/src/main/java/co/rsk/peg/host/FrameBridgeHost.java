package co.rsk.peg.host;

import co.rsk.peg.BridgeAddresses;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.PrecompiledContractTransaction;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Arrays;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * The bridge's view of a Besu message frame.
 *
 * <p>Three facts the EVM has no opcode for reach the bridge on the frame's transaction-lifetime values,
 * which every frame of a transaction shares: the transaction hash, the origin's public key and whether
 * the execution is a simulation. Sharing matters, because a bridge call made from a contract runs in a
 * child frame and has to see the same values as a call made directly.
 *
 * <p>An execution that no transaction started has no hash to offer. That cannot happen to the bridge,
 * which no system call reaches, so asking fails loudly rather than letting a mis-wired node store zero
 * hashes into consensus state. A simulation, by contrast, is a plain false when nobody says otherwise:
 * a host that forgets the flag then refuses the local-only queries, which is the safe way to be wrong.
 *
 * <p>Besu enforces {@code STATICCALL} inside the opcodes, so a precompile polices itself: on a static frame
 * the host refuses a storage change, a transfer and a log. Writing a value that is already there is not a
 * change, so read-only bridge methods that re-save unchanged state keep working under a static call.
 */
public final class FrameBridgeHost implements BridgeHost {

    private static final byte UNCOMPRESSED_PREFIX = 0x04;

    private final MessageFrame frame;

    public FrameBridgeHost(MessageFrame frame) {
        this.frame = frame;
    }

    @Override
    public long blockNumber() {
        return frame.getBlockValues().getNumber();
    }

    @Override
    public long blockTimestamp() {
        return frame.getBlockValues().getTimestamp();
    }

    @Override
    public Address origin() {
        return frame.getOriginatorAddress();
    }

    @Override
    public Address caller() {
        return frame.getSenderAddress();
    }

    @Override
    public Hash transactionHash() {
        return frame.getPrecompiledContractTransaction()
            .orElseThrow(() -> new IllegalStateException("The bridge was called outside of a transaction"))
            .hash();
    }

    @Override
    public Wei callValue() {
        return frame.getValue();
    }

    @Override
    public CallKind callKind() {
        if (frame.isStatic()) {
            return CallKind.STATICCALL;
        }
        // Besu keeps one frame type for every message call; a delegate call or callcode is the case
        // where the code being run belongs to an address other than the recipient of the frame.
        if (!frame.getRecipientAddress().equals(frame.getContractAddress())) {
            return CallKind.DELEGATECALL;
        }
        return CallKind.CALL;
    }

    @Override
    public boolean isLocalCall() {
        return frame.isSimulation();
    }

    @Override
    public boolean callerIsContract() {
        return frame.getDepth() > 0;
    }

    /** The node hands over the 64-byte X||Y form; the bridge works in uncompressed SEC, which prefixes 0x04. */
    @Override
    public Optional<byte[]> originPublicKey() {
        return frame.getPrecompiledContractTransaction()
            .flatMap(PrecompiledContractTransaction::senderPublicKey)
            .map(xy -> Bytes.concatenate(Bytes.of(UNCOMPRESSED_PREFIX), xy).toArrayUnsafe());
    }

    @Override
    public byte[] getStorage(Bytes32 key) {
        return new ChunkedStorage(bridgeAccount()).get(key);
    }

    @Override
    public void putStorage(Bytes32 key, byte[] value) {
        if (frame.isStatic() && !Arrays.equals(getStorage(key), value)) {
            throw staticChange("storage write");
        }
        new ChunkedStorage(bridgeAccount()).put(key, value);
    }

    @Override
    public UInt256 getSlot(UInt256 slot) {
        return bridgeAccount().getStorageValue(slot);
    }

    @Override
    public void putSlot(UInt256 slot, UInt256 value) {
        MutableAccount account = bridgeAccount();
        if (!account.getStorageValue(slot).equals(value)) {
            if (frame.isStatic()) {
                throw staticChange("storage write");
            }
            account.setStorageValue(slot, value);
        }
    }

    @Override
    public Wei balanceOf(Address address) {
        Account account = world().get(address);
        return account == null ? Wei.ZERO : account.getBalance();
    }

    @Override
    public void transfer(Address from, Address to, Wei amount) {
        if (amount.isZero()) {
            return;
        }
        if (frame.isStatic()) {
            throw staticChange("transfer");
        }
        world().getOrCreate(from).decrementBalance(amount);
        world().getOrCreate(to).incrementBalance(amount);
    }

    @Override
    public void emitLog(Log log) {
        if (frame.isStatic()) {
            throw staticChange("log");
        }
        frame.addLog(log);
    }

    private static IllegalStateException staticChange(String change) {
        return new IllegalStateException("A static call to the bridge attempted a " + change);
    }

    private WorldUpdater world() {
        return frame.getWorldUpdater();
    }

    private MutableAccount bridgeAccount() {
        return world().getOrCreate(BridgeAddresses.BRIDGE);
    }
}
