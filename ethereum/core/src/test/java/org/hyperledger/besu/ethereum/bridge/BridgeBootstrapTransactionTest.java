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

import org.hyperledger.besu.config.StubGenesisConfigOptions;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleBuilder;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecAdapters;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.math.BigInteger;
import java.util.Optional;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

/**
 * The bootstrap rule as the chain actually applies it: a peg operator with no coins at all gets a
 * bridge transaction into a block, and nobody else does.
 */
class BridgeBootstrapTransactionTest {

  private static final Address BRIDGE =
      Address.fromHexString("0x0000000000000000000000000000000001000006");
  private static final Address COINBASE =
      Address.fromHexString("0x00000000000000000000000000000000000000c0");
  private static final long WINDOW_CLOSES_AT = 100L;
  private static final Wei BASE_FEE = Wei.of(1_000_000_000L);

  /** Regtest's first genesis federation member; on regtest its bitcoin and RSK keys are the same. */
  private static final KeyPair FEDERATOR =
      SignatureAlgorithmFactory.getInstance()
          .createKeyPair(
              SignatureAlgorithmFactory.getInstance()
                  .createPrivateKey(
                      new BigInteger(
                          "45c5b07fc1a6f58892615b7c31dca6c96db58c4bbc538a6b8a22999aaa860c32", 16)));

  private static final KeyPair STRANGER =
      SignatureAlgorithmFactory.getInstance()
          .createKeyPair(
              SignatureAlgorithmFactory.getInstance().createPrivateKey(BigInteger.valueOf(9999)));

  @Test
  void aPennilessFederatorGetsItsBridgeTransactionIntoABlock() {
    final TransactionProcessingResult result = run(bridgeCall(FEDERATOR, Wei.ZERO), 0);

    assertThat(result.isInvalid())
        .describedAs("a federator with no coins must still be able to start the peg")
        .isFalse();
  }

  @Test
  void nobodyElseDoes() {
    final TransactionProcessingResult result = run(bridgeCall(STRANGER, Wei.ZERO), 0);

    assertThat(result.isInvalid()).isTrue();
    assertThat(result.getValidationResult().getErrorMessage())
        .contains("gasPrice is less than the current BaseFee");
  }

  @Test
  void andNotAfterTheWindowHasClosed() {
    final TransactionProcessingResult result = run(bridgeCall(FEDERATOR, Wei.ZERO), WINDOW_CLOSES_AT);

    assertThat(result.isInvalid()).isTrue();
  }

  @Test
  void anExemptTransactionPaysItsMinerNothing() {
    final MutableWorldState world = InMemoryKeyValueStorageProvider.createInMemoryWorldState();
    run(world, bridgeCall(FEDERATOR, Wei.ZERO), 0);

    assertThat(balanceOf(world, COINBASE)).isEqualTo(Wei.ZERO);
  }

  @Test
  void anOrdinaryTransactionPaysItsMinerTheWholeFee() {
    // Not (gasPrice - baseFee): a chain with a bridge redirects the base fee rather than burning it,
    // because a burned coin is bitcoin that nobody can ever redeem.
    final MutableWorldState world = InMemoryKeyValueStorageProvider.createInMemoryWorldState();
    final Wei gasPrice = BASE_FEE.multiply(2);
    fund(world, Address.extract(STRANGER.getPublicKey()), Wei.of(BigInteger.TEN.pow(20)));

    final TransactionProcessingResult result =
        run(world, transferFrom(STRANGER, gasPrice), 0);

    assertThat(result.isSuccessful()).isTrue();
    assertThat(balanceOf(world, COINBASE))
        .isEqualTo(gasPrice.multiply(result.getEstimateGasUsedByTransaction()));
  }

  // ---------------------------------------------------------------- helpers

  private static TransactionProcessingResult run(final Transaction tx, final long blockNumber) {
    return run(InMemoryKeyValueStorageProvider.createInMemoryWorldState(), tx, blockNumber);
  }

  private static TransactionProcessingResult run(
      final MutableWorldState world, final Transaction tx, final long blockNumber) {
    final ProtocolSpec spec = spec();
    final BlockHeader header =
        new BlockHeaderTestFixture()
            .number(blockNumber)
            .baseFeePerGas(BASE_FEE)
            .coinbase(COINBASE)
            .buildHeader();
    final WorldUpdater updater = world.updater();
    final TransactionProcessingResult result =
        spec.getTransactionProcessor()
            .processTransaction(
                updater,
                header,
                tx,
                COINBASE,
                OperationTracer.NO_TRACING,
                (frame, number) -> org.hyperledger.besu.datatypes.Hash.ZERO,
                TransactionValidationParams.processingBlock(),
                Wei.ZERO);
    updater.commit();
    return result;
  }

  private static ProtocolSpec spec() {
    final StubGenesisConfigOptions options = new StubGenesisConfigOptions();
    options.bridgeNetwork(Optional.of("regtest"));
    options.bridgeTxsPaidBlock(WINDOW_CLOSES_AT);
    options.londonBlock(0);

    final ProtocolSchedule schedule =
        new ProtocolScheduleBuilder(
                options,
                Optional.of(BigInteger.ONE),
                ProtocolSpecAdapters.create(0, s -> s.blockReward(Wei.ZERO)),
                false,
                EvmConfiguration.DEFAULT,
                MiningConfiguration.MINING_DISABLED,
                new BadBlockManager(),
                false,
                BalConfiguration.DEFAULT,
                new NoOpMetricsSystem())
            .createProtocolSchedule();
    return schedule.getByBlockHeader(new BlockHeaderTestFixture().number(0).buildHeader());
  }

  private static Transaction bridgeCall(final KeyPair sender, final Wei value) {
    return Transaction.builder()
        .type(TransactionType.FRONTIER)
        .nonce(0)
        .gasPrice(Wei.ZERO)
        .gasLimit(200_000)
        .to(BRIDGE)
        .value(value)
        .payload(Bytes.EMPTY)
        .signAndBuild(sender);
  }

  private static Transaction transferFrom(final KeyPair sender, final Wei gasPrice) {
    return Transaction.builder()
        .type(TransactionType.FRONTIER)
        .nonce(0)
        .gasPrice(gasPrice)
        .gasLimit(100_000)
        .to(COINBASE)
        .value(Wei.ZERO)
        .payload(Bytes.EMPTY)
        .signAndBuild(sender);
  }

  private static void fund(final MutableWorldState world, final Address who, final Wei howMuch) {
    final WorldUpdater updater = world.updater();
    updater.getOrCreate(who).setBalance(howMuch);
    updater.commit();
  }

  private static Wei balanceOf(final MutableWorldState world, final Address who) {
    final var account = world.get(who);
    return account == null ? Wei.ZERO : account.getBalance();
  }
}
