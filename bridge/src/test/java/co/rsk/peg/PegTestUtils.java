package co.rsk.peg;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.TransactionOutput;
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.peg.bitcoin.FlyoverRedeemScriptBuilderImpl;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationArgs;
import co.rsk.peg.federation.FederationFactory;
import co.rsk.peg.federation.FederationMember;
import co.rsk.peg.federation.FederationTestUtils;
import co.rsk.peg.host.CallContext;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;

import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

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

    public static Address createRandomP2PKHBtcAddress(NetworkParameters networkParameters) {
        BtcECKey key = new BtcECKey();
        return key.toAddress(networkParameters);
    }

    /** RSKj's createRandomRskAddress: the address of a fresh key. */
    public static org.hyperledger.besu.datatypes.Address createRandomRskAddress() {
        return co.rsk.peg.utils.PublicKeys.addressOf(new BtcECKey());
    }

    public static UTXO createUTXO(Sha256Hash btcHash, long index, Coin value) {
        return new UTXO(
            btcHash,
            index,
            value,
            10,
            false,
            ScriptBuilder.createOutputScript(new BtcECKey())
        );
    }

    public static TransactionOutput createBech32Output(NetworkParameters networkParameters, Coin valuesToSend) {
        byte[] scriptBytes = networkParameters.getId().equals(NetworkParameters.ID_MAINNET) ?
            Hex.decode("001437c383ea78269585c73289daa36d7b7014b65294") :
            Hex.decode("0014ef57424d0d625cf82fabe4fd7657d24a5f13dfb2");
        return new TransactionOutput(networkParameters, null, valuesToSend, scriptBytes);
    }

    public static Federation createFederation(BridgeConstants bridgeConstants, String... fedKeys) {
        List<BtcECKey> federationKeys = Arrays.stream(fedKeys)
            .map(s -> BtcECKey.fromPrivate(Hex.decode(s)))
            .collect(Collectors.toList());
        return createFederation(bridgeConstants, federationKeys);
    }

    public static Federation createFederation(BridgeConstants bridgeConstants, List<BtcECKey> federationKeys) {
        federationKeys.sort(BtcECKey.PUBKEY_COMPARATOR);
        List<FederationMember> fedMembers = FederationTestUtils.getFederationMembersWithBtcKeys(federationKeys);
        Instant creationTime = Instant.ofEpochMilli(1000L);
        NetworkParameters btcParams = bridgeConstants.getBtcParams();

        FederationArgs federationArgs = new FederationArgs(fedMembers, creationTime, 0L, btcParams);
        return FederationFactory.buildStandardMultiSigFederation(federationArgs);
    }

    public static Address getFlyoverAddressFromRedeemScript(BridgeConstants bridgeConstants, Script redeemScript, Sha256Hash derivationArgumentHash) {
        Hash flyoverDerivationHash = Hash.wrap(Bytes32.wrap(derivationArgumentHash.getBytes()));
        Script flyoverRedeemScript = FlyoverRedeemScriptBuilderImpl.builder().of(
            flyoverDerivationHash,
            redeemScript
        );

        Script flyoverP2SH = ScriptBuilder.createP2SHOutputScript(flyoverRedeemScript);
        return Address.fromP2SHScript(bridgeConstants.getBtcParams(), flyoverP2SH);
    }

    public static Script createBaseInputScriptThatSpendsFromTheFederation(Federation federation) {
        Script scriptPubKey = federation.getP2SHScript();
        return scriptPubKey.createEmptyInputScript(null, federation.getRedeemScript());
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
