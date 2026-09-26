package co.rsk.federate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import co.rsk.federate.config.SequencerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code --check} answers.
 *
 * <p>It exists so that "are these key files right" can be asked without a running node and a
 * bitcoin peer, which is when an operator most wants to ask it. So it must find the things that
 * would otherwise surface hours later as a sequencer that started and then did nothing, and it
 * must say each of them once: a list where the real problem is buried under its own consequences
 * is barely better than no list.
 */
class SequencerRunnerTest {

    @TempDir Path home;

    private static final String KEY = "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179";

    private Path keys;

    @BeforeEach
    void setUp() throws Exception {
        keys = home.resolve("keys");
        Files.createDirectories(keys);
        for (String name : List.of("btc", "rsk", "mst")) {
            writeKey(name);
        }
    }

    @Test
    void aGoodConfigurationHasNothingToReport() throws Exception {
        assertThat(new SequencerRunner(config()).check()).isEmpty();
    }

    @Test
    void aKeyFileThatIsNotThereIsReportedOnce() throws Exception {
        // And not also for its permissions, which a file that does not exist does not have.
        Files.delete(keys.resolve("mst.key"));

        List<String> problems = new SequencerRunner(config()).check();

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("does not exist");
    }

    @Test
    void aKeyFileAnyoneCanReadIsReported() throws Exception {
        // The whole point of holding a key in a file is that only its owner can read it.
        Files.setPosixFilePermissions(keys.resolve("btc.key"),
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OTHERS_READ));

        assertThat(new SequencerRunner(config()).check())
            .anySatisfy(problem -> assertThat(problem).contains("permissions"));
    }

    @Test
    void aNetworkTheBridgeDoesNotHaveIsReportedOnce() throws Exception {
        // Nothing after this can be checked without knowing the network, so repeating the same
        // complaint under a second heading would only bury it.
        List<String> problems = new SequencerRunner(config("moonnet", peers())).check();

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("moonnet");
    }

    @Test
    void aBitcoinPeerThatCannotBeResolvedIsReported() throws Exception {
        // A typo here otherwise surfaces as a peer group that silently never syncs.
        List<String> problems =
            new SequencerRunner(config("regtest", "\"no-such-host.invalid:18444\"")).check();

        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("Bitcoin peers"));
    }

    @Test
    void checkingConnectsToNothing() throws Exception {
        // The node in this configuration does not exist. Checking must still answer.
        SequencerConfig config = SequencerConfig.of(com.typesafe.config.ConfigFactory.parseString(
            configText("regtest", peers()).replace("http://127.0.0.1:8545", "http://127.0.0.1:1")));

        assertThat(new SequencerRunner(config).check()).isEmpty();
    }

    @Test
    void closingSomethingNeverStartedIsHarmless() throws Exception {
        // The shutdown hook runs whether or not start() got anywhere.
        SequencerRunner runner = new SequencerRunner(config());

        runner.close();
        runner.close();

        assertThat(runner.isClosed()).isTrue();
    }

    @Test
    void howFarBackToLookAllowsADayOfMargin() {
        // Being early costs a little scanning; being late costs a peg-in that is never seen.
        long federationCreated = java.time.Instant.parse("2026-06-01T12:00:00Z").getEpochSecond();

        long lookBackTo = SequencerRunner.withMargin(federationCreated);

        assertThat(lookBackTo).isEqualTo(federationCreated - 86_400);
    }

    @Test
    void howFarBackToLookIsNeverZero() {
        // Zero is the one value bitcoinj treats specially: it skips the checkpoint that makes the
        // initial sync bearable, and logs that the sync will be very slow.
        assertThat(SequencerRunner.withMargin(0)).isPositive();
        assertThat(SequencerRunner.withMargin(100)).isPositive();
        assertThat(SequencerRunner.withMargin(-5)).isPositive();
    }

    // ---------------------------------------------------------------- helpers

    private void writeKey(String name) throws Exception {
        Path file = keys.resolve(name + ".key");
        Files.writeString(file, KEY);
        Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ));
    }

    private String peers() {
        return "\"127.0.0.1:18444\"";
    }

    private SequencerConfig config() {
        return config("regtest", peers());
    }

    private SequencerConfig config(String network, String peers) {
        return SequencerConfig.of(com.typesafe.config.ConfigFactory.parseString(configText(network, peers)));
    }

    private String configText(String network, String peers) {
        return """
            sequencer {
              besu { url = "http://127.0.0.1:8545", chainId = 33 }
              network = "%s"
              bridgeTxsPaidFromBlock = 100000
              bitcoinPeerAddresses = [ %s ]
              databaseDir = "%s"
              signers {
                BTC { type = "keyFile", path = "%s" }
                RSK { type = "keyFile", path = "%s" }
                MST { type = "keyFile", path = "%s" }
              }
            }
            """.formatted(
            network, peers, home.resolve("data"),
            keys.resolve("btc.key"), keys.resolve("rsk.key"), keys.resolve("mst.key"));
    }
}
