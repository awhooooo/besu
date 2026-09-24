package co.rsk.peg.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.rsk.bitcoinj.core.BtcBlock;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.StoredBlock;
import co.rsk.peg.BridgeAddresses;
import co.rsk.peg.BridgeEvents;
import co.rsk.peg.BridgeMethods;
import co.rsk.peg.BridgeStorageProvider;
import co.rsk.peg.BridgeSupport;
import co.rsk.peg.BridgeSupportFactory;
import co.rsk.peg.ReleaseRequestQueue;
import co.rsk.peg.RepositoryBtcBlockStoreWithCache;
import co.rsk.peg.abi.AbiFunction;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.utils.PublicKeys;
import co.rsk.peg.utils.RejectedPegoutReason;
import co.rsk.peg.utils.StorageKeys;
import co.rsk.peg.utils.Weis;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;

/**
 * Drives the bridge the way the node will: through Besu's message call processor with the bridge registered
 * as a precompile, over a real in-memory world state. Gas, value transfer, logs, and the commit or rollback
 * of state are Besu's own. The expected gas is RSKj's cost table plus two gas per input byte.
 */
class BridgePrecompiledContractTest {

    private static final BridgeConstants CONSTANTS = new BridgeRegTestConstants();
    private static final NetworkParameters PARAMS = CONSTANTS.getBtcParams();
    private static final long INITIAL_GAS = 10_000_000L;
    private static final Hash TX_HASH = Hash.hash(Bytes.of(1));
    private static final BtcECKey SENDER_KEY = BtcECKey.fromPrivate(BigInteger.valueOf(1234));
    private static final Address SENDER = PublicKeys.addressOf(SENDER_KEY);
    private static final Bytes SENDER_PUBLIC_KEY_XY = Bytes.wrap(PublicKeys.uncompressed(SENDER_KEY)).slice(1);
    private static final Wei ONE_BTC = Weis.fromSatoshis(Coin.COIN);
    /** Regtest keys serve as BTC and RSK keys alike, so a genesis federation key gives a federator's address. */
    private static final Address FEDERATOR = PublicKeys.addressOf(CONSTANTS.getFederationConstants().getGenesisFederationPublicKeys().get(0));

    private MutableWorldState world;
    private BridgePrecompiledContract contract;
    private MessageCallProcessor processor;

    @BeforeEach
    void setUp() {
        world = InMemoryKeyValueStorageProvider.createInMemoryWorldState();
        contract = new BridgePrecompiledContract(
            CONSTANTS,
            new BridgeSupportFactory(new RepositoryBtcBlockStoreWithCache.Factory(PARAMS), CONSTANTS)
        );
        PrecompileContractRegistry registry = new PrecompileContractRegistry();
        registry.put(BridgeAddresses.BRIDGE, contract);
        processor = new MessageCallProcessor(MainnetEVMs.cancun(EvmConfiguration.DEFAULT), registry);
    }

    @Test
    void reportsNoUpfrontRequirementBecauseTheBridgePricesFromState() {
        assertEquals("Bridge", contract.getName());
        assertEquals(0, contract.gasRequirement(BridgeMethods.GET_FEDERATION_SIZE.getFunction().encode()));
    }

    static Stream<Arguments> methods() {
        int genesisFederationSize = CONSTANTS.getFederationConstants().getGenesisFederationPublicKeys().size();
        return Stream.of(
            Arguments.of(BridgeMethods.GET_FEDERATION_SIZE, true, 10_000L, BigInteger.valueOf(genesisFederationSize)),
            Arguments.of(BridgeMethods.GET_FEE_PER_KB, true, 2_000L, BigInteger.valueOf(Coin.MILLICOIN.value)),
            Arguments.of(BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT, false, 19_000L, BigInteger.ZERO)
        );
    }

