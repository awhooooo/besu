package co.rsk.federate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import co.rsk.federate.signing.ECDSASignerFromFileKey;
import co.rsk.federate.signing.KeyId;
import co.rsk.federate.testing.FakeBitcoinWrapper;
import co.rsk.federate.testing.FakeNode;
import co.rsk.federate.tx.LegacyTransactionSigner;
import co.rsk.peg.BridgeMethods;
import co.rsk.peg.constants.BridgeRegTestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sequencer spends most of its life doing nothing visible. Without a heartbeat there is no
 * telling that from a process that has quietly stopped, and the first symptom would be a peg-in
 * nobody relayed.
 */
class SequencerLoggerTest {

    @TempDir Path home;

    private static final class TickingClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private FakeNode node;
    private FakeBitcoinWrapper bitcoin;
    private FederatorSupport federatorSupport;

    @BeforeEach
    void setUp() throws Exception {
        node = new FakeNode().atHeight(5_000)
            .answering(BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT, BigInteger.valueOf(42));
        bitcoin = new FakeBitcoinWrapper();
        Path keyFile = home.resolve("rsk.key");
        Files.writeString(keyFile, "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179");
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        federatorSupport = new FederatorSupport(
            new BridgeClient(
                node,
                new LegacyTransactionSigner(
                    new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile.toString()),
                    new KeyId("RSK"), BigInteger.valueOf(33)),
                GasPolicy.alwaysPaid(BigInteger.ONE), 4_000_000),
            new BridgeRegTestConstants().getBtcParams());
    }

    @Test
    void theFirstCallSpeaks() {
        TickingClock clock = new TickingClock();
        SequencerLogger heartbeat = new SequencerLogger(bitcoin, federatorSupport, clock, 60_000);

        heartbeat.log();

        // It read both chains, which is the work; that it wrote a line is the logger's business.
        assertThat(node.callCount()).isEqualTo(1);
    }

    @Test
    void speakingTooSoonIsSkipped() {
        TickingClock clock = new TickingClock();
        SequencerLogger heartbeat = new SequencerLogger(bitcoin, federatorSupport, clock, 60_000);

        heartbeat.log();
        node.clearSent();
        clock.advance(59_000);
        heartbeat.log();

        assertThat(node.callCount()).isZero();
    }

    @Test
    void onceTheGapHasPassedItSpeaksAgain() {
        TickingClock clock = new TickingClock();
        SequencerLogger heartbeat = new SequencerLogger(bitcoin, federatorSupport, clock, 60_000);

        heartbeat.log();
        node.clearSent();
        clock.advance(61_000);
        heartbeat.log();

        assertThat(node.callCount()).isEqualTo(1);
    }

    @Test
    void aChainThatCannotBeReadIsNotAReasonToFail() {
        // The heartbeat must never be why a tick throws; it is the least important thing running.
        TickingClock clock = new TickingClock();
        FakeNode silent = new FakeNode();
        FederatorSupport unanswerable = new FederatorSupport(
            new BridgeClient(silent,
                federatorSupportSignerStandIn(), GasPolicy.alwaysPaid(BigInteger.ONE), 4_000_000),
            new BridgeRegTestConstants().getBtcParams());
        SequencerLogger heartbeat = new SequencerLogger(bitcoin, unanswerable, clock, 0);

        heartbeat.log();
    }

    private LegacyTransactionSigner federatorSupportSignerStandIn() {
        try {
            Path keyFile = home.resolve("other.key");
            Files.writeString(keyFile, "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179");
            Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
            return new LegacyTransactionSigner(
                new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile.toString()),
                new KeyId("RSK"), BigInteger.valueOf(33));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
