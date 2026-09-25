/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderBuilder;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.ExecutionContextTestFixture;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.BodyValidation;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleBuilder;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecAdapters;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import co.rsk.peg.BridgeMethods;
import co.rsk.peg.BridgeState;
import co.rsk.peg.constants.BridgeRegTestConstants;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RSKj's RskForksBridgeTest, against Besu's own chain.
 *
 * <p>A peg-out is requested, and the chain then forks around it. What the bridge believes about that
 * peg-out has to follow whichever branch wins, every time: the request appears when the branch
 * holding it is canonical, and is gone the moment a branch without it takes over. On Besu the
 * bridge's state is ordinary account storage, so this is really asking whether a precompile that
 * writes through the world updater reorganises like everything else does. It should, and here it is
 * shown doing so through real block import rather than by argument.
 *
 * <p>Two things differ from the original, both because the protocol moved on. There is no
 * whitelisting transaction, since the lock whitelist is gone from the peg. And the three valueless
 * bridge calls are sent at a zero gas price by a federator holding nothing, which the bootstrap
 * window allows and which the original had no need of, having funded its federator instead.
 */
class RskForksBridgeTest {

  private static final Address BRIDGE =
      Address.fromHexString("0x0000000000000000000000000000000001000006");
  private static final BigInteger CHAIN_ID = BigInteger.valueOf(33);
  private static final long WINDOW_CLOSES_AT = 1_000L;

  /** Regtest's first genesis federation member; its bitcoin and RSK keys are the same key. */
  private static final KeyPair FEDERATOR =
      SignatureAlgorithmFactory.getInstance()
          .createKeyPair(
              SignatureAlgorithmFactory.getInstance()
                  .createPrivateKey(
                      new BigInteger(
                          "45c5b07fc1a6f58892615b7c31dca6c96db58c4bbc538a6b8a22999aaa860c32", 16)));

  private static final Address FEDERATOR_ADDRESS = Address.extract(FEDERATOR.getPublicKey());

  /** Any key at all: getStateForDebugging is a local call and nobody has to be able to pay for it. */
  private static final KeyPair OBSERVER =
      SignatureAlgorithmFactory.getInstance()
          .createKeyPair(
              SignatureAlgorithmFactory.getInstance().createPrivateKey(BigInteger.valueOf(7)));

  /** Twenty-one million coins, which the bridge must hold at genesis. */
  private static final Wei WHOLE_SUPPLY =
      Wei.of(BigInteger.valueOf(21_000_000).multiply(BigInteger.TEN.pow(18)));

  /**
   * What the federator is given to peg out with, as RSKj's original gave it too.
   *
   * <p>A peg-in credits the account that sent the bitcoin, not the federation, so nothing the peg
   * does here would put coins in a federator's hands. On a real chain its operator moves them across
   * after seeding the peg; a genesis that simply allocates them, as this one does, has issued coins
   * no bitcoin backs, which is fine for a test and would not be fine for a chain.
   */
  private static final Wei FEDERATOR_FLOAT = Wei.of(BigInteger.TEN.pow(19));

  private ExecutionContextTestFixture context;
  private MutableBlockchain blockchain;
  private WorldStateArchive worldStateArchive;
  private ProtocolSchedule protocolSchedule;
  private Block blockBase;

  @BeforeEach
  void setUp() {
    final GenesisConfig genesisConfig = GenesisConfig.fromConfig(genesisJson());
    protocolSchedule = scheduleFor(genesisConfig);
    context =
        ExecutionContextTestFixture.builder(genesisConfig)
            .protocolSchedule(protocolSchedule)
            .build();
    blockchain = context.getBlockchain();
    worldStateArchive = context.getStateArchive();

    blockBase =
        buildBlock(
            context.getGenesis(), 2L, receiveHeadersTx(0), registerBtcTransactionTx(1));
    importAsBest(blockBase);
  }

