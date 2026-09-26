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
import co.rsk.federate.testing.PegoutFixture;
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
class FederationProviderTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(33);
    private static final String RSK_KEY = "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179";
    private static final Instant CREATED = Instant.ofEpochSecond(1_700_000_000L);
    private static final long CREATED_IN_BLOCK = 42L;

    @TempDir Path home;

    private BridgeConstants bridgeConstants;
    private List<BtcECKey> keys;
    private FakeNode node;
    private FederationProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        bridgeConstants = new BridgeRegTestConstants();
        keys = List.of(
            BtcECKey.fromPrivate(BigInteger.valueOf(201)),
            BtcECKey.fromPrivate(BigInteger.valueOf(202)),
            BtcECKey.fromPrivate(BigInteger.valueOf(203)));
        node = new FakeNode();
        provider = new FederationProvider(bridgeClient(), bridgeConstants);
    }

    @Test
    void aGenesisFederationIsRecognisedAsAStandardMultisig() {
        Federation expected = FederationFactory.buildStandardMultiSigFederation(args());
        bridgeAnswers(expected.getAddress().toBase58());

        Federation read = provider.getActiveFederation();

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

        Federation read = provider.getActiveFederation();

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
        // A real address, belonging to a federation these members do not make. Watching it would
        // mean seeing no peg-in ever arrive, with nothing to say why.
        Federation somebodyElses = FederationFactory.buildStandardMultiSigFederation(
            new FederationArgs(
                FederationMember.getFederationMembersFromKeys(PegoutFixture.strangersKeys(3)),
                CREATED, CREATED_IN_BLOCK, bridgeConstants.getBtcParams()));
        bridgeAnswers(somebodyElses.getAddress().toBase58());

        assertThatThrownBy(() -> provider.getActiveFederation())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Refusing to watch an address the bridge is not using");
    }

    @Test
    void anAddressThatIsNotAnAddressStopsTheReaderSooner() {
        // Parsing the address rather than comparing strings means a malformed one fails here,
        // where it is obvious, rather than by quietly matching nothing.
        bridgeAnswers("not-a-bitcoin-address");

        assertThatThrownBy(() -> provider.getActiveFederationAddress())
            .isInstanceOf(co.rsk.bitcoinj.core.AddressFormatException.class);
    }

    @Test
    void aWrongCreationTimeIsCaughtByTheAddressNotMatching() {
        // The creation time is part of the federation's identity but not of its script, so this is
        // really a check that the reader passes through what the bridge said rather than a default.
        Federation expected = FederationFactory.buildStandardMultiSigFederation(args());
        bridgeAnswers(expected.getAddress().toBase58());

        assertThat(provider.getActiveFederation().getCreationTime()).isEqualTo(CREATED);
    }

    @Test
    void everyKeyTheBridgeNamedIsInTheFederationThatComesBack() {
        // A federation orders its members canonically rather than by the order they arrived, so
        // what must survive is the set of keys and all three roles of each member, not an index.
        Federation expected = FederationFactory.buildStandardMultiSigFederation(args());
        bridgeAnswers(expected.getAddress().toBase58());

        Federation read = provider.getActiveFederation();

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

        Federation read = provider.getActiveFederation();

        for (int i = 0; i < inBridgeOrder.size(); i++) {
            assertThat(read.getMembers().get(i).getBtcPublicKey().getPubKeyPoint())
                .isEqualTo(inBridgeOrder.get(i).getPubKeyPoint());
        }
    }

    // ---------------------------------------------------------------- the other two federations

    @Test
    void anEmptyAddressMeansThereIsNoRetiringFederation() {
        // How the bridge says so: not an error, an empty string. Its other getters fall back on
        // a size of -1 and a creation time of -1, which would decode as real values if read.
        noRetiringFederation();

        assertThat(provider.getRetiringFederationAddress()).isEmpty();
        assertThat(provider.getRetiringFederation()).isEmpty();
    }

    @Test
    void anEmptyAddressMeansThereIsNoProposedFederation() {
        noProposedFederation();

        assertThat(provider.getProposedFederationAddress()).isEmpty();
        assertThat(provider.getProposedFederation()).isEmpty();
    }

    @Test
    void aRetiringFederationIsReadLikeTheActiveOne() {
        List<BtcECKey> outgoing = PegoutFixture.federationKeys(3);
        Federation expected = FederationFactory.buildStandardMultiSigFederation(
            new FederationArgs(FederationMember.getFederationMembersFromKeys(outgoing),
                CREATED, CREATED_IN_BLOCK, bridgeConstants.getBtcParams()));
        answerFor(expected.getAddress().toBase58(), outgoing,
            BridgeMethods.GET_RETIRING_FEDERATION_ADDRESS, BridgeMethods.GET_RETIRING_FEDERATION_SIZE,
            BridgeMethods.GET_RETIRING_FEDERATION_CREATION_TIME,
            BridgeMethods.GET_RETIRING_FEDERATION_CREATION_BLOCK_NUMBER,
            BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE);

        assertThat(provider.getRetiringFederation()).hasValueSatisfying(
            federation -> assertThat(federation.getAddress()).isEqualTo(expected.getAddress()));
    }

    @Test
    void aProposedFederationIsReadLikeTheActiveOne() {
        // Always P2SH-P2WSH, since that is the only kind a vote can create.
        List<BtcECKey> incoming = PegoutFixture.strangersKeys(3);
        Federation expected = FederationFactory.buildP2shP2wshErpFederation(
            new FederationArgs(FederationMember.getFederationMembersFromKeys(incoming),
                CREATED, CREATED_IN_BLOCK, bridgeConstants.getBtcParams()),
            bridgeConstants.getFederationConstants().getErpFedPubKeysList(),
            bridgeConstants.getFederationConstants().getErpFedActivationDelay());
        answerFor(expected.getAddress().toBase58(), incoming,
            BridgeMethods.GET_PROPOSED_FEDERATION_ADDRESS, BridgeMethods.GET_PROPOSED_FEDERATION_SIZE,
            BridgeMethods.GET_PROPOSED_FEDERATION_CREATION_TIME,
            BridgeMethods.GET_PROPOSED_FEDERATION_CREATION_BLOCK_NUMBER,
            BridgeMethods.GET_PROPOSED_FEDERATOR_PUBLIC_KEY_OF_TYPE);

        assertThat(provider.getProposedFederation()).hasValueSatisfying(
            federation -> assertThat(federation.getAddress()).isEqualTo(expected.getAddress()));
    }

    @Test
    void readingAnAddressCostsOneCallRatherThanOnePerMember() {
        // Which is the whole reason the watcher polls addresses: a change happens twice in a
        // chain's life, and rebuilding a federation costs a call for every member it has.
        Federation expected = FederationFactory.buildStandardMultiSigFederation(args());
        bridgeAnswers(expected.getAddress().toBase58());
        node.clearSent();

        provider.getActiveFederationAddress();
        int forAddressAlone = node.callCount();
        provider.getActiveFederation();

        assertThat(forAddressAlone).isEqualTo(1);
        assertThat(node.callCount()).isGreaterThan(keys.size());
    }

    private FederationArgs args() {
        return new FederationArgs(
            FederationMember.getFederationMembersFromKeys(keys),
            CREATED,
            CREATED_IN_BLOCK,
            bridgeConstants.getBtcParams());
    }

    private void bridgeAnswers(String federationAddress) {
        answerFor(federationAddress, keys,
            BridgeMethods.GET_FEDERATION_ADDRESS, BridgeMethods.GET_FEDERATION_SIZE,
            BridgeMethods.GET_FEDERATION_CREATION_TIME, BridgeMethods.GET_FEDERATION_CREATION_BLOCK_NUMBER,
            BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE);
    }

    /** Answers one of the three federations; an empty address is how the bridge says there is none. */
    private void answerFor(
        String address,
        List<BtcECKey> federationKeys,
        BridgeMethods addressMethod,
        BridgeMethods sizeMethod,
        BridgeMethods creationTimeMethod,
        BridgeMethods creationBlockMethod,
        BridgeMethods publicKeyMethod) {
        node.answering(addressMethod, address)
            .answering(sizeMethod, BigInteger.valueOf(federationKeys.size()))
            .answering(creationTimeMethod, BigInteger.valueOf(CREATED.getEpochSecond()))
            .answering(creationBlockMethod, BigInteger.valueOf(CREATED_IN_BLOCK))
            .answeringWith(publicKeyMethod, arguments -> {
                int index = ((BigInteger) arguments[0]).intValue();
                // As the bridge does: the BTC key compressed, the RSK and MST keys not.
                boolean compressed = "btc".equals((String) arguments[1]);
                return new Object[] {federationKeys.get(index).getPubKeyPoint().getEncoded(compressed)};
            });
    }

    private void noRetiringFederation() {
        node.answering(BridgeMethods.GET_RETIRING_FEDERATION_ADDRESS, "");
    }

    private void noProposedFederation() {
        node.answering(BridgeMethods.GET_PROPOSED_FEDERATION_ADDRESS, "");
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
