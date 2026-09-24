package co.rsk.peg;

import static co.rsk.peg.PegTestUtils.createHash3;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.abi.AbiFunction;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.exception.VMException;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.FederationMember.KeyType;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.CallKind;
import co.rsk.peg.utils.PublicKeys;
import co.rsk.test.builders.BridgeBuilder;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.bouncycastle.util.encoders.Hex;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BridgeTest {
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[]{};

    private NetworkParameters networkParameters;
    private BridgeBuilder bridgeBuilder;
    private final BridgeConstants bridgeMainNetConstants = BridgeMainNetConstants.getInstance();

    @BeforeEach
    void resetConfigToMainnet() {
        networkParameters = BridgeMainNetConstants.getInstance().getBtcParams();
        bridgeBuilder = new BridgeBuilder();
    }

    @Test
    void getActivePowpegRedeemScript_after_RSKIP293_activation() throws VMException {
        AbiFunction getActivePowpegRedeemScriptFunction = BridgeMethods.GET_ACTIVE_POWPEG_REDEEM_SCRIPT.getFunction();

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Script activePowpegRedeemScript = FederationTestUtils.getGenesisFederation(
            bridgeMainNetConstants.getFederationConstants()).getRedeemScript();
        when(bridgeSupportMock.getActiveFederationRedeemScript()).thenReturn(
            Optional.of(activePowpegRedeemScript)
        );

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        Bytes data = getActivePowpegRedeemScriptFunction.encode();
        Bytes result = bridge.execute(data);
        byte[] decodedResult = (byte[]) getActivePowpegRedeemScriptFunction.decodeResult(result)[0];
        Script obtainedRedeemScript = new Script(decodedResult);

        assertEquals(activePowpegRedeemScript, obtainedRedeemScript);
    }

    @Test
    void getLockingCap_after_RSKIP134_activation() throws VMException {
        AbiFunction getLockingCapFunction = BridgeMethods.GET_LOCKING_CAP.getFunction();

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Coin lockingCap = Coin.COIN;
        when(bridgeSupportMock.getLockingCap()).thenReturn(lockingCap);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .localCall(true)
            .build();

        Bytes data = getLockingCapFunction.encode();
        Bytes result = bridge.execute(data);
        BigInteger decodedResult = (BigInteger) getLockingCapFunction.decodeResult(result)[0];
        Coin obtainedLockingCap = Coin.valueOf(decodedResult.longValue());

        assertEquals(lockingCap, obtainedLockingCap);

        // Also test the method itself
        long lockingCapFromTheBridge = bridge.getLockingCap(new Object[]{});
        assertEquals(lockingCap.getValue(), lockingCapFromTheBridge);
    }

    @ParameterizedTest()
    @MethodSource("lockingCapValues")
    void increaseLockingCap_after_RSKIP134_activation(long newLockingCapValue) throws VMException {
        AbiFunction increaseLockingCapFunction = BridgeMethods.INCREASE_LOCKING_CAP.getFunction();

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.increaseLockingCap(any(), any())).thenReturn(true);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        Bytes data = increaseLockingCapFunction.encode(newLockingCapValue);
        Bytes result = bridge.execute(data);
        boolean decodedResult = (boolean) increaseLockingCapFunction.decodeResult(result)[0];

        assertTrue(decodedResult);

        // Also test the method itself
        boolean resultFromTheBridge = bridge.increaseLockingCap(new Object[]{BigInteger.valueOf(newLockingCapValue)});
        assertTrue(resultFromTheBridge);
    }

    private static Stream<Arguments> lockingCapValues() {
        return Stream.of(
            Arguments.of(1),
            Arguments.of(21_000_0000)
        );
    }

    @Test
    void increaseLockingCap_whenNewLockingCapIsInvalidParameter_shouldThrowVMException() {
        AbiFunction increaseLockingCapFunction = BridgeMethods.INCREASE_LOCKING_CAP.getFunction();
        Bridge bridge = bridgeBuilder
            .build();

        // Uses the proper signature but appends invalid data type
        // This will be rejected by the solidity decoder in the Bridge directly
        final Bytes invalidTypeData = Bytes.concatenate(increaseLockingCapFunction.encodeSignature(), Bytes.wrap(Hex.decode("ab")));
        assertThrows(VMException.class, () -> bridge.execute(invalidTypeData));

        // Uses the proper signature and data type, but with a value that exceeds the long max value
        final Bytes aboveMaxLengthData = Bytes.concatenate(increaseLockingCapFunction.encodeSignature(), Bytes.wrap(Hex.decode("0000000000000000000000000000000000000000000000080000000000000000")));
        assertThrows(VMException.class, () -> bridge.execute(aboveMaxLengthData));
    }

    @Test
    void increaseLockingCap_whenNoArgumentsInTheMethodSignature_shouldThrowVMException() {
        // Arrange
        AbiFunction increaseLockingCapFunction = BridgeMethods.INCREASE_LOCKING_CAP.getFunction();
        Bridge bridge = bridgeBuilder
            .build();

        // No arguments signature
        final Bytes noArgumentData = Bytes.EMPTY;

        // Act / Assert
        assertThrows(VMException.class, () -> bridge.execute(noArgumentData));
    }

    @Test
    void increaseLockingCap_whenNewLockingCapIsNegativeValue_shouldThrowVMException() {
        // Arrange
        AbiFunction increaseLockingCapFunction = BridgeMethods.INCREASE_LOCKING_CAP.getFunction();
        Bridge bridge = bridgeBuilder
            .build();

        // When new LockingCap is a negative value
        final Bytes negativeValueData = increaseLockingCapFunction.encode(Coin.NEGATIVE_SATOSHI.getValue()).slice(4);

        // Act / Assert
        assertThrows(VMException.class, () -> bridge.execute(negativeValueData));
    }

    @Test
    void increaseLockingCap_whenNewLockingCapIsZeroValue_shouldThrowVMException() {
        // Arrange
        AbiFunction increaseLockingCapFunction = BridgeMethods.INCREASE_LOCKING_CAP.getFunction();
        Bridge bridge = bridgeBuilder
            .build();

        // When new LockingCap is a zero value
        final Bytes negativeValueData = increaseLockingCapFunction.encode(Coin.ZERO.getValue()).slice(4);

        // Act / Assert
        assertThrows(VMException.class, () -> bridge.execute(negativeValueData));
    }

    @Test
    void registerBtcCoinbaseTransaction_after_RSKIP143_activation() throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        byte[] value = Sha256Hash.ZERO_HASH.getBytes();

        Bytes data = BridgeMethods.REGISTER_BTC_COINBASE_TRANSACTION.getFunction().encode(value, value, value, value, value);

        bridge.execute(data);
        verify(bridgeSupportMock, times(1)).registerBtcCoinbaseTransaction(
            value,
            Sha256Hash.wrap(value),
            value,
            Sha256Hash.wrap(value),
            value
        );
    }

    @Test
    void registerBtcCoinbaseTransaction_after_RSKIP143_activation_null_data() {
        AbiFunction registerBtcCoinbaseTransactionFunction = BridgeMethods.REGISTER_BTC_COINBASE_TRANSACTION.getFunction();

        Bridge bridge = bridgeBuilder
            .build();

        final Bytes emptyData = registerBtcCoinbaseTransactionFunction.encodeSignature();
        assertThrows(VMException.class, () -> bridge.execute(emptyData));

        final Bytes invalidStringData = Bytes.concatenate(registerBtcCoinbaseTransactionFunction.encodeSignature(), Bytes.wrap(Hex.decode("ab")));
        assertThrows(VMException.class, () -> bridge.execute(invalidStringData));

        final Bytes invalidHexData = Bytes.concatenate(registerBtcCoinbaseTransactionFunction.encodeSignature(), Bytes.wrap(Hex.decode("0000000000000000000000000000000000000000000000080000000000000000")));
        assertThrows(VMException.class, () -> bridge.execute(invalidHexData));
    }

    @Test
    void registerBtcTransaction_afterRskip199_acceptsExternalCalls() throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        byte[] value = Sha256Hash.ZERO_HASH.getBytes();
        int zero = 0;
        Bytes data = BridgeMethods.REGISTER_BTC_TRANSACTION.getFunction().encode(value, zero, value);

        bridge.execute(data);

        verify(bridgeSupportMock, times(1)).registerBtcTransaction(
            any(CallContext.class),
            any(byte[].class),
            anyInt(),
            any(byte[].class)
        );
    }

    @Test
    void getActiveFederationCreationBlockHeight_after_RSKIP186_activation() throws VMException {

        long activeFederationCreationBlockHeight = 1L;
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getActiveFederationCreationBlockHeight()).thenReturn(activeFederationCreationBlockHeight);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        AbiFunction function = BridgeMethods.GET_ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT.getFunction();
        Bytes data = function.encode();
        Bytes result = bridge.execute(data);
        BigInteger decodedResult = (BigInteger)function.decodeResult(result)[0];

        assertEquals(activeFederationCreationBlockHeight, decodedResult.longValue());

        // Also test the method itself
        long resultFromTheBridge = bridge.getActiveFederationCreationBlockHeight(new Object[]{});
        assertEquals(activeFederationCreationBlockHeight, resultFromTheBridge);
    }

    @Test
    void receiveHeader_empty_parameter() {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        Bytes data = BridgeMethods.RECEIVE_HEADER.getFunction().encodeSignature();

        assertThrows(VMException.class, () -> bridge.execute(data));
        verifyNoInteractions(bridgeSupportMock);
    }

    @Test
    void receiveHeader_after_RSKIP200_NOT_OK() throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        co.rsk.bitcoinj.core.BtcBlock block = new co.rsk.bitcoinj.core.BtcBlock(
            networkParameters,
            1,
            BitcoinTestUtils.createHash(1),
            BitcoinTestUtils.createHash(2),
            1L,
            100L,
            1L,
            new ArrayList<>()
        ).cloneAsHeader();

        AbiFunction function = BridgeMethods.RECEIVE_HEADER.getFunction();

        Bytes data = function.encode(block.bitcoinSerialize());
        
        // Only active and retiring federation members are allowed to execute receiveHeader
        assertThrows(VMException.class, () -> bridge.execute(data));
    }

    @Test
    void receiveHeader_bridgeSupport_Exception() throws IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        doThrow(new IOException()).when(bridgeSupportMock).receiveHeader(any())
        ;
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        co.rsk.bitcoinj.core.BtcBlock block = new co.rsk.bitcoinj.core.BtcBlock(
            networkParameters,
            1,
            BitcoinTestUtils.createHash(1),
            BitcoinTestUtils.createHash(1),
            1,
            Utils.encodeCompactBits(networkParameters.getMaxTarget()),
            1,
            new ArrayList<>()
        ).cloneAsHeader();

        Object[] parameters = new Object[]{block.bitcoinSerialize()};
        Bytes data = BridgeMethods.RECEIVE_HEADER.getFunction().encode(parameters);

        assertThrows(VMException.class, () -> bridge.execute(data));
    }

    @Test
    void receiveHeaders_after_RSKIP200_notFederation() {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Federation genesisFederation = FederationTestUtils.getGenesisFederation(bridgeMainNetConstants.getFederationConstants());

        when(bridgeSupportMock.getRetiringFederation()).thenReturn(null);
        when(bridgeSupportMock.getActiveFederation()).thenReturn(genesisFederation);

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(new BtcECKey());

        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .build();

        Bytes data = BridgeMethods.RECEIVE_HEADERS.getFunction().encode((Object) new byte[][]{});

        assertThrows(VMException.class, () -> bridge.execute(data));
    }

    @Test
    void receiveHeaders_after_RSKIP200_header_wrong_size() throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        int senderPK = 101; // Sender PK belongs to active federation member PKs
        Integer[] activeMemberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Federation activeFederation = FederationTestUtils.getFederation(activeMemberPKs);

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(senderPK)));
        doReturn(activeFederation).when(bridgeSupportMock).getActiveFederation();

        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .build();

        Object[] parameters = new Object[]{Sha256Hash.ZERO_HASH.getBytes()};
        Bytes data = BridgeMethods.RECEIVE_HEADER.getFunction().encode(parameters);

        Bytes result = bridge.execute(data);
        BigInteger decodedResult = (BigInteger) BridgeMethods.RECEIVE_HEADER.getFunction().decodeResult(result)[0];
        assertEquals(BigInteger.valueOf(-20), decodedResult);
    }

    @Test
    void getBtcBlockchainBestChainHeightOnlyAllowsLocalCalls_afterRskip220() {

        Bridge bridge = bridgeBuilder
            .build();

        assertFalse(bridge.getBtcBlockchainBestChainHeightOnlyAllowsLocalCalls(new Object[0]));
    }

    @Test
    void activeAndRetiringFederationOnly_activeFederationIsNotFromFederateMember_retiringFederationIsNull_throwsVMException() {
        // Given
        BridgeMethods.BridgeMethodExecutor executor = Bridge.activeAndRetiringFederationOnly(
            null,
            null
        );

        int senderPK = 999; // Sender PK does not belong to Member PKs
        Integer[] memberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Federation activeFederation = FederationTestUtils.getFederation(memberPKs);

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        doReturn(activeFederation).when(bridgeSupportMock).getActiveFederation();
        doReturn(null).when(bridgeSupportMock).getRetiringFederation();

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(senderPK)));

        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .build();

        assertThrows(VMException.class, () -> executor.execute(bridge, null));
    }

    @Test
    void activeAndRetiringFederationOnly_activeFederationIsNotFromFederateMember_retiringFederationIsNotNull_retiringFederationIsNotFromFederateMember_throwsVMException() {
        // Given
        BridgeMethods.BridgeMethodExecutor executor = Bridge.activeAndRetiringFederationOnly(
            null,
            null
        );

        int senderPK = 999; // Sender PK does not belong to Member PKs of active nor retiring fed
        Integer[] activeMemberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Integer[] retiringMemberPKs = new Integer[]{ 101, 202, 303, 404, 505, 606 };

        Federation activeFederation = FederationTestUtils.getFederation(activeMemberPKs);
        Federation retiringFederation = FederationTestUtils.getFederation(retiringMemberPKs);

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        doReturn(activeFederation).when(bridgeSupportMock).getActiveFederation();
        doReturn(retiringFederation).when(bridgeSupportMock).getRetiringFederation();

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(senderPK)));

        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .build();

        assertThrows(VMException.class, () -> executor.execute(bridge, null));
    }

    @Test
    void activeAndRetiringFederationOnly_activeFederationIsFromFederateMember_OK() throws Exception {
        // Given
        BridgeMethods.BridgeMethodExecutor decorate = mock(
            BridgeMethods.BridgeMethodExecutor.class
        );
        BridgeMethods.BridgeMethodExecutor executor = Bridge.activeAndRetiringFederationOnly(
            decorate,
            null
        );

        int senderPK = 101; // Sender PK belongs to active federation member PKs
        Integer[] memberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Federation activeFederation = FederationTestUtils.getFederation(memberPKs);

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        doReturn(activeFederation).when(bridgeSupportMock).getActiveFederation();
        doReturn(null).when(bridgeSupportMock).getRetiringFederation();

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(senderPK)));

        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .build();

        // When
        executor.execute(bridge, null);

        // Then
        verify(bridgeSupportMock, times(1)).getActiveFederation();
        verify(bridgeSupportMock, times(1)).getRetiringFederation();
        verify(decorate, times(1)).execute(any(), any());
    }

    @Test
    void activeAndRetiringFederationOnly_activeFederationIsNotFromFederateMember_retiringFederationIsNotNull_retiringFederationIsFromFederateMember_OK() throws Exception {
        // Given
        BridgeMethods.BridgeMethodExecutor decorate = mock(
            BridgeMethods.BridgeMethodExecutor.class
        );
        BridgeMethods.BridgeMethodExecutor executor = Bridge.activeAndRetiringFederationOnly(
            decorate,
            null
        );

        int senderPK = 405; // Sender PK belongs to retiring federation member PKs
        Integer[] activeMemberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Integer[] retiringMemberPKs = new Integer[]{ 101, 202, 303, 404, 505, 606 };

        Federation activeFederation = FederationTestUtils.getFederation(activeMemberPKs);
        Federation retiringFederation = FederationTestUtils.getFederation(retiringMemberPKs);

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        doReturn(activeFederation).when(bridgeSupportMock).getActiveFederation();
        doReturn(retiringFederation).when(bridgeSupportMock).getRetiringFederation();

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(senderPK)));

        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .build();

        // When
        executor.execute(bridge, null);

        // Then
        verify(bridgeSupportMock, times(1)).getActiveFederation();
        verify(bridgeSupportMock, times(1)).getRetiringFederation();
        verify(decorate, times(1)).execute(any(), any());
    }

    @Test
    void getNextPegoutCreationBlockNumber_after_RSKIP271_activation() throws VMException {

        long nextPegoutCreationHeight = 1L;
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getNextPegoutCreationBlockNumber()).thenReturn(nextPegoutCreationHeight);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        AbiFunction function = BridgeMethods.GET_NEXT_PEGOUT_CREATION_BLOCK_NUMBER.getFunction();
        Bytes data = function.encode();
        Bytes result = bridge.execute(data);
        BigInteger decodedResult = (BigInteger) function.decodeResult(result)[0];

        assertEquals(nextPegoutCreationHeight, decodedResult.longValue());

        // Also test the method itself
        long resultFromTheBridge = bridge.getNextPegoutCreationBlockNumber(new Object[]{});
        assertEquals(nextPegoutCreationHeight, resultFromTheBridge);
    }

    @Test
    void getQueuedPegoutsCount_after_RSKIP271_activation() throws VMException, IOException {

        int queuedPegoutsCount = 1;
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getQueuedPegoutsCount()).thenReturn(queuedPegoutsCount);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        AbiFunction function = BridgeMethods.GET_QUEUED_PEGOUTS_COUNT.getFunction();
        Bytes data = function.encode();
        Bytes result = bridge.execute(data);
        BigInteger decodedResult = (BigInteger) function.decodeResult(result)[0];

        assertEquals(queuedPegoutsCount, decodedResult.intValue());

        // Also test the method itself
        int resultFromTheBridge = bridge.getQueuedPegoutsCount(new Object[]{});
        assertEquals(queuedPegoutsCount, resultFromTheBridge);
    }

    @Test
    void getEstimatedFeesForNextPegOutEvent_after_RSKIP271_activation() throws VMException, IOException {

        Coin estimatedFeesForNextPegout = Coin.SATOSHI;
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getEstimatedFeesForNextPegOutEvent()).thenReturn(estimatedFeesForNextPegout);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        AbiFunction function = BridgeMethods.GET_ESTIMATED_FEES_FOR_NEXT_PEGOUT_EVENT.getFunction();
        Bytes data = function.encode();
        Bytes result = bridge.execute(data);
        BigInteger decodedResult = (BigInteger) function.decodeResult(result)[0];

        assertEquals(estimatedFeesForNextPegout.getValue(), decodedResult.longValue());

        // Also test the method itself
        long resultFromTheBridge = bridge.getEstimatedFeesForNextPegOutEvent(new Object[]{});
        assertEquals(estimatedFeesForNextPegout.getValue(), resultFromTheBridge);
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void addFederatorPublicKeyMultikey(CallKind msgType) throws VMException {
        String publicKey = "039a060badbeb24bee49eb2063f616c0f0f0765d4ca646b20a88ce828f259fcdb9";
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.ADD_FEDERATOR_PUBLIC_KEY_MULTIKEY.getFunction();
        Bytes data = function.encode(Hex.decode(publicKey), Hex.decode(publicKey), Hex.decode(publicKey));

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).voteFederationChange(any(), any());
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void addSignatures(CallKind msgType) throws Exception {
        String pegnatoryPublicKey = "039a060badbeb24bee49eb2063f616c0f0f0765d4ca646b20a88ce828f259fcdb9";
        String signature = "3045022100a0963cea7551eb3174a3470c6ed25cda901c4b1093818d4d54792b87508820220220325f93b5aecc98385a664328e68d1cec7a2a2fe81810a7692358bd870aeecb74";
        List<byte[]> derEncodedSigs = Collections.singletonList(Hex.decode(signature));
        Hash rskTxHash = createHash3(1);

        int senderPK = 101; // Sender PK belongs to active federation member PKs
        Integer[] activeMemberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Federation activeFederation = FederationTestUtils.getFederation(activeMemberPKs);

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(senderPK)));

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        doReturn(activeFederation).when(bridgeSupportMock).getActiveFederation();
        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.ADD_SIGNATURE.getFunction();
        Bytes data = function.encode(Hex.decode(pegnatoryPublicKey), derEncodedSigs, rskTxHash.getBytes());

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            Bytes result = bridge.execute(data);
            assertVoidMethodResult(result);
            verify(bridgeSupportMock, times(1)).addSignature(
                any(),
                any(),
                any()
            );
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void commitFederation(CallKind msgType) throws VMException {
        Hash commitTransactionHash = createHash3(2);
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.COMMIT_FEDERATION.getFunction();
        Bytes data = function.encode(commitTransactionHash.getBytes());

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).voteFederationChange(
                any(),
                any()
            );
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void createFederation(CallKind msgType) throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.CREATE_FEDERATION.getFunction();
        Bytes data = function.encode();

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).voteFederationChange(
                any(),
                any()
            );
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getBtcBlockchainBestChainHeight(CallKind msgType)
        throws VMException, BlockStoreException, IOException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getBtcBlockchainBestChainHeight();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getBtcBlockchainInitialBlockHeight(CallKind msgType)
        throws VMException, IOException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_BTC_BLOCKCHAIN_INITIAL_BLOCK_HEIGHT.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getBtcBlockchainInitialBlockHeight();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getBtcBlockchainBlockHashAtDepth(CallKind msgType)
        throws VMException, IOException, BlockStoreException {

        int depth = 1000;
        Sha256Hash blockHashAtDepth = BitcoinTestUtils.createHash(1);
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getBtcBlockchainBlockHashAtDepth(depth)).thenReturn(blockHashAtDepth);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_BTC_BLOCKCHAIN_BLOCK_HASH_AT_DEPTH.getFunction();
        Bytes data = function.encode(depth);

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getBtcBlockchainBlockHashAtDepth(depth);
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getBtcTransactionConfirmations(CallKind msgType)
        throws VMException, IOException, BlockStoreException {

        Sha256Hash btcTxHash = BitcoinTestUtils.createHash(1);
        Sha256Hash btcBlockHash = BitcoinTestUtils.createHash(2);
        int merkleBranchPath = 1;
        List<Sha256Hash> merkleBranchHashes = Arrays.asList(
            BitcoinTestUtils.createHash(10),
            BitcoinTestUtils.createHash(11),
            BitcoinTestUtils.createHash(12)
        );
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_BTC_TRANSACTION_CONFIRMATIONS.getFunction();
        Bytes data = function.encode(
            btcTxHash.getBytes(),
            btcBlockHash.getBytes(),
            merkleBranchPath,
            merkleBranchHashes.stream().map(Sha256Hash::getBytes).toArray()
        );

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getBtcTransactionConfirmations(
                any(),
                any(),
                any()
            );
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getBtcTxHashProcessedHeight(CallKind msgType) throws VMException, IOException {

        Sha256Hash btcTxHash = BitcoinTestUtils.createHash(1);
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_BTC_TX_HASH_PROCESSED_HEIGHT.getFunction();
        Bytes data = function.encode(btcTxHash.toString());

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getBtcTxHashProcessedHeight(btcTxHash);
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getFederationAddress(CallKind msgType) throws VMException {

        Address federationAddress = Address.fromBase58(networkParameters, "32Bhwee9FzQbuaG29RcXpdrvYnvZeMk11M");
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getActiveFederationAddress()).thenReturn(federationAddress);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_FEDERATION_ADDRESS.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getActiveFederationAddress();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getFederationCreationBlockNumber(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_FEDERATION_CREATION_BLOCK_NUMBER.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getActiveFederationCreationBlockNumber();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getFederationCreationTime(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getActiveFederationCreationTime()).thenReturn(Instant.ofEpochSecond(100_000L));
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_FEDERATION_CREATION_TIME.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getActiveFederationCreationTime();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getFederationSize(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_FEDERATION_SIZE.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getActiveFederationSize();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getFederationThreshold(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_FEDERATION_THRESHOLD.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getActiveFederationThreshold();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getFederatorPublicKeyOfType(CallKind msgType) throws VMException {

        int federatorIndex = 1;
        KeyType keyType = KeyType.BTC;
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction();
        Bytes data = function.encode(federatorIndex, keyType.getValue());

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getActiveFederatorPublicKeyOfType(
                federatorIndex,
                keyType
            );
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getFeePerKb(CallKind msgType) throws VMException {

        Coin feePerKb = Coin.COIN;
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getFeePerKb()).thenReturn(feePerKb);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_FEE_PER_KB.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getFeePerKb();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getMinimumLockTxValue(CallKind msgType) throws VMException {

        Coin minimumPeginTxValue = Coin.COIN;
        BridgeConstants bridgeConstants = mock(BridgeConstants.class);
        when(bridgeConstants.getMinimumPeginTxValue()).thenReturn(minimumPeginTxValue);
        when(bridgeConstants.getBtcParams()).thenReturn(BridgeMainNetConstants.getInstance().getBtcParams());

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeConstants(bridgeConstants)
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_MINIMUM_LOCK_TX_VALUE.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeConstants, times(1)).getMinimumPeginTxValue();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getPendingFederationHash(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_PENDING_FEDERATION_HASH.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getPendingFederationHash();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getPendingFederationSize(CallKind msgType) throws VMException
    {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_PENDING_FEDERATION_SIZE.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getPendingFederationSize();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getPendingFederatorPublicKeyOfType(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_PENDING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction();

        int federatorIndex = 1;
        FederationMember.KeyType keyType = FederationMember.KeyType.BTC;
        Bytes data = function.encode(federatorIndex, keyType.getValue());

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getPendingFederatorPublicKeyOfType(
                federatorIndex,
                keyType
            );
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getRetiringFederationAddress(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Address retiringFederationAddress = Address.fromBase58(networkParameters, "32Bhwee9FzQbuaG29RcXpdrvYnvZeMk11M");
        when(bridgeSupportMock.getRetiringFederationAddress()).thenReturn(retiringFederationAddress);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_RETIRING_FEDERATION_ADDRESS.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getRetiringFederationAddress();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getRetiringFederationCreationBlockNumber(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_RETIRING_FEDERATION_CREATION_BLOCK_NUMBER.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getRetiringFederationCreationBlockNumber();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getRetiringFederationCreationTime(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.getActiveFederationCreationTime()).thenReturn(Instant.ofEpochSecond(100_000L));
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_RETIRING_FEDERATION_CREATION_TIME.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getRetiringFederationCreationTime();
        }
    }

    @Test
    void getActiveFederationCreationTime_returnsCreationTimeInExpectedTimeUnit() {
        long expectedActiveFederationCreationTime = 5;
        // arrange
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Instant creationTime = Instant.ofEpochMilli(5000);
        when(bridgeSupportMock.getActiveFederationCreationTime()).thenReturn(creationTime);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        // act
        long actualActiveFederationCreationTime = bridge.getFederationCreationTime(new Object[]{});

        assertEquals(expectedActiveFederationCreationTime, actualActiveFederationCreationTime);
    }

    @Test
    void getRetiringFederationCreationTime_returnsCreationTimeInExpectedTimeUnit() {
        long expectedRetiringFederationCreationTime = 5;
        // arrange
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Instant creationTime = Instant.ofEpochMilli(5000);
        when(bridgeSupportMock.getRetiringFederationCreationTime()).thenReturn(creationTime);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        // act
        long actualRetiringFederationCreationTime = bridge.getRetiringFederationCreationTime(new Object[]{});

        // assert
        assertEquals(expectedRetiringFederationCreationTime, actualRetiringFederationCreationTime);
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getRetiringFederationSize(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_RETIRING_FEDERATION_SIZE.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getRetiringFederationSize();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getRetiringFederationThreshold(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_RETIRING_FEDERATION_THRESHOLD.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getRetiringFederationThreshold();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getRetiringFederatorPublicKeyOfType(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction();

        int federatorIndex = 1;
        FederationMember.KeyType keyType = FederationMember.KeyType.BTC;
        Bytes data = function.encode(federatorIndex, keyType.getValue());

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getRetiringFederatorPublicKeyOfType(
                federatorIndex,
                keyType
            );
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getProposedFederationAddress(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Address expectedAddress = Address.fromBase58(networkParameters, "32Bhwee9FzQbuaG29RcXpdrvYnvZeMk11M");
        when(bridgeSupportMock.getProposedFederationAddress()).thenReturn(Optional.of(expectedAddress));

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_PROPOSED_FEDERATION_ADDRESS.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock).getProposedFederationAddress();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getProposedFederationSize(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Integer expectedSize = 9;
        when(bridgeSupportMock.getProposedFederationSize()).thenReturn(Optional.of(expectedSize));

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_PROPOSED_FEDERATION_SIZE.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock).getProposedFederationSize();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getProposedFederationCreationTime(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Instant expectedCreationTime = Instant.EPOCH;
        when(bridgeSupportMock.getProposedFederationCreationTime()).thenReturn(Optional.of(expectedCreationTime));

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_PROPOSED_FEDERATION_CREATION_TIME.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock).getProposedFederationCreationTime();
        }
    }

    @Test
    void getProposedFederationCreationTime_shouldReturnValueFromSeconds() {
        // arrange

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        long creationTimeInSeconds = 1000;
        Instant creationTime = Instant.ofEpochSecond(creationTimeInSeconds);
        when(bridgeSupportMock.getProposedFederationCreationTime()).thenReturn(Optional.of(creationTime));

        // act & assert
        assertEquals(creationTimeInSeconds, bridge.getProposedFederationCreationTime(new Object[]{}));
    }

    @Test
    void getProposedFederationCreationTime_whenBridgeSupportReturnsEmpty_shouldReturnMinusOne() {
        // arrange

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .build();

        when(bridgeSupportMock.getProposedFederationCreationTime()).thenReturn(Optional.empty());

        // act & assert
        long expectedCreationTime = -1L;
        assertEquals(expectedCreationTime, bridge.getProposedFederationCreationTime(new Object[]{}));
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getProposedFederationCreationBlockNumber(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        long expectedCreationBlockNumber = 123456L;
        when(bridgeSupportMock.getProposedFederationCreationBlockNumber()).thenReturn(Optional.of(expectedCreationBlockNumber));

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_PROPOSED_FEDERATION_CREATION_BLOCK_NUMBER.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock).getProposedFederationCreationBlockNumber();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getProposedFederatorPublicKeyOfType(CallKind msgType) throws VMException {

        int federatorIndex = 1;
        KeyType keyType = KeyType.BTC;
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_PROPOSED_FEDERATOR_PUBLIC_KEY_OF_TYPE.getFunction();
        Bytes data = function.encode(federatorIndex, keyType.getValue());

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock).getProposedFederatorPublicKeyOfType(federatorIndex, keyType);
        }
    }

    @Test
    void getProposedFederatorPublicKeyOfType_whenBridgeSupportCallThrowsIOOBE_throwsVMException() {
        // arrange
        CallKind msgType = CallKind.CALL;

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        // this value is not being actually tested since we are mocking the response
        int outOfBoundsIndex = 1000;
        KeyType keyType = KeyType.BTC;
        when(bridgeSupportMock.getProposedFederatorPublicKeyOfType(outOfBoundsIndex, keyType)).thenThrow(IndexOutOfBoundsException.class);

        // act & assert
        Object[] args = new Object[]{ BigInteger.valueOf(outOfBoundsIndex), keyType.getValue() };
        assertThrows(VMException.class, () -> bridge.getProposedFederatorPublicKeyOfType(args));
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getStateForBtcReleaseClient(CallKind msgType) throws VMException, IOException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_STATE_FOR_BTC_RELEASE_CLIENT.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getStateForBtcReleaseClient();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getStateForSvpClient(CallKind msgType) throws VMException, IOException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_STATE_FOR_SVP_CLIENT.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock).getStateForSvpClient();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getStateForDebugging(CallKind msgType) throws VMException, IOException, BlockStoreException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_STATE_FOR_DEBUGGING.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getStateForDebugging();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getLockingCap(CallKind msgType) throws VMException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Coin lockingCap = Coin.COIN;
        when(bridgeSupportMock.getLockingCap()).thenReturn(lockingCap);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.GET_LOCKING_CAP.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getLockingCap();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getActivePowpegRedeemScript(CallKind msgType) throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Integer[] activeMemberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Federation activeFederation = FederationTestUtils.getFederation(activeMemberPKs);
        Script activePowpegRedeemScript = activeFederation.getRedeemScript();
        when(bridgeSupportMock.getActiveFederationRedeemScript()).thenReturn(Optional.of(activePowpegRedeemScript));

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_ACTIVE_POWPEG_REDEEM_SCRIPT.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getActiveFederationRedeemScript();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getActiveFederationCreationBlockHeight(CallKind msgType) throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getActiveFederationCreationBlockHeight();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void increaseLockingCap(CallKind msgType) throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        when(bridgeSupportMock.increaseLockingCap(any(), any())).thenReturn(true);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.INCREASE_LOCKING_CAP.getFunction();

        long newLockingCap = 1;
        Bytes data = function.encode(newLockingCap);

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).increaseLockingCap(any(), any());
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void isBtcTxHashAlreadyProcessed(CallKind msgType) throws VMException, IOException {

        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Sha256Hash btcTxHash = Sha256Hash.of("btcTxHash".getBytes());
        when(bridgeSupportMock.isBtcTxHashAlreadyProcessed(btcTxHash)).thenReturn(true);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .localCall(true)
            .build();

        AbiFunction function = BridgeMethods.IS_BTC_TX_HASH_ALREADY_PROCESSED.getFunction();
        Bytes data = function.encode(btcTxHash.toString());

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).isBtcTxHashAlreadyProcessed(btcTxHash);
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void receiveHeaders(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        // receiveHeaders is callable only from active or retiring federation members
        int senderPK = 101; // Sender PK belongs to active federation member PKs
        Integer[] activeMemberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Federation activeFederation = FederationTestUtils.getFederation(activeMemberPKs);

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(senderPK)));
        doReturn(activeFederation).when(bridgeSupportMock).getActiveFederation();

        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.RECEIVE_HEADERS.getFunction();
        Bytes data = function.encode((Object) new byte[][]{});

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            Bytes result = bridge.execute(data);
            assertVoidMethodResult(result);
            verify(bridgeSupportMock, times(1)).receiveHeaders(new BtcBlock[]{});
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void receiveHeader(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        // receiveHeader is callable only from active or retiring federation fed members
        int senderPK = 101; // Sender PK belongs to active federation member PKs
        Integer[] activeMemberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Federation activeFederation = FederationTestUtils.getFederation(activeMemberPKs);

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(senderPK)));
        doReturn(activeFederation).when(bridgeSupportMock).getActiveFederation();

        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.RECEIVE_HEADER.getFunction();

        BtcBlock btcBlock = new BtcBlock(
            networkParameters,
            1,
            BitcoinTestUtils.createHash(1),
            BitcoinTestUtils.createHash(2),
            1,
            100L,
            1,
            new ArrayList<>()
        ).cloneAsHeader();

        byte[] serializedBlockHeader = btcBlock.bitcoinSerialize();
        Bytes data = function.encode(serializedBlockHeader);

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
            // boolean ret = true;
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).receiveHeader(any());
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void registerBtcTransaction(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        // registerBtcTransaction is callable by anyone since RSKIP199

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.REGISTER_BTC_TRANSACTION.getFunction();

        byte[] btcTxSerialized = new byte[]{1};
        int height = 0;
        byte[] pmtSerialized = new byte[]{2};
        Bytes data = function.encode(btcTxSerialized, height, pmtSerialized);

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            Bytes result = bridge.execute(data);
            assertVoidMethodResult(result);
            verify(bridgeSupportMock, times(1)).registerBtcTransaction(
                any(CallContext.class),
                any(byte[].class),
                anyInt(),
                any(byte[].class)
            );
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void releaseBtc(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.RELEASE_BTC.getFunction();
        Bytes data = function.encode();

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            Bytes result = bridge.execute(data);
            assertVoidMethodResult(result);
            verify(bridgeSupportMock, times(1)).releaseBtc(any());
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void rollbackFederation(CallKind msgType) throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.ROLLBACK_FEDERATION.getFunction();
        Bytes data = function.encode();

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).voteFederationChange(any(), any());
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void updateCollections(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        // updateCollections is only callable from active or retiring federation
        int senderPK = 101; // Sender PK belongs to active federation member PKs
        Integer[] activeMemberPKs = new Integer[]{ 100, 200, 300, 400, 500, 600 };
        Federation activeFederation = FederationTestUtils.getFederation(activeMemberPKs);

        org.hyperledger.besu.datatypes.Address txSender = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(senderPK)));
        doReturn(activeFederation).when(bridgeSupportMock).getActiveFederation();

        Bridge bridge = bridgeBuilder
            .sender(txSender)
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.UPDATE_COLLECTIONS.getFunction();
        Bytes data = function.encode();

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            Bytes result = bridge.execute(data);
            assertVoidMethodResult(result);
            verify(bridgeSupportMock, times(1)).updateCollections(any(CallContext.class));
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void voteFeePerKb(CallKind msgType) throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.VOTE_FEE_PER_KB.getFunction();

        long feePerKB = 10_000;
        Bytes data = function.encode(feePerKB);

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).voteFeePerKbChange(any(), any());
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void registerBtcCoinbaseTransaction(CallKind msgType) throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.REGISTER_BTC_COINBASE_TRANSACTION.getFunction();

        byte[] btcTxSerialized = EMPTY_BYTE_ARRAY;
        Sha256Hash blockHash = BitcoinTestUtils.createHash(1);
        byte[] pmtSerialized = EMPTY_BYTE_ARRAY;
        Sha256Hash witnessMerkleRoot = BitcoinTestUtils.createHash(2);
        byte[] witnessReservedValue = new byte[32];
        Bytes data = function.encode(
            btcTxSerialized,
            blockHash.getBytes(),
            pmtSerialized,
            witnessMerkleRoot.getBytes(),
            witnessReservedValue
        );

        if (!msgType.equals(CallKind.CALL)) {
            // Post arrowhead should fail for any msg type != CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            Bytes result = bridge.execute(data);
            assertVoidMethodResult(result);
            verify(bridgeSupportMock, times(1)).registerBtcCoinbaseTransaction(
                btcTxSerialized,
                blockHash,
                pmtSerialized,
                witnessMerkleRoot,
                witnessReservedValue
            );
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void hasBtcBlockCoinbaseTransactionInformation(CallKind msgType) throws VMException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.HAS_BTC_BLOCK_COINBASE_TRANSACTION_INFORMATION.getFunction();

        Sha256Hash blockHash = BitcoinTestUtils.createHash(2);
        Bytes data = function.encode(blockHash.getBytes());

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATICCALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).hasBtcBlockCoinbaseTransactionInformation(any());
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getBtcBlockchainBestBlockHeader(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_BLOCK_HEADER.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getBtcBlockchainBestBlockHeader();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getBtcBlockchainBlockHeaderByHash(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_BTC_BLOCKCHAIN_BLOCK_HEADER_BY_HASH.getFunction();

        byte[] hashBytes = new byte[32];
        Bytes data = function.encode(hashBytes);

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getBtcBlockchainBlockHeaderByHash(Sha256Hash.wrap(hashBytes));
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getBtcBlockchainBlockHeaderByHeight(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_BTC_BLOCKCHAIN_BLOCK_HEADER_BY_HEIGHT.getFunction();

        int height = 20;
        Bytes data = function.encode(height);

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getBtcBlockchainBlockHeaderByHeight(height);
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getBtcBlockchainParentBlockHeaderByHash(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_BTC_BLOCKCHAIN_PARENT_BLOCK_HEADER_BY_HASH.getFunction();

        byte[] hashBytes = new byte[32];
        Bytes data = function.encode(hashBytes);

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getBtcBlockchainParentBlockHeaderByHash(any());
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getNextPegoutCreationBlockNumber(CallKind msgType) throws VMException, IOException, BlockStoreException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_NEXT_PEGOUT_CREATION_BLOCK_NUMBER.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getNextPegoutCreationBlockNumber();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getQueuedPegoutsCount(CallKind msgType) throws VMException, IOException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_QUEUED_PEGOUTS_COUNT.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getQueuedPegoutsCount();
        }
    }

    @ParameterizedTest()
    @MethodSource("msgTypes")
    void getEstimatedFeesForNextPegoutEvent(CallKind msgType) throws VMException, IOException {
        BridgeSupport bridgeSupportMock = mock(BridgeSupport.class);
        Coin estimatedFeesForNextPegout = Coin.SATOSHI;
        when(bridgeSupportMock.getEstimatedFeesForNextPegOutEvent()).thenReturn(estimatedFeesForNextPegout);

        Bridge bridge = bridgeBuilder
            .bridgeSupport(bridgeSupportMock)
            .callKind(msgType)
            .build();

        AbiFunction function = BridgeMethods.GET_ESTIMATED_FEES_FOR_NEXT_PEGOUT_EVENT.getFunction();
        Bytes data = function.encode();

        if (!(msgType.equals(CallKind.CALL) || msgType.equals(CallKind.STATICCALL))) {
            // Post arrowhead should fail for any msg type != CALL or STATIC CALL
            assertThrows(VMException.class, () -> bridge.execute(data));
        } else {
            bridge.execute(data);
            verify(bridgeSupportMock, times(1)).getEstimatedFeesForNextPegOutEvent();
        }
    }

    private static Stream<Arguments> msgTypes() {
        return Arrays.stream(CallKind.values()).map(Arguments::of);
    }

    private void assertVoidMethodResult(Bytes result) {
        assertEquals(Bytes.EMPTY, result);
    }
}