  @Test
  void theBridgeSeesThePeginAndTheFederationHasNotPaidForIt() {
    // Both calls in blockBase went at a zero gas price, which only the bootstrap window allows, so
    // the federator's float is untouched and the headers and the peg-in still landed.
    assertThat(balanceOf(FEDERATOR_ADDRESS)).isEqualTo(FEDERATOR_FLOAT);
    assertThat(bridgeState().getBtcBlockchainBestChainHeight())
        .isEqualTo(RegtestBitcoinFixture.HEADERS.size());
    // The peg-in released coins, so the bridge no longer holds all of them.
    assertThat(balanceOf(BRIDGE)).isLessThan(WHOLE_SUPPLY);
  }

  @Test
  void testNoFork() {
    final Block blockB1 = buildBlock(blockBase, 2L, releaseTx());
    importAsBest(blockB1);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockB2 = buildBlock(blockB1, 2L);
    importAsBest(blockB2);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockB3 = buildBlock(blockB2, 2L, updateCollectionsTx(3));
    importAsBest(blockB3);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_CONFIRMATIONS);
  }

  @Test
  void testLosingForkBuiltFirst() {
    final Block blockA1 = buildBlock(blockBase, 4L);
    importAsBest(blockA1);
    assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

    final Block blockA2 = buildBlock(blockA1, 6L, releaseTx());
    importAsBest(blockA2);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockB1 = buildBlock(blockBase, 1L, releaseTx());
    importAsSideFork(blockB1);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockB2 = buildBlock(blockB1, 2L);
    importAsSideFork(blockB2);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockB3 = buildBlock(blockB2, 10L, updateCollectionsTx(3));
    importAsBest(blockB3);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_CONFIRMATIONS);
  }

  @Test
  void testWinningForkBuiltFirst() {
    final Block blockB1 = buildBlock(blockBase, 2L, releaseTx());
    importAsBest(blockB1);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockB2 = buildBlock(blockB1, 6L);
    importAsBest(blockB2);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockA1 = buildBlock(blockBase, 1L);
    importAsSideFork(blockA1);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockA2 = buildBlock(blockA1, 1L, releaseTx());
    importAsSideFork(blockA2);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockB3 = buildBlock(blockB2, 12L, updateCollectionsTx(3));
    importAsBest(blockB3);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_CONFIRMATIONS);
  }

  @Test
  void testReleaseTxJustInLoosingFork() {
    final Block blockA1 = buildBlock(blockBase, 2L, releaseTx());
    importAsBest(blockA1);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockA2 = buildBlock(blockA1, 3L);
    importAsBest(blockA2);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockB1 = buildBlock(blockBase, 2L);
    importAsSideFork(blockB1);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    final Block blockB2 = buildBlock(blockB1, 1L);
    importAsSideFork(blockB2);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_SELECTION);

    // The winning branch never held the peg-out, so the bridge must forget it entirely — and the
    // federator, never having sent it here, is still on nonce 2.
    final Block blockB3 = buildBlock(blockB2, 6L, updateCollectionsTx(2));
    importAsBest(blockB3);
    assertReleaseTransactionState(ReleaseTransactionState.NO_TX);
  }

  @Test
  void testReleaseTxJustInWinningFork() {
    final Block blockA1 = buildBlock(blockBase, 4L);
    importAsBest(blockA1);
    assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

    final Block blockA2 = buildBlock(blockA1, 5L);
    importAsBest(blockA2);
    assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

    final Block blockB1 = buildBlock(blockBase, 1L, releaseTx());
    importAsSideFork(blockB1);
    assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

    final Block blockB2 = buildBlock(blockB1, 1L);
    importAsSideFork(blockB2);
    assertReleaseTransactionState(ReleaseTransactionState.NO_TX);

    final Block blockB3 = buildBlock(blockB2, 10L, updateCollectionsTx(3));
    importAsBest(blockB3);
    assertReleaseTransactionState(ReleaseTransactionState.WAITING_FOR_CONFIRMATIONS);
  }

  // --------------------------------------------------------------- bridge state

  private enum ReleaseTransactionState {
    NO_TX,
    WAITING_FOR_SIGNATURES,
    WAITING_FOR_SELECTION,
    WAITING_FOR_CONFIRMATIONS
  }

  private void assertReleaseTransactionState(final ReleaseTransactionState expected) {
    final BridgeState state = bridgeState();
    final int queued = state.getReleaseRequestQueue().getEntries().size();
    final int awaitingConfirmations = state.getPegoutsWaitingForConfirmations().getEntries().size();
    final int awaitingSignatures = state.getRskTxsWaitingForSignatures().size();

    switch (expected) {
      case WAITING_FOR_SELECTION -> assertThat(List.of(queued, awaitingConfirmations, awaitingSignatures)).containsExactly(1, 0, 0);
      case WAITING_FOR_SIGNATURES -> assertThat(List.of(queued, awaitingConfirmations, awaitingSignatures)).containsExactly(0, 0, 1);
      case WAITING_FOR_CONFIRMATIONS -> assertThat(List.of(queued, awaitingConfirmations, awaitingSignatures)).containsExactly(0, 1, 0);
      case NO_TX -> assertThat(List.of(queued, awaitingConfirmations, awaitingSignatures)).containsExactly(0, 0, 0);
    }
  }

  /** Reads the bridge as a client would: a local call against whatever the chain head now is. */
  private BridgeState bridgeState() {
    final BlockHeader head = blockchain.getChainHeadHeader();
    final Transaction query =
        Transaction.builder()
            .type(TransactionType.FRONTIER)
            .nonce(0)
            .gasPrice(Wei.ZERO)
            .gasLimit(10_000_000L)
            .to(BRIDGE)
            .value(Wei.ZERO)
            .payload(BridgeMethods.GET_STATE_FOR_DEBUGGING.getFunction().encode())
            .chainId(CHAIN_ID)
            .signAndBuild(OBSERVER);

    final MutableWorldState worldState = worldStateAt(head);
    final WorldUpdater updater = worldState.updater();
    final TransactionProcessingResult result =
        specFor(head)
            .getTransactionProcessor()
            .processTransaction(
                updater,
                head,
                query,
                head.getCoinbase(),
                OperationTracer.NO_TRACING,
                (frame, number) -> Hash.ZERO,
                // as eth_call does: a query need not meet the fee floor
                TransactionValidationParams.transactionSimulatorAllowUnderpricedAndFutureNonce(),
                Wei.ZERO);

    if (!result.isSuccessful()) {
      throw new AssertionError(
          "getStateForDebugging did not succeed: "
              + (result.isInvalid()
                  ? result.getValidationResult().getErrorMessage()
                  : result.getRevertReason().map(Bytes::toString).orElse("reverted")));
    }
    try {
      final Object[] decoded =
          BridgeMethods.GET_STATE_FOR_DEBUGGING.getFunction().decodeResult(result.getOutput());
      return BridgeState.create(new BridgeRegTestConstants(), (byte[]) decoded[0]);
    } catch (final Exception e) {
      throw new IllegalStateException("could not read the bridge's state", e);
    }
  }

  // ------------------------------------------------------------- transactions

  /** Valueless bridge calls from a federator who holds nothing: free, inside the window. */
  private Transaction bridgeCall(final long nonce, final Bytes payload) {
    return Transaction.builder()
        .type(TransactionType.FRONTIER)
        .nonce(nonce)
        .gasPrice(Wei.ZERO)
        .gasLimit(6_000_000L)
        .to(BRIDGE)
        .value(Wei.ZERO)
        .payload(payload)
        .chainId(CHAIN_ID)
        .signAndBuild(FEDERATOR);
  }

  private Transaction receiveHeadersTx(final long nonce) {
    final Object[] headers =
        RegtestBitcoinFixture.HEADERS.stream()
            .map(hex -> Bytes.fromHexString(hex).toArrayUnsafe())
            .toArray();
    return bridgeCall(
        nonce, BridgeMethods.RECEIVE_HEADERS.getFunction().encode(new Object[] {headers}));
  }

  private Transaction registerBtcTransactionTx(final long nonce) {
    return bridgeCall(
        nonce,
        BridgeMethods.REGISTER_BTC_TRANSACTION
            .getFunction()
            .encode(
                Bytes.fromHexString(RegtestBitcoinFixture.PEGIN_TRANSACTION).toArrayUnsafe(),
                RegtestBitcoinFixture.PEGIN_BTC_HEIGHT,
                Bytes.fromHexString(RegtestBitcoinFixture.PEGIN_PARTIAL_MERKLE_TREE)
                    .toArrayUnsafe()));
  }

  /**
   * The nonce is the branch's, not the chain's. A federator that asked for a peg-out on one branch
   * has not asked for it on another, so what it may send next differs between them, which is the
   * same fact these tests are about seen from the sender's side.
   */
  private Transaction updateCollectionsTx(final long nonce) {
    return bridgeCall(nonce, BridgeMethods.UPDATE_COLLECTIONS.getFunction().encode());
  }

  /**
   * The peg-out. It carries value, so the bootstrap window does not cover it and the federator pays
   * for it like anybody else, out of what the peg-in released.
   */
  private Transaction releaseTx() {
    return Transaction.builder()
        .type(TransactionType.FRONTIER)
        .nonce(2)
        .gasPrice(Wei.of(10))
        .gasLimit(1_000_000L)
        .to(BRIDGE)
        .value(Wei.of(BigInteger.TEN.pow(18)))
        .payload(BridgeMethods.RELEASE_BTC.getFunction().encode())
        .chainId(CHAIN_ID)
        .signAndBuild(FEDERATOR);
  }

  // ------------------------------------------------------------------ blocks

  private void importAsBest(final Block block) {
    append(block);
    assertThat(blockchain.getChainHeadHash())
        .describedAs("block %d should have become the chain head", block.getHeader().getNumber())
        .isEqualTo(block.getHash());
  }

  private void importAsSideFork(final Block block) {
    final Hash headBefore = blockchain.getChainHeadHash();
    append(block);
    assertThat(blockchain.getChainHeadHash())
        .describedAs("block %d should not have become the chain head", block.getHeader().getNumber())
        .isEqualTo(headBefore);
  }

  private void append(final Block block) {
    blockchain.appendBlock(block, receiptsOf(block));
  }

  private final java.util.Map<Hash, List<TransactionReceipt>> receipts = new java.util.HashMap<>();

  private List<TransactionReceipt> receiptsOf(final Block block) {
    return receipts.get(block.getHash());
  }

  /**
   * Executes the transactions against the parent's state and seals the result into a block, so that
   * a branch can be built on any parent regardless of where the chain head happens to be.
   */
  private Block buildBlock(final Block parent, final long difficulty, final Transaction... txs) {
    final List<Transaction> transactions = List.of(txs);
    final BlockHeader parentHeader = parent.getHeader();
    final MutableWorldState worldState = worldStateAt(parentHeader);
    final ProtocolSpec spec = specFor(parentHeader);

    final org.hyperledger.besu.ethereum.core.ProcessableBlockHeader pending =
        BlockHeaderBuilder.create()
            .parentHash(parent.getHash())
            .coinbase(Address.ZERO)
            .difficulty(Difficulty.of(difficulty))
            .number(parentHeader.getNumber() + 1)
            .gasLimit(parentHeader.getGasLimit())
            .timestamp(parentHeader.getTimestamp() + 1)
            .baseFee(Wei.ONE)
            .buildProcessableBlockHeader();

    final List<TransactionReceipt> blockReceipts = new ArrayList<>();
    long cumulativeGas = 0;
    final WorldUpdater updater = worldState.updater();
    for (final Transaction tx : transactions) {
      final TransactionProcessingResult result =
          spec.getTransactionProcessor()
              .processTransaction(
                  updater,
                  pending,
                  tx,
                  Address.ZERO,
                  OperationTracer.NO_TRACING,
                  (frame, number) -> Hash.ZERO,
                  TransactionValidationParams.processingBlock(),
                  Wei.ZERO);
      if (result.isInvalid()) {
        throw new AssertionError(
            "transaction rejected while building a block: "
                + result.getValidationResult().getErrorMessage());
      }
      cumulativeGas += result.getEstimateGasUsedByTransaction();
      blockReceipts.add(
          spec.getTransactionReceiptFactory()
              .create(tx.getType(), result, worldState, cumulativeGas));
    }
    updater.commit();
    worldState.persist(null);

    final BlockHeader header =
        BlockHeaderBuilder.create()
            .parentHash(parent.getHash())
            .ommersHash(BodyValidation.ommersHash(Collections.emptyList()))
            .coinbase(Address.ZERO)
            .stateRoot(worldState.rootHash())
            .transactionsRoot(BodyValidation.transactionsRoot(transactions))
            .receiptsRoot(BodyValidation.receiptsRoot(blockReceipts))
            .logsBloom(BodyValidation.logsBloom(blockReceipts))
            .difficulty(Difficulty.of(difficulty))
            .number(parentHeader.getNumber() + 1)
            .gasLimit(parentHeader.getGasLimit())
            .gasUsed(cumulativeGas)
            .timestamp(parentHeader.getTimestamp() + 1)
            .extraData(Bytes.EMPTY)
            .mixHash(Hash.ZERO)
            .nonce(0L)
            .baseFee(Wei.ONE)
            .blockHeaderFunctions(new MainnetBlockHeaderFunctions())
            .buildBlockHeader();

    final Block block =
        new Block(header, new BlockBody(transactions, Collections.emptyList()));
    receipts.put(block.getHash(), blockReceipts);
    return block;
  }

  // ------------------------------------------------------------------ harness

  private MutableWorldState worldStateAt(final BlockHeader header) {
    return worldStateArchive
        .getWorldState(
            org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams
                .withStateRootAndBlockHashAndUpdateNodeHead(
                    header.getStateRoot(), header.getBlockHash()))
        .orElseThrow(() -> new IllegalStateException("no world state at " + header.getNumber()));
  }

  private ProtocolSpec specFor(final BlockHeader header) {
    return protocolSchedule.getByBlockHeader(header);
  }

  private Wei balanceOf(final Address address) {
    final var account = worldStateAt(blockchain.getChainHeadHeader()).get(address);
    return account == null ? Wei.ZERO : account.getBalance();
  }

  private static ProtocolSchedule scheduleFor(final GenesisConfig genesisConfig) {
    return new ProtocolScheduleBuilder(
            genesisConfig.getConfigOptions(),
            Optional.of(CHAIN_ID),
            // stands in for the consensus engine, which is what zeroes Ethereum's block rewards
            ProtocolSpecAdapters.create(0, spec -> spec.blockReward(Wei.ZERO)),
            false,
            EvmConfiguration.DEFAULT,
            MiningConfiguration.MINING_DISABLED,
            new BadBlockManager(),
            false,
            BalConfiguration.DEFAULT,
            new NoOpMetricsSystem())
        .createProtocolSchedule();
  }

  private static String genesisJson() {
    return "{"
        + "\"config\":{"
        + "\"chainId\":33,"
        + "\"bridgeNetwork\":\"regtest\","
        + "\"bridgeTxsPaidBlock\":" + WINDOW_CLOSES_AT + ","
        + "\"homesteadBlock\":0,\"eip150Block\":0,\"eip155Block\":0,\"eip158Block\":0,"
        + "\"byzantiumBlock\":0,\"constantinopleBlock\":0,\"petersburgBlock\":0,"
        + "\"istanbulBlock\":0,\"berlinBlock\":0,\"londonBlock\":0,\"ethash\":{}"
        + "},"
        + "\"nonce\":\"0x0\",\"timestamp\":\"0x0\",\"gasLimit\":\"0x2fefd800\","
        + "\"difficulty\":\"0x1\",\"baseFeePerGas\":\"0x1\","
        + "\"alloc\":{"
        + "\"0000000000000000000000000000000001000006\":{\"balance\":\"" + WHOLE_SUPPLY.toBigInteger() + "\"},"
        + "\"" + FEDERATOR_ADDRESS.toHexString().substring(2) + "\":{\"balance\":\"" + FEDERATOR_FLOAT.toBigInteger() + "\"}"
        + "}}";
  }
}
