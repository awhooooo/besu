package co.rsk.peg;

import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.constants.BridgeTestNetConstants;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.FeePerKbSupport;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.utils.BridgeEventLogger;
import co.rsk.test.builders.BridgeSupportBuilder;
import co.rsk.test.builders.FederationSupportBuilder;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static co.rsk.peg.ReleaseTransactionBuilder.BTC_TX_VERSION_2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BridgeSupportProcessFundsMigrationTest {

    private static Stream<Arguments> processFundMigrationArgsProvider() {
        BridgeMainNetConstants bridgeMainNetConstants = BridgeMainNetConstants.getInstance();
        BridgeTestNetConstants bridgeTestNetConstants = BridgeTestNetConstants.getInstance();

        return Stream.of(
            Arguments.of(bridgeTestNetConstants, false),
            Arguments.of(bridgeTestNetConstants, true),
            Arguments.of(bridgeMainNetConstants, false),
            Arguments.of(bridgeMainNetConstants, true)
        );
    }

    private static Stream<Arguments> processFundsMigrationMultipleAttempts_ArgsProvider() {
        BridgeMainNetConstants bridgeMainNetConstants = BridgeMainNetConstants.getInstance();
        BridgeTestNetConstants bridgeTestNetConstants = BridgeTestNetConstants.getInstance();

        return Stream.of(
            Arguments.of(bridgeMainNetConstants, true),
            Arguments.of(bridgeMainNetConstants, false),
            Arguments.of(bridgeTestNetConstants, true),
            Arguments.of(bridgeTestNetConstants, false)
        );
    }

    @ParameterizedTest
    @MethodSource("processFundMigrationArgsProvider")
    void test_processFundsMigration(
        BridgeConstants bridgeConstants,
        boolean inMigrationAge
    ) throws IOException {
        FederationConstants federationConstants = bridgeConstants.getFederationConstants();

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);
        Federation oldFederation = FederationTestUtils.getGenesisFederation(bridgeConstants.getFederationConstants());
        long federationActivationAge = federationConstants.getFederationActivationAge();

        long federationCreationBlockNumber = 5L;
        long federationInMigrationAgeHeight = federationCreationBlockNumber + federationActivationAge +
            federationConstants.getFundsMigrationAgeSinceActivationBegin() + 1;
        long federationPastMigrationAgeHeight = federationCreationBlockNumber +
            federationActivationAge +
            federationConstants.getFundsMigrationAgeSinceActivationEnd() + 1;

        FederationArgs newFederationArgs = new FederationArgs(
            FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            federationCreationBlockNumber,
            bridgeConstants.getBtcParams()
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(newFederationArgs);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        FederationStorageProvider federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getOldFederation(federationConstants)).thenReturn(oldFederation);
        when(federationStorageProvider.getNewFederation(federationConstants)).thenReturn(newFederation);

        long updateCollectionsCallHeight = inMigrationAge ? federationInMigrationAgeHeight : federationPastMigrationAgeHeight;
        InMemoryBridgeHost host = new InMemoryBridgeHost().blockNumber(updateCollectionsCallHeight);

        FeePerKbSupport feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBridgeConstants(bridgeConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withHost(host)
            .withFeePerKbSupport(feePerKbSupport)
            .withFederationSupport(federationSupport)
            .build();

        List<UTXO> sufficientUTXOsForMigration = new ArrayList<>();
        sufficientUTXOsForMigration.add(BitcoinTestUtils.createUTXO(
            0,
            0,
            Coin.COIN,
            oldFederation.getAddress()
        ));
        when(federationStorageProvider.getOldFederationBtcUTXOs()).thenReturn(sufficientUTXOsForMigration);

        CallContext updateCollectionsTx = buildUpdateCollectionsCall(0);
        bridgeSupport.updateCollections(updateCollectionsTx);

        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntriesWithoutHash().size());
        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntriesWithHash().size());

        assertTrue(sufficientUTXOsForMigration.isEmpty());
        if (inMigrationAge){
            verify(federationStorageProvider, never()).setOldFederation(null);
        } else {
            verify(federationStorageProvider, times(1)).setOldFederation(null);
        }

        // Should have been logged with the migrated UTXO
        PegoutsWaitingForConfirmations.Entry entry = (PegoutsWaitingForConfirmations.Entry) provider.getPegoutsWaitingForConfirmations()
            .getEntriesWithHash()
            .toArray()[0];
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(
            updateCollectionsTx.getHash().getBytes().toArrayUnsafe(),
            entry.getBtcTransaction(),
            Coin.COIN
        );

        assertEquals(BTC_TX_VERSION_2, entry.getBtcTransaction().getVersion());
    }

    @ParameterizedTest
    @MethodSource("processFundsMigrationMultipleAttempts_ArgsProvider")
    void test_processFundsMigrationMultipleAttempts(BridgeConstants bridgeConstants, boolean inMigrationAge) throws IOException {
        FederationConstants federationConstants = bridgeConstants.getFederationConstants();

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);
        Federation oldFederation = FederationTestUtils.getGenesisFederation(bridgeConstants.getFederationConstants());
        long federationActivationAge = federationConstants.getFederationActivationAge();

        long federationCreationBlockNumber = 5L;
        long federationInMigrationAgeHeight = federationCreationBlockNumber + federationActivationAge +
            federationConstants.getFundsMigrationAgeSinceActivationBegin() + 1;
        long federationPastMigrationAgeHeight = federationCreationBlockNumber +
            federationActivationAge +
            federationConstants.getFundsMigrationAgeSinceActivationEnd() + 1;

        FederationArgs newFederationArgs = new FederationArgs(
            FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            federationCreationBlockNumber,
            bridgeConstants.getBtcParams()
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(newFederationArgs);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        FederationStorageProvider federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getOldFederation(federationConstants)).thenReturn(oldFederation);
        when(federationStorageProvider.getNewFederation(federationConstants)).thenReturn(newFederation);

        long updateCollectionsCallHeight = inMigrationAge ? federationInMigrationAgeHeight : federationPastMigrationAgeHeight;
        InMemoryBridgeHost host = new InMemoryBridgeHost().blockNumber(updateCollectionsCallHeight);

        FeePerKbSupport feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBridgeConstants(bridgeConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withHost(host)
            .withFeePerKbSupport(feePerKbSupport)
            .withFederationSupport(federationSupport)
            .build();

        List<UTXO> sufficientUTXOsForMigration = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            sufficientUTXOsForMigration.add(BitcoinTestUtils.createUTXO(
                i,
                0,
                Coin.COIN,
                oldFederation.getAddress()
            ));
        }

        when(federationStorageProvider.getOldFederationBtcUTXOs()).thenReturn(sufficientUTXOsForMigration);

        assertEquals(20, (int)(sufficientUTXOsForMigration.size() / bridgeConstants.getMaxInputsPerPegoutTransaction()));
        // Since max input size of migration transaction is 50, we expect 20 transactions to move all 1000 UTXOs
        for (int j = 0; j < 20; j++) {
            CallContext updateCollectionsTx = buildUpdateCollectionsCall(j);
            bridgeSupport.updateCollections(updateCollectionsTx);

            assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntriesWithoutHash().size());
            assertEquals(j + 1, provider.getPegoutsWaitingForConfirmations().getEntriesWithHash().size());

            if (inMigrationAge){
                verify(federationStorageProvider, never()).setOldFederation(null);
            } else {
                verify(federationStorageProvider, times(j + 1)).setOldFederation(null);
            }

            // Should have been logged with the migrated UTXO
            Hash expectedHash = updateCollectionsTx.getHash();
            PegoutsWaitingForConfirmations.Entry entry = provider.getPegoutsWaitingForConfirmations()
                .getEntriesWithHash()
                .stream()
                .filter(e -> e.getPegoutCreationRskTxHash().equals(expectedHash))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Pegout entry not found for tx hash"));
            verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(
                updateCollectionsTx.getHash().getBytes().toArrayUnsafe(),
                entry.getBtcTransaction(),
                Coin.FIFTY_COINS
            );
            assertEquals(BTC_TX_VERSION_2, entry.getBtcTransaction().getVersion());
        }

        assertTrue(sufficientUTXOsForMigration.isEmpty());
        assertEquals(20, provider.getPegoutsWaitingForConfirmations().getEntriesWithHash().size());
        verify(bridgeEventLogger, times(20)).logReleaseBtcRequested(
            any(byte[].class),
            any(BtcTransaction.class),
            eq(Coin.FIFTY_COINS)
        );
    }

    /** RSKj signed an updateCollections transaction for its hash; here the call carries one directly. */
    private CallContext buildUpdateCollectionsCall(long nonce) {
        return PegTestUtils.callWithHash(PegTestUtils.createHash3((int) nonce));
    }
}
