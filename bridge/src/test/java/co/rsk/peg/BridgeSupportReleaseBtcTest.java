/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package co.rsk.peg;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import co.rsk.RskTestUtils;
import co.rsk.bitcoinj.core.*;
import co.rsk.peg.abi.AbiFunction;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.FeePerKbSupport;
import co.rsk.peg.feeperkb.FeePerKbSupportImpl;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.peg.utils.*;
import co.rsk.test.builders.BridgeSupportBuilder;
import co.rsk.test.builders.FederationSupportBuilder;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BridgeSupportReleaseBtcTest {
    private static final BigInteger NONCE = new BigInteger("0");
    private static final BigInteger GAS_PRICE = new BigInteger("100");
    private static final BigInteger GAS_LIMIT = new BigInteger("1000");
    private static final String DATA = "80af2871";
    private static final Hash RELEASE_TX_HASH = RskTestUtils.createHash(1);
    private static final Hash UPDATE_TX_HASH = RskTestUtils.createHash(2);
    private static final org.hyperledger.besu.datatypes.Address BRIDGE_ADDRESS = BridgeAddresses.BRIDGE;
    private static final BtcECKey SENDER_KEY = BitcoinTestUtils.getBtcEcKeyFromSeed("sender");
    private static final org.hyperledger.besu.datatypes.Address SENDER = co.rsk.peg.utils.PublicKeys.addressOf(SENDER_KEY);
    private static final BridgeConstants BRIDGE_CONSTANTS = BridgeMainNetConstants.getInstance();
    private static final FederationConstants FEDERATION_CONSTANTS = BRIDGE_CONSTANTS.getFederationConstants();
    private static final NetworkParameters NETWORK_PARAMETERS = BRIDGE_CONSTANTS.getBtcParams();

    private Federation activeFederation;
    private InMemoryBridgeHost host;
    private BridgeEventLogger eventLogger;
    private BridgeStorageProvider provider;
    private FederationStorageProvider federationStorageProvider;
    private BridgeSupport bridgeSupport;
    private CallContext releaseTx;
    private BridgeSupportBuilder bridgeSupportBuilder;
    private FeePerKbSupport feePerKbSupport;

    @BeforeEach
    void setUpOnEachTest() {
        activeFederation = P2shErpFederationBuilder.builder().build();
        host = spy(new InMemoryBridgeHost());
        host.balance(BRIDGE_ADDRESS, Weis.fromSatoshis(BRIDGE_CONSTANTS.getMaxRbtc()));
        eventLogger = mock(BridgeEventLogger.class);
        provider = initProvider();
        federationStorageProvider = initFederationStorageProvider();
        bridgeSupportBuilder = BridgeSupportBuilder.builder();
        feePerKbSupport = mock(FeePerKbSupportImpl.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.valueOf(5_000L));
        bridgeSupport = spy(initBridgeSupport(eventLogger));
        releaseTx = buildReleaseRskTx(Weis.fromSatoshis(Coin.FIFTY_COINS.multiply(15)));
    }

    @Test
    void eventLogger_logReleaseBtcRequested() throws IOException {
        bridgeSupport.releaseBtc(releaseTx);

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        verify(host, never()).transfer(any(), any(), any());
        verify(eventLogger, times(1)).logReleaseBtcRequested(any(byte[].class), any(BtcTransaction.class), any(Coin.class));
        verify(eventLogger, times(1)).logReleaseBtcRequestReceived(any(), any(), any());
        verify(eventLogger, never()).logReleaseBtcRequestRejected(any(), any(), any());
    }

    @Test
    void release_logPegoutTransactionCreated_use_value_in_weis() throws IOException {
        BridgeEventLoggerImpl bridgeEventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(bridgeEventLogger);

        Wei pegoutRequestValue = Weis.fromSatoshis(BRIDGE_CONSTANTS.getMinimumPegoutTxValue());
        bridgeSupport.releaseBtc(buildReleaseRskTx(pegoutRequestValue));

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        verify(host, never()).transfer(any(), any(), any());

        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());

        assertEquals(5, host.logs().size());
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(
            any(byte[].class),
            any(BtcTransaction.class),
            any(Coin.class)
        );
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequestReceived(any(), any(), any());
        verify(bridgeEventLogger, times(1)).logUpdateCollections(any());
        verify(bridgeEventLogger, times(1)).logBatchPegoutCreated(any(), any());
        verify(bridgeEventLogger, times(1)).logPegoutTransactionCreated(any(), any());

        Log firstLog = host.logs().get(0);
        AbiFunction event = BridgeEvents.RELEASE_REQUEST_RECEIVED.getEvent();
        String expectedBtcDestinationAddress = SENDER_KEY.toAddress(NETWORK_PARAMETERS).toString();

        assertEquals(event.encodeEventData(expectedBtcDestinationAddress, pegoutRequestValue.toBigInteger()), firstLog.getData());
    }

    @Test
    void release_rejected_lowAmount() throws IOException {
        BridgeEventLoggerImpl bridgeEventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(bridgeEventLogger);

        releaseTx = buildReleaseRskTx(Wei.ZERO);
        bridgeSupport.releaseBtc(releaseTx);

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        verify(host, times(1)).transfer(
            argThat(a -> a.equals(BRIDGE_ADDRESS)),
            argThat(a -> a.equals(SENDER)),
            argThat(a -> a.equals(Weis.fromSatoshis(Coin.ZERO)))
        );

        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());

        assertEquals(2, host.logs().size());
        verify(bridgeEventLogger, never()).logReleaseBtcRequestReceived(any(), any(), any());
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequestRejected(any(), any(), any());
        verify(bridgeEventLogger, times(1)).logUpdateCollections(any());
    }

    @Test
    void release_rejected_contractCaller_emits_rejection_event() throws IOException {
        BridgeEventLoggerImpl bridgeEventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(bridgeEventLogger);

        releaseTx = buildReleaseRskTx_fromContract(Weis.fromSatoshis(Coin.COIN));
        bridgeSupport.releaseBtc(releaseTx);

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        verify(host, never()).transfer(
            any(), any(), any()
        );

        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        verify(bridgeEventLogger, never()).logReleaseBtcRequestReceived(any(), any(), any());
        assertEquals(2, host.logs().size());

        verify(bridgeEventLogger, times(1)).logReleaseBtcRequestRejected(any(), any(), any());
        verify(bridgeEventLogger, times(1)).logUpdateCollections(any());
    }

    @Test
    void release_minimum_inclusive() throws IOException {
        BridgeEventLoggerImpl bridgeEventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(bridgeEventLogger);

        // Get a value exactly to current minimum
        Coin value = BRIDGE_CONSTANTS.getMinimumPegoutTxValue();
        bridgeSupport.releaseBtc(buildReleaseRskTx(Weis.fromSatoshis(value)));

        CallContext rskTx = buildUpdateTx();

        verify(host, never()).transfer(any(), any(), any());

        assertEquals(1, provider.getReleaseRequestQueue().getEntries().size());

        assertEquals(1, host.logs().size());
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequestReceived(any(), any(), any());

        Log firstLog = host.logs().get(0);
        AbiFunction event = BridgeEvents.RELEASE_REQUEST_RECEIVED.getEvent();
        assertEquals(event.encodeEventTopics(SENDER.getBytes().toUnprefixedHexString()), firstLog.getTopics());

        String expectedBtcDestinationAddress = SENDER_KEY.toAddress(NETWORK_PARAMETERS).toString();
        assertEquals(event.encodeEventData(expectedBtcDestinationAddress, Weis.fromSatoshis(value).toBigInteger()), firstLog.getData());
    }

    @Disabled("Minimum pegout amount is large enough to discard this case")
    @Test
    void release_verify_fee_below_fee_is_rejected() throws IOException {
        Coin value = BRIDGE_CONSTANTS.getMinimumPegoutTxValue().add(Coin.SATOSHI);
        testPegoutMinimumWithFeeVerificationRejectedByFeeAboveValue(Coin.COIN, Weis.fromSatoshis(value));
    }

    @Disabled("Minimum pegout amount is large enough to discard this case")
    @Test
    void release_verify_fee_above_fee_but_below_gap_is_rejected() throws IOException {
        Coin feePerKB = Coin.COIN;

        int pegoutSize = BridgeUtils.getRegularPegoutTxSize(
            federationStorageProvider.getNewFederation(FEDERATION_CONSTANTS)
        );
        Coin value = feePerKB.div(1000).times(pegoutSize);
        testPegoutMinimumWithFeeVerificationRejectedByFeeAboveValue(feePerKB, Weis.fromSatoshis(value));
    }

    @Test
    void release_verify_fee_above_fee_but_below_minimum_is_rejected() throws IOException {
        Coin valueBelowMinimum =  BRIDGE_CONSTANTS.getMinimumPegoutTxValue().minus(Coin.SATOSHI);
        testPegoutMinimumWithFeeVerificationRejectedByLowAmount(
            Coin.MILLICOIN,
            Weis.fromSatoshis(valueBelowMinimum)
        );
    }

    @Test
    void release_verify_fee_above_fee_and_minimum_is_accepted() throws IOException {
        testPegoutMinimumWithFeeVerificationPass(Coin.COIN, Weis.fromSatoshis(Coin.FIFTY_COINS.multiply(10)));
    }

    @Test
    void processPegoutsInBatch() throws IOException {
        List<UTXO> utxos = new ArrayList<>();
        utxos.add(BitcoinTestUtils.createUTXO(1, 0, Coin.COIN.multiply(4), activeFederation.getAddress()));

        ReleaseRequestQueue pegoutRequests = new ReleaseRequestQueue(Arrays.asList(
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "one"), Coin.MILLICOIN),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "two"), Coin.MILLICOIN),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "three"), Coin.MILLICOIN)
        ));

        federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxos);

        provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(pegoutRequests);
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        Coin totalValue = pegoutRequests.getEntries()
            .stream()
            .map(ReleaseRequestQueue.Entry::getAmount)
            .reduce(Coin.ZERO, Coin::add);

        List<Hash> rskHashesList = pegoutRequests.getEntries()
            .stream()
            .map(ReleaseRequestQueue.Entry::getRskTxHash)
            .collect(Collectors.toList());

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .withEventLogger(eventLogger)
            .build();

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        BtcTransaction generatedTransaction = provider.getPegoutsWaitingForConfirmations().getEntries().iterator().next().getBtcTransaction();

        verify(provider, times(1)).getNextPegoutHeight();
        verify(provider, times(1)).setNextPegoutHeight(any(Long.class));

        verify(eventLogger, times(1)).logBatchPegoutCreated(generatedTransaction.getHash(), rskHashesList);
        verify(eventLogger, times(1)).logReleaseBtcRequested(rskTx.getHash().getBytes().toArrayUnsafe(), generatedTransaction, totalValue);
    }

    @Test
    void processPegoutsInBatch_next_pegout_height_not_reached() throws IOException {
        long executionBlockNumber = 100L;
        host.blockNumber(executionBlockNumber);

        provider = mock(BridgeStorageProvider.class);
        when(provider.getNextPegoutHeight()).thenReturn(Optional.of(executionBlockNumber + BRIDGE_CONSTANTS.getNumberOfBlocksBetweenPegouts() - 1));
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Arrays.asList(
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "one"), Coin.MILLICOIN),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "two"), Coin.MILLICOIN)
        )));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .withHost(host)
            .build();

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        verify(provider, times(1)).getNextPegoutHeight();
        verify(provider, never()).setNextPegoutHeight(any(Long.class));

        assertEquals(2, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
    }

    @Test
    void processPegoutsInBatch_no_requests_in_queue_updates_next_pegout_height() throws IOException {
        provider = mock(BridgeStorageProvider.class);
        when(provider.getNextPegoutHeight()).thenReturn(Optional.of(100L));
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        host.blockNumber(100L);

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .withHost(host)
            .build();

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        long nextPegoutHeight = host.blockNumber() + BRIDGE_CONSTANTS.getNumberOfBlocksBetweenPegouts();

        verify(provider, times(1)).getNextPegoutHeight();
        verify(provider, times(1)).setNextPegoutHeight(nextPegoutHeight);
    }

    @Test
    void processPegoutsInBatch_Insufficient_Money() throws IOException {
        List<UTXO> utxos = new ArrayList<>();
        utxos.add(BitcoinTestUtils.createUTXO(2, 0, Coin.FIFTY_COINS.multiply(100), activeFederation.getAddress()));

        federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxos);
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxos);

        provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Arrays.asList(
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "one"), Coin.FIFTY_COINS.multiply(25)),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "two"), Coin.FIFTY_COINS.multiply(25)),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "three"), Coin.FIFTY_COINS.multiply(25)),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "four"), Coin.FIFTY_COINS.multiply(25)),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "five"), Coin.FIFTY_COINS.multiply(25))
        )));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .build();

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        // Insufficient_Money i.e 100 BTC UTXO Available For 125 BTC Transaction.
        // Pegout requests can't be processed and remains in the queue
        assertEquals(5, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
    }

    @Test
    void processPegoutsInBatch_divide_transaction_when_max_size_exceeded() throws IOException {
        List<UTXO> utxos = BitcoinTestUtils.createUTXOs( 310, activeFederation.getAddress());

        federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxos);
        when(federationStorageProvider.getNewFederation(FEDERATION_CONSTANTS)).thenReturn(activeFederation);
        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(FEDERATION_CONSTANTS)
            .withFederationStorageProvider(federationStorageProvider)
            .build();

        provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(PegTestUtils.createReleaseRequestQueueEntries(300)));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .withFederationSupport(federationSupport)
            .build();

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        // First Quarter of the PegoutRequests [300 / 2 / 2 = 75] Is Batched For The First Time
        // Max input size is limited to 100. See bitcoinj-thin's completeTx() in Wallet.java
        assertEquals(225, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        // 2nd peg out transaction's input size would be [225 / 2 / 2] = 56 so 225 - 56 = 169
        assertEquals(169, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(2, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        // 3rd peg out transaction's input size would be [169 / 2] = 84 so 169 - 84 = 85
        assertEquals(85, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(3, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        // 4th peg out transaction processes all of the leftovers
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(4, provider.getPegoutsWaitingForConfirmations().getEntries().size());
    }

    @Test
    void processPegoutsInBatch_when_max_size_exceeded_for_one_pegout() throws IOException {
        List<UTXO> utxos = BitcoinTestUtils.createUTXOs(700, activeFederation.getAddress());

        federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxos);

        provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.singletonList(
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "one"), Coin.FIFTY_COINS.multiply(7000))
        )));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .build();

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        assertEquals(1, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
    }

    @Test
    void processPegoutsInBatch_when_max_size_exceeded_for_two_pegout() throws IOException {
        List<UTXO> utxos = BitcoinTestUtils.createUTXOs(1400, activeFederation.getAddress());

        federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxos);

        provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Arrays.asList(
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "one"), Coin.FIFTY_COINS.multiply(7000)),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "two"), Coin.FIFTY_COINS.multiply(7000))
        )));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .build();

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        assertEquals(2, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
    }

    @Test
    void check_wallet_balance_process_no_requests() throws IOException {
        List<UTXO> utxos = new ArrayList<>();
        utxos.add(BitcoinTestUtils.createUTXO(1, 0, Coin.FIFTY_COINS.multiply(40), activeFederation.getAddress()));
        utxos.add(BitcoinTestUtils.createUTXO(2, 1, Coin.FIFTY_COINS.multiply(40), activeFederation.getAddress()));
        utxos.add(BitcoinTestUtils.createUTXO(3, 2, Coin.FIFTY_COINS.multiply(30), activeFederation.getAddress()));

        federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxos);

        provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Arrays.asList(
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "one"), Coin.FIFTY_COINS.multiply(50)),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "two"), Coin.FIFTY_COINS.multiply(45)),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "three"), Coin.FIFTY_COINS.multiply(30))
        )));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .withEventLogger(eventLogger)
            .build();

        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        assertEquals(3, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        verify(eventLogger, never()).logBatchPegoutCreated(any(), any());
        verify(provider, never()).setNextPegoutHeight(any(Long.class));
    }

    @Test
    void check_wallet_balance_process_all_requests_when_utxos_available() throws IOException {
        List<UTXO> utxos = new ArrayList<>();
        utxos.add(BitcoinTestUtils.createUTXO(1, 0, Coin.FIFTY_COINS.multiply(40), activeFederation.getAddress()));
        utxos.add(BitcoinTestUtils.createUTXO(2, 1, Coin.FIFTY_COINS.multiply(40), activeFederation.getAddress()));
        utxos.add(BitcoinTestUtils.createUTXO(3, 2, Coin.FIFTY_COINS.multiply(20), activeFederation.getAddress()));

        federationStorageProvider = mock(FederationStorageProvider.class);

        federationStorageProvider = mock(FederationStorageProvider.class);
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxos);
        when(federationStorageProvider.getNewFederation(FEDERATION_CONSTANTS)).thenReturn(activeFederation);

        provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Arrays.asList(
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "one"), Coin.FIFTY_COINS.multiply(30)),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "two"), Coin.FIFTY_COINS.multiply(50)),
            new ReleaseRequestQueue.Entry(BitcoinTestUtils.createP2PKHAddress(BRIDGE_CONSTANTS.getBtcParams(), "three"), Coin.FIFTY_COINS.multiply(40))
        )));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .withEventLogger(eventLogger)
            .build();

        // First Call To updateCollections
        CallContext rskTx = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx);

        assertEquals(3, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        verify(eventLogger, never()).logBatchPegoutCreated(any(), any());
        verify(provider, never()).setNextPegoutHeight(any(Long.class));

        utxos.add(BitcoinTestUtils.createUTXO(4, 3, Coin.FIFTY_COINS.multiply(50), activeFederation.getAddress()));
        when(federationStorageProvider.getNewFederationBtcUTXOs()).thenReturn(utxos);
        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(FEDERATION_CONSTANTS)
            .withFederationStorageProvider(federationStorageProvider)
            .build();

        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .withEventLogger(eventLogger)
            .withFederationSupport(federationSupport)
            .build();

        // Second Call To updateCollections
        CallContext rskTx2 = buildUpdateTx();
        bridgeSupport.updateCollections(rskTx2);

        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        verify(eventLogger, times(1)).logBatchPegoutCreated(any(), any());
        verify(provider, times(1)).setNextPegoutHeight(any(Long.class));
    }

    private void testPegoutMinimumWithFeeVerificationPass(Coin feePerKB, Wei pegoutRequestedValue) throws IOException {
        eventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(eventLogger);
        when(feePerKbSupport.getFeePerKb()).thenReturn(feePerKB);

        int pegoutSize = BridgeUtils.getRegularPegoutTxSize(
            federationStorageProvider.getNewFederation(FEDERATION_CONSTANTS)
        );
        Coin minValueAccordingToFee = feePerKbSupport.getFeePerKb().div(1000).times(pegoutSize);
        Coin minValueWithGapAboveFee = minValueAccordingToFee.add(minValueAccordingToFee.times(
            BRIDGE_CONSTANTS.getMinimumPegoutValuePercentageToReceiveAfterFee()).div(100));

        Coin valueToRelease = Weis.toSatoshis(pegoutRequestedValue);
        assertFalse(valueToRelease.isLessThan(minValueWithGapAboveFee) ||
            valueToRelease.isLessThan(BRIDGE_CONSTANTS.getMinimumPegoutTxValue()));

        bridgeSupport.releaseBtc(buildReleaseRskTx(pegoutRequestedValue));

        CallContext rskTx = buildUpdateTx();

        verify(host, never()).transfer(any(), any(), any());

        assertEquals(1, provider.getReleaseRequestQueue().getEntries().size());

        assertEquals(1, host.logs().size());
        verify(eventLogger, times(1)).logReleaseBtcRequestReceived(any(), any(), any());
        verify(eventLogger, never()).logReleaseBtcRequestRejected(any(), any(), any());
    }

    private void testPegoutMinimumWithFeeVerificationRejectedByLowAmount(
        Coin feePerKB,
        Wei pegoutRequestValue
    ) throws IOException {
        eventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(eventLogger);
        when(feePerKbSupport.getFeePerKb()).thenReturn(feePerKB);

        int pegoutSize = BridgeUtils.getRegularPegoutTxSize(
            federationStorageProvider.getNewFederation(FEDERATION_CONSTANTS)
        );
        Coin minValueAccordingToFee = feePerKbSupport.getFeePerKb().div(1000).times(pegoutSize);
        Coin minValueWithGapAboveFee = minValueAccordingToFee.add(minValueAccordingToFee.times(
            BRIDGE_CONSTANTS.getMinimumPegoutValuePercentageToReceiveAfterFee()).div(100));

        Coin valueToRelease = Weis.toSatoshis(pegoutRequestValue);
        assertTrue(valueToRelease.isGreaterThan(minValueWithGapAboveFee));

        bridgeSupport.releaseBtc(buildReleaseRskTx(pegoutRequestValue));

        org.hyperledger.besu.datatypes.Address senderAddress = SENDER;

        verify(host, times(1)).transfer(BRIDGE_ADDRESS, senderAddress, pegoutRequestValue);
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(1, host.logs().size());

        verify(eventLogger, never()).logReleaseBtcRequestReceived(any(), any(), any());
        verify(eventLogger, times(1)).logReleaseBtcRequestRejected(
            senderAddress,
            pegoutRequestValue,
            RejectedPegoutReason.LOW_AMOUNT
        );
    }

    private void testPegoutMinimumWithFeeVerificationRejectedByFeeAboveValue(
        Coin feePerKB,
        Wei pegoutRequestValue
    ) throws IOException {
        eventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(eventLogger);
        when(feePerKbSupport.getFeePerKb()).thenReturn(feePerKB);

        int pegoutSize = BridgeUtils.getRegularPegoutTxSize(
            federationStorageProvider.getNewFederation(FEDERATION_CONSTANTS)
        );
        Coin minValueAccordingToFee = feePerKbSupport.getFeePerKb().div(1000).times(pegoutSize);
        Coin minValueWithGapAboveFee = minValueAccordingToFee.add(minValueAccordingToFee.times(
            BRIDGE_CONSTANTS.getMinimumPegoutValuePercentageToReceiveAfterFee()).div(100));

        Coin valueToRelease = Weis.toSatoshis(pegoutRequestValue);
        assertTrue(valueToRelease.isLessThan(minValueWithGapAboveFee));

        bridgeSupport.releaseBtc(buildReleaseRskTx(pegoutRequestValue));

        CallContext rskTx = buildUpdateTx();

        org.hyperledger.besu.datatypes.Address senderAddress = SENDER;

        verify(host, times(1)).transfer(
            BRIDGE_ADDRESS,
            senderAddress,
            pegoutRequestValue
        );
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(1, host.logs().size());

        verify(eventLogger, never()).logReleaseBtcRequestReceived(any(), any(), any());
        verify(eventLogger, times(1)).logReleaseBtcRequestRejected(
            senderAddress,
            pegoutRequestValue,
            RejectedPegoutReason.FEE_ABOVE_VALUE
        );
    }

    @Test
    @DisplayName("A rejected pegout due to low amount. Both in the emitted event and the value refunded.")
    void low_amount_release_request_rejected() throws IOException {
        eventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(eventLogger);

        Coin belowPegoutMinimumValue = BRIDGE_CONSTANTS.getMinimumPegoutTxValue().minus(Coin.SATOSHI);
        Wei pegoutRequestValue = Weis.fromSatoshis(belowPegoutMinimumValue);

        bridgeSupport.releaseBtc(buildReleaseRskTx(pegoutRequestValue));

        org.hyperledger.besu.datatypes.Address senderAddress = SENDER;

        verify(host, times(1)).transfer(
            BRIDGE_ADDRESS,
            senderAddress,
            pegoutRequestValue
        );

        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());

        assertEquals(1, host.logs().size());
        verify(eventLogger, never()).logReleaseBtcRequestReceived(any(), any(), any());
        verify(eventLogger, times(1)).logReleaseBtcRequestRejected(
            senderAddress,
            pegoutRequestValue,
            RejectedPegoutReason.LOW_AMOUNT
        );

        Log firstLog = host.logs().get(0);
        AbiFunction event = BridgeEvents.RELEASE_REQUEST_REJECTED.getEvent();
        assertEquals(event.encodeEventTopics(SENDER.getBytes().toUnprefixedHexString()), firstLog.getTopics());

        assertEquals(event.encodeEventData(pegoutRequestValue.toBigInteger(), RejectedPegoutReason.LOW_AMOUNT.getValue()), firstLog.getData());
    }

    @Test
    @DisplayName("A pegout from a contract is rejected. Both in the emitted event and the value refunded.")
    void contract_caller_release_request_rejected() throws IOException {
        eventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(eventLogger);

        Wei pegoutRequestValue = Weis.fromSatoshis(BRIDGE_CONSTANTS.getMinimumPegoutTxValue());
        // Add some extra weis to the value, but less than 1 satoshi.
        // To ensure that the pegout value is rounded down to fit in satoshis.
        Wei oneSatoshiInWeis = Weis.fromSatoshis(Coin.SATOSHI);
        Wei oneWei = Wei.ONE;
        Wei extraWeis = oneSatoshiInWeis.subtract(oneWei);
        pegoutRequestValue = pegoutRequestValue.add(extraWeis);

        bridgeSupport.releaseBtc(buildReleaseRskTx_fromContract(pegoutRequestValue));

        org.hyperledger.besu.datatypes.Address senderAddress = SENDER;

        // No refund is made to a contract
        verify(host, never()).transfer(any(), any(), any());

        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());

        assertEquals(1, host.logs().size());
        verify(eventLogger, never()).logReleaseBtcRequestReceived(any(), any(), any());
        verify(eventLogger, times(1)).logReleaseBtcRequestRejected(
            senderAddress,
            pegoutRequestValue,
            RejectedPegoutReason.CALLER_CONTRACT
        );

        Log firstLog = host.logs().get(0);
        AbiFunction event = BridgeEvents.RELEASE_REQUEST_REJECTED.getEvent();
        assertEquals(event.encodeEventTopics(SENDER.getBytes().toUnprefixedHexString()), firstLog.getTopics());

        assertEquals(event.encodeEventData(pegoutRequestValue.toBigInteger(), RejectedPegoutReason.CALLER_CONTRACT.getValue()), firstLog.getData());
    }

    @Disabled("Minimum pegout amount is large enough to discard this case")
    @Test
    @DisplayName("A pegout rejected due to high fees. Both in the emitted event and the value refunded.")
    void fee_above_value_release_request_rejected() throws IOException {
        eventLogger = spy(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host));
        bridgeSupport = initBridgeSupport(eventLogger);
        // Set a high fee per kb to ensure the resulting pegout is above the min pegout value
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.COIN);

        int pegoutSize = BridgeUtils.getRegularPegoutTxSize(
            federationStorageProvider.getNewFederation(FEDERATION_CONSTANTS)
        );
        Coin minValueAccordingToFee = feePerKbSupport.getFeePerKb().div(1000).times(pegoutSize);
        Coin minValueWithGapAboveFee = minValueAccordingToFee.add(minValueAccordingToFee.times(
            BRIDGE_CONSTANTS.getMinimumPegoutValuePercentageToReceiveAfterFee()).div(100)
        );

        Coin pegoutRequestValueWithGapAboveFee = minValueWithGapAboveFee.minus(Coin.SATOSHI);
        Wei pegoutRequestValue = Weis.fromSatoshis(pegoutRequestValueWithGapAboveFee);
        // Add some extra weis to the value, but less than 1 satoshi.
        // To ensure that the pegout value is rounded down to fit in satoshis.
        Wei oneSatoshiInWeis = Weis.fromSatoshis(Coin.SATOSHI);
        Wei oneWei = Wei.ONE;
        Wei extraWeis = oneSatoshiInWeis.subtract(oneWei);
        pegoutRequestValue = pegoutRequestValue.add(extraWeis);

        bridgeSupport.releaseBtc(buildReleaseRskTx(pegoutRequestValue));

        org.hyperledger.besu.datatypes.Address senderAddress = SENDER;

        verify(host, times(1)).transfer(
            BRIDGE_ADDRESS,
            senderAddress,
            pegoutRequestValue
        );

        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());

        assertEquals(1, host.logs().size());
        verify(eventLogger, never()).logReleaseBtcRequestReceived(any(), any(), any());
        verify(eventLogger, times(1)).logReleaseBtcRequestRejected(
            senderAddress,
            pegoutRequestValue,
            RejectedPegoutReason.FEE_ABOVE_VALUE
        );

        Log firstLog = host.logs().get(0);
        AbiFunction event = BridgeEvents.RELEASE_REQUEST_REJECTED.getEvent();
        assertEquals(event.encodeEventTopics(SENDER.getBytes().toUnprefixedHexString()), firstLog.getTopics());

        assertEquals(event.encodeEventData(pegoutRequestValue.toBigInteger(), RejectedPegoutReason.FEE_ABOVE_VALUE.getValue()), firstLog.getData());
    }

    /**********************************
     *  -------     UTILS     ------- *
     *********************************/

    private UTXO buildUTXO() {
        return new UTXO(
            BitcoinTestUtils.createHash(11),
            0,
            Coin.FIFTY_COINS.multiply(100),
            1,
            false,
            activeFederation.getP2SHScript()
        );
    }

    private CallContext buildReleaseRskTx(Wei value) {
        return new CallContext(SENDER, RELEASE_TX_HASH, value, false, co.rsk.peg.utils.PublicKeys.uncompressed(SENDER_KEY));
    }

    private CallContext buildReleaseRskTx_fromContract(Wei pegoutRequestValue) {
        return new CallContext(SENDER, RELEASE_TX_HASH, pegoutRequestValue, true, co.rsk.peg.utils.PublicKeys.uncompressed(SENDER_KEY));
    }

    private CallContext buildUpdateTx() {
        return new CallContext(SENDER, UPDATE_TX_HASH, Wei.ONE, false, co.rsk.peg.utils.PublicKeys.uncompressed(SENDER_KEY));
    }

    private BridgeSupport initBridgeSupport(BridgeEventLogger eventLogger) {
        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(FEDERATION_CONSTANTS)
            .withFederationStorageProvider(federationStorageProvider)
            .build();

        return bridgeSupportBuilder
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .withHost(host)
            .withEventLogger(eventLogger)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();
    }

    private BridgeStorageProvider initProvider() {
        return new BridgeStorageProvider(
            new BridgeStorageAccessorImpl(host),
            NETWORK_PARAMETERS
        );
    }

    private FederationStorageProvider initFederationStorageProvider() {
        UTXO utxo = buildUTXO();
        StorageAccessor bridgeStorageAccessor = new BridgeStorageAccessorImpl(host);
        FederationStorageProvider storageProvider = new FederationStorageProviderImpl(bridgeStorageAccessor);
        storageProvider.getNewFederationBtcUTXOs().add(utxo);
        storageProvider.setNewFederation(activeFederation);

        return storageProvider;
    }
}