    @ParameterizedTest
    @MethodSource("methods")
    void chargesTheCostTablePlusTwoGasPerInputByte(BridgeMethods method, boolean local, long cost, BigInteger output) {
        Bytes input = method.getFunction().encode();

        MessageFrame frame = new Call().input(input).local(local).run();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());
        assertEquals(cost + 2 * input.size(), INITIAL_GAS - frame.getRemainingGas());
        assertEquals(output, method.getFunction().decodeResult(frame.getOutputData())[0]);
    }

    @Test
    void updateCollectionsIsForFederatorsAndReturnsNothing() {
        Bytes input = BridgeMethods.UPDATE_COLLECTIONS.getFunction().encode();

        MessageFrame frame = new Call().input(input).run();
        assertEquals(MessageFrame.State.COMPLETED_FAILED, frame.getState());
        assertTrue(frame.getExceptionalHaltReason().isEmpty());
        assertEquals(48_000 + 2 * input.size(), INITIAL_GAS - frame.getRemainingGas());

        frame = new Call().input(input).sender(FEDERATOR).run();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());
        assertEquals(48_000 + 2 * input.size(), INITIAL_GAS - frame.getRemainingGas());
        assertTrue(frame.getOutputData().isEmpty());
        AbiFunction event = BridgeEvents.UPDATE_COLLECTIONS.getEvent();
        assertEquals(1, frame.getLogs().size());
        assertEquals(event.encodeEventTopics(), frame.getLogs().get(0).getTopics());
        assertEquals(event.encodeEventData(FEDERATOR.getBytes().toUnprefixedHexString()), frame.getLogs().get(0).getData());
    }

    @Test
    void pricesTheConfirmationsQueryFromTheDepthOfTheBlock() {
        // Each block holds one transaction, so its merkle root is that transaction's hash
        Sha256Hash txHash = Sha256Hash.of(new byte[]{7});
        Sha256Hash headTxHash = Sha256Hash.of(new byte[]{8});
        BtcBlock genesis = PARAMS.getGenesisBlock();
        BtcBlock blockAtHeight1 = new BtcBlock(PARAMS, 1, genesis.getHash(), txHash, 1, 1, 1, new ArrayList<>());
        BtcBlock blockAtHeight2 = new BtcBlock(PARAMS, 1, blockAtHeight1.getHash(), headTxHash, 2, 1, 2, new ArrayList<>());
        writeHeaderChain(blockAtHeight1, blockAtHeight2);
        AbiFunction function = BridgeMethods.GET_BTC_TRANSACTION_CONFIRMATIONS.getFunction();

        // One block below the chain head: the basic cost, one depth step, two confirmations
        Bytes input = function.encode(txHash.getBytes(), blockAtHeight1.getHash().getBytes(), 0, new byte[0][]);
        MessageFrame frame = new Call().input(input).run();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());
        assertEquals(27_000 + 315 + 2 * input.size(), INITIAL_GAS - frame.getRemainingGas());
        assertEquals(BigInteger.valueOf(2), function.decodeResult(frame.getOutputData())[0]);

        // The chain head itself: no depth step, one confirmation
        input = function.encode(headTxHash.getBytes(), blockAtHeight2.getHash().getBytes(), 0, new byte[0][]);
        frame = new Call().input(input).run();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());
        assertEquals(27_000 + 2 * input.size(), INITIAL_GAS - frame.getRemainingGas());
        assertEquals(BigInteger.ONE, function.decodeResult(frame.getOutputData())[0]);

        // A block the bridge does not know: the basic cost, the error code
        input = function.encode(txHash.getBytes(), Sha256Hash.of(new byte[]{9}).getBytes(), 0, new byte[0][]);
        frame = new Call().input(input).run();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());
        assertEquals(27_000 + 2 * input.size(), INITIAL_GAS - frame.getRemainingGas());
        assertEquals(
            BigInteger.valueOf(BridgeSupport.BTC_TRANSACTION_CONFIRMATION_INEXISTENT_BLOCK_HASH_ERROR_CODE),
            function.decodeResult(frame.getOutputData())[0]
        );
    }

    @Test
    void releaseBtcMovesTheValueQueuesTheRequestAndLogsIt() {
        fund(SENDER, ONE_BTC.multiply(2));

        MessageFrame frame = new Call().value(ONE_BTC).context(FrameBridgeHost.ORIGIN_PUBLIC_KEY, SENDER_PUBLIC_KEY_XY).run();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());
        assertEquals(23_000, INITIAL_GAS - frame.getRemainingGas());
        assertTrue(frame.getOutputData().isEmpty());
        assertEquals(ONE_BTC, balance(BridgeAddresses.BRIDGE));
        assertEquals(ONE_BTC, balance(SENDER));

        co.rsk.bitcoinj.core.Address destination = SENDER_KEY.toAddress(PARAMS);
        AbiFunction event = BridgeEvents.RELEASE_REQUEST_RECEIVED.getEvent();
        assertEquals(1, frame.getLogs().size());
        Log log = frame.getLogs().get(0);
        assertEquals(BridgeAddresses.BRIDGE, log.getLogger());
        assertEquals(event.encodeEventTopics(SENDER.getBytes().toUnprefixedHexString()), log.getTopics());
        assertEquals(event.encodeEventData(destination.toString(), ONE_BTC.toBigInteger()), log.getData());

        List<ReleaseRequestQueue.Entry> entries = storageProvider().getReleaseRequestQueue().getEntries();
        assertEquals(1, entries.size());
        assertEquals(destination, entries.get(0).getDestination());
        assertEquals(Coin.COIN, entries.get(0).getAmount());
        assertEquals(TX_HASH, entries.get(0).getRskTxHash());
    }

    @Test
    void aReleaseBelowTheMinimumIsRefundedAndRejected() {
        fund(SENDER, ONE_BTC);
        Wei value = Weis.fromSatoshis(Coin.valueOf(1_000)); // the regtest minimum is 250 000 satoshis

        MessageFrame frame = new Call().value(value).context(FrameBridgeHost.ORIGIN_PUBLIC_KEY, SENDER_PUBLIC_KEY_XY).run();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());
        assertEquals(Wei.ZERO, balance(BridgeAddresses.BRIDGE));
        assertEquals(ONE_BTC, balance(SENDER));

        AbiFunction event = BridgeEvents.RELEASE_REQUEST_REJECTED.getEvent();
        assertEquals(1, frame.getLogs().size());
        Log log = frame.getLogs().get(0);
        assertEquals(event.encodeEventTopics(SENDER.getBytes().toUnprefixedHexString()), log.getTopics());
        assertEquals(event.encodeEventData(value.toBigInteger(), RejectedPegoutReason.LOW_AMOUNT.getValue()), log.getData());
        assertTrue(storageProvider().getReleaseRequestQueue().getEntries().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0xdeadbeef", "0x0102"})
    void malformedInputRevertsKeepingTheReleaseBtcPrice(String hex) {
        MessageFrame frame = new Call().input(Bytes.fromHexString(hex)).run();

        assertEquals(MessageFrame.State.COMPLETED_FAILED, frame.getState());
        assertTrue(frame.getExceptionalHaltReason().isEmpty());
        assertEquals(23_000, INITIAL_GAS - frame.getRemainingGas());
        assertTrue(frame.getOutputData().isEmpty());
        assertTrue(frame.getLogs().isEmpty());
        assertNull(world.get(BridgeAddresses.BRIDGE));
    }

    @Test
    void localOnlyMethodsRejectTransactions() {
        Bytes input = BridgeMethods.GET_FEDERATION_SIZE.getFunction().encode();

        MessageFrame frame = new Call().input(input).run();

        assertEquals(MessageFrame.State.COMPLETED_FAILED, frame.getState());
        assertTrue(frame.getExceptionalHaltReason().isEmpty());
        assertEquals(10_000 + 2 * input.size(), INITIAL_GAS - frame.getRemainingGas());
    }

    @Test
    void tooLittleGasHaltsAndConsumesAllOfIt() {
        Bytes input = BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.getFunction().encode();
        long required = 19_000 + 2 * input.size();

        MessageFrame frame = new Call().input(input).gas(required - 1).run();
        assertEquals(MessageFrame.State.COMPLETED_FAILED, frame.getState());
        assertEquals(Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS), frame.getExceptionalHaltReason());
        assertEquals(0, frame.getRemainingGas());
        assertNull(world.get(BridgeAddresses.BRIDGE));

        frame = new Call().input(input).gas(required).run();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());
        assertEquals(0, frame.getRemainingGas());
    }

    @Test
    void staticCallsMayReadButNotChangeState() {
        MessageFrame frame = new Call().input(BridgeMethods.GET_FEDERATION_SIZE.getFunction().encode()).local(true).isStatic().run();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());

        // On a chain with no headers yet, this query would initialize the header store: a write a static frame refuses
        Bytes heightQuery = BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.getFunction().encode();
        frame = new Call().input(heightQuery).isStatic().run();
        assertEquals(MessageFrame.State.COMPLETED_FAILED, frame.getState());
        assertTrue(frame.getExceptionalHaltReason().isEmpty());
        assertNull(new FrameBridgeHost(new Call().frame()).getStorage(StorageKeys.name("blockStoreChainHead")));

        // Once the header store exists the same query is a pure read
        new Call().input(heightQuery).run();
        frame = new Call().input(heightQuery).isStatic().run();
        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, frame.getState());
        assertEquals(BigInteger.ZERO, BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT.getFunction().decodeResult(frame.getOutputData())[0]);

        // A state-changing method is refused by the bridge's own call-type rule before it runs
        Bytes update = BridgeMethods.UPDATE_COLLECTIONS.getFunction().encode();
        frame = new Call().input(update).sender(FEDERATOR).isStatic().run();
        assertEquals(MessageFrame.State.COMPLETED_FAILED, frame.getState());
        assertEquals(48_000 + 2 * update.size(), INITIAL_GAS - frame.getRemainingGas());
    }

    @Test
    void aFrameWithoutTheNodeContextFailsBeforeExecuting() {
        Bytes input = BridgeMethods.GET_FEDERATION_SIZE.getFunction().encode();

        Call withoutHash = new Call().input(input).local(true).context(FrameBridgeHost.TRANSACTION_HASH, null);
        assertThrows(IllegalStateException.class, withoutHash::run);

        Call withoutLocalFlag = new Call().input(input).context(FrameBridgeHost.LOCAL_CALL, null);
        assertThrows(IllegalStateException.class, withoutLocalFlag::run);
    }

    private void writeHeaderChain(BtcBlock... blocks) {
        MessageFrame frame = new Call().frame();
        FrameBridgeHost host = new FrameBridgeHost(frame);
        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), PARAMS);
        // The constructor stores the genesis block and makes it the chain head
        RepositoryBtcBlockStoreWithCache store = new RepositoryBtcBlockStoreWithCache(PARAMS, host, new HashMap<>(), CONSTANTS, provider);
        StoredBlock stored = store.getChainHead();
        for (BtcBlock block : blocks) {
            stored = new StoredBlock(block, stored.getChainWork().add(BigInteger.ONE), stored.getHeight() + 1);
            store.put(stored);
        }
        store.setChainHead(stored);
        provider.save();
        frame.getWorldUpdater().commit();
    }

    private void fund(Address account, Wei amount) {
        WorldUpdater updater = world.updater();
        updater.getOrCreate(account).setBalance(amount);
        updater.commit();
    }

    private Wei balance(Address account) {
        Account state = world.get(account);
        return state == null ? Wei.ZERO : state.getBalance();
    }

    private BridgeStorageProvider storageProvider() {
        return new BridgeStorageProvider(new BridgeStorageAccessorImpl(new FrameBridgeHost(new Call().frame())), PARAMS);
    }

    /** One call to the bridge through Besu's message call processor, with the context the node attaches. */
    private final class Call {
        private Bytes input = Bytes.EMPTY;
        private long gas = INITIAL_GAS;
        private Wei value = Wei.ZERO;
        private Address sender = SENDER;
        private boolean isStatic;
        private final Map<String, Object> context = new HashMap<>();

        Call() {
            context.put(FrameBridgeHost.TRANSACTION_HASH, TX_HASH);
            context.put(FrameBridgeHost.LOCAL_CALL, false);
        }

        Call input(Bytes input) {
            this.input = input;
            return this;
        }

        Call gas(long gas) {
            this.gas = gas;
            return this;
        }

        Call value(Wei value) {
            this.value = value;
            return this;
        }

        Call sender(Address sender) {
            this.sender = sender;
            return this;
        }

        Call local(boolean local) {
            context.put(FrameBridgeHost.LOCAL_CALL, local);
            return this;
        }

        Call isStatic() {
            this.isStatic = true;
            return this;
        }

        Call context(String name, Object variable) {
            if (variable == null) {
                context.remove(name);
            } else {
                context.put(name, variable);
            }
            return this;
        }

        MessageFrame run() {
            MessageFrame frame = frame();
            processor.process(frame, OperationTracer.NO_TRACING);
            return frame;
        }

        MessageFrame frame() {
            return MessageFrame.builder()
                .type(MessageFrame.Type.MESSAGE_CALL)
                .worldUpdater(world.updater())
                .initialGas(gas)
                .address(BridgeAddresses.BRIDGE)
                .contract(BridgeAddresses.BRIDGE)
                .originator(sender)
                .sender(sender)
                .gasPrice(Wei.ZERO)
                .blobGasPrice(Wei.ZERO)
                .inputData(input)
                .value(value)
                .apparentValue(value)
                .code(Code.EMPTY_CODE)
                .blockValues(new BlockHeaderTestFixture().number(1).timestamp(1_700_000_000L).buildHeader())
                .completer(completed -> {})
                .miningBeneficiary(Address.ZERO)
                .blockHashLookup((messageFrame, number) -> Hash.ZERO)
                .maxStackSize(MessageFrame.DEFAULT_MAX_STACK_SIZE)
                .isStatic(isStatic)
                .contextVariables(context)
                .build();
        }
    }
}
