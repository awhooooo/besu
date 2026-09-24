package co.rsk.peg;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.wallet.Wallet;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.bitcoin.BitcoinUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.FeePerKbSupport;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.test.builders.BridgeSupportBuilder;
import co.rsk.test.builders.FederationSupportBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class BridgeSupportSigHashTest {

    private static final BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
    private static final FederationConstants federationMainnetConstants = bridgeMainnetConstants.getFederationConstants();
    private static final NetworkParameters btcMainnetParams = bridgeMainnetConstants.getBtcParams();

    private BridgeStorageProvider provider;
    private FederationStorageProvider federationStorageProvider;

    @BeforeEach
    void init() throws IOException {
        provider = mock(BridgeStorageProvider.class);
        federationStorageProvider = mock(FederationStorageProvider.class);

        when(provider.getPegoutsWaitingForSignatures())
            .thenReturn(new TreeMap<>());

        when(provider.getPegoutsWaitingForConfirmations())
            .thenReturn(new PegoutsWaitingForConfirmations(new HashSet<>()));
    }
    @Test
    void test_pegoutTxIndex_when_pegout_batch_is_created() throws IOException {
        Federation genesisFederation = FederationTestUtils.getGenesisFederation(bridgeMainnetConstants.getFederationConstants());
        Address federationAddress = genesisFederation.getAddress();
        // Arrange
        List<UTXO> fedUTXOs = BitcoinTestUtils.createUTXOs(
            10,
            federationAddress
        );
        when(federationStorageProvider.getNewFederationBtcUTXOs())
            .thenReturn(fedUTXOs);

        when(provider.getReleaseRequestQueue()).thenReturn(
            new ReleaseRequestQueue(PegTestUtils.createReleaseRequestQueueEntries(3))
        );

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        FeePerKbSupport feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        // Act
        bridgeSupport.updateCollections(PegTestUtils.callWithHash(PegTestUtils.createHash3(1)));

        // Assertions

        // Assert one pegout tx was added to pegoutsWaitingForConfirmations from the creation of a pegout batch
        assertEquals(1, pegoutsWaitingForConfirmations.getEntries().size());

        PegoutsWaitingForConfirmations.Entry pegoutBatchTx = pegoutsWaitingForConfirmations.getEntries().stream().findFirst().get();
        Optional<Sha256Hash> firstInputSigHash = BitcoinUtils.getSigHashForPegoutIndex(pegoutBatchTx.getBtcTransaction());
        assertTrue(firstInputSigHash.isPresent());
        verify(provider, times(1)).setPegoutTxSigHash(firstInputSigHash.get());
    }

    @Test
    void test_pegoutTxIndex_when_migration_tx_is_created() throws IOException {
        // Arrange
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        Federation oldFederation = FederationTestUtils.getGenesisFederation(bridgeMainnetConstants.getFederationConstants());
        long newFedCreationBlockNumber = 5L;

        FederationArgs newFederationArgs = new FederationArgs(
            FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            newFedCreationBlockNumber,
            btcMainnetParams
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(newFederationArgs);
        when(federationStorageProvider.getOldFederation(federationMainnetConstants)).thenReturn(oldFederation);
        when(federationStorageProvider.getNewFederation(federationMainnetConstants)).thenReturn(newFederation);

        // Utxos to migrate
        List<UTXO> utxos = BitcoinTestUtils.createUTXOs(10, oldFederation.getAddress());
        when(federationStorageProvider.getOldFederationBtcUTXOs()).thenReturn(utxos);

        // Advance blockchain to migration phase. Migration phase starts 1 block after migration age is reached.
        long migrationAge =
            federationMainnetConstants.getFederationActivationAge() +
            federationMainnetConstants.getFundsMigrationAgeSinceActivationBegin() +
            newFedCreationBlockNumber + 1;
        InMemoryBridgeHost host = new InMemoryBridgeHost().blockNumber(migrationAge);

        FeePerKbSupport feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        CallContext rskTx = PegTestUtils.callWithHash(PegTestUtils.createHash3(1));

        // Act
        bridgeSupport.updateCollections(rskTx);

        // Assertions

        // Assert one migration tx was added to pegoutsWaitingForConfirmations from the creation of a pegout batch
        assertEquals(1, pegoutsWaitingForConfirmations.getEntries().size());

        PegoutsWaitingForConfirmations.Entry migrationTx = pegoutsWaitingForConfirmations.
            getEntries().
            stream().
            findFirst().
            get();
        Optional<Sha256Hash> firstInputSigHash = BitcoinUtils.getSigHashForPegoutIndex(migrationTx.getBtcTransaction());
        assertTrue(firstInputSigHash.isPresent());
        verify(provider, times(1)).setPegoutTxSigHash(firstInputSigHash.get());
    }

    @Test
    void test_pegoutTxIndex_when_migration_and_pegout_batch_tx_are_created() throws IOException {
        // Arrange
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        when(provider.getReleaseRequestQueue())
            .thenReturn(new ReleaseRequestQueue(PegTestUtils.createReleaseRequestQueueEntries(3)));

        Federation oldFederation = FederationTestUtils.getGenesisFederation(bridgeMainnetConstants.getFederationConstants());

        long newFedCreationBlockNumber = 5L;
        FederationArgs newFederationArgs = new FederationArgs(
            FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            newFedCreationBlockNumber,
            btcMainnetParams
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(newFederationArgs);
        when(federationStorageProvider.getOldFederation(federationMainnetConstants)).thenReturn(oldFederation);
        when(federationStorageProvider.getNewFederation(federationMainnetConstants)).thenReturn(newFederation);

        // Utxos to migrate
        List<UTXO> utxos = BitcoinTestUtils.createUTXOs(10, oldFederation.getAddress());
        when(federationStorageProvider.getOldFederationBtcUTXOs()).thenReturn(utxos);

        List<UTXO> utxosNew = BitcoinTestUtils.createUTXOs(10, newFederation.getAddress());
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxosNew);

        // Advance blockchain to migration phase. Migration phase starts 1 block after migration age is reached.
        long migrationAge =
            federationMainnetConstants.getFederationActivationAge() +
            federationMainnetConstants.getFundsMigrationAgeSinceActivationBegin() +
            newFedCreationBlockNumber + 1;

        InMemoryBridgeHost host = new InMemoryBridgeHost().blockNumber(migrationAge);

        FeePerKbSupport feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        CallContext rskTx = PegTestUtils.callWithHash(PegTestUtils.createHash3(1));

        // Act
        bridgeSupport.updateCollections(rskTx);

        // Assertions

        // Assert two pegouts was added to pegoutsWaitingForConfirmations from the creation of each pegout batch for the migration and pegout
        assertEquals(2, pegoutsWaitingForConfirmations.getEntries().size());

        Optional<PegoutsWaitingForConfirmations.Entry> migrationTx = Optional.empty();
        Optional<PegoutsWaitingForConfirmations.Entry> pegoutBatchTx = Optional.empty();

        // Get new fed wallet to identify the migration tx
        Wallet newFedWallet = BridgeUtils.getFederationNoSpendWallet(
            new Context(btcMainnetParams),
            newFederation
        );

        // If all outputs are sent to the active fed then it's the migration tx; if not, it's the peg-out batch
        for (PegoutsWaitingForConfirmations.Entry entry : pegoutsWaitingForConfirmations.getEntries()) {
            List<TransactionOutput> walletOutputs = entry.getBtcTransaction().getWalletOutputs(newFedWallet);
            if (walletOutputs.size() == entry.getBtcTransaction().getOutputs().size()){
                migrationTx = Optional.of(entry);
            } else {
                pegoutBatchTx = Optional.of(entry);
            }
        }
        assertTrue(migrationTx.isPresent());
        assertTrue(pegoutBatchTx.isPresent());

        Optional<Sha256Hash> migrationTxSigHash = BitcoinUtils.getSigHashForPegoutIndex(migrationTx.get().getBtcTransaction());
        assertTrue(migrationTxSigHash.isPresent());
        verify(provider, times(1)).setPegoutTxSigHash(migrationTxSigHash.get());

        Optional<Sha256Hash> pegoutBatchTxSigHash = BitcoinUtils.getSigHashForPegoutIndex(pegoutBatchTx.get().getBtcTransaction());
        assertTrue(pegoutBatchTxSigHash.isPresent());
        verify(provider, times(1)).setPegoutTxSigHash(pegoutBatchTxSigHash.get());
    }
}
