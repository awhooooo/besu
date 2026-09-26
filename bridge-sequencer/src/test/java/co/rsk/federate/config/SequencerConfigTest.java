package co.rsk.federate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

import co.rsk.federate.signing.SequencerKeyId;
import co.rsk.federate.signing.config.SignerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Configuration is read once and then the sequencer runs on it for months. A value that is quietly
 * wrong is worse than one that is missing, so what is checked here is which mistakes are refused
 * and which values fall back to a default rather than to zero.
 */
class SequencerConfigTest {

    @TempDir Path home;

    private static final String MINIMAL = """
        sequencer {
          besu { url = "http://127.0.0.1:8545", chainId = 33 }
          network = "regtest"
          bridgeTxsPaidFromBlock = 100000
          bitcoinPeerAddresses = [ "127.0.0.1:18444" ]
          databaseDir = "/var/lib/seq"
          signers {
            BTC { type = "keyFile", path = "/secure/btc.key" }
            RSK { type = "keyFile", path = "/secure/rsk.key" }
            MST { type = "keyFile", path = "/secure/mst.key" }
          }
        }
        """;

    @Test
    void theValuesWithNoSensibleDefaultAreRead() throws Exception {
        SequencerConfig config = write(MINIMAL);

        assertThat(config.besuUrl()).isEqualTo("http://127.0.0.1:8545");
        assertThat(config.chainId()).isEqualTo(33);
        assertThat(config.network()).isEqualTo("regtest");
        assertThat(config.bridgeTxsPaidFromBlock()).isEqualTo(100_000);
        assertThat(config.bitcoinPeerAddresses()).containsExactly("127.0.0.1:18444");
        assertThat(config.databaseDir()).isEqualTo(Path.of("/var/lib/seq"));
    }

    @Test
    void whatIsLeftOutFallsBackToTheDefaultRatherThanToZero() throws Exception {
        SequencerConfig config = write(MINIMAL);

        assertThat(config.turnPeriodMs()).isEqualTo(SequencerDefaults.UPDATE_BRIDGE_EXECUTION_PERIOD_MS);
        assertThat(config.watcherPeriodMs()).isEqualTo(SequencerDefaults.WATCHER_PERIOD_MS);
        assertThat(config.releasePeriodMs()).isEqualTo(SequencerDefaults.RELEASE_PERIOD_MS);
        assertThat(config.gasLimit()).isEqualTo(SequencerDefaults.GAS_LIMIT);
        assertThat(config.gasPrice()).isEqualTo(BigInteger.valueOf(SequencerDefaults.GAS_PRICE));
        assertThat(config.minimumConfirmationsOnRsk())
            .isEqualTo(SequencerDefaults.MINIMUM_CONFIRMATIONS_ON_RSK);
        assertThat(config.logQueryWindow()).isEqualTo(SequencerDefaults.LOG_QUERY_WINDOW);
        assertThat(config.nodeUnreachableAttempts()).isEqualTo(SequencerDefaults.NODE_UNREACHABLE_ATTEMPTS);
        assertThat(config.nodeRetryPeriodMs()).isEqualTo(SequencerDefaults.NODE_RETRY_PERIOD_MS);
    }

    @Test
    void whatIsGivenOverridesTheDefault() throws Exception {
        SequencerConfig config = write(MINIMAL.replace(
            "databaseDir = \"/var/lib/seq\"",
            "databaseDir = \"/var/lib/seq\"\n  turnPeriodMs = 60000\n"
                + "  releasePeriodMs = 45000\n  nodeUnreachableAttempts = 3"));

        assertThat(config.turnPeriodMs()).isEqualTo(60_000);
        assertThat(config.releasePeriodMs()).isEqualTo(45_000);
        assertThat(config.nodeUnreachableAttempts()).isEqualTo(3);
    }

    @Test
    void allThreeKeysAreRead() throws Exception {
        SequencerConfig config = write(MINIMAL);

        assertThat(config.signers()).hasSize(3);
        for (SequencerKeyId key : SequencerKeyId.values()) {
            assertThat(config.signerFor(key).getSignerType()).isEqualTo(SignerType.KEYFILE);
        }
        assertThat(config.signerFor(SequencerKeyId.BTC).getConfig().getString("path"))
            .isEqualTo("/secure/btc.key");
    }

    @Test
    void aMissingKeyIsRefusedRatherThanLeftUnset() throws Exception {
        // MST signs nothing, but the bridge registers three keys per member and reports three.
        // A text block strips incidental indentation, so match the content rather than the layout.
        String withoutMst = MINIMAL.lines()
            .filter(line -> !line.contains("MST {"))
            .collect(java.util.stream.Collectors.joining("\n"));
        SequencerConfig config = write(withoutMst);

        assertThatThrownBy(() -> config.signerFor(SequencerKeyId.MST))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No signer configured");
    }

    @Test
    void anEmptyPeerListIsRefused() throws Exception {
        // Otherwise bitcoinj would go looking for peers on its own, which is not what anyone
        // running their own bitcoin node wants.
        SequencerConfig config = write(MINIMAL.replace(
            "[ \"127.0.0.1:18444\" ]", "[ ]"));

        assertThatThrownBy(config::bitcoinPeerAddresses)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name the bitcoin node");
    }

    @Test
    void aFileThatIsNotThereIsRefusedRatherThanRunOnDefaults() {
        assertThatThrownBy(() -> SequencerConfig.from(home.resolve("nothing.conf")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No readable configuration file");
    }

    @Test
    void aMissingRequiredValueIsRefusedWhenItIsAskedFor() throws Exception {
        String withoutNetwork = MINIMAL.lines()
            .filter(line -> !line.contains("network ="))
            .collect(java.util.stream.Collectors.joining("\n"));
        SequencerConfig config = write(withoutNetwork);

        assertThatThrownBy(config::network).isInstanceOf(RuntimeException.class);
    }

    @Test
    void theSampleConfigurationShippedWithThisModuleIsReadable() throws Exception {
        // It is the thing an operator copies. If it does not parse, the first thing they do fails.
        Path sample = Path.of("src/main/resources/sequencer-sample.conf");
        assertThat(Files.isReadable(sample)).isTrue();

        SequencerConfig config = SequencerConfig.from(sample);

        assertThat(config.network()).isEqualTo("regtest");
        assertThat(config.signers()).hasSize(3);
        assertThat(config.turnPeriodMs()).isEqualTo(SequencerDefaults.UPDATE_BRIDGE_EXECUTION_PERIOD_MS);
    }

    private SequencerConfig write(String contents) throws Exception {
        Path file = home.resolve("sequencer.conf");
        Files.writeString(file, contents);
        return SequencerConfig.from(file);
    }
}
