package co.rsk.federate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.federate.btcreleaseclient.BtcReleaseClient;
import co.rsk.federate.btcreleaseclient.PegoutSignedCacheImpl;
import co.rsk.federate.io.BtcToRskClientFileStorageImpl;
import co.rsk.federate.io.BtcToRskClientFileStorageInfo;
import co.rsk.federate.signing.ECDSASigner;
import co.rsk.federate.signing.ECDSASignerFromFileKey;
import co.rsk.federate.signing.KeyId;
import co.rsk.federate.signing.SequencerKeyId;
import co.rsk.federate.testing.FakeBitcoinWrapper;
import co.rsk.federate.testing.FakeNode;
import co.rsk.federate.testing.PegoutFixture;
import co.rsk.federate.tx.LegacyTransactionSigner;
import co.rsk.federate.watcher.FederationWatcher;
import co.rsk.federate.watcher.FederationWatcherListener;
import co.rsk.peg.BridgeMethods;
import co.rsk.peg.StateForFederator;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scheduler is where the three shapes of work are told apart.
 *
 * <p>Informing the bridge about bitcoin is redundant between members, so it is taken in turns.
 * Signing is not redundant at all, so it is not. And the turn has to be recomputed as the
 * federation changes, because both the number of members and this member's place in it move.
 *
 * <p>The ticks are driven directly rather than through the timers, so nothing here waits.
 */
class SequencerSchedulerTest {

    private static final int TURN_MS = 90_000;

    @TempDir Path home;

    private static final class TickingClock extends Clock {
        private Instant now = Instant.EPOCH;

