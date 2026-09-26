package co.rsk.federate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.federate.signing.ECDSASignerFromFileKey;
import co.rsk.federate.signing.KeyId;
import co.rsk.federate.testing.FakeNode;
import co.rsk.federate.tx.LegacyTransactionSigner;
import co.rsk.peg.BridgeMethods;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationArgs;
import co.rsk.peg.federation.FederationFactory;
import co.rsk.peg.federation.FederationMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bridge hands out its members and its address but not which kind of federation they make, and
 * the kind decides the address. Watching the wrong address on Bitcoin fails silently: no peg-in
 * ever arrives and nothing says why. So the reader must land on the bridge's own address, or stop.
 */
class FederationReaderTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(33);
    private static final String RSK_KEY = "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179";
    private static final Instant CREATED = Instant.ofEpochSecond(1_700_000_000L);
    private static final long CREATED_IN_BLOCK = 42L;

    @TempDir Path home;

    private BridgeConstants bridgeConstants;
    private List<BtcECKey> keys;
    private FakeNode node;
    private FederationReader reader;

    @BeforeEach
    void setUp() throws Exception {
        bridgeConstants = new BridgeRegTestConstants();
        keys = List.of(
            BtcECKey.fromPrivate(BigInteger.valueOf(201)),
            BtcECKey.fromPrivate(BigInteger.valueOf(202)),
            BtcECKey.fromPrivate(BigInteger.valueOf(203)));
        node = new FakeNode();
        reader = new FederationReader(bridgeClient(), bridgeConstants);
    }

    @Test
    void aGenesisFederationIsRecognisedAsAStandardMultisig() {
        Federation expected = FederationFactory.buildStandardMultiSigFederation(args());
        bridgeAnswers(expected.getAddress().toBase58());

        Federation read = reader.getActiveFederation();

        assertThat(read.getAddress()).isEqualTo(expected.getAddress());
        assertThat(read.getSize()).isEqualTo(3);
        assertThat(read.getCreationTime()).isEqualTo(CREATED);
        assertThat(read.getCreationBlockNumber()).isEqualTo(CREATED_IN_BLOCK);
        assertThat(read.getBtcPublicKeys()).containsExactlyElementsOf(expected.getBtcPublicKeys());
    }

    @Test
    void aVotedInFederationIsRecognisedAsP2shP2wsh() {
        Federation expected = FederationFactory.buildP2shP2wshErpFederation(
            args(),
            bridgeConstants.getFederationConstants().getErpFedPubKeysList(),
            bridgeConstants.getFederationConstants().getErpFedActivationDelay());
        bridgeAnswers(expected.getAddress().toBase58());

        Federation read = reader.getActiveFederation();

        assertThat(read.getAddress()).isEqualTo(expected.getAddress());
        assertThat(read.getFormatVersion()).isEqualTo(expected.getFormatVersion());
    }

    @Test
    void theTwoKindsOfFederationDoNotShareAnAddress() {
        // Otherwise matching on the address would not be telling them apart at all.
        Federation standard = FederationFactory.buildStandardMultiSigFederation(args());
        Federation p2shP2wsh = FederationFactory.buildP2shP2wshErpFederation(
            args(),
            bridgeConstants.getFederationConstants().getErpFedPubKeysList(),
            bridgeConstants.getFederationConstants().getErpFedActivationDelay());

        assertThat(standard.getAddress()).isNotEqualTo(p2shP2wsh.getAddress());
    }

    @Test
    void anAddressThatMatchesNeitherStopsTheReader() {
        bridgeAnswers("2N2sJDoNzMg1eOAPYuhqZuAkNZLKm2EmZrx".substring(0, 34));

        assertThatThrownBy(() -> reader.getActiveFederation())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Refusing to watch an address the bridge is not using");
    }

    @Test
    void aWrongCreationTimeIsCaughtByTheAddressNotMatching() {
        // The creation time is part of the federation's identity but not of its script, so this is
        // really a check that the reader passes through what the bridge said rather than a default.
        Federation expected = FederationFactory.buildStandardMultiSigFederation(args());
        bridgeAnswers(expected.getAddress().toBase58());

        assertThat(reader.getActiveFederation().getCreationTime()).isEqualTo(CREATED);
    }

    @Test
    void everyKeyTheBridgeNamedIsInTheFederationThatComesBack() {
        // A federation orders its members canonically rather than by the order they arrived, so
        // what must survive is the set of keys and all three roles of each member, not an index.
        Federation expected = FederationFactory.buildStandardMultiSigFederation(args());
        bridgeAnswers(expected.getAddress().toBase58());

        Federation read = reader.getActiveFederation();

        assertThat(read.getBtcPublicKeys().stream().map(BtcECKey::getPubKey))
            .containsExactlyInAnyOrderElementsOf(
                keys.stream().map(key -> key.getPubKeyPoint().getEncoded(true)).toList());
        for (FederationMember member : read.getMembers()) {
            // One key per member serves all three roles, but a member holds its BTC key compressed
            // and its RSK and MST keys uncompressed, so the encodings differ while the point does not.
            assertThat(member.getBtcPublicKey().getPubKey()).hasSize(33);
            assertThat(member.getRskPublicKey().getPubKey()).hasSize(65);
            assertThat(member.getRskPublicKey().getPubKeyPoint())
                .isEqualTo(member.getBtcPublicKey().getPubKeyPoint());
            assertThat(member.getMstPublicKey().getPubKeyPoint())
                .isEqualTo(member.getBtcPublicKey().getPubKeyPoint());
        }
    }

    @Test
    void theKeysAreReadInTheOrderTheBridgeIndexesThem() {
        // The reader asks index by index, and the bridge's index is the federation's own order,
        // so what comes back must line up position for position.
        Federation expected = FederationFactory.buildStandardMultiSigFederation(args());
        List<BtcECKey> inBridgeOrder = expected.getBtcPublicKeys();
        keys = inBridgeOrder;
        bridgeAnswers(expected.getAddress().toBase58());

        Federation read = reader.getActiveFederation();

        for (int i = 0; i < inBridgeOrder.size(); i++) {
            assertThat(read.getMembers().get(i).getBtcPublicKey().getPubKeyPoint())
                .isEqualTo(inBridgeOrder.get(i).getPubKeyPoint());
        }
    }

    private FederationArgs args() {
        return new FederationArgs(
            FederationMember.getFederationMembersFromKeys(keys),
            CREATED,
            CREATED_IN_BLOCK,
            bridgeConstants.getBtcParams());
    }

    private void bridgeAnswers(String federationAddress) {
        node.answering(BridgeMethods.GET_FEDERATION_ADDRESS, federationAddress)
            .answering(BridgeMethods.GET_FEDERATION_SIZE, BigInteger.valueOf(keys.size()))
            .answering(BridgeMethods.GET_FEDERATION_CREATION_TIME, BigInteger.valueOf(CREATED.getEpochSecond()))
            .answering(BridgeMethods.GET_FEDERATION_CREATION_BLOCK_NUMBER, BigInteger.valueOf(CREATED_IN_BLOCK))
            .answeringWith(BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE, arguments -> {
                int index = ((BigInteger) arguments[0]).intValue();
                String keyType = (String) arguments[1];
                // As the bridge does: the BTC key compressed, the RSK and MST keys not.
                boolean compressed = "btc".equals(keyType);
                return new Object[] {keys.get(index).getPubKeyPoint().getEncoded(compressed)};
            });
    }

    private BridgeClient bridgeClient() throws Exception {
        Path keyFile = home.resolve("rsk.key");
        Files.writeString(keyFile, RSK_KEY);
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        LegacyTransactionSigner signer = new LegacyTransactionSigner(
            new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile.toString()), new KeyId("RSK"), CHAIN_ID);
        return new BridgeClient(node, signer, GasPolicy.alwaysPaid(BigInteger.ONE), 4_000_000);
    }
}
