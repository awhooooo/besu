package co.rsk.peg.host;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Everything the bridge needs from the node that executes it.
 *
 * <p>Storage is exposed with byte-array semantics under 32-byte logical keys, exactly as RSKj's
 * repository exposed it to the bridge. How a host maps a logical key and a value of arbitrary
 * length onto the account's storage slots is the host's business.
 */
public interface BridgeHost {

    long blockNumber();

    long blockTimestamp();

    /** Transaction origin. All bridge authorization is based on it (scope decision D11). */
    Address origin();

    /** Immediate caller of the bridge (msg.sender). */
    Address caller();

    Hash transactionHash();

    Wei callValue();

    CallKind callKind();

    /** True under eth_call, eth_estimateGas and similar simulations; false for anything mined or replayed. */
    boolean isLocalCall();

    /** True when the bridge was reached from a contract rather than directly from the transaction origin. */
    boolean callerIsContract();

    /** The origin's public key in uncompressed SEC form (65 bytes, 0x04 prefix), when the node provides it. */
    Optional<byte[]> originPublicKey();

    /** Value stored under the logical key, or null when nothing is stored. */
    byte[] getStorage(Bytes32 key);

    /** Stores the value under the logical key. A null or empty value removes the entry. */
    void putStorage(Bytes32 key, byte[] value);

    Wei balanceOf(Address account);

    void transfer(Address from, Address to, Wei amount);

    void emitLog(Log log);
}