        void setMillis(long millis) {
            now = Instant.ofEpochMilli(millis);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private BridgeConstants bridgeConstants;
    private FakeNode node;
    private FakeBitcoinWrapper bitcoin;
    private TickingClock clock;
    private ECDSASigner signer;
    private BridgeClient bridge;
    private FederatorSupport federatorSupport;
    private BtcToRskClient peginClient;
    private BtcReleaseClient releaseClient;
    private StubWatcher watcher;
    private List<BtcECKey> keys;

    @BeforeEach
    void setUp() throws Exception {
        bridgeConstants = new BridgeRegTestConstants();
        keys = PegoutFixture.federationKeys(3);
        node = new FakeNode().atHeight(1_000)
            .answering(BridgeMethods.GET_STATE_FOR_BTC_RELEASE_CLIENT,
                new StateForFederator(new TreeMap<>()).encodeToRlp())
            .answering(BridgeMethods.GET_STATE_FOR_SVP_CLIENT, new byte[0]);
        bitcoin = new FakeBitcoinWrapper();
        clock = new TickingClock();
        signer = federatorSigner();
        bridge = bridgeClient();
        federatorSupport = new FederatorSupport(bridge, bridgeConstants.getBtcParams());
        peginClient = peginClient();
        releaseClient = releaseClient();
        watcher = new StubWatcher(new FederationProvider(bridge, bridgeConstants));
    }

    // ---------------------------------------------------------------- taking turns

    @Test
    void theTurnIsOursInOurSlotAndNobodyElses() {
        // Nine slots of ninety seconds; ours is the third, so 180 to 270 seconds into every round.
        watcher.active = federationOfNine(2);
        SequencerScheduler scheduler = scheduler();

        clock.setMillis(179_999);
        assertThat(scheduler.isOurTurn()).isFalse();
        clock.setMillis(180_000);
        assertThat(scheduler.isOurTurn()).isTrue();
        clock.setMillis(269_999);
        assertThat(scheduler.isOurTurn()).isTrue();
        clock.setMillis(270_000);
        assertThat(scheduler.isOurTurn()).isFalse();
    }

    @Test
    void everySlotInARoundBelongsToSomebodyAndOnlyOneIsOurs() {
        watcher.active = federationOfNine(2);
        SequencerScheduler scheduler = scheduler();

        int ours = 0;
        for (int slot = 0; slot < 9; slot++) {
            clock.setMillis((long) slot * TURN_MS + 1_000);
            if (scheduler.isOurTurn()) {
                ours++;
            }
        }

        assertThat(ours).isEqualTo(1);
    }

    @Test
    void theTurnComesRoundAgainEveryRoundAndNotBetween() {
        watcher.active = federationOfNine(2);
        SequencerScheduler scheduler = scheduler();
        long round = 9L * TURN_MS;

        for (int cycle = 0; cycle < 4; cycle++) {
            clock.setMillis(cycle * round + 200_000);
            assertThat(scheduler.isOurTurn()).as("cycle %d, in our slot", cycle).isTrue();
            clock.setMillis(cycle * round + 200_000 + TURN_MS);
            assertThat(scheduler.isOurTurn()).as("cycle %d, the next slot", cycle).isFalse();
        }
    }

    @Test
    void aSequencerWithNoSlotAlwaysActs() {
        // Not in the active federation: nothing to wait for. Registering a peg-in is open to
        // anyone, and the client decides which calls it may make.
        watcher.active = PegoutFixture.federationOfStrangers(bridgeConstants.getBtcParams());
        SequencerScheduler scheduler = scheduler();

        for (long at : List.of(0L, 100_000L, 400_000L, 800_000L)) {
            clock.setMillis(at);
            assertThat(scheduler.isOurTurn()).as("at t=%d", at).isTrue();
        }
    }

    @Test
    void theTurnFollowsTheFederationWhenItChanges() {
        // A slot chosen once at startup would be wrong from the next change onwards: both the
        // number of participants and this member's index move.
        watcher.active = federationOfNine(2);
        SequencerScheduler scheduler = scheduler();
        clock.setMillis(200_000);
        assertThat(scheduler.isOurTurn()).isTrue();

        Federation three = PegoutFixture.standardFederation(keys, bridgeConstants.getBtcParams());
        watcher.active = three;

        // The same instant now falls in somebody else's slot, because the round is shorter.
        long ourNewSlot = turnStartFor(three);
        assertThat(scheduler.isOurTurn()).isEqualTo(200_000 / TURN_MS % 3 == ourNewSlot / TURN_MS);
        clock.setMillis(ourNewSlot + 1_000);
        assertThat(scheduler.isOurTurn()).isTrue();
        clock.setMillis(ourNewSlot + TURN_MS + 1_000);
        assertThat(scheduler.isOurTurn()).isFalse();
    }

    @Test
    void inOurSlotTheClientsAreActuallyDriven() {
        Federation nine = federationOfNine(2);
        watcher.active = nine;
        clientsFollow(nine);
        SequencerScheduler scheduler = scheduler();

        clock.setMillis(0);
        scheduler.inform();
        int inSomebodyElsesSlot = node.callCount();

        clock.setMillis(200_000);
        scheduler.inform();

        assertThat(inSomebodyElsesSlot).isZero();
        assertThat(node.callCount()).isGreaterThan(0);
    }

    // ---------------------------------------------------------------- not taking turns

    @Test
    void signingHappensEveryTickWithNoTurnAtAll() {
        // Every signature counts towards the threshold, so there is nothing redundant to share out.
        Federation nine = federationOfNine(2);
        watcher.active = nine;
        clientsFollow(nine);
        SequencerScheduler scheduler = scheduler();

        for (long at : List.of(0L, 10_000L, 500_000L)) {
            node.clearSent();
            clock.setMillis(at);
            scheduler.sign();
            assertThat(node.callCount()).as("at t=%d", at).isGreaterThan(0);
        }
    }

    // ---------------------------------------------------------------- the node going away

    @Test
    void theWatchTickLooksForAFederationChange() {
        watcher.active = federationOfNine(2);
        SequencerScheduler scheduler = scheduler();

        scheduler.watch();

        assertThat(watcher.updates).isEqualTo(1);
    }

    @Test
    void aNodeThatStopsAnsweringLongEnoughStopsTheSequencer() {
        watcher.active = federationOfNine(2);
        AtomicBoolean lost = new AtomicBoolean();
        SequencerScheduler scheduler = scheduler(new NodeWatchdog(3, 1_000, millis -> { }));
        scheduler.onNodeLost(() -> lost.set(true));
        node.syncing(true);

        scheduler.watch();
        scheduler.watch();
        assertThat(lost).isFalse();
        scheduler.watch();

        assertThat(lost).isTrue();
        // The first two attempts still looked, because a node that misses a beat is not a node
        // that is gone. The third gave up, and that tick did not look.
        assertThat(watcher.updates).isEqualTo(2);
    }

    @Test
    void aNodeThatComesBackIsForgiven() {
        watcher.active = federationOfNine(2);
        AtomicBoolean lost = new AtomicBoolean();
        SequencerScheduler scheduler = scheduler(new NodeWatchdog(3, 1_000, millis -> { }));
        scheduler.onNodeLost(() -> lost.set(true));

        node.syncing(true);
        scheduler.watch();
        scheduler.watch();
        node.syncing(false);
        scheduler.watch();
        node.syncing(true);
        scheduler.watch();
        scheduler.watch();

        assertThat(lost).isFalse();
    }

    // ---------------------------------------------------------------- one tick failing

    @Test
    void aTickThatThrowsDoesNotStopTheRest() {
        // A task that throws out of scheduleAtFixedRate is never run again, silently.
        watcher.active = federationOfNine(2);
        watcher.throwOnUpdate = true;
        SequencerScheduler scheduler = scheduler();

        scheduler.start();
        try {
            // Nothing thrown out of start, and the other ticks are still scheduled.
            assertThat(scheduler).isNotNull();
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void aSchedulerCannotBeStartedTwice() {
        watcher.active = federationOfNine(2);
        SequencerScheduler scheduler = scheduler();
        scheduler.start();
        try {
            assertThatThrownBy(scheduler::start).isInstanceOf(IllegalStateException.class);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void aCadenceOfZeroIsRefused() {
        watcher.active = federationOfNine(2);
        assertThatThrownBy(() -> new SequencerScheduler(
            watcher, List.of(peginClient), releaseClient, signer,
            new NodeWatchdog(10, 1_000, millis -> { }), bridge, heartbeat(), clock, 0, 1, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- helpers

    private SequencerScheduler scheduler() {
        return scheduler(new NodeWatchdog(10, 1_000, millis -> { }));
    }

    /** Points both clients at a federation, as the watcher's listener would. */
    private void clientsFollow(Federation federation) {
        peginClient.start(federation);
        releaseClient.start(federation);
    }

    private SequencerScheduler scheduler(NodeWatchdog watchdog) {
        return new SequencerScheduler(
            watcher, List.of(peginClient), releaseClient, signer, watchdog, bridge, heartbeat(), clock,
            TURN_MS, 120_000, 90_000);
    }

    private SequencerLogger heartbeat() {
        // A gap longer than any test, so the heartbeat never adds calls of its own.
        return new SequencerLogger(bitcoin, federatorSupport, clock, Long.MAX_VALUE / 4);
    }

    /** A federation of nine whose member at {@code ourIndex} holds this sequencer's BTC key. */
    private Federation federationOfNine(int ourIndex) {
        List<BtcECKey> nine = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(BtcECKey.fromPrivate(BigInteger.valueOf(2_000 + i)));
        }
        nine.set(ourIndex, keys.get(0));
        return PegoutFixture.standardFederation(nine, bridgeConstants.getBtcParams());
    }

    /** When this sequencer's slot starts in the round, for the given federation. */
    private long turnStartFor(Federation federation) throws RuntimeException {
        try {
            BtcECKey ours = signer.getPublicKey(SequencerKeyId.BTC.getKeyId()).toBtcKey();
            int index = federation.getBtcPublicKeyIndex(ours).orElseThrow();
            return (long) index * TURN_MS;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BtcToRskClient peginClient() throws IOException {
        return new BtcToRskClient(
            bitcoin, federatorSupport, bridgeConstants,
            new BtcToRskClientFileStorageImpl(new BtcToRskClientFileStorageInfo(home.resolve("pegin"))),
            new BtcLockSenderProvider(), new PeginInstructionsProvider(), 100, 3_600);
    }

    private BtcReleaseClient releaseClient() {
        BridgeEventReader events = new BridgeEventReader(node, 4_500);
        return new BtcReleaseClient(
            bitcoin, federatorSupport, bridgeConstants, signer,
            new PegoutOutpointValues(events, 50_000), events,
            new PegoutSignedCacheImpl(Duration.ofMinutes(30), Clock.systemUTC()), 4_500);
    }

    private ECDSASigner federatorSigner() throws Exception {
        Path keyFile = home.resolve("btc.key");
        Files.writeString(keyFile, PegoutFixture.federationKeys(3).get(0).getPrivateKeyAsHex());
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        return new ECDSASignerFromFileKey(SequencerKeyId.BTC.getKeyId(), keyFile.toString());
    }

    private BridgeClient bridgeClient() throws Exception {
        Path keyFile = home.resolve("rsk.key");
        Files.writeString(keyFile, "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179");
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        return new BridgeClient(
            node,
            new LegacyTransactionSigner(
                new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile.toString()),
                new KeyId("RSK"), BigInteger.valueOf(33)),
            GasPolicy.alwaysPaid(BigInteger.ONE), 4_000_000);
    }

    /** A watcher whose answers the test sets, so the scheduler's turn maths can be driven. */
    private static final class StubWatcher extends FederationWatcher {
        private Federation active;
        private int updates;
        private boolean throwOnUpdate;

        StubWatcher(FederationProvider provider) {
            // The provider is never reached: both methods the scheduler uses are overridden here.
            super(provider, new FederationWatcherListener() {
                @Override public void onActiveFederationChange(Federation f) { }
                @Override public void onRetiringFederationChange(Federation f) { }
                @Override public void onProposedFederationChange(Federation f) { }
            });
        }

        @Override
        public void updateState() {
            if (throwOnUpdate) {
                throw new IllegalStateException("the bridge did not answer");
            }
            updates++;
        }

        @Override
        public java.util.Optional<Federation> getActiveFederation() {
            return java.util.Optional.ofNullable(active);
        }
    }
}
