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

import static co.rsk.bitcoinj.core.Utils.uint32ToByteStreamLE;
import static co.rsk.peg.federation.FederationStorageIndexKey.*;
import static co.rsk.peg.federation.FederationTestUtils.REGTEST_FEDERATION_PRIVATE_KEYS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import co.rsk.peg.abi.AbiFunction;
import co.rsk.peg.exception.VMException;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.utils.PublicKeys;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Wei;
import co.rsk.peg.constants.BridgeMainNetConstants;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.*;
import java.util.function.BiFunction;

import co.rsk.peg.vote.ABICallSpec;
import co.rsk.peg.federation.*;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.params.RegTestParams;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.bitcoin.MerkleBranch;
import co.rsk.peg.bitcoin.SimpleBtcTransaction;

/**
 * Created by ajlopez on 6/8/2016.
 */
@ExtendWith(MockitoExtension.class)
// to avoid Junit5 unnecessary stub error due to some setup generalizations
@MockitoSettings(strictness = Strictness.LENIENT)
class BridgeIT {
    private static final org.hyperledger.besu.datatypes.Address BRIDGE_ADDRESS = BridgeAddresses.BRIDGE;
    private static final String BRIDGE_ADDRESS_TO_STRING = BridgeAddresses.BRIDGE.getBytes().toUnprefixedHexString();
    private static final BtcECKey FEDERATOR_KEY = BtcECKey.fromPrivate(REGTEST_FEDERATION_PRIVATE_KEYS.get(0).getPrivKey());
    private static final String[] ACTIVE_FED_SEEDS = new String[] {
        "activeFedMember1",
        "activeFedMember2",
        "activeFedMember3",
        "activeFedMember4",
        "activeFedMember5",
        "activeFedMember6",
        "activeFedMember7",
        "activeFedMember8",
        "activeFedMember9"
    };
    private static final List<BtcECKey> ACTIVE_FEDERATION_KEYS = BitcoinTestUtils.getBtcEcKeysFromSeeds(ACTIVE_FED_SEEDS, true);
    private static final BtcECKey ACTIVE_FEDERATOR_SIGNER_KEY = ACTIVE_FEDERATION_KEYS.get(0);
    private static final Federation ACTIVE_FEDERATION = P2shErpFederationBuilder.builder()
        .withMembersBtcPublicKeys(ACTIVE_FEDERATION_KEYS)
        .build();
    private static final String[] RETIRING_FED_SEEDS = new String[] {
        "retiringFedMember1",
        "retiringFedMember2",
        "retiringFedMember3",
        "retiringFedMember4",
        "retiringFedMember5",
        "retiringFedMember6",
        "retiringFedMember7",
        "retiringFedMember8",
        "retiringFedMember9"
    };
    private static final List<BtcECKey> RETIRING_FEDERATION_KEYS = BitcoinTestUtils.getBtcEcKeysFromSeeds(RETIRING_FED_SEEDS, true);
    private static final BtcECKey RETIRING_FEDERATOR_SIGNER_KEY = RETIRING_FEDERATION_KEYS.get(0);
    private static final Federation RETIRING_FEDERATION = P2shErpFederationBuilder.builder()
        .withMembersBtcPublicKeys(RETIRING_FEDERATION_KEYS)
        .build();
    private static final String[] PROPOSED_FED_SEEDS = new String[] {
        "proposedFedMember1",
        "proposedFedMember2",
        "proposedFedMember3",
        "proposedFedMember4",
        "proposedFedMember5",
        "proposedFedMember6",
        "proposedFedMember7",
        "proposedFedMember8",
        "proposedFedMember9"
    };
    private static final List<BtcECKey> PROPOSED_FEDERATION_KEYS = BitcoinTestUtils.getBtcEcKeysFromSeeds(PROPOSED_FED_SEEDS, true);
    private static final BtcECKey PROPOSED_FEDERATOR_SIGNER_KEY = PROPOSED_FEDERATION_KEYS.get(0);
    private static final Federation PROPOSED_FEDERATION = P2shErpFederationBuilder.builder()
        .withMembersBtcPublicKeys(PROPOSED_FEDERATION_KEYS)
        .build();

    private static final BridgeConstants bridgeRegTestConstants = new BridgeRegTestConstants();
    private static final BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
    private static final NetworkParameters regtestParameters = bridgeRegTestConstants.getBtcParams();

    private static final BigInteger AMOUNT = new BigInteger("1000000000000000000");
    private static final BigInteger NONCE = new BigInteger("0");
    private static final BigInteger GAS_PRICE = new BigInteger("100");
    private static final BigInteger GAS_LIMIT = new BigInteger("1000");
    private static final String DATA = "80af2871";
    private static final String ERR_NOT_FROM_ACTIVE_OR_RETIRING_FED = "The sender is not a member of the active or retiring federations";
    private static final String ERR_NOT_FROM_ACTIVE_RETIRING_OR_PROPOSED_FED = "The sender is not a member of the active, retiring, or proposed federations";

    private Bridge bridge;
    private InMemoryBridgeHost track;

