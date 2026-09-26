package co.rsk.federate.watcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.federate.BridgeClient;
import co.rsk.federate.BridgeEventReader;
import co.rsk.federate.BtcToRskClient;
import co.rsk.federate.FederatorSupport;
import co.rsk.federate.GasPolicy;
import co.rsk.federate.PegoutOutpointValues;
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
 * What a federation change does to the clients.
 *
 * <p>During one there are two bitcoin addresses that matter and two kinds of transaction to sign,
 * and whether coins keep moving depends on every client being pointed at the right thing. These
 * check where each one ends up rather than that a method was called.
 */
class FederationWatcherListenerImplTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(33);
    private static final String RSK_KEY = "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179";
    private static final Coin PER_INPUT = Coin.COIN.multiply(3);

    @TempDir Path home;

    private BridgeConstants bridgeConstants;
    private FakeNode node;
    private FakeBitcoinWrapper bitcoin;
    private BtcToRskClient activeClient;
    private BtcToRskClient retiringClient;
    private BtcReleaseClient releaseClient;
    private FederationWatcherListenerImpl listener;

    private Federation outgoing;
    private Federation incoming;

    @BeforeEach
    void setUp() throws Exception {
        bridgeConstants = new BridgeRegTestConstants();
        node = new FakeNode().atHeight(100_000);
        bitcoin = new FakeBitcoinWrapper();

        FederatorSupport federatorSupport = new FederatorSupport(bridgeClient(), bridgeConstants.getBtcParams());
        activeClient = peginClient(federatorSupport, "active");
        retiringClient = peginClient(federatorSupport, "retiring");

        BridgeEventReader events = new BridgeEventReader(node, 4_500);
        releaseClient = new BtcReleaseClient(
            bitcoin, federatorSupport, bridgeConstants, federatorSigner(),
            new PegoutOutpointValues(events, 50_000), events,
            new PegoutSignedCacheImpl(java.time.Duration.ofMinutes(30), java.time.Clock.systemUTC()),
            4_500);

        listener = new FederationWatcherListenerImpl(activeClient, retiringClient, releaseClient, bitcoin);

        outgoing = PegoutFixture.standardFederation(PegoutFixture.federationKeys(3), bridgeConstants.getBtcParams());
        incoming = PegoutFixture.segwitFederation(
            PegoutFixture.strangersKeys(3), bridgeConstants.getBtcParams(),
            bridgeConstants.getFederationConstants());
    }

    @Test
    void theActiveFederationGetsItsOwnPeginClient() {
        listener.onActiveFederationChange(outgoing);

        assertThat(bitcoin.isWatching(outgoing)).isTrue();
    }

    @Test
    void aRetiringFederationGetsTheOtherPeginClient() {
        // Two clients because during a change there are two addresses to watch, each with its
        // own record of what it is waiting to prove.
        listener.onActiveFederationChange(incoming);
        listener.onRetiringFederationChange(outgoing);

        assertThat(bitcoin.isWatching(incoming)).isTrue();
        assertThat(bitcoin.isWatching(outgoing)).isTrue();
    }

    @Test
    void aRetiringFederationGoingAwayStopsItsClient() {
        listener.onRetiringFederationChange(outgoing);
        assertThat(bitcoin.isWatching(outgoing)).isTrue();

        listener.onRetiringFederationChange(null);

        assertThat(bitcoin.isWatching(outgoing)).isFalse();
    }

    @Test
    void aProposalIsWatchedByTheActiveClient() {
        // It has no peg of its own to watch, only the two transactions that validate it.
        listener.onActiveFederationChange(outgoing);
        listener.onProposedFederationChange(incoming);

        assertThat(bitcoin.isWatching(incoming)).isTrue();
    }

    @Test
    void theReleaseClientKeepsBothFederationsThroughAChange() throws Exception {
        // Which is what lets it sign a migration out of the old one and a peg-out of the new one
        // in the same pass.
        listener.onActiveFederationChange(outgoing);
        listener.onActiveFederationChange(incoming);
        listener.onRetiringFederationChange(outgoing);

        BtcTransaction migrationFromOutgoing = PegoutFixture.legacyPegout(
            outgoing, bridgeConstants.getBtcParams(), 1, PER_INPUT);
        TreeMap<org.hyperledger.besu.datatypes.Hash, BtcTransaction> waiting = new TreeMap<>();
        waiting.put(org.hyperledger.besu.datatypes.Hash.fromHexStringLenient("0x71"), migrationFromOutgoing);
        node.answering(BridgeMethods.GET_STATE_FOR_BTC_RELEASE_CLIENT, new StateForFederator(waiting).encodeToRlp())
            .answering(BridgeMethods.GET_STATE_FOR_SVP_CLIENT, new byte[0]);

        releaseClient.updateBridge();

        // Our keys are the outgoing federation's, so that is the one we can sign for.
        assertThat(node.sentOf(BridgeMethods.ADD_SIGNATURE)).hasSize(1);
    }

    @Test
    void aProposalThisSequencerIsNotInIsFollowedAnyway() {
        // An outgoing member watches the proposal so that it can register the validation spend
        // on bitcoin, even though it holds none of the keys that sign it.
        listener.onActiveFederationChange(outgoing);
        listener.onProposedFederationChange(incoming);

        assertThat(bitcoin.isWatching(incoming)).isTrue();
    }

    @Test
    void aProposalMakesTheActiveClientRelayForItToo() throws Exception {
        // Watching the address is not enough. The funding transaction pays the proposed federation
        // and its flyover address and nobody else, so a client deciding what to relay by asking
        // only about its own federation would drop it — and the change would stall, because the
        // bridge learns the funding confirmed only when somebody registers it.
        listener.onActiveFederationChange(outgoing);
        listener.onProposedFederationChange(incoming);

        org.bitcoinj.core.Address proposedAddress = org.bitcoinj.core.LegacyAddress.fromBase58(
            co.rsk.federate.testing.BitcoinFixture.REGTEST, incoming.getAddress().toBase58());
        org.bitcoinj.core.Transaction fundTx = co.rsk.federate.testing.BitcoinFixture.payTo(
            proposedAddress, org.bitcoinj.core.Coin.COIN.multiply(2), 1);
        org.bitcoinj.core.Block genesis = co.rsk.federate.testing.BitcoinFixture.block(
            org.bitcoinj.core.Sha256Hash.ZERO_HASH,
            List.of(co.rsk.federate.testing.BitcoinFixture.coinbase(0)));
        bitcoin.appendToBestChain(genesis);
        org.bitcoinj.core.Block withFundTx = co.rsk.federate.testing.BitcoinFixture.block(
            genesis.getHash(),
            List.of(co.rsk.federate.testing.BitcoinFixture.coinbase(1), fundTx));
        bitcoin.appendToBestChain(withFundTx);
        bitcoin.confirm(fundTx);

        activeClient.onTransaction(fundTx);
        activeClient.onBlock(withFundTx);
        node.answering(BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT, BigInteger.ONE)
            .answering(BridgeMethods.GET_BTC_BLOCKCHAIN_INITIAL_BLOCK_HEIGHT, BigInteger.ZERO)
            .answeringWith(BridgeMethods.GET_BTC_BLOCKCHAIN_BLOCK_HASH_AT_DEPTH, arguments -> {
                int depth = ((BigInteger) arguments[0]).intValue();
                return new Object[] {bitcoin.getBlockAtHeight(1 - depth).getHeader().getHash().getBytes()};
            })
            .answering(BridgeMethods.IS_BTC_TX_HASH_ALREADY_PROCESSED, false)
            .answering(BridgeMethods.HAS_BTC_BLOCK_COINBASE_TRANSACTION_INFORMATION, false);

        activeClient.updateBridge();

        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).hasSize(1);
    }

    @Test
    void aProposalEndingIsNotAFailure() {
        listener.onProposedFederationChange(incoming);

        listener.onProposedFederationChange(null);

        // Nothing to undo: the proposal was never the peg, and the release client keeps what it
        // has in case a signature it already made is still being collected.
        assertThat(bitcoin.isWatching(incoming)).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private BtcToRskClient peginClient(FederatorSupport federatorSupport, String name) throws IOException {
        return new BtcToRskClient(
            bitcoin,
            federatorSupport,
            bridgeConstants,
            new BtcToRskClientFileStorageImpl(new BtcToRskClientFileStorageInfo(home.resolve(name))),
            new BtcLockSenderProvider(),
            new PeginInstructionsProvider(),
            100,
            3_600);
    }

    private ECDSASigner federatorSigner() throws Exception {
        Path keyFile = home.resolve("btc.key");
        Files.writeString(keyFile, PegoutFixture.federationKeys(3).get(0).getPrivateKeyAsHex());
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        return new ECDSASignerFromFileKey(SequencerKeyId.BTC.getKeyId(), keyFile.toString());
    }

    private BridgeClient bridgeClient() throws Exception {
        Path keyFile = home.resolve("rsk.key");
        Files.writeString(keyFile, RSK_KEY);
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        LegacyTransactionSigner txSigner = new LegacyTransactionSigner(
            new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile.toString()), new KeyId("RSK"), CHAIN_ID);
        return new BridgeClient(node, txSigner, GasPolicy.alwaysPaid(BigInteger.ONE), 4_000_000);
    }
}
