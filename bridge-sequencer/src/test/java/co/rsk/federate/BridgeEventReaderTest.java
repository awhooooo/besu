package co.rsk.federate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import co.rsk.federate.rpc.EthClient;
import co.rsk.federate.testing.FakeNode;
import co.rsk.peg.BridgeEvents;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

/**
 * Nodes cap how many blocks one log query may span — Besu's default is 5,000 — so anything the
 * sequencer wants from further back has to be asked for in pieces. Asking for too wide a range is
 * not a slow query but a refused one, and a refused query means a peg-out that is never signed.
 */
class BridgeEventReaderTest {

    private static final Bytes32 PEGOUT_CREATED =
        BridgeEventReader.topicOf(BridgeEvents.PEGOUT_TRANSACTION_CREATED);
    private static final List<List<Bytes32>> ANY_PEGOUT_CREATED = List.of(List.of(PEGOUT_CREATED));

    @Test
    void oneWindowIsEnoughWhenTheEventIsRecent() {
        FakeNode node = new FakeNode().emitting(9_900, PEGOUT_CREATED, List.of(), Bytes.of(1));
        BridgeEventReader reader = new BridgeEventReader(node, 4_500);

        List<EthClient.LogEntry> found = reader.findBackwards(10_000, 50_000, ANY_PEGOUT_CREATED);

        assertThat(found).hasSize(1);
        assertThat(node.logQueries()).hasSize(1);
    }

    @Test
    void noWindowEverExceedsTheSizeItWasGiven() {
        // The one thing that must hold however far back the search goes.
        FakeNode node = new FakeNode();
        BridgeEventReader reader = new BridgeEventReader(node, 4_500);

        reader.findBackwards(100_000, 50_000, ANY_PEGOUT_CREATED);

        assertThat(node.logQueries()).isNotEmpty();
        for (EthClient.LogFilter query : node.logQueries()) {
            assertThat(query.toBlock() - query.fromBlock() + 1).isLessThanOrEqualTo(4_500);
        }
    }

    @Test
    void theSearchWalksBackUntilItFindsSomething() {
        // Three windows back, which is where a sequencer that was switched off would have to look.
        FakeNode node = new FakeNode().emitting(88_000, PEGOUT_CREATED, List.of(), Bytes.of(7));
        BridgeEventReader reader = new BridgeEventReader(node, 4_500);

        List<EthClient.LogEntry> found = reader.findBackwards(100_000, 50_000, ANY_PEGOUT_CREATED);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).blockNumber()).isEqualTo(88_000);
        assertThat(node.logQueries().size()).isGreaterThan(1);
    }

    @Test
    void theSearchStopsAtTheLookbackRatherThanAtGenesis() {
        FakeNode node = new FakeNode().emitting(1_000, PEGOUT_CREATED, List.of(), Bytes.of(7));
        BridgeEventReader reader = new BridgeEventReader(node, 4_500);

        List<EthClient.LogEntry> found = reader.findBackwards(100_000, 10_000, ANY_PEGOUT_CREATED);

        assertThat(found).isEmpty();
        long lowest = node.logQueries().stream().mapToLong(EthClient.LogFilter::fromBlock).min().orElseThrow();
        assertThat(lowest).isEqualTo(90_001);
    }

    @Test
    void theSearchNeverAsksForANegativeBlock() {
        FakeNode node = new FakeNode();
        BridgeEventReader reader = new BridgeEventReader(node, 4_500);

        reader.findBackwards(100, 50_000, ANY_PEGOUT_CREATED);

        assertThat(node.logQueries()).allSatisfy(query -> assertThat(query.fromBlock()).isNotNegative());
    }

    @Test
    void theMostRecentAnswerWins() {
        // A peg-out could in principle be announced more than once; the latest is the live one.
        FakeNode node = new FakeNode()
            .emitting(80_000, PEGOUT_CREATED, List.of(), Bytes.of(1))
            .emitting(99_000, PEGOUT_CREATED, List.of(), Bytes.of(2));
        BridgeEventReader reader = new BridgeEventReader(node, 4_500);

        List<EthClient.LogEntry> found = reader.findBackwards(100_000, 50_000, ANY_PEGOUT_CREATED);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).blockNumber()).isEqualTo(99_000);
    }

    @Test
    void aForwardScanCoversTheWholeRangeInWindows() {
        FakeNode node = new FakeNode()
            .emitting(10, PEGOUT_CREATED, List.of(), Bytes.of(1))
            .emitting(6_000, PEGOUT_CREATED, List.of(), Bytes.of(2))
            .emitting(9_000, PEGOUT_CREATED, List.of(), Bytes.of(3));
        BridgeEventReader reader = new BridgeEventReader(node, 4_500);

        List<EthClient.LogEntry> found = reader.findAll(0, 10_000, ANY_PEGOUT_CREATED);

        assertThat(found).hasSize(3);
        assertThat(node.logQueries()).allSatisfy(
            query -> assertThat(query.toBlock() - query.fromBlock() + 1).isLessThanOrEqualTo(4_500));
    }

    @Test
    void aTopicThatMatchesNothingFindsNothing() {
        Bytes32 other = BridgeEventReader.topicOf(BridgeEvents.RELEASE_BTC);
        FakeNode node = new FakeNode().emitting(9_900, PEGOUT_CREATED, List.of(), Bytes.of(1));
        BridgeEventReader reader = new BridgeEventReader(node, 4_500);

        assertThat(reader.findBackwards(10_000, 50_000, List.of(List.of(other)))).isEmpty();
    }

    @Test
    void aWindowMustSpanAtLeastABlock() {
        assertThatThrownBy(() -> new BridgeEventReader(new FakeNode(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
