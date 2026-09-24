package co.rsk.peg;

import static co.rsk.peg.BridgeSupportTestUtil.createValidPmtForTransactions;
import static co.rsk.peg.BridgeSupportTestUtil.mockChainOfStoredBlocks;
import static co.rsk.peg.pegin.RejectedPeginReason.INVALID_AMOUNT;
import static co.rsk.peg.pegin.RejectedPeginReason.PEGIN_V1_INVALID_PAYLOAD;
import static co.rsk.peg.utils.NonRefundablePeginReason.LEGACY_PEGIN_UNDETERMINED_SENDER;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.lockingcap.LockingCapSupport;
import co.rsk.peg.pegin.RejectedPeginReason;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import co.rsk.peg.utils.BridgeEventLogger;
import co.rsk.peg.utils.NonRefundablePeginReason;
import co.rsk.peg.utils.Weis;
import co.rsk.test.builders.BridgeSupportBuilder;
import co.rsk.test.builders.FederationSupportBuilder;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BridgeSupportRejectedPeginTest {
    private static final BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
    private static final FederationConstants federationMainnetConstants = bridgeMainnetConstants.getFederationConstants();
    private static final NetworkParameters btcMainnetParams = bridgeMainnetConstants.getBtcParams();
    private static final Coin minimumPeginTxValue = bridgeMainnetConstants.getMinimumPeginTxValue();
    private static final Coin belowMinimumPeginTxValue = minimumPeginTxValue.minus(Coin.SATOSHI);

    private static final int FIRST_OUTPUT_INDEX = 0;
    /** RSKj started using the pegout index at a fixed bitcoin height; the port always uses it, so this is just a height. */
    private static final int REGISTER_HEIGHT = 100;

    private BridgeStorageProvider provider;
    private FederationStorageProvider federationStorageProvider;

    private Address userAddress;

    private Federation activeFederation;
    private Federation retiringFederation;

    private BtcBlockStoreWithCache.Factory mockFactory;
    private BridgeEventLogger bridgeEventLogger;
    private BtcLockSenderProvider btcLockSenderProvider;
    private PeginInstructionsProvider peginInstructionsProvider;

    private final List<UTXO> retiringFederationUtxos = new ArrayList<>();
    private final List<UTXO> activeFederationUtxos = new ArrayList<>();
    private PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations;
    private InMemoryBridgeHost host;
    private CallContext rskTx;

    private co.rsk.bitcoinj.core.BtcBlock registerHeader;

    @BeforeEach
    void init() throws IOException {
        registerHeader = null;

        userAddress = BitcoinTestUtils.createP2PKHAddress(btcMainnetParams, "userAddress");
        NetworkParameters btcParams = bridgeMainnetConstants.getBtcParams();

        List<BtcECKey> erpPubKeys = federationMainnetConstants.getErpFedPubKeysList();
        long activationDelay = federationMainnetConstants.getErpFedActivationDelay();

        List<BtcECKey> retiringFedSigners = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[]{"fa04", "fa05", "fa06"}, true
        );
        retiringFedSigners.sort(BtcECKey.PUBKEY_COMPARATOR);
        List<FederationMember> retiringFedMembers = FederationTestUtils.getFederationMembersWithBtcKeys(
            retiringFedSigners);
        Instant retiringCreationTime = Instant.ofEpochMilli(1000L);
        long retiringFedCreationBlockNumber = 1;

        FederationArgs retiringFedArgs =
            new FederationArgs(retiringFedMembers, retiringCreationTime,
                retiringFedCreationBlockNumber, btcParams);
        retiringFederation = FederationFactory.buildP2shErpFederation(retiringFedArgs, erpPubKeys,
            activationDelay);

        List<BtcECKey> activeFedSigners = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[]{"fa07", "fa08", "fa09", "fa10", "fa11"}, true
        );
        activeFedSigners.sort(BtcECKey.PUBKEY_COMPARATOR);
        List<FederationMember> activeFedMembers = FederationTestUtils.getFederationMembersWithBtcKeys(
            activeFedSigners);
        long activeFedCreationBlockNumber = 2L;
        Instant creationTime = Instant.ofEpochMilli(1000L);
        FederationArgs activeFedArgs =
            new FederationArgs(activeFedMembers, creationTime, activeFedCreationBlockNumber,
                btcParams);
        activeFederation = FederationFactory.buildP2shErpFederation(activeFedArgs, erpPubKeys,
            activationDelay);

        mockFactory = mock(BtcBlockStoreWithCache.Factory.class);

        bridgeEventLogger = mock(BridgeEventLogger.class);
        btcLockSenderProvider = new BtcLockSenderProvider();

        peginInstructionsProvider = new PeginInstructionsProvider();

        provider = mock(BridgeStorageProvider.class);
        when(provider.getHeightIfBtcTxhashIsAlreadyProcessed(any(Sha256Hash.class))).thenReturn(
            Optional.empty());

        LockingCapSupport lockingCapSupport = mock(LockingCapSupport.class);
        when(lockingCapSupport.getLockingCap()).thenReturn(
            Optional.of(bridgeMainnetConstants.getMaxRbtc()));

        federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getOldFederationBtcUTXOs())
            .thenReturn(retiringFederationUtxos);
        when(federationStorageProvider.getNewFederationBtcUTXOs())
            .thenReturn(activeFederationUtxos);

        pegoutsWaitingForConfirmations = new PegoutsWaitingForConfirmations(new HashSet<>());
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(
            pegoutsWaitingForConfirmations);

        when(federationStorageProvider.getNewFederation(any(FederationConstants.class)))
            .thenReturn(activeFederation);

        // Set execution block right after the fed creation block, and fund the bridge to its cap
        long executionBlockNumber = activeFederation.getCreationBlockNumber() + 1;
        host = new InMemoryBridgeHost()
            .blockNumber(executionBlockNumber)
            .balance(BridgeAddresses.BRIDGE, Weis.fromSatoshis(bridgeMainnetConstants.getMaxRbtc()));

        rskTx = PegTestUtils.callWithHash(PegTestUtils.createHash3(1));
    }

    private PartialMerkleTree createPmtAndMockBlockStore(BtcTransaction btcTransaction)
        throws BlockStoreException {

        PartialMerkleTree pmt = createValidPmtForTransactions(List.of(btcTransaction), btcMainnetParams);

        Sha256Hash blockMerkleRoot = pmt.getTxnHashAndMerkleRoot(new ArrayList<>());
        registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcMainnetParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"),
            REGISTER_HEIGHT);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        co.rsk.bitcoinj.core.BtcBlock headBlock = new co.rsk.bitcoinj.core.BtcBlock(
            btcMainnetParams,
            1,
            BitcoinTestUtils.createHash(2),
            Sha256Hash.of(new byte[]{1}),
            1,
            1,
            1,
            new ArrayList<>()
        );

        StoredBlock chainHead = new StoredBlock(headBlock, new BigInteger("0"),
            REGISTER_HEIGHT
                + bridgeMainnetConstants.getBtc2RskMinimumAcceptableConfirmations());
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        co.rsk.bitcoinj.core.BtcBlock btcBlock = new co.rsk.bitcoinj.core.BtcBlock(
            btcMainnetParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        mockChainOfStoredBlocks(
            btcBlockStore,
            btcBlock,
            REGISTER_HEIGHT
                + bridgeMainnetConstants.getBtc2RskMinimumAcceptableConfirmations(),
            REGISTER_HEIGHT
        );
        return pmt;
    }

    @Test
    void registerBtcTransaction_whenBelowTheMinimum_shouldRejectPegin()
        throws BlockStoreException, BridgeIllegalArgumentException, IOException {
        // arrange
        BtcTransaction btcTransaction = new BtcTransaction(btcMainnetParams);
        btcTransaction.addInput(BitcoinTestUtils.createHash(1), FIRST_OUTPUT_INDEX,
            new Script(new byte[]{}));
        btcTransaction.addOutput(belowMinimumPeginTxValue, activeFederation.getAddress());

        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBtcBlockStoreFactory(mockFactory)
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .build();

        // act
        bridgeSupport.registerBtcTransaction(
            rskTx,
            btcTransaction.bitcoinSerialize(),
            REGISTER_HEIGHT,
            createPmtAndMockBlockStore(btcTransaction).bitcoinSerialize()
        );

        // assert

        // tx should be marked as processed
        verify(provider, times(1)).setHeightBtcTxhashAlreadyProcessed(any(), anyLong());

        verify(bridgeEventLogger, times(1)).logRejectedPegin(btcTransaction, INVALID_AMOUNT);
        verify(bridgeEventLogger, times(1)).logNonRefundablePegin(btcTransaction,
            NonRefundablePeginReason.INVALID_AMOUNT);
        verify(bridgeEventLogger, never()).logPeginBtc(any(), any(), any(), anyInt());
        assertTrue(activeFederationUtxos.isEmpty());
        assertTrue(retiringFederationUtxos.isEmpty());
    }

    @Test
    void registerBtcTransaction_whenUndeterminedSender_shouldRejectPegin()
        throws BlockStoreException, BridgeIllegalArgumentException, IOException {
        // arrange
        btcLockSenderProvider = mock(BtcLockSenderProvider.class);
        // return empty to simulate undetermined sender
        when(btcLockSenderProvider.tryGetBtcLockSender(any())).thenReturn(Optional.empty());

        Coin amountToSend = Coin.FIFTY_COINS.multiply(10);
        BtcTransaction btcTransaction = new BtcTransaction(btcMainnetParams);
        btcTransaction.addInput(
            BitcoinTestUtils.createHash(1),
            FIRST_OUTPUT_INDEX,
            new Script(new byte[]{})
        );
        btcTransaction.addOutput(amountToSend, activeFederation.getAddress());

        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBtcBlockStoreFactory(mockFactory)
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .build();

        // act
        bridgeSupport.registerBtcTransaction(
            rskTx,
            btcTransaction.bitcoinSerialize(),
            REGISTER_HEIGHT,
            createPmtAndMockBlockStore(btcTransaction).bitcoinSerialize()
        );

        // assert
        verify(bridgeEventLogger, times(1)).logRejectedPegin(
            btcTransaction, RejectedPeginReason.LEGACY_PEGIN_UNDETERMINED_SENDER
        );
        verify(bridgeEventLogger, times(1)).logNonRefundablePegin(
            btcTransaction,
            LEGACY_PEGIN_UNDETERMINED_SENDER
        );

        verify(bridgeEventLogger, never()).logPeginBtc(any(), any(), any(), anyInt());
        verify(bridgeEventLogger, never()).logReleaseBtcRequested(any(), any(), any());

        // tx should be marked as processed
        verify(provider, times(1)).setHeightBtcTxhashAlreadyProcessed(any(), anyLong());

        Assertions.assertTrue(activeFederationUtxos.isEmpty());
        Assertions.assertTrue(retiringFederationUtxos.isEmpty());
        Assertions.assertTrue(pegoutsWaitingForConfirmations.getEntries().isEmpty());
    }

    @Test
    void registerBtcTransaction_whenPeginV1WithInvalidPayloadAndUnderminedSender_shouldRejectPegin()
        throws BlockStoreException, BridgeIllegalArgumentException, IOException {
        // arrange
        btcLockSenderProvider = mock(BtcLockSenderProvider.class);
        // return empty to simulate undetermined sender
        when(btcLockSenderProvider.tryGetBtcLockSender(any())).thenReturn(Optional.empty());

        BtcTransaction btcTransaction = new BtcTransaction(btcMainnetParams);
        btcTransaction.addInput(
            BitcoinTestUtils.createHash(1),
            FIRST_OUTPUT_INDEX,
            new Script(new byte[]{})
        );
        btcTransaction.addOutput(Coin.FIFTY_COINS.multiply(10), activeFederation.getAddress());
        btcTransaction.addOutput(Coin.ZERO,
            PegTestUtils.createOpReturnScriptForRskWithCustomPayload(1, new byte[]{}));

        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBtcBlockStoreFactory(mockFactory)
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .build();

        // act
        bridgeSupport.registerBtcTransaction(
            rskTx,
            btcTransaction.bitcoinSerialize(),
            REGISTER_HEIGHT,
            createPmtAndMockBlockStore(btcTransaction).bitcoinSerialize()
        );

        // assert

        // tx should be marked as processed
        verify(provider, times(1)).setHeightBtcTxhashAlreadyProcessed(any(), anyLong());

        verify(bridgeEventLogger, times(1)).logRejectedPegin(
            btcTransaction, PEGIN_V1_INVALID_PAYLOAD
        );
        verify(bridgeEventLogger, times(1)).logNonRefundablePegin(
            btcTransaction,
            LEGACY_PEGIN_UNDETERMINED_SENDER
        );

        verify(bridgeEventLogger, never()).logPeginBtc(any(), any(), any(), anyInt());
        verify(bridgeEventLogger, never()).logReleaseBtcRequested(any(), any(), any());
        verify(bridgeEventLogger, never()).logPegoutTransactionCreated(any(), any());

        assertTrue(activeFederationUtxos.isEmpty());
        assertTrue(retiringFederationUtxos.isEmpty());
        assertTrue(pegoutsWaitingForConfirmations.getEntries().isEmpty());
    }

    @Test
    void registerBtcTransaction_whenUtxoToActiveFedBelowMinimumAndUtxoToRetiringFedAboveMinimum_shouldRejectPegin() throws BlockStoreException, BridgeIllegalArgumentException, IOException {
        // arrange
        BtcTransaction btcTransaction = new BtcTransaction(btcMainnetParams);
        btcTransaction.addInput(BitcoinTestUtils.createHash(1), FIRST_OUTPUT_INDEX,
            new Script(new byte[]{}));
        btcTransaction.addOutput(belowMinimumPeginTxValue, activeFederation.getAddress());
        btcTransaction.addOutput(minimumPeginTxValue, retiringFederation.getAddress());

        when(federationStorageProvider.getOldFederation(federationMainnetConstants)).thenReturn(retiringFederation);
        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBtcBlockStoreFactory(mockFactory)
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .build();

        // act
        bridgeSupport.registerBtcTransaction(
            rskTx,
            btcTransaction.bitcoinSerialize(),
            REGISTER_HEIGHT,
            createPmtAndMockBlockStore(btcTransaction).bitcoinSerialize()
        );

        // assert
        verify(provider, times(1)).setHeightBtcTxhashAlreadyProcessed(any(), anyLong());

        verify(bridgeEventLogger, times(1)).logRejectedPegin(btcTransaction,
            RejectedPeginReason.LEGACY_PEGIN_UNDETERMINED_SENDER);
        verify(bridgeEventLogger, times(1)).logNonRefundablePegin(btcTransaction,
            NonRefundablePeginReason.LEGACY_PEGIN_UNDETERMINED_SENDER);
        verify(bridgeEventLogger, never()).logPeginBtc(any(), any(), any(), anyInt());
        assertTrue(activeFederationUtxos.isEmpty());
        assertTrue(retiringFederationUtxos.isEmpty());
    }

    // flyover pegin
    @Test
    void registerBtcTransaction_whenAttemptToRegisterFlyoverPegin_shouldIgnorePegin()
        throws BlockStoreException, BridgeIllegalArgumentException, IOException {
        // arrange
        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBtcBlockStoreFactory(mockFactory)
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .build();

        Sha256Hash flyoverDerivationHash = BitcoinTestUtils.createHash(1);

        Address flyoverFederationAddress = PegTestUtils.getFlyoverAddressFromRedeemScript(
            bridgeMainnetConstants,
            activeFederation.getRedeemScript(),
            flyoverDerivationHash
        );

        BtcTransaction btcTransaction = new BtcTransaction(bridgeMainnetConstants.getBtcParams());
        btcTransaction.addInput(BitcoinTestUtils.createHash(1), FIRST_OUTPUT_INDEX,
            new Script(new byte[]{}));
        btcTransaction.addOutput(minimumPeginTxValue, flyoverFederationAddress);

        // act
        bridgeSupport.registerBtcTransaction(
            rskTx,
            btcTransaction.bitcoinSerialize(),
            REGISTER_HEIGHT,
            createPmtAndMockBlockStore(btcTransaction).bitcoinSerialize()
        );

        assertUnknownTxIsIgnored();
    }

    private void assertUnknownTxIsIgnored() throws IOException {
        verify(bridgeEventLogger, never()).logRejectedPegin(any(), any());
        verify(bridgeEventLogger, never()).logNonRefundablePegin(any(), any());
        verify(bridgeEventLogger, never()).logPeginBtc(any(), any(), any(), anyInt());
        verify(provider, never()).setHeightBtcTxhashAlreadyProcessed(any(), anyLong());
        assertTrue(activeFederationUtxos.isEmpty());
        assertTrue(retiringFederationUtxos.isEmpty());
    }

    @Test
    void registerBtcTransaction_whenNoUtxoToFed_shouldIgnorePegin()
        throws BlockStoreException, BridgeIllegalArgumentException, IOException {
        // arrange
        BtcTransaction btcTransaction = new BtcTransaction(btcMainnetParams);
        btcTransaction.addInput(BitcoinTestUtils.createHash(1), FIRST_OUTPUT_INDEX,
            new Script(new byte[]{}));
        btcTransaction.addOutput(minimumPeginTxValue, userAddress);

        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withBtcBlockStoreFactory(mockFactory)
            .withBridgeConstants(bridgeMainnetConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .build();

        // act
        bridgeSupport.registerBtcTransaction(
            rskTx,
            btcTransaction.bitcoinSerialize(),
            REGISTER_HEIGHT,
            createPmtAndMockBlockStore(btcTransaction).bitcoinSerialize()
        );

        // assert
        assertUnknownTxIsIgnored();
    }
}
