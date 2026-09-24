package co.rsk.peg;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.peg.host.CallContext;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.encoders.Hex;

/** Ported from RSKj's test utilities; only the members the bridge tests use. */
public final class PegTestUtils {

    private PegTestUtils() {
    }

    /** RSKj's TransactionUtils.getTransactionFromCaller: the per-call facts of a value-less call from the sender. */
    public static CallContext callFrom(org.hyperledger.besu.datatypes.Address sender) {
        return new CallContext(sender, Hash.ZERO, Wei.ZERO, false, null);
    }

    /** A 32-byte RSK transaction hash whose first two bytes are the little-endian value. */
    public static Hash createHash3(int nHash) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) (nHash & 0xFF);
        bytes[1] = (byte) (nHash >> 8 & 0xFF);
        return Hash.wrap(Bytes32.wrap(bytes));
    }

    /** A Bitcoin hash whose first four bytes are the little-endian value. */
    public static Sha256Hash createHash(int nHash) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) (0xFF & nHash);
        bytes[1] = (byte) (0xFF & nHash >> 8);
        bytes[2] = (byte) (0xFF & nHash >> 16);
        bytes[3] = (byte) (0xFF & nHash >> 24);
        return Sha256Hash.wrap(bytes);
    }

    public static Script createOpReturnScriptForRsk(
        int protocolVersion,
        org.hyperledger.besu.datatypes.Address rskDestinationAddress,
        Optional<Address> btcRefundAddressOptional
    ) {
        int index = 0;
        int payloadLength;
        if (btcRefundAddressOptional.isPresent()) {
            payloadLength = 46;
        } else {
            payloadLength = 25;
        }
        byte[] payloadBytes = new byte[payloadLength];

        byte[] prefix = Hex.decode("52534b54"); // 'RSKT' in hexa
        System.arraycopy(prefix, 0, payloadBytes, index, prefix.length);
        index += prefix.length;

        payloadBytes[index] = (byte) protocolVersion;
        index++;

        byte[] rskDestinationAddressBytes = rskDestinationAddress.getBytes().toArrayUnsafe();
        System.arraycopy(
            rskDestinationAddressBytes,
            0,
            payloadBytes,
            index,
            rskDestinationAddressBytes.length
        );
        index += rskDestinationAddressBytes.length;

        if (btcRefundAddressOptional.isPresent()) {
            Address btcRefundAddress = btcRefundAddressOptional.get();
            if (btcRefundAddress.isP2SHAddress()) {
                payloadBytes[index] = 2; // P2SH address type
            } else {
                payloadBytes[index] = 1; // P2PKH address type
            }
            index++;

            System.arraycopy(
                btcRefundAddress.getHash160(),
                0,
                payloadBytes,
                index,
                btcRefundAddress.getHash160().length
            );
        }

        return ScriptBuilder.createOpReturnScript(payloadBytes);
    }

    public static Script createOpReturnScriptForRskWithCustomPayload(int protocolVersion, byte[] customPayload) {
        int index = 0;
        int payloadLength = customPayload.length;

        byte[] payloadBytes = new byte[payloadLength + 5]; // Add 4 bytes for the prefix, and another for the protocol version

        byte[] prefix = Hex.decode("52534b54"); // 'RSKT' in hexa
        System.arraycopy(prefix, 0, payloadBytes, index, prefix.length);
        index += prefix.length;

        payloadBytes[index] = (byte) protocolVersion;
        index++;

        System.arraycopy(customPayload, 0, payloadBytes, index, customPayload.length);

        return ScriptBuilder.createOpReturnScript(payloadBytes);
    }

    public static List<BtcECKey> createRandomBtcECKeys(int keysCount) {
        List<BtcECKey> keys = new ArrayList<>();
        for (int i = 0; i < keysCount; i++) {
            keys.add(new BtcECKey());
        }
        return keys;
    }
}
