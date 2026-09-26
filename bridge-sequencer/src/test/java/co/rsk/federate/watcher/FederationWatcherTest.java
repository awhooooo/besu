package co.rsk.federate.watcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.federate.BridgeClient;
import co.rsk.federate.FederationProvider;
import co.rsk.federate.GasPolicy;
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
 * The watcher exists to notice a change that happens perhaps twice in a chain's life, cheaply
 * enough to ask about constantly. So what is checked is that it reports each change exactly once,
 * that it does not report anything when nothing moved, and that asking costs three calls rather
 * than a hundred.
 */
class FederationWatcherTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(33);
    private static final String RSK_KEY = "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179";
    private static final Instant CREATED = Instant.ofEpochSecond(1_700_000_000L);

    @TempDir Path home;

    private BridgeConstants bridgeConstants;
    private FakeNode node;
    private FederationProvider provider;
    private RecordingListener listener;
    private FederationWatcher watcher;

    private Federation first;
    private Federation second;
    private Federation third;

    @BeforeEach
    void setUp() throws Exception {
        bridgeConstants = new BridgeRegTestConstants();
        node = new FakeNode();
        provider = new FederationProvider(bridgeClient(), bridgeConstants);
        listener = new RecordingListener();
        watcher = new FederationWatcher(provider, listener);

        first = federationOf(PegoutFixture.federationKeys(3));
        second = federationOf(PegoutFixture.strangersKeys(3));
        third = federationOf(PegoutFixture.strangersKeys(4));

        active(first);
        noRetiring();
        noProposed();
    }

    @Test
    void theFirstLookReportsWhatIsThere() {
        watcher.updateState();

        assertThat(listener.activeChanges).containsExactly(first.getAddress().toBase58());
        assertThat(listener.retiringChanges).isEmpty();
        assertThat(listener.proposedChanges).isEmpty();
    }

    @Test
    void nothingIsReportedWhenNothingMoved() {
        watcher.updateState();
        listener.clear();

        watcher.updateState();
        watcher.updateState();

        assertThat(listener.activeChanges).isEmpty();
        assertThat(listener.retiringChanges).isEmpty();
        assertThat(listener.proposedChanges).isEmpty();
    }

    @Test
    void aProposalIsReportedWhenItAppearsAndAgainWhenItGoes() {
        watcher.updateState();
        listener.clear();

        proposed(second);
        watcher.updateState();
        assertThat(listener.proposedChanges).containsExactly(second.getAddress().toBase58());

        noProposed();
        watcher.updateState();
        assertThat(listener.proposedChanges)
            .containsExactly(second.getAddress().toBase58(), null);
    }

    @Test
    void aChangeOfFederationIsReportedOnBothSides() {
        // What a completed change looks like: the proposal becomes active, the old active retires.
        watcher.updateState();
        proposed(second);
        watcher.updateState();
        listener.clear();

        active(second);
        retiring(first);
        noProposed();
        watcher.updateState();

        assertThat(listener.activeChanges).containsExactly(second.getAddress().toBase58());
        assertThat(listener.retiringChanges).containsExactly(first.getAddress().toBase58());
        assertThat(listener.proposedChanges).containsExactly((String) null);
    }

    @Test
    void aProposalIsReportedBeforeAnythingElseMoves() {
        // A client that will have to sign for a proposal should hear of it before the ground
        // shifts under the federation it is already following.
        watcher.updateState();
        listener.clear();

        proposed(third);
        active(second);
        watcher.updateState();

        assertThat(listener.order).containsExactly("proposed", "active");
    }

    @Test
    void aRetiringFederationGoingAwayIsReportedAsNothing() {
        retiring(second);
        watcher.updateState();
        listener.clear();

        noRetiring();
        watcher.updateState();

        assertThat(listener.retiringChanges).containsExactly((String) null);
    }

    @Test
    void theWatcherRemembersWhatItLastSaw() {
        proposed(second);
        retiring(third);
        watcher.updateState();

        assertThat(watcher.getActiveFederation()).hasValueSatisfying(
            federation -> assertThat(federation.getAddress()).isEqualTo(first.getAddress()));
        assertThat(watcher.getRetiringFederation()).hasValueSatisfying(
            federation -> assertThat(federation.getAddress()).isEqualTo(third.getAddress()));
        assertThat(watcher.getProposedFederation()).hasValueSatisfying(
            federation -> assertThat(federation.getAddress()).isEqualTo(second.getAddress()));
    }

    @Test
    void anUnchangedPollCostsThreeCalls() {
        // The reason to poll addresses rather than federations. Rebuilding all three would be a
        // call for every member of each.
        watcher.updateState();
        node.clearSent();

        watcher.updateState();

        assertThat(node.callCount()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- helpers

    private void active(Federation federation) {
        answer(federation, BridgeMethods.GET_FEDERATION_ADDRESS, BridgeMethods.GET_FEDERATION_SIZE,
            BridgeMethods.GET_FEDERATION_CREATION_TIME, BridgeMethods.GET_FEDERATION_CREATION_BLOCK_NUMBER,
            BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE);
    }

    private void retiring(Federation federation) {
        answer(federation, BridgeMethods.GET_RETIRING_FEDERATION_ADDRESS,
            BridgeMethods.GET_RETIRING_FEDERATION_SIZE, BridgeMethods.GET_RETIRING_FEDERATION_CREATION_TIME,
            BridgeMethods.GET_RETIRING_FEDERATION_CREATION_BLOCK_NUMBER,
            BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE);
    }

    private void proposed(Federation federation) {
        answer(federation, BridgeMethods.GET_PROPOSED_FEDERATION_ADDRESS,
            BridgeMethods.GET_PROPOSED_FEDERATION_SIZE, BridgeMethods.GET_PROPOSED_FEDERATION_CREATION_TIME,
            BridgeMethods.GET_PROPOSED_FEDERATION_CREATION_BLOCK_NUMBER,
            BridgeMethods.GET_PROPOSED_FEDERATOR_PUBLIC_KEY_OF_TYPE);
    }

    private void noRetiring() {
        node.answering(BridgeMethods.GET_RETIRING_FEDERATION_ADDRESS, "");
    }

    private void noProposed() {
        node.answering(BridgeMethods.GET_PROPOSED_FEDERATION_ADDRESS, "");
    }

    private void answer(
        Federation federation,
        BridgeMethods addressMethod,
        BridgeMethods sizeMethod,
        BridgeMethods creationTimeMethod,
        BridgeMethods creationBlockMethod,
        BridgeMethods publicKeyMethod) {
        List<BtcECKey> federationKeys = federation.getBtcPublicKeys();
        node.answering(addressMethod, federation.getAddress().toBase58())
            .answering(sizeMethod, BigInteger.valueOf(federationKeys.size()))
            .answering(creationTimeMethod, BigInteger.valueOf(CREATED.getEpochSecond()))
            .answering(creationBlockMethod, BigInteger.ONE)
            .answeringWith(publicKeyMethod, arguments -> {
                int index = ((BigInteger) arguments[0]).intValue();
                boolean compressed = "btc".equals((String) arguments[1]);
                return new Object[] {federationKeys.get(index).getPubKeyPoint().getEncoded(compressed)};
            });
    }

    private Federation federationOf(List<BtcECKey> keys) {
        return FederationFactory.buildStandardMultiSigFederation(new FederationArgs(
            FederationMember.getFederationMembersFromKeys(keys), CREATED, 1L, bridgeConstants.getBtcParams()));
    }

    private BridgeClient bridgeClient() throws Exception {
        Path keyFile = home.resolve("rsk.key");
        Files.writeString(keyFile, RSK_KEY);
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        LegacyTransactionSigner signer = new LegacyTransactionSigner(
            new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile.toString()), new KeyId("RSK"), CHAIN_ID);
        return new BridgeClient(node, signer, GasPolicy.alwaysPaid(BigInteger.ONE), 4_000_000);
    }

    /** Records what it was told, and in what order. */
    private static final class RecordingListener implements FederationWatcherListener {
        private final List<String> activeChanges = new ArrayList<>();
        private final List<String> retiringChanges = new ArrayList<>();
        private final List<String> proposedChanges = new ArrayList<>();
        private final List<String> order = new ArrayList<>();

        void clear() {
            activeChanges.clear();
            retiringChanges.clear();
            proposedChanges.clear();
            order.clear();
        }

        @Override
        public void onActiveFederationChange(Federation federation) {
            activeChanges.add(addressOf(federation));
            order.add("active");
        }

        @Override
        public void onRetiringFederationChange(Federation federation) {
            retiringChanges.add(addressOf(federation));
            order.add("retiring");
        }

        @Override
        public void onProposedFederationChange(Federation federation) {
            proposedChanges.add(addressOf(federation));
            order.add("proposed");
        }

        private static String addressOf(Federation federation) {
            return federation == null ? null : federation.getAddress().toBase58();
        }
    }
}
