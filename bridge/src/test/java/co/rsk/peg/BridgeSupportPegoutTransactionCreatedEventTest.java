package co.rsk.peg;

import static co.rsk.peg.bitcoin.BitcoinTestUtils.createUTXOs;
import static co.rsk.peg.bitcoin.UtxoUtils.extractOutpointValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.wallet.Wallet;
import co.rsk.peg.ReleaseRequestQueue.Entry;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.*;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.utils.BridgeEventLogger;
import co.rsk.test.builders.BridgeSupportBuilder;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BridgeSupportPegoutTransactionCreatedEventTest {

    private static final BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
    private static final FederationConstants federationMainNetConstants = bridgeMainnetConstants.getFederationConstants();
    private static final NetworkParameters btcMainnetParams = bridgeMainnetConstants.getBtcParams();
    private static final ErpFederation ERP_FEDERATION = FederationTestUtils.getErpFederation(btcMainnetParams);

    private BridgeStorageProvider provider;
    private FeePerKbSupport feePerKbSupport;
    private FederationStorageProvider federationStorageProvider;
    private BridgeEventLogger eventLogger;
    private InMemoryBridgeHost host;
    private Hash pegoutCreationRskTxHash;
    private CallContext executionRskTx;

    @BeforeEach
    void init() throws IOException {
        List<UTXO> fedUTXOs = createUTXOs(
            10,
            ERP_FEDERATION.getAddress()
        );

        host = new InMemoryBridgeHost().blockNumber(ERP_FEDERATION.getCreationBlockNumber());

        provider = mock(BridgeStorageProvider.class);
        when(provider.getPegoutsWaitingForSignatures()).thenReturn(new TreeMap<>());
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(new HashSet<>()));

        FeePerKbStorageProvider feePerKbStorageProvider = mock(FeePerKbStorageProvider.class);
        when(feePerKbStorageProvider.getFeePerKb()).thenReturn(Optional.of(Coin.MILLICOIN));
        feePerKbSupport = new FeePerKbSupportImpl(
            bridgeMainnetConstants.getFeePerKbConstants(),
            feePerKbStorageProvider
        );

        federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(fedUTXOs);
        when(federationStorageProvider.getNewFederation(any())).thenReturn(ERP_FEDERATION);

        pegoutCreationRskTxHash = PegTestUtils.createHash3(1);
        executionRskTx = PegTestUtils.callWithHash(pegoutCreationRskTxHash);

        eventLogger = mock(BridgeEventLogger.class);
    }

    @Test
    void updateCollections_whenPegoutBatchIsCreated_shouldLogPegoutTransactionCreatedEvent() throws IOException {
        // Arrange
        List<Entry> pegoutRequests = PegTestUtils.createReleaseRequestQueueEntries(3);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(pegoutRequests));

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        FederationSupport federationSupport = new FederationSupportImpl(
            federationMainNetConstants,
            federationStorageProvider,
            host
        );

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withEventLogger(eventLogger)
            .withHost(host)
            .withFeePerKbSupport(feePerKbSupport)
            .withFederationSupport(federationSupport)
            .build();

        // Act
        bridgeSupport.updateCollections(executionRskTx);

        // Assertions
        // Assert one pegout tx was added to pegoutsWaitingForConfirmations from the creation of a pegout batch
        assertEquals(1, pegoutsWaitingForConfirmations.getEntries().size());

        PegoutsWaitingForConfirmations.Entry pegoutEntry = pegoutsWaitingForConfirmations.getEntries().stream().findFirst().get();
        BtcTransaction pegoutBatchTransaction = pegoutEntry.getBtcTransaction();
        Sha256Hash pegoutTxHash = pegoutBatchTransaction.getHash();
        List<Coin> outpointValues = extractOutpointValues(pegoutBatchTransaction);
        List<Hash> pegoutRequestRskTxHashes = pegoutRequests.stream().map(Entry::getRskTxHash).collect(Collectors.toList());
        Coin totalTransactionAmount = pegoutRequests.stream().map(Entry::getAmount).reduce(Coin.ZERO, Coin::add);

        verify(eventLogger, times(1)).logBatchPegoutCreated(pegoutTxHash, pegoutRequestRskTxHashes);
        verify(eventLogger, times(1)).logReleaseBtcRequested(pegoutCreationRskTxHash.getBytes().toArrayUnsafe(), pegoutBatchTransaction, totalTransactionAmount);

        verify(eventLogger, times(1)).logPegoutTransactionCreated(pegoutTxHash, outpointValues);
    }

    @Test
    void updateCollections_whenPegoutMigrationIsCreated_shouldLogPegoutTransactionCreatedEvent() throws IOException {
        // Arrange
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        Federation oldFederation = ERP_FEDERATION;
        when(federationStorageProvider.getOldFederation(federationMainNetConstants)).thenReturn(oldFederation);

        long newFedCreationBlockNumber = 5L;
        FederationArgs newFederationArgs = new FederationArgs(
            FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            newFedCreationBlockNumber,
            btcMainnetParams
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(newFederationArgs);

        when(federationStorageProvider.getNewFederation(federationMainNetConstants)).thenReturn(newFederation);

        // Utxos to migrate
        List<UTXO> utxosToMigrate = createUTXOs(10, oldFederation.getAddress());
        Coin totalTransactionInputAmount = utxosToMigrate.stream().map(UTXO::getValue).reduce(Coin.ZERO, Coin::add);
        when(federationStorageProvider.getOldFederationBtcUTXOs()).thenReturn(utxosToMigrate);

        // Advance blockchain to migration phase. Migration phase starts 1 block after migration age is reached.
        long migrationAge = federationMainNetConstants.getFederationActivationAge() +
            federationMainNetConstants.getFundsMigrationAgeSinceActivationBegin() +
            newFedCreationBlockNumber + 1;

        host = new InMemoryBridgeHost().blockNumber(migrationAge);

        FederationSupport federationSupport = new FederationSupportImpl(
            federationMainNetConstants,
            federationStorageProvider,
            host
        );
        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withEventLogger(eventLogger)
            .withHost(host)
            .withFeePerKbSupport(feePerKbSupport)
            .withFederationSupport(federationSupport)
            .build();

        // Act
        bridgeSupport.updateCollections(executionRskTx);

        // Assertions

        // Assert one migration tx was added to pegoutsWaitingForConfirmations from the creation of a pegout batch
        assertEquals(1, pegoutsWaitingForConfirmations.getEntries().size());

        PegoutsWaitingForConfirmations.Entry pegoutEntry = pegoutsWaitingForConfirmations.
            getEntries().
            stream().
            findFirst().
            get();

        BtcTransaction migrationTransaction = pegoutEntry.getBtcTransaction();
        Sha256Hash btcTxHash = migrationTransaction.getHash();

        List<Coin> outpointValues = extractOutpointValues(migrationTransaction);

        verify(eventLogger, never()).logBatchPegoutCreated(any(), any());
        verify(eventLogger, times(1)).logReleaseBtcRequested(pegoutCreationRskTxHash.getBytes().toArrayUnsafe(), migrationTransaction, totalTransactionInputAmount);

        verify(eventLogger, times(1)).logPegoutTransactionCreated(btcTxHash, outpointValues);
    }

    @Test
    void updateCollections_whenPegoutMigrationAndBatchAreCreated_shouldLogPegoutTransactionCreatedEvent() throws IOException {
        // Arrange
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        List<Entry> pegoutRequests = PegTestUtils.createReleaseRequestQueueEntries(3);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(pegoutRequests));

        Federation oldFederation = ERP_FEDERATION;
        when(federationStorageProvider.getOldFederation(federationMainNetConstants)).thenReturn(oldFederation);

        long newFedCreationBlockNumber = 5L;
        FederationArgs newFederationArgs = new FederationArgs(
            FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            newFedCreationBlockNumber,
            btcMainnetParams
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(newFederationArgs);

        when(federationStorageProvider.getNewFederation(federationMainNetConstants)).thenReturn(newFederation);

        // Utxos to migrate
        List<UTXO> utxosToMigrate = createUTXOs(10, oldFederation.getAddress());
        when(federationStorageProvider.getOldFederationBtcUTXOs()).thenReturn(utxosToMigrate);
        Coin migrationTotalAmount = utxosToMigrate.stream().map(UTXO::getValue).reduce(Coin.ZERO, Coin::add);

        List<UTXO> utxosNewFederation = createUTXOs(10, newFederation.getAddress());
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxosNewFederation);

        // Advance blockchain to migration phase. Migration phase starts 1 block after migration age is reached.
        long migrationAge = federationMainNetConstants.getFederationActivationAge() +
            federationMainNetConstants.getFundsMigrationAgeSinceActivationBegin() +
            newFedCreationBlockNumber + 1;

        host = new InMemoryBridgeHost().blockNumber(migrationAge);

        FederationSupport federationSupport = new FederationSupportImpl(
            federationMainNetConstants,
            federationStorageProvider,
            host
        );
        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withEventLogger(eventLogger)
            .withHost(host)
            .withFeePerKbSupport(feePerKbSupport)
            .withFederationSupport(federationSupport)
            .build();

        CallContext rskTx = PegTestUtils.callWithHash(PegTestUtils.createHash3(1));

        // Act
        bridgeSupport.updateCollections(rskTx);

        // Assertions

        // Assert two pegouts was added to pegoutsWaitingForConfirmations from the creation of each pegout batch for the migration and pegout
        assertEquals(2, pegoutsWaitingForConfirmations.getEntries().size());

        Optional<PegoutsWaitingForConfirmations.Entry> migrationEntry = Optional.empty();
        Optional<PegoutsWaitingForConfirmations.Entry> pegoutEntry = Optional.empty();

        // Get new fed wallet to identify the migration tx
        Wallet fedWallet = BridgeUtils.getFederationNoSpendWallet(
            new Context(btcMainnetParams),
            newFederation
        );

        // If all outputs are sent to the active fed then it's the migration tx; if not, it's the peg-out batch
        for (PegoutsWaitingForConfirmations.Entry entry : pegoutsWaitingForConfirmations.getEntries()) {
            List<TransactionOutput> walletOutputs = entry.getBtcTransaction().getWalletOutputs(fedWallet);
            if (walletOutputs.size() == entry.getBtcTransaction().getOutputs().size()){
                migrationEntry = Optional.of(entry);
            } else {
                pegoutEntry = Optional.of(entry);
            }
        }
        assertTrue(migrationEntry.isPresent());
        assertTrue(pegoutEntry.isPresent());

        BtcTransaction pegoutBatchTx = pegoutEntry.get().getBtcTransaction();
        Hash pegoutBatchCreationRskTxHash = pegoutEntry.get().getPegoutCreationRskTxHash();
        Sha256Hash pegoutBatchBtcTxHash = pegoutBatchTx.getHash();

        Coin pegoutBatchTotalAmount = pegoutRequests.stream().map(Entry::getAmount).reduce(Coin.ZERO, Coin::add);
        List<Coin> pegoutBatchTxOutpointValues = extractOutpointValues(pegoutBatchTx);

        List<Hash> pegoutRequestRskTxHashes = pegoutRequests.stream().map(Entry::getRskTxHash).collect(Collectors.toList());

        verify(eventLogger, times(1)).logBatchPegoutCreated(pegoutBatchBtcTxHash, pegoutRequestRskTxHashes);
        verify(eventLogger, times(1)).logReleaseBtcRequested(pegoutBatchCreationRskTxHash.getBytes().toArrayUnsafe(), pegoutBatchTx, pegoutBatchTotalAmount);

        Hash migrationCreationRskTxHash = migrationEntry.get().getPegoutCreationRskTxHash();
        BtcTransaction migrationTx = migrationEntry.get().getBtcTransaction();
        Sha256Hash migrationTxHash = migrationTx.getHash();

        List<Coin> migrationTxOutpointValues = extractOutpointValues(migrationTx);

        verify(eventLogger, times(1)).logReleaseBtcRequested(migrationCreationRskTxHash.getBytes().toArrayUnsafe(), migrationTx, migrationTotalAmount);

        verify(eventLogger, times(1)).logPegoutTransactionCreated(pegoutBatchBtcTxHash, pegoutBatchTxOutpointValues);
        verify(eventLogger, times(1)).logPegoutTransactionCreated(migrationTxHash, migrationTxOutpointValues);
    }
}
