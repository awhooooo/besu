package co.rsk.peg.host;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;

import java.util.Objects;
import java.util.Optional;

/**
 * The per-call facts the bridge used to read from RSKj's Transaction object.
 * Accessor names mirror the RSKj calls they replace so the port stays reviewable.
 */
public final class CallContext {

    private final Address sender;
    private final Hash hash;
    private final Wei value;
    private final boolean fromContract;
    private final byte[] senderPublicKey;

    public CallContext(Address sender, Hash hash, Wei value, boolean fromContract, byte[] senderPublicKey) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.hash = Objects.requireNonNull(hash, "hash");
        this.value = Objects.requireNonNull(value, "value");
        this.fromContract = fromContract;
        this.senderPublicKey = senderPublicKey == null ? null : senderPublicKey.clone();
    }

    public static CallContext of(BridgeHost host) {
        return new CallContext(
            host.origin(),
            host.transactionHash(),
            host.callValue(),
            host.callerIsContract(),
            host.originPublicKey().orElse(null)
        );
    }

    /** The transaction origin. Replaces {@code rskTx.getSender(signatureCache)}. */
    public Address getSender() {
        return sender;
    }

    /** The transaction hash. Replaces {@code rskTx.getHash()}. */
    public Hash getHash() {
        return hash;
    }

    /** The value sent with the call. Replaces {@code rskTx.getValue()}. */
    public Wei getValue() {
        return value;
    }

    /** Replaces {@code BridgeUtils.isContractTx(rskTx)}. */
    public boolean isFromContract() {
        return fromContract;
    }

    /** The origin's public key, uncompressed SEC form, when available. Replaces {@code rskTx.getKey()}. */
    public Optional<byte[]> getSenderPublicKey() {
        return Optional.ofNullable(senderPublicKey).map(byte[]::clone);
    }

    @Override
    public String toString() {
        return "CallContext{sender=" + sender + ", hash=" + hash + ", value=" + value + ", fromContract=" + fromContract + "}";
    }
}