    @Test
    void callUpdateCollectionsWithSignatureNotFromFederation() throws IOException {
        BtcTransaction tx1 = createTransaction();

        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;

        BridgeStorageProvider provider0 = new BridgeStorageProvider(new BridgeStorageAccessorImpl(track), regtestParameters);

        provider0.getPegoutsWaitingForConfirmations().add(tx1, 1L, PegTestUtils.createHash3(0));
        provider0.save();




        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(new BtcECKey())).caller(PublicKeys.addressOf(new BtcECKey())).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);
        try {
            bridge.execute(BridgeMethods.UPDATE_COLLECTIONS.getFunction().encode());
            fail();
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains(ERR_NOT_FROM_ACTIVE_OR_RETIRING_FED));
        }
    }

    @Test
    void callUpdateCollectionsWithTransactionsWaitingForConfirmation() throws IOException, VMException {
        BtcTransaction tx1 = createTransaction(2, bridgeRegTestConstants.getMinimumPegoutTxValue());
        BtcTransaction tx2 = createTransaction(3, bridgeRegTestConstants.getMinimumPegoutTxValue().add(Coin.MILLICOIN));
        BtcTransaction tx3 = createTransaction(4, bridgeRegTestConstants.getMinimumPegoutTxValue().add(Coin.MILLICOIN).add(Coin.MILLICOIN));

        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;

        BridgeStorageProvider provider0 = new BridgeStorageProvider(new BridgeStorageAccessorImpl(track), regtestParameters);

        provider0.getPegoutsWaitingForConfirmations().add(tx1, 1L, PegTestUtils.createHash3(1));
        provider0.getPegoutsWaitingForConfirmations().add(tx2, 2L, PegTestUtils.createHash3(2));
        provider0.getPegoutsWaitingForConfirmations().add(tx3, 3L, PegTestUtils.createHash3(3));

        provider0.save();



        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);


        bridge.execute(BridgeMethods.UPDATE_COLLECTIONS.getFunction().encode());


        //Reusing same storage configuration as the height doesn't affect storage configurations for releases.
        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(repository), regtestParameters);

        assertEquals(3, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForSignatures().size());
    }

    @Test
    void callUpdateCollectionsWithTransactionsWaitingForConfirmationWithEnoughConfirmations() throws IOException, VMException {
        BtcTransaction tx1 = createTransaction(2, bridgeRegTestConstants.getMinimumPegoutTxValue());
        BtcTransaction tx2 = createTransaction(3, bridgeRegTestConstants.getMinimumPegoutTxValue().add(Coin.MILLICOIN));
        BtcTransaction tx3 = createTransaction(4, bridgeRegTestConstants.getMinimumPegoutTxValue().add(Coin.MILLICOIN).add(Coin.MILLICOIN));

        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;

        BridgeStorageProvider provider0 = new BridgeStorageProvider(new BridgeStorageAccessorImpl(track), regtestParameters);

        provider0.getPegoutsWaitingForConfirmations().add(tx1, 1L, PegTestUtils.createHash3(4));
        provider0.getPegoutsWaitingForConfirmations().add(tx2, 2L, PegTestUtils.createHash3(5));
        provider0.getPegoutsWaitingForConfirmations().add(tx3, 3L, PegTestUtils.createHash3(6));

        provider0.save();



        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        track.blockNumber(10);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        bridge.execute(BridgeMethods.UPDATE_COLLECTIONS.getFunction().encode());


        // reusing same storage configuration as the height doesn't affect storage configurations for releases.
        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(repository), regtestParameters);

        assertEquals(2, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertEquals(1, provider.getPegoutsWaitingForSignatures().size());
    }

    @Test
    void sendNoBlockHeader() throws BlockStoreException, IOException, VMException {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = mock(BridgeSupportFactory.class);
        BridgeSupport bridgeSupport = mock(BridgeSupport.class);
        when(bridgeSupportFactory.newInstance(any())).thenReturn(bridgeSupport);
        when(bridgeSupport.getActiveFederation()).thenReturn(FederationTestUtils.getGenesisFederation(bridgeRegTestConstants.getFederationConstants()));
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        bridge.execute(BridgeMethods.RECEIVE_HEADERS.getFunction().encode((Object) new byte[0][]));


        verify(bridgeSupport, times(1)).receiveHeaders(new BtcBlock[]{});
        // TODO improve test
    }

    @Test
    void sendOrphanBlockHeader() throws VMException {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        Integer previousHeight = bridge.getBtcBlockchainBestChainHeight(new Object[]{});

        co.rsk.bitcoinj.core.BtcBlock block = new co.rsk.bitcoinj.core.BtcBlock(regtestParameters, 1, PegTestUtils.createHash(1), PegTestUtils.createHash(1), 1, Utils.encodeCompactBits(regtestParameters.getMaxTarget()), 1, new ArrayList<>())
                .cloneAsHeader();
        co.rsk.bitcoinj.core.BtcBlock[] headers = new co.rsk.bitcoinj.core.BtcBlock[1];
        headers[0] = block;

        Object[] objectArray = new Object[headers.length];

        for (int i = 0; i < headers.length; i++)
            objectArray[i] = headers[i].bitcoinSerialize();

        bridge.execute(BridgeMethods.RECEIVE_HEADERS.getFunction().encode(new Object[]{objectArray}));


        assertEquals(previousHeight, bridge.getBtcBlockchainBestChainHeight(new Object[]{}));
        // TODO improve test
    }

    @Test
    void executeWithFunctionSignatureLengthTooShortAfterRskip88() {

        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, host);

        try {
            bridge.execute(Bytes.wrap(new byte[3]));
            fail();
        } catch (VMException e) {
            assertTrue(e.getMessage().contains("Invalid data given"));
        }
    }

    @Test
    void executeWithInexistentFunctionAfterRskip88() {

        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, host);

        try {
            bridge.execute(Bytes.wrap(new byte[4]));
            fail();
        } catch (VMException e) {
            assertTrue(e.getMessage().contains("Invalid data given"));
        }
    }

    @Test
    void receiveHeadersNotFromTheFederation() {

        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        host.origin(PublicKeys.addressOf(new BtcECKey())).caller(PublicKeys.addressOf(new BtcECKey())).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, host);
        try {
            bridge.execute(BridgeMethods.RECEIVE_HEADERS.getFunction().encode((Object) new byte[0][]));
            fail();
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains(ERR_NOT_FROM_ACTIVE_OR_RETIRING_FED));
        }
    }

    @Test
    void receiveHeadersWithNonParseableHeader() throws VMException {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        Object[] objectArray = new Object[1];
        objectArray[0] = new byte[60];

        Bytes data = BridgeMethods.RECEIVE_HEADERS.getFunction().encode(new Object[]{objectArray});

        // since RSKIP88 a malformed argument is rethrown instead of returning nothing
        assertThrows(VMException.class, () -> bridge.execute(data));

    }

    @Test
    void receiveHeadersWithCorrectSizeHeaders() throws Exception {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
        Bridge bridge = spy(new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, track));



        final int numBlocks = 10;
        co.rsk.bitcoinj.core.BtcBlock[] headers = new co.rsk.bitcoinj.core.BtcBlock[numBlocks];

        for (int i = 0; i < numBlocks; i++) {
            co.rsk.bitcoinj.core.BtcBlock block = new co.rsk.bitcoinj.core.BtcBlock(regtestParameters, 1, PegTestUtils.createHash(2 * i + 1), PegTestUtils.createHash(2 * i + 2), 1, Utils.encodeCompactBits(regtestParameters.getMaxTarget()), 1, new ArrayList<>()).cloneAsHeader();
            headers[i] = block;
        }

        byte[][] headersSerialized = new byte[headers.length][];

        for (int i = 0; i < headers.length; i++) {
            headersSerialized[i] = headers[i].bitcoinSerialize();
        }

        try (MockedStatic<BridgeUtils> bridgeUtilsMocked = mockStatic(BridgeUtils.class)) {
            bridgeUtilsMocked.when(() -> BridgeUtils.isFromFederateMember(any(), any())).thenReturn(true);

            MessageSerializer serializer = regtestParameters.getDefaultSerializer();
            MessageSerializer spySerializer = Mockito.spy(serializer);

            NetworkParameters btcParamsMock = mock(NetworkParameters.class);
            BridgeConstants bridgeConstantsMock = mock(BridgeConstants.class);

            when(bridgeConstantsMock.getBtcParams()).thenReturn(btcParamsMock);
            when(btcParamsMock.getDefaultSerializer()).thenReturn(spySerializer);

            setInternalState(bridge, "bridgeConstants", bridgeConstantsMock);


            bridge.execute(BridgeMethods.RECEIVE_HEADERS.getFunction().encode(new Object[]{headersSerialized}));


            verify(bridgeSupportMock, times(1)).receiveHeaders(headers);
            for (int i = 0; i < headers.length; i++) {
                verify(spySerializer, times(1)).makeBlock(headersSerialized[i]);
            }
        }
    }

    @Test
    void receiveHeadersWithIncorrectSizeHeaders() throws Exception {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;



        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);

        track.origin(PublicKeys.addressOf(new BtcECKey())).caller(PublicKeys.addressOf(new BtcECKey())).callValue(Wei.of(AMOUNT));
        Bridge spiedBridge = spy(new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, track));

        final int numBlocks = 10;
        co.rsk.bitcoinj.core.BtcBlock[] headers = new co.rsk.bitcoinj.core.BtcBlock[numBlocks];
        byte[][] headersSerialized = new byte[headers.length][];

        // Add a couple of transactions to the block so that it doesn't serialize as just the header
        for (int i = 0; i < numBlocks; i++) {
            co.rsk.bitcoinj.core.BtcBlock block = new co.rsk.bitcoinj.core.BtcBlock(
                regtestParameters,
                    1,
                    PegTestUtils.createHash(2 * i + 1),
                    PegTestUtils.createHash(2 * i + 2),
                    1,
                    Utils.encodeCompactBits(regtestParameters.getMaxTarget()),
                    1,
                    new ArrayList<>()
            );

            BtcECKey from = new BtcECKey();
            BtcECKey to = new BtcECKey();

            // Coinbase TX
            BtcTransaction coinbaseTx = new BtcTransaction(regtestParameters);
            coinbaseTx.addInput(Sha256Hash.ZERO_HASH, -1, ScriptBuilder.createOpReturnScript(new byte[0]));
            block.addTransaction(coinbaseTx);

            // Random TX
            BtcTransaction inputTx = new BtcTransaction(regtestParameters);
            inputTx.addOutput(Coin.FIFTY_COINS, from.toAddress(regtestParameters));
            BtcTransaction outputTx = new BtcTransaction(regtestParameters);
            outputTx.addInput(inputTx.getOutput(0));
            outputTx.getInput(0).disconnect();
            outputTx.addOutput(Coin.COIN, to.toAddress(regtestParameters));
            block.addTransaction(outputTx);

            headers[i] = block;
            headersSerialized[i] = block.bitcoinSerialize();

            // Make sure we would be able to deserialize the block
            assertEquals(block, regtestParameters.getDefaultSerializer().makeBlock(headersSerialized[i]));
        }

        try (MockedStatic<BridgeUtils> bridgeUtilsMocked = mockStatic(BridgeUtils.class)) {
            bridgeUtilsMocked.when(() -> BridgeUtils.isFromFederateMember(any(), any())).thenReturn(true);

            NetworkParameters btcParamsMock = mock(NetworkParameters.class);
            BridgeConstants bridgeConstantsMock = mock(BridgeConstants.class);

            setInternalState(spiedBridge, "bridgeConstants", bridgeConstantsMock);

            // since RSKIP88 a header of the wrong size is rethrown instead of returning nothing
            assertThrows(VMException.class,
                () -> spiedBridge.execute(BridgeMethods.RECEIVE_HEADERS.getFunction().encode(new Object[]{headersSerialized})));


            verify(bridgeSupportMock, never()).receiveHeaders(headers);
            verify(btcParamsMock, never()).getDefaultSerializer();
        }
    }

    @Test
    void receiveHeadersWithHugeDeclaredTransactionsSize() throws VMException {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        NetworkParameters btcParams = RegTestParams.get();
        BtcBlock block = new BtcBlock(btcParams, 1, PegTestUtils.createHash(1), PegTestUtils.createHash(1), 1, 1, 1, new ArrayList<BtcTransaction>()) {
            @Override
            protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
                Utils.uint32ToByteStreamLE(getVersion(), stream);
                stream.write(getPrevBlockHash().getReversedBytes());
                stream.write(getMerkleRoot().getReversedBytes());
                Utils.uint32ToByteStreamLE(getTimeSeconds(), stream);
                Utils.uint32ToByteStreamLE(getDifficultyTarget(), stream);
                Utils.uint32ToByteStreamLE(getNonce(), stream);

                stream.write(new VarInt(Integer.MAX_VALUE).encode());
            }

            @Override
            public byte[] bitcoinSerialize() {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    bitcoinSerializeToStream(baos);
                } catch (IOException e) {
                }
                return baos.toByteArray();
            }
        };

        Object[] objectArray = new Object[1];
        objectArray[0] = block.bitcoinSerialize();

        Bytes data = BridgeMethods.RECEIVE_HEADERS.getFunction().encode(new Object[]{objectArray});

        // since RSKIP88 a malformed argument is rethrown instead of returning nothing
        assertThrows(VMException.class, () -> bridge.execute(data));

    }

    @Test
    void registerBtcTransactionWithNonParseableTx() throws VMException {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);


        Bytes data = BridgeMethods.REGISTER_BTC_TRANSACTION.getFunction().encode(new byte[3], 1, new byte[30]);

        assertEquals(Bytes.EMPTY, bridge.execute(data));
    }

    @Test
    void registerBtcTransactionWithHugeDeclaredInputsSize() throws VMException {
        NetworkParameters btcParams = RegTestParams.get();
        BtcTransaction tx = new HugeDeclaredSizeBtcTransaction(btcParams, true, false, false, false);
        registerBtcTransactionWithHugeDeclaredSize(tx);
    }

    @Test
    void registerBtcTransactionWithHugeDeclaredOutputsSize() throws VMException {
        NetworkParameters btcParams = RegTestParams.get();
        BtcTransaction tx = new HugeDeclaredSizeBtcTransaction(btcParams, false, true, false, false);
        registerBtcTransactionWithHugeDeclaredSize(tx);
    }

    @Test
    void registerBtcTransactionWithHugeDeclaredWitnessPushCountSize() throws VMException {
        NetworkParameters btcParams = RegTestParams.get();
        BtcTransaction tx = new HugeDeclaredSizeBtcTransaction(btcParams, false, false, true, false);
        registerBtcTransactionWithHugeDeclaredSize(tx);
    }

    @Test
    void registerBtcTransactionWithHugeDeclaredWitnessPushSize() throws VMException {
        NetworkParameters btcParams = RegTestParams.get();
        BtcTransaction tx = new HugeDeclaredSizeBtcTransaction(btcParams, false, false, false, true);
        registerBtcTransactionWithHugeDeclaredSize(tx);
    }

    @Test
    void registerBtcTransactionWithNonParseableMerkleeProof1() throws Exception {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        NetworkParameters btcParams = RegTestParams.get();
        BtcTransaction tx = new BtcTransaction(btcParams);
        tx.addOutput(Coin.COIN, new BtcECKey().toAddress(btcParams));
        tx.addInput(PegTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, new BtcECKey()));

        Bytes data = BridgeMethods.REGISTER_BTC_TRANSACTION.getFunction().encode(tx.bitcoinSerialize(), 1, new byte[3]);

        assertEquals(Bytes.EMPTY, bridge.execute(data));
    }

    @Test
    void registerBtcTransactionWithNonParseableMerkleeProof2() throws VMException {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        NetworkParameters btcParams = RegTestParams.get();
        BtcTransaction tx = new BtcTransaction(btcParams);
        tx.addOutput(Coin.COIN, new BtcECKey().toAddress(btcParams));
        tx.addInput(PegTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, new BtcECKey()));

        Bytes data = BridgeMethods.REGISTER_BTC_TRANSACTION.getFunction().encode(tx.bitcoinSerialize(), 1, new byte[30]);

        assertEquals(Bytes.EMPTY, bridge.execute(data));
    }

    @Test
    void registerBtcTransactionWithHugeDeclaredSizeMerkleeProof() throws VMException {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        NetworkParameters btcParams = RegTestParams.get();
        BtcTransaction tx = new BtcTransaction(btcParams);
        tx.addOutput(Coin.COIN, new BtcECKey().toAddress(btcParams));
        tx.addInput(PegTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, new BtcECKey()));

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001"));
        hashes.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000002"));
        hashes.add(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000003"));
        PartialMerkleTree pmt = new PartialMerkleTree(btcParams, bits, hashes, 3) {
            @Override
            public void bitcoinSerializeToStream(OutputStream stream) throws IOException {
                uint32ToByteStreamLE(getTransactionCount(), stream);
                stream.write(new VarInt(Integer.MAX_VALUE).encode());
                for (Sha256Hash hash : hashes) {
                    stream.write(hash.getReversedBytes());
                }

                stream.write(new VarInt(bits.length).encode());
                stream.write(bits);
            }
        };
        byte[] pmtSerialized = pmt.bitcoinSerialize();

        Bytes data = BridgeMethods.REGISTER_BTC_TRANSACTION.getFunction().encode(tx.bitcoinSerialize(), 1, pmtSerialized);

        assertEquals(Bytes.EMPTY, bridge.execute(data));
    }

    @Test
    void getFederationAddress() throws Exception {
        // Case with genesis federation
        Federation genesisFederation = FederationTestUtils.getGenesisFederation(bridgeRegTestConstants.getFederationConstants());
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;

        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        // since RSKIP88 this is a local call only method
        track.localCall(true);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        Bytes data = BridgeMethods.GET_FEDERATION_ADDRESS.getFunction().encode();

        assertEquals(BridgeMethods.GET_FEDERATION_ADDRESS.getFunction().encodeOutputs(genesisFederation.getAddress().toString()), bridge.execute(data));
    }

    @Test
    void getMinimumLockTxValue() throws Exception {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;

        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        // since RSKIP88 this is a local call only method
        track.localCall(true);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        Bytes data = BridgeMethods.GET_MINIMUM_LOCK_TX_VALUE.getFunction().encode();

        Assertions.assertEquals(BridgeMethods.GET_MINIMUM_LOCK_TX_VALUE.getFunction().encodeOutputs(bridgeRegTestConstants.getMinimumPeginTxValue().value), bridge.execute(data));
    }

    @Test
    void addSignature_fromNewFederator_whenNewAndOldFederationsAndNewFedIsActive_shouldCall() {
        // arrange
        setUp();

        saveRetiringFederationAsOldFederation();
        saveActiveFederationAsNewFederation();

        BtcECKey activeFederatorSignerKey = ACTIVE_FEDERATION_KEYS.get(0);
        callFrom(activeFederatorSignerKey);

        long blockNumberForNewFedToBeActive = bridgeMainnetConstants.getFederationConstants().getFederationActivationAge()
            + ACTIVE_FEDERATION.getCreationBlockNumber();
        initializeBridge(blockNumberForNewFedToBeActive);

        // get the data to call method
        Bytes data = getAddSignatureEncodedData(activeFederatorSignerKey);

        // act & assert
        assertDoesNotThrow(() -> bridge.execute(data));
    }

    @Test
    void addSignature_fromOldFederator_whenNewAndOldFederationsAndNewFedIsActive_shouldCall() {
        // arrange
        setUp();

        saveRetiringFederationAsOldFederation();
        saveActiveFederationAsNewFederation();

        callFrom(RETIRING_FEDERATOR_SIGNER_KEY);

        long blockNumberForNewFedToBeActive = bridgeMainnetConstants.getFederationConstants().getFederationActivationAge()
            + ACTIVE_FEDERATION.getCreationBlockNumber();
        initializeBridge(blockNumberForNewFedToBeActive);

        Bytes data = getAddSignatureEncodedData(RETIRING_FEDERATOR_SIGNER_KEY);

        // act & assert
        assertDoesNotThrow(() -> bridge.execute(data));
    }

    @Test
    void addSignature_fromOldFederator_whenNewAndOldFederationsAndNewFedIsInactive_shouldCall() {
        // arrange
        setUp();

        saveRetiringFederationAsOldFederation();
        saveActiveFederationAsNewFederation();

        callFrom(RETIRING_FEDERATOR_SIGNER_KEY);

        long blockNumberForNewFedToBeInactive = bridgeMainnetConstants.getFederationConstants().getFederationActivationAge()
            + ACTIVE_FEDERATION.getCreationBlockNumber()
            - 1;
        initializeBridge(blockNumberForNewFedToBeInactive);

        Bytes data = getAddSignatureEncodedData(RETIRING_FEDERATOR_SIGNER_KEY);

        // act & assert
        assertDoesNotThrow(() -> bridge.execute(data));
    }

    @Test
    void addSignature_fromNewFederator_whenNewAndOldFederationsAndNewFedIsInactive_shouldThrow() {
        // arrange
        setUp();

        saveRetiringFederationAsOldFederation();
        saveActiveFederationAsNewFederation();

        BtcECKey activeFederatorSignerKey = ACTIVE_FEDERATION_KEYS.get(0);
        callFrom(activeFederatorSignerKey);

        long blockNumberForNewFedToBeInactive = bridgeMainnetConstants.getFederationConstants().getFederationActivationAge()
            + ACTIVE_FEDERATION.getCreationBlockNumber()
            - 1;
        initializeBridge(blockNumberForNewFedToBeInactive);

        Bytes data = getAddSignatureEncodedData(activeFederatorSignerKey);

        // act & assert
        VMException result = assertThrows(VMException.class, () -> bridge.execute(data));
        assertTrue(result.getMessage().contains(ERR_NOT_FROM_ACTIVE_RETIRING_OR_PROPOSED_FED));
    }

    @Test
    void addSignature_fromNewFederator_whenNoOldFederation_shouldCall() {
        // arrange
        setUp();

        saveActiveFederationAsNewFederation();

        callFrom(ACTIVE_FEDERATOR_SIGNER_KEY);

        initializeBridge(0L);

        Bytes data = getAddSignatureEncodedData(ACTIVE_FEDERATOR_SIGNER_KEY);

        // act & assert
        assertDoesNotThrow(() -> bridge.execute(data));
    }

    private void saveActiveFederationAsNewFederation() {
        byte[] activeFederationSerialized = BridgeSerializationUtils.serializeFederation(ACTIVE_FEDERATION);
        track.putStorage(NEW_FEDERATION_FORMAT_VERSION.getKey(), BridgeSerializationUtils.serializeInteger(3000));
        track.putStorage(NEW_FEDERATION_KEY.getKey(), activeFederationSerialized);
    }

    private void saveRetiringFederationAsOldFederation() {
        byte[] retiringFederationSerialized = BridgeSerializationUtils.serializeFederation(RETIRING_FEDERATION);
        track.putStorage(OLD_FEDERATION_FORMAT_VERSION.getKey(), BridgeSerializationUtils.serializeInteger(3000));
        track.putStorage(OLD_FEDERATION_KEY.getKey(), retiringFederationSerialized);
    }

    @Test
    void addSignature_fromProposedFederator_whenProposedFederation_shouldCall() {
        // arrange
        setUp();

        saveProposedFederation();

        callFrom(PROPOSED_FEDERATOR_SIGNER_KEY);

        initializeBridge(0L);

        Bytes data = getAddSignatureEncodedData(PROPOSED_FEDERATOR_SIGNER_KEY);

        // act & assert
        assertDoesNotThrow(() -> bridge.execute(data));
    }

    private void saveProposedFederation() {
        byte[] proposedFederationSerialized = BridgeSerializationUtils.serializeFederation(PROPOSED_FEDERATION);
        track.putStorage(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), BridgeSerializationUtils.serializeInteger(3000));
        track.putStorage(FederationStorageIndexKey.PROPOSED_FEDERATION.getKey(), proposedFederationSerialized);
    }

    @Test
    void addSignature_fromProposedFederator_whenNoProposedFederation_shouldThrow() {
        // arrange
        setUp();

        callFrom(PROPOSED_FEDERATOR_SIGNER_KEY);

        initializeBridge(0L);

        Bytes data = getAddSignatureEncodedData(PROPOSED_FEDERATOR_SIGNER_KEY);

        // act & assert
        VMException result = assertThrows(VMException.class, () -> bridge.execute(data));
        assertTrue(result.getMessage().contains(ERR_NOT_FROM_ACTIVE_RETIRING_OR_PROPOSED_FED));
    }

    private void setUp() {
        track = new InMemoryBridgeHost();
    }

    /** Replaces RSKj's recreateRskTx plus sign: who is calling is a fact of the host. */
    private void callFrom(BtcECKey signerKey) {
        org.hyperledger.besu.datatypes.Address sender = PublicKeys.addressOf(signerKey);
        track.origin(sender).caller(sender).callValue(Wei.of(AMOUNT));
    }

    private void initializeBridge(long executionBlockNumber) {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(
            new RepositoryBtcBlockStoreWithCache.Factory(bridgeMainnetConstants.getBtcParams()),
            bridgeMainnetConstants
        );

        track.blockNumber(executionBlockNumber);
        bridge = new Bridge(bridgeMainnetConstants, bridgeSupportFactory, track);
    }

    private Bytes getAddSignatureEncodedData(BtcECKey signerKey) {
        byte[] rskTxHash = new byte[32];
        byte[] signerPublicKeySerialized = signerKey.getPubKey();
        BtcECKey.ECDSASignature signature = signerKey.sign(Sha256Hash.ZERO_HASH);
        Object[] signaturesObjectArray = new Object[]{ signature.encodeToDER() };
        return BridgeMethods.ADD_SIGNATURE.getFunction().encode(signerPublicKeySerialized, signaturesObjectArray, rskTxHash);
    }

    @Test
    void addSignature_fromNotFederatorSigner_shouldThrow() {
        // arrange
        setUp();

        // this is not a situation that can happen in real life, but it simplifies the test
        saveActiveFederationAsNewFederation();
        saveRetiringFederationAsOldFederation();
        saveProposedFederation();

        BtcECKey notFederatorSigner = BitcoinTestUtils.getBtcEcKeyFromSeed("notFederator");
        callFrom(notFederatorSigner);

        initializeBridge(0L);

        Bytes data = getAddSignatureEncodedData(notFederatorSigner);

        // act & assert
        VMException result = assertThrows(VMException.class, () -> bridge.execute(data));
        assertTrue(result.getMessage().contains(ERR_NOT_FROM_ACTIVE_RETIRING_OR_PROPOSED_FED));
    }

    @Test
    void addSignatureWithNonParseablePublicKey() throws Exception {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        byte[] federatorPublicKeySerialized = new byte[3];
        Object[] signaturesObjectArray = new Object[0];
        byte[] rskTxHash = new byte[32];
        Bytes data = BridgeMethods.ADD_SIGNATURE.getFunction().encode(federatorPublicKeySerialized, signaturesObjectArray, rskTxHash);

        // since RSKIP88 a malformed argument is rethrown instead of returning nothing
        assertThrows(VMException.class, () -> bridge.execute(data));
    }

    @Test
    void addSignatureWithEmptySignatureArray() throws Exception {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        byte[] federatorPublicKeySerialized = new BtcECKey().getPubKey();
        Object[] signaturesObjectArray = new Object[0];
        byte[] rskTxHash = new byte[32];
        Bytes data = BridgeMethods.ADD_SIGNATURE.getFunction().encode(federatorPublicKeySerialized, signaturesObjectArray, rskTxHash);

        // since RSKIP88 a malformed argument is rethrown instead of returning nothing
        assertThrows(VMException.class, () -> bridge.execute(data));
    }

    @Test
    void addSignatureWithNonParseableSignature() throws Exception {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        byte[] federatorPublicKeySerialized = new BtcECKey().getPubKey();
        Object[] signaturesObjectArray = new Object[]{new byte[3]};
        byte[] rskTxHash = new byte[32];
        Bytes data = BridgeMethods.ADD_SIGNATURE.getFunction().encode(federatorPublicKeySerialized, signaturesObjectArray, rskTxHash);

        // since RSKIP88 a malformed argument is rethrown instead of returning nothing
        assertThrows(VMException.class, () -> bridge.execute(data));
    }

    @Test
    void addSignatureWithNonParseableRskTx() throws Exception {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        byte[] federatorPublicKeySerialized = new BtcECKey().getPubKey();
        Object[] signaturesObjectArray = new Object[]{new BtcECKey().sign(Sha256Hash.ZERO_HASH).encodeToDER()};
        byte[] rskTxHash = new byte[3];
        Bytes data = BridgeMethods.ADD_SIGNATURE.getFunction().encode(federatorPublicKeySerialized, signaturesObjectArray, rskTxHash);

        // since RSKIP88 a malformed argument is rethrown instead of returning nothing
        assertThrows(VMException.class, () -> bridge.execute(data));
    }

    @Test
    void exceptionInUpdateCollection() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, mock(BridgeSupportFactory.class), host);

        try {
            bridge.updateCollections(null);
            fail();
        } catch (VMException ex) {
            assertEquals("Exception onBlock", ex.getMessage());
        }
    }

    @Test
    void exceptionInReleaseBtc() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, mock(BridgeSupportFactory.class), host);

        try {
            bridge.releaseBtc(null);
            fail();
        } catch (VMException ex) {
            assertEquals("Exception in releaseBtc", ex.getMessage());
        }
    }

    @Test
    void exceptionInGetStateForBtcReleaseClient() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, mock(BridgeSupportFactory.class), host);

        try {
            bridge.getStateForBtcReleaseClient(null);
            fail();
        } catch (VMException ex) {
            assertEquals("Exception in getStateForBtcReleaseClient", ex.getMessage());
        }
    }

    @Test
    void exceptionInGetStateForSvpClient() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, mock(BridgeSupportFactory.class), host);

        try {
            bridge.getStateForSvpClient(null);
            fail();
        } catch (VMException ex) {
            assertEquals("Exception in getStateForSvpClient", ex.getMessage());
        }
    }

    @Test
    void exceptionInGetStateForDebugging() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, mock(BridgeSupportFactory.class), host);

        try {
            bridge.getStateForDebugging(null);
            fail();
        } catch (VMException ex) {
            assertEquals("Exception in getStateForDebugging", ex.getMessage());
        }
    }

    @Test
    void exceptionInGetBtcBlockchainBestChainHeight() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, mock(BridgeSupportFactory.class), host);

        try {
            bridge.getBtcBlockchainBestChainHeight(null);
            fail();
        } catch (VMException ex) {
            assertEquals("Exception in getBtcBlockchainBestChainHeight", ex.getMessage());
        }
    }

    @Test
    void isBtcTxHashAlreadyProcessed_normalFlow() throws IOException, VMException {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        Set<Sha256Hash> hashes = new HashSet<>();
        when(bridgeSupportMock.isBtcTxHashAlreadyProcessed(any(Sha256Hash.class))).then((InvocationOnMock invocation) -> hashes.contains(invocation.<Sha256Hash>getArgument(0)));

        hashes.add(Sha256Hash.of("hash_1".getBytes()));
        hashes.add(Sha256Hash.of("hash_2".getBytes()));
        hashes.add(Sha256Hash.of("hash_3".getBytes()));
        hashes.add(Sha256Hash.of("hash_4".getBytes()));

        for (Sha256Hash hash : hashes) {
            assertTrue(bridge.isBtcTxHashAlreadyProcessed(new Object[]{hash.toString()}));
            verify(bridgeSupportMock).isBtcTxHashAlreadyProcessed(hash);
        }
        Assertions.assertFalse(bridge.isBtcTxHashAlreadyProcessed(new Object[]{Sha256Hash.of("anything".getBytes()).toString()}));
        Assertions.assertFalse(bridge.isBtcTxHashAlreadyProcessed(new Object[]{Sha256Hash.of("yetanotheranything".getBytes()).toString()}));
    }

    @Test
    void isBtcTxHashAlreadyProcessed_exception() throws IOException {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);

        try {
            bridge.isBtcTxHashAlreadyProcessed(new Object[]{"notahash"});
            fail();
        } catch (VMException e) {
            verify(bridgeSupportMock, never()).isBtcTxHashAlreadyProcessed(any());
        }
    }

    @Test
    void getBtcTxHashProcessedHeight_normalFlow() throws IOException, VMException {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        Map<Sha256Hash, Long> hashes = new HashMap<>();
        when(bridgeSupportMock.getBtcTxHashProcessedHeight(any(Sha256Hash.class))).then((InvocationOnMock invocation) -> hashes.get(invocation.<Sha256Hash>getArgument(0)));

        hashes.put(Sha256Hash.of("hash_1".getBytes()), 1L);
        hashes.put(Sha256Hash.of("hash_2".getBytes()), 2L);
        hashes.put(Sha256Hash.of("hash_3".getBytes()), 3L);
        hashes.put(Sha256Hash.of("hash_4".getBytes()), 4L);

        for (Map.Entry<Sha256Hash, Long> entry : hashes.entrySet()) {
            assertEquals(entry.getValue(), bridge.getBtcTxHashProcessedHeight(new Object[]{entry.getKey().toString()}));
            verify(bridgeSupportMock).getBtcTxHashProcessedHeight(entry.getKey());
        }
        assertNull(bridge.getBtcTxHashProcessedHeight(new Object[]{Sha256Hash.of("anything".getBytes()).toString()}));
    }

    @Test
    void getBtcTxHashProcessedHeight_exception() throws IOException {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);

        try {
            bridge.getBtcTxHashProcessedHeight(new Object[]{"notahash"});
            fail();
        } catch (VMException e) {
            verify(bridgeSupportMock, never()).getBtcTxHashProcessedHeight(any());
        }
    }

    @Test
    void getFederationSize() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.getActiveFederationSize()).thenReturn(1234);

        assertEquals(1234, bridge.getFederationSize(new Object[]{}).intValue());
    }

    @Test
    void getFederationThreshold() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.getActiveFederationThreshold()).thenReturn(5678);

        assertEquals(5678, bridge.getFederationThreshold(new Object[]{}).intValue());
    }

    @Test
    void getFederationCreationBlockNumber() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.getActiveFederationCreationBlockNumber()).thenReturn(42L);

        assertEquals(42L, bridge.getFederationCreationBlockNumber(new Object[]{}));
    }

    @Test
    void getFederatorPublicKeyOfType_afterMultikey() throws Exception {

        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
        // since RSKIP88 this is a local call only method
        host.localCall(true);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);

        when(bridgeSupportMock.getActiveFederatorPublicKeyOfType(any(int.class), any(FederationMember.KeyType.class))).then((InvocationOnMock invocation) ->
                BigInteger.valueOf(invocation.<Number>getArgument(0).longValue()).toString()
                        .concat((invocation.<FederationMember.KeyType>getArgument(1)).getValue()).getBytes()
        );

        assertArrayEquals(
            "10btc".getBytes(),
            (byte[]) BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().decodeResult(bridge.execute(BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().encode(BigInteger.valueOf(10), "btc")))[0]
        );

        assertArrayEquals(
            "200rsk".getBytes(),
            (byte[]) BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().decodeResult(bridge.execute(BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().encode(BigInteger.valueOf(200), "rsk")))[0]
        );

        assertArrayEquals(
            "172mst".getBytes(),
            (byte[]) BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().decodeResult(bridge.execute(BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().encode(BigInteger.valueOf(172), "mst")))[0]
        );
    }

    @Test
    void getRetiringFederationSize() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.getRetiringFederationSize()).thenReturn(1234);

        assertEquals(1234, bridge.getRetiringFederationSize(new Object[]{}).intValue());
    }

    @Test
    void getRetiringFederationThreshold() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.getRetiringFederationThreshold()).thenReturn(5678);

        assertEquals(5678, bridge.getRetiringFederationThreshold(new Object[]{}).intValue());
    }

    @Test
    void getRetiringFederationCreationBlockNumber() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.getRetiringFederationCreationBlockNumber()).thenReturn(42L);

        assertEquals(42L, bridge.getRetiringFederationCreationBlockNumber(new Object[]{}));
    }

    @Test
    void getRetiringFederatorPublicKeyOfType_afterMultikey() throws Exception {
        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
        // since RSKIP88 this is a local call only method
        host.localCall(true);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);


        when(bridgeSupportMock.getRetiringFederatorPublicKeyOfType(any(int.class), any(FederationMember.KeyType.class))).then((InvocationOnMock invocation) ->
                BigInteger.valueOf(invocation.<Number>getArgument(0).longValue()).toString()
                        .concat((invocation.<FederationMember.KeyType>getArgument(1)).getValue()).getBytes()
        );

        assertTrue(Arrays.equals("10btc".getBytes(),
                (byte[]) BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().decodeResult(
                        bridge.execute(BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().encode(new Object[]{BigInteger.valueOf(10), "btc"}))
                )[0]
        ));

        assertTrue(Arrays.equals("105rsk".getBytes(),
                (byte[]) BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().decodeResult(
                        bridge.execute(BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().encode(new Object[]{BigInteger.valueOf(105), "rsk"}))
                )[0]
        ));

        assertTrue(Arrays.equals("232mst".getBytes(),
                (byte[]) BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().decodeResult(
                        bridge.execute(BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().encode(new Object[]{BigInteger.valueOf(232), "mst"}))
                )[0]
        ));
    }

    @Test
    void getPendingFederationSize() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.getPendingFederationSize()).thenReturn(1234);

        assertEquals(1234, bridge.getPendingFederationSize(new Object[]{}).intValue());
    }

    @Test
    void getPendingFederatorPublicKeyOfType_afterMultikey() throws Exception {
        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
        // since RSKIP88 this is a local call only method
        host.localCall(true);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);


        when(bridgeSupportMock.getPendingFederatorPublicKeyOfType(any(int.class), any(FederationMember.KeyType.class))).then((InvocationOnMock invocation) ->
                BigInteger.valueOf(invocation.<Number>getArgument(0).longValue()).toString()
                        .concat((invocation.<FederationMember.KeyType>getArgument(1)).getValue()).getBytes()
        );

        assertArrayEquals("10btc".getBytes(), (byte[]) BridgeMethods.GET_PENDING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().decodeResult(
            bridge.execute(BridgeMethods.GET_PENDING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().encode(new Object[]{BigInteger.valueOf(10), "btc"}))
        )[0]);

        assertArrayEquals("82rsk".getBytes(), (byte[]) BridgeMethods.GET_PENDING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().decodeResult(
            bridge.execute(BridgeMethods.GET_PENDING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().encode(new Object[]{BigInteger.valueOf(82), "rsk"}))
        )[0]);

        assertArrayEquals(
            "123mst".getBytes(),
            (byte[]) BridgeMethods.GET_PENDING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().decodeResult(
                bridge.execute(BridgeMethods.GET_PENDING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction().encode(BigInteger.valueOf(123), "mst"))
            )[0]
        );
    }

    @Test
    void createFederation() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.voteFederationChange(any(), eq(new ABICallSpec("create", new byte[][]{})))).thenReturn(123);

        assertEquals(123, bridge.createFederation(new Object[]{}).intValue());
    }

    @Test
    void addFederatorPublicKeyMultikey_afterMultikey() throws Exception {


        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);


        when(bridgeSupportMock.voteFederationChange(any(), eq(new ABICallSpec("add-multi", new byte[][]{
                Hex.decode("aabb"), Hex.decode("ccdd"), Hex.decode("eeff")
        })))).thenReturn(123);

        assertEquals(123,
                ((BigInteger) BridgeMethods.ADD_FEDERATOR_PUBLIC_KEY_MULTIKEY.getFunction().decodeResult(
                        bridge.execute(BridgeMethods.ADD_FEDERATOR_PUBLIC_KEY_MULTIKEY.getFunction().encode(new Object[]{
                                Hex.decode("aabb"), Hex.decode("ccdd"), Hex.decode("eeff")
                        }))
                )[0]).intValue()
        );
    }

    @Test
    void commitFederation_ok() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);

        when(bridgeSupportMock.voteFederationChange(any(), eq(new ABICallSpec("commit", new byte[][]{Hex.decode("01020304")})))).thenReturn(123);

        assertEquals(123, bridge.commitFederation(new Object[]{Hex.decode("01020304")}).intValue());
    }

    @Test
    void commitFederation_wrongParameterType() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);

        assertEquals(-10, bridge.commitFederation(new Object[]{"i'm not a byte array"}).intValue());
        verify(bridgeSupportMock, never()).voteFederationChange(any(), any());
    }

    @Test
    void rollbackFederation() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.voteFederationChange(any(), eq(new ABICallSpec("rollback", new byte[][]{})))).thenReturn(456);

        assertEquals(456, bridge.rollbackFederation(new Object[]{}).intValue());
    }

    @Test
    void getFeePerKb() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.getFeePerKb())
                .thenReturn(Coin.valueOf(12345678901234L));

        assertEquals(12345678901234L, bridge.getFeePerKb(new Object[]{}));
    }

    @Test
    void voteFeePerKb_ok() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.voteFeePerKbChange(any(), eq(Coin.valueOf(2))))
                .thenReturn(123);

        assertEquals(123, bridge.voteFeePerKbChange(new Object[]{BigInteger.valueOf(2)}).intValue());
    }

    @Test
    void voteFeePerKb_wrongParameterType() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);

        assertEquals(-10, bridge.voteFeePerKbChange(new Object[]{"i'm not a byte array"}).intValue());
        verify(bridgeSupportMock, never()).voteFederationChange(any(), any());
    }

    @Test
    void precompiledContractAddress() {
        byte[] bridgeAddressSerialized = BRIDGE_ADDRESS.getBytes().toArrayUnsafe();

        Assertions.assertArrayEquals(
                bridgeAddressSerialized,
                Hex.decode(BRIDGE_ADDRESS_TO_STRING));
    }

    @Test
    void executeMethodWithOnlyLocalCallsAllowed_localCallTx() throws Exception {
        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
        // since RSKIP88 this is a local call only method
        host.localCall(true);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);

        Address address = new BtcECKey().toAddress(regtestParameters);
        when(bridgeSupportMock.getActiveFederationAddress()).thenReturn(address);


        Bytes data = BridgeMethods.GET_FEDERATION_ADDRESS.getFunction().encode(new Object[]{});
        bridge.execute(data);

        verify(bridgeSupportMock, times(1)).getActiveFederationAddress();
    }

    @Test
    void executeMethodWithOnlyLocalCallsAllowed_nonLocalCallTx() {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        try {
            BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
            when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
            Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);

            Bytes data = BridgeMethods.GET_FEDERATION_ADDRESS.getFunction().encode(new Object[]{});
            bridge.execute(data);
            fail();
        } catch (VMException e) {
            verify(bridgeSupportMock, never()).getActiveFederationAddress();
            assertTrue(e.getMessage().contains("Non-local-call"));
        }
    }

    @Test
    void executeMethodWithAnyCallsAllowed_localCallTx() throws Exception {
        executeAndCheckMethodWithAnyCallsAllowed(true);
    }

    @Test
    void executeMethodWithAnyCallsAllowed_nonLocalCallTx() throws Exception {
        executeAndCheckMethodWithAnyCallsAllowed(false);
    }

    @Test
    void getBtcTransactionConfirmationsAfterWasabi_ok() throws Exception {
        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        BiFunction<List<Sha256Hash>, Integer, MerkleBranch> merkleBranchFactory = mock(BiFunction.class);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), merkleBranchFactory,host);


        byte[] btcTxHash = Sha256Hash.of(Hex.decode("aabbcc")).getBytes();
        byte[] btcBlockHash = Sha256Hash.of(Hex.decode("ddeeff")).getBytes();
        byte[][] merkleBranchHashes = new byte[][]{
                Sha256Hash.of(Hex.decode("11")).getBytes(),
                Sha256Hash.of(Hex.decode("22")).getBytes(),
                Sha256Hash.of(Hex.decode("33")).getBytes(),
        };
        BigInteger merkleBranchBits = BigInteger.valueOf(123);

        MerkleBranch merkleBranch = mock(MerkleBranch.class);
        when(merkleBranchFactory.apply(any(), any())).then((Answer<MerkleBranch>) invocation -> {
            // Check constructor parameters are correct

            List<Sha256Hash> hashes = invocation.getArgument(0);
            Assertions.assertArrayEquals(merkleBranchHashes[0], hashes.get(0).getBytes());
            Assertions.assertArrayEquals(merkleBranchHashes[1], hashes.get(1).getBytes());
            Assertions.assertArrayEquals(merkleBranchHashes[2], hashes.get(2).getBytes());

            Integer bits = invocation.getArgument(1);
            assertEquals(123, bits.intValue());

            return merkleBranch;
        });

        when(bridgeSupportMock.getBtcTransactionConfirmations(any(Sha256Hash.class), any(Sha256Hash.class), any(MerkleBranch.class))).then((Answer<Integer>) invocation -> {
            // Check parameters are correct
            Sha256Hash txHash = invocation.getArgument(0);
            Assertions.assertArrayEquals(btcTxHash, txHash.getBytes());

            Sha256Hash blockHash = invocation.getArgument(1);
            Assertions.assertArrayEquals(btcBlockHash, blockHash.getBytes());

            MerkleBranch merkleBranchArg = invocation.getArgument(2);
            assertEquals(merkleBranch, merkleBranchArg);

            return 78;
        });

        assertEquals(78, bridge.getBtcTransactionConfirmations(new Object[]{
                btcTxHash,
                btcBlockHash,
                merkleBranchBits,
                merkleBranchHashes
        }));
    }

    @Test
    void getBtcTransactionConfirmationsAfterWasabi_errorInBridgeSupport() throws Exception {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        BiFunction<List<Sha256Hash>, Integer, MerkleBranch> merkleBranchFactory = mock(BiFunction.class);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = spy(new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), merkleBranchFactory,host));

        byte[] btcTxHash = Sha256Hash.of(Hex.decode("aabbcc")).getBytes();
        byte[] btcBlockHash = Sha256Hash.of(Hex.decode("ddeeff")).getBytes();
        byte[][] merkleBranchHashes = new byte[][]{
                Sha256Hash.of(Hex.decode("11")).getBytes(),
                Sha256Hash.of(Hex.decode("22")).getBytes(),
                Sha256Hash.of(Hex.decode("33")).getBytes(),
        };
        BigInteger merkleBranchBits = BigInteger.valueOf(123);

        MerkleBranch merkleBranch = mock(MerkleBranch.class);
        when(merkleBranchFactory.apply(any(), any())).then((Answer<MerkleBranch>) invocation -> {
            // Check constructor parameters are correct

            List<Sha256Hash> hashes = invocation.getArgument(0);
            Assertions.assertArrayEquals(merkleBranchHashes[0], hashes.get(0).getBytes());
            Assertions.assertArrayEquals(merkleBranchHashes[1], hashes.get(1).getBytes());
            Assertions.assertArrayEquals(merkleBranchHashes[2], hashes.get(2).getBytes());

            Integer bits = invocation.getArgument(1);
            assertEquals(123, bits.intValue());

            return merkleBranch;
        });

        when(bridgeSupportMock.getBtcTransactionConfirmations(any(Sha256Hash.class), any(Sha256Hash.class), any(MerkleBranch.class))).then((Answer<Integer>) invocation -> {
            // Check parameters are correct
            Sha256Hash txHash = invocation.getArgument(0);
            Assertions.assertArrayEquals(btcTxHash, txHash.getBytes());

            Sha256Hash blockHash = invocation.getArgument(1);
            Assertions.assertArrayEquals(btcBlockHash, blockHash.getBytes());

            MerkleBranch merkleBranchArg = invocation.getArgument(2);
            assertEquals(merkleBranch, merkleBranchArg);

            throw new VMException("bla bla bla");
        });

        try {
            bridge.getBtcTransactionConfirmations(new Object[]{
                    btcTxHash,
                    btcBlockHash,
                    merkleBranchBits,
                    merkleBranchHashes
            });
            fail();
        } catch (VMException e) {
            assertTrue(e.getMessage().contains("in getBtcTransactionConfirmations"));
            assertEquals(VMException.class, e.getCause().getClass());
            assertTrue(e.getCause().getMessage().contains("bla bla bla"));
        }
    }

    @Test
    void getBtcTransactionConfirmationsAfterWasabi_merkleBranchConstructionError() throws Exception {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        BiFunction<List<Sha256Hash>, Integer, MerkleBranch> merkleBranchFactory = mock(BiFunction.class);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = spy(new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), merkleBranchFactory,host));

        byte[] btcTxHash = Sha256Hash.of(Hex.decode("aabbcc")).getBytes();
        byte[] btcBlockHash = Sha256Hash.of(Hex.decode("ddeeff")).getBytes();
        byte[][] merkleBranchHashes = new byte[][]{
                Sha256Hash.of(Hex.decode("11")).getBytes(),
                Sha256Hash.of(Hex.decode("22")).getBytes(),
                Sha256Hash.of(Hex.decode("33")).getBytes(),
        };
        BigInteger merkleBranchBits = BigInteger.valueOf(123);

        when(merkleBranchFactory.apply(any(), any())).then((Answer<MerkleBranch>) invocation -> {
            // Check constructor parameters are correct

            List<Sha256Hash> hashes = invocation.getArgument(0);
            Assertions.assertArrayEquals(merkleBranchHashes[0], hashes.get(0).getBytes());
            Assertions.assertArrayEquals(merkleBranchHashes[1], hashes.get(1).getBytes());
            Assertions.assertArrayEquals(merkleBranchHashes[2], hashes.get(2).getBytes());

            Integer bits = invocation.getArgument(1);
            assertEquals(123, bits.intValue());

            throw new IllegalArgumentException("blabla");
        });

        try {
            bridge.getBtcTransactionConfirmations(new Object[]{
                    btcTxHash,
                    btcBlockHash,
                    merkleBranchBits,
                    merkleBranchHashes
            });
            fail();
        } catch (VMException e) {
            assertTrue(e.getMessage().contains("in getBtcTransactionConfirmations"));
            assertEquals(IllegalArgumentException.class, e.getCause().getClass());
            verify(bridgeSupportMock, never()).getBtcTransactionConfirmations(any(), any(), any());
        }
    }

    @Test
    void getBtcTransactionConfirmations_gasCost() {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = spy(new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host));

        try (MockedStatic<BridgeUtils> bridgeUtilsMocked = mockStatic(BridgeUtils.class)) {

            byte[] btcTxHash = Sha256Hash.of(Hex.decode("aabbcc")).getBytes();
            byte[] btcBlockHash = Sha256Hash.of(Hex.decode("ddeeff")).getBytes();
            byte[][] merkleBranchHashes = new byte[][]{
                    Sha256Hash.of(Hex.decode("11")).getBytes(),
                    Sha256Hash.of(Hex.decode("22")).getBytes(),
                    Sha256Hash.of(Hex.decode("33")).getBytes(),
            };
            BigInteger merkleBranchBits = BigInteger.valueOf(123);

            Object[] args = new Object[]{
                    btcTxHash,
                    btcBlockHash,
                    merkleBranchBits,
                    merkleBranchHashes
            };

            when(bridgeSupportMock.getBtcTransactionConfirmationsGetCost(eq(args))).thenReturn(1234L); // NOSONAR: eq is needed
            AbiFunction fn = BridgeMethods.GET_BTC_TRANSACTION_CONFIRMATIONS.getFunction();

            Bytes data = fn.encode(args);

            assertEquals(2 * data.size() + 1234L, bridge.getGasForData(data));
        }
    }

    @Test
    void getBtcBlockchainBlockHashAtDepth() throws BlockStoreException, IOException, VMException {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        Sha256Hash mockedResult = Sha256Hash.of(Hex.decode("aabbcc"));
        when(bridgeSupportMock.getBtcBlockchainBlockHashAtDepth(555)).thenReturn(mockedResult);

        assertEquals(mockedResult, Sha256Hash.wrap(bridge.getBtcBlockchainBlockHashAtDepth(new Object[]{BigInteger.valueOf(555)})));
    }

    @Test
    void localCallOnlyMethodsDefinition() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, mock(BridgeSupportFactory.class), host);

        // Actual tests
        Arrays.asList(
                BridgeMethods.GET_BTC_BLOCKCHAIN_INITIAL_BLOCK_HEIGHT,
                BridgeMethods.GET_BTC_BLOCKCHAIN_BLOCK_HASH_AT_DEPTH,
                BridgeMethods.GET_BTC_TX_HASH_PROCESSED_HEIGHT,
                BridgeMethods.GET_FEDERATION_ADDRESS,
                BridgeMethods.GET_FEDERATION_CREATION_BLOCK_NUMBER,
                BridgeMethods.GET_FEDERATION_CREATION_TIME,
                BridgeMethods.GET_FEDERATION_SIZE,
                BridgeMethods.GET_FEDERATION_THRESHOLD,
                BridgeMethods.GET_FEE_PER_KB,
                BridgeMethods.GET_MINIMUM_LOCK_TX_VALUE,
                BridgeMethods.GET_PENDING_FEDERATION_HASH,
                BridgeMethods.GET_PENDING_FEDERATION_SIZE,
                BridgeMethods.GET_RETIRING_FEDERATION_ADDRESS,
                BridgeMethods.GET_RETIRING_FEDERATION_CREATION_BLOCK_NUMBER,
                BridgeMethods.GET_RETIRING_FEDERATION_CREATION_TIME,
                BridgeMethods.GET_RETIRING_FEDERATION_SIZE,
                BridgeMethods.GET_RETIRING_FEDERATION_THRESHOLD,
                BridgeMethods.GET_STATE_FOR_BTC_RELEASE_CLIENT,
                BridgeMethods.GET_STATE_FOR_DEBUGGING,
                BridgeMethods.IS_BTC_TX_HASH_ALREADY_PROCESSED
        ).forEach(m -> {
            assertTrue(m.onlyAllowsLocalCalls(bridge, new Object[0]));
        });
    }

    @Test
    void mineableMethodsDefinition() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, mock(BridgeSupportFactory.class), host);

        // Actual tests
        Arrays.asList(
                BridgeMethods.ADD_SIGNATURE,
                BridgeMethods.COMMIT_FEDERATION,
                BridgeMethods.CREATE_FEDERATION,
                BridgeMethods.RECEIVE_HEADERS,
                BridgeMethods.REGISTER_BTC_TRANSACTION,
                BridgeMethods.RELEASE_BTC,
                BridgeMethods.ROLLBACK_FEDERATION,
                BridgeMethods.UPDATE_COLLECTIONS,
                BridgeMethods.VOTE_FEE_PER_KB,
                BridgeMethods.GET_ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT
        ).stream().forEach(m -> {
            Assertions.assertFalse(m.onlyAllowsLocalCalls(bridge, new Object[0]));
        });
    }

    @Test
    void getBtcBlockchainBestChainHeight_isMineable() {
        Bridge bridge = getBridgeInstance();

        Assertions.assertFalse(BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.onlyAllowsLocalCalls(bridge, new Object[0]));
    }

    @Test
    void receiveHeadersAccess_noAccessIfNotFromFederationMember() throws Exception {

        org.hyperledger.besu.datatypes.Address sender = org.hyperledger.besu.datatypes.Address.fromHexString("2acc95758f8b5f583470ba265eb685a8f45fc9d5");

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getRetiringFederation()).thenReturn(null);
        when(bridgeSupportMock.getActiveFederation()).thenReturn(FederationTestUtils.getGenesisFederation(bridgeRegTestConstants.getFederationConstants()));

        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        InMemoryBridgeHost host = new InMemoryBridgeHost().origin(sender).caller(sender);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);

        byte[][] headers = new byte[][]{Hex.decode(
                "0000002006226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910ff698ee112158f5573a90b7403cfba074addd61c547b3639c6afdcf52588eb8e2a1ef825cffff7f2000000000"
        )};

        Bytes data = BridgeMethods.RECEIVE_HEADERS.getFunction().encode(new Object[]{headers});

        try {
            bridge.execute(data);
            fail();
        } catch (VMException e) {
            assertTrue(e.getMessage().contains("The sender is not a member of the active"));
        }
        verify(bridgeSupportMock, never()).receiveHeaders(any(BtcBlock[].class));
    }

    @Test
    void receiveHeadersAccess_accessIfFromFederationMember() throws Exception {

        org.hyperledger.besu.datatypes.Address sender = PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0));

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getRetiringFederation()).thenReturn(null);
        when(bridgeSupportMock.getActiveFederation()).thenReturn(FederationTestUtils.getGenesisFederation(bridgeRegTestConstants.getFederationConstants()));

        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        InMemoryBridgeHost host = new InMemoryBridgeHost().origin(sender).caller(sender);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);

        byte[][] headers = new byte[][]{Hex.decode(
                "0000002006226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910ff698ee112158f5573a90b7403cfba074addd61c547b3639c6afdcf52588eb8e2a1ef825cffff7f2000000000"
        )};

        Bytes data = BridgeMethods.RECEIVE_HEADERS.getFunction().encode(new Object[]{headers});

        assertEquals(Bytes.EMPTY, bridge.execute(data));
        verify(bridgeSupportMock, times(1)).receiveHeaders(any(BtcBlock[].class));
    }

    @Test
    void bridgeSupportIsCreatedOnConstruction() {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);

        // A bridge serves one call, so it takes its support from the factory at construction
        // rather than through the init method RSKj called before each execution.
        verify(bridgeSupportFactoryMock, times(1)).newInstance(host);
        assertSame(bridgeSupportMock, getInternalState(bridge, "bridgeSupport"));
    }

    private void executeAndCheckMethodWithAnyCallsAllowed(boolean localCall) throws Exception {

        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);

        InMemoryBridgeHost host = new InMemoryBridgeHost().localCall(localCall);
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(bridgeSupportMock);
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, host);


        when(bridgeSupportMock.voteFeePerKbChange(any(), eq(Coin.CENT))).thenReturn(1);


        Bytes data = BridgeMethods.VOTE_FEE_PER_KB.getFunction().encode(new Object[]{Coin.CENT.longValue()});
        bridge.execute(data);

        verify(bridgeSupportMock, times(1)).voteFeePerKbChange(any(), eq(Coin.CENT));
    }

    @Test
    void getBtcBlockchainInitialBlockHeight() throws IOException, VMException {
        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = new Bridge(bridgeRegTestConstants, factoryReturning(bridgeSupportMock), host);
        when(bridgeSupportMock.getBtcBlockchainInitialBlockHeight()).thenReturn(1234);

        assertEquals(1234, bridge.getBtcBlockchainInitialBlockHeight(new Object[]{}).intValue());
    }

    private BtcTransaction createTransaction() {
        return createTransaction(BitcoinTestUtils.createHash(1));
    }

    private BtcTransaction createTransaction(Sha256Hash hash) {
        return new SimpleBtcTransaction(regtestParameters, hash);
    }

    private BtcTransaction createTransaction(int toPk, Coin value) {
        return createTransaction(toPk, value, new BtcECKey());
    }

    private BtcTransaction createTransaction(int toPk, Coin value, BtcECKey btcECKey) {
        NetworkParameters params = NetworkParameters.fromID(NetworkParameters.ID_REGTEST);
        BtcTransaction input = new BtcTransaction(params);

        input.addOutput(Coin.COIN, btcECKey.toAddress(params));

        Address to = BtcECKey.fromPrivate(BigInteger.valueOf(toPk)).toAddress(params);

        BtcTransaction result = new BtcTransaction(params);
        result.addInput(input.getOutput(0));
        result.getInput(0).disconnect();
        result.addOutput(value, to);
        return result;
    }



    private void registerBtcTransactionWithHugeDeclaredSize(BtcTransaction tx) throws VMException {
        InMemoryBridgeHost repository = new InMemoryBridgeHost();
        InMemoryBridgeHost track = repository;


        BridgeSupportFactory bridgeSupportFactory = new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(regtestParameters), bridgeRegTestConstants);
        track.origin(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).caller(PublicKeys.addressOf(REGTEST_FEDERATION_PRIVATE_KEYS.get(0))).callValue(Wei.of(AMOUNT));
        Bridge bridge = new Bridge(bridgeRegTestConstants, bridgeSupportFactory, track);

        byte[] serializedTx = tx.bitcoinSerialize();

        Bytes data = BridgeMethods.REGISTER_BTC_TRANSACTION.getFunction().encode(serializedTx, 1, new byte[30]);

        assertEquals(Bytes.EMPTY, bridge.execute(data));
    }

    private Bridge getBridgeInstance() {
        BridgeSupportFactory bridgeSupportFactoryMock = mock(BridgeSupportFactory.class);
        when(bridgeSupportFactoryMock.newInstance(any())).thenReturn(mock(BridgeSupport.class));

        return new Bridge(bridgeRegTestConstants, bridgeSupportFactoryMock, new InMemoryBridgeHost());
    }

    private static class HugeDeclaredSizeBtcTransaction extends BtcTransaction {

        private final boolean hackInputsSize;
        private final boolean hackOutputsSize;
        private final boolean hackWitnessPushCountSize;
        private final boolean hackWitnessPushSize;

        public HugeDeclaredSizeBtcTransaction(NetworkParameters params, boolean hackInputsSize, boolean hackOutputsSize, boolean hackWitnessPushCountSize, boolean hackWitnessPushSize) {
            super(params);
            BtcTransaction inputTx = new BtcTransaction(params);
            inputTx.addOutput(Coin.FIFTY_COINS, BtcECKey.fromPrivate(BigInteger.valueOf(123456)).toAddress(params));
            Address to = BtcECKey.fromPrivate(BigInteger.valueOf(1000)).toAddress(params);
            this.addInput(inputTx.getOutput(0));
            this.getInput(0).disconnect();
            TransactionWitness witness = new TransactionWitness(1);
            witness.setPush(0, new byte[]{0});
            this.setWitness(0, witness);
            this.addOutput(Coin.COIN, to);

            this.hackInputsSize = hackInputsSize;
            this.hackOutputsSize = hackOutputsSize;
            this.hackWitnessPushCountSize = hackWitnessPushCountSize;
            this.hackWitnessPushSize = hackWitnessPushSize;
        }

        @Override
        protected void bitcoinSerializeToStream(OutputStream stream, boolean serializeWitRequested) throws IOException {
            boolean serializeWit = serializeWitRequested && hasWitness();
            uint32ToByteStreamLE(getVersion(), stream);
            if (serializeWit) {
                stream.write(new byte[]{0, 1});
            }

            long inputsSize = hackInputsSize ? Integer.MAX_VALUE : getInputs().size();
            stream.write(new VarInt(inputsSize).encode());
            for (TransactionInput in : getInputs()) {
                in.bitcoinSerialize(stream);
            }
            long outputsSize = hackOutputsSize ? Integer.MAX_VALUE : getOutputs().size();
            stream.write(new VarInt(outputsSize).encode());
            for (TransactionOutput out : getOutputs()) {
                out.bitcoinSerialize(stream);
            }
            if (serializeWit) {
                for (int i = 0; i < getInputs().size(); i++) {
                    TransactionWitness witness = getWitness(i);
                    long pushCount = hackWitnessPushCountSize ? Integer.MAX_VALUE : witness.getPushCount();
                    stream.write(new VarInt(pushCount).encode());
                    for (int y = 0; y < witness.getPushCount(); y++) {
                        byte[] push = witness.getPush(y);
                        long pushLength = hackWitnessPushSize ? Integer.MAX_VALUE : push.length;
                        stream.write(new VarInt(pushLength).encode());
                        stream.write(push);
                    }
                }
            }
            uint32ToByteStreamLE(getLockTime(), stream);
        }
    }
    /** RSKj replaced the bridge's support after construction; here the factory hands it the one the test wants. */
    private static BridgeSupportFactory factoryReturning(BridgeSupport bridgeSupport) {
        BridgeSupportFactory factory = mock(BridgeSupportFactory.class);
        when(factory.newInstance(any())).thenReturn(bridgeSupport);
        return factory;
    }

    /** RSKj used {@code TestUtils.setInternalState}; a couple of the bridge's fields are still only reachable this way. */
    private static void setInternalState(Object instance, String fieldName, Object value) {
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not set " + fieldName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getInternalState(Object instance, String fieldName) {
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read " + fieldName, e);
        }
    }

}
