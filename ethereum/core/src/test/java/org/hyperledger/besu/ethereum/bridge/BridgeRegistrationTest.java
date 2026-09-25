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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.config.StubGenesisConfigOptions;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleBuilder;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecAdapters;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecBuilder;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.math.BigInteger;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/** The bridge is registered from the genesis file, on every milestone, or not at all. */
class BridgeRegistrationTest {

  /** Same address as on RSK. */
  private static final Address BRIDGE =
      Address.fromHexString("0x0000000000000000000000000000000001000006");

  private static final Address ECRECOVER = Address.fromHexString("0x01");

  @Test
  void aChainThatNamesNoBitcoinNetworkHasNothingAtTheBridgeAddress() {
    final ProtocolSpec spec = specFor(new StubGenesisConfigOptions());

    assertThat(spec.getPrecompileContractRegistry().get(BRIDGE)).isNull();
    assertThat(spec.getPrecompileContractRegistry().get(ECRECOVER)).isNotNull();
  }

  @Test
  void aChainThatNamesOneGetsTheBridge() {
    final ProtocolSpec spec = specFor(bridgeOn("regtest"));

    final PrecompiledContract bridge = spec.getPrecompileContractRegistry().get(BRIDGE);
    assertThat(bridge).isNotNull();
    assertThat(bridge.getName()).isEqualTo("Bridge");
  }

  @Test
  void theEthereumPrecompilesAreKept() {
    final ProtocolSpec withBridge = specFor(bridgeOn("regtest"));
    final ProtocolSpec without = specFor(new StubGenesisConfigOptions());

    // Registering the bridge adds to the milestone's precompiles; it does not replace them.
    assertThat(withBridge.getPrecompileContractRegistry().getPrecompileAddresses())
        .containsAll(without.getPrecompileContractRegistry().getPrecompileAddresses())
        .contains(BRIDGE);
  }

  @Test
  void everyMilestoneSharesTheOneBridge() {
    // The bridge caches Bitcoin headers. One instance per milestone would mean one cache per
    // milestone, all but the current one cold and refilling from storage on every switch.
    final ProtocolSchedule schedule =
        scheduleFor(bridgeOn("regtest").homesteadBlock(1).byzantiumBlock(2).berlinBlock(3));

    final PrecompiledContract atGenesis = bridgeIn(schedule, 0);
    assertThat(atGenesis).isNotNull();
    assertThat(bridgeIn(schedule, 1)).isSameAs(atGenesis);
    assertThat(bridgeIn(schedule, 2)).isSameAs(atGenesis);
    assertThat(bridgeIn(schedule, 3)).isSameAs(atGenesis);
  }

  @Test
  void theNetworkNameIsNotCaseSensitive() {
    assertThat(specFor(bridgeOn("RegTest")).getPrecompileContractRegistry().get(BRIDGE)).isNotNull();
  }

  @Test
  void aBitcoinNetworkThatDoesNotExistFailsAtStartup() {
    // Not on the first block that reaches the bridge, and not as a silently absent precompile.
    assertThatThrownBy(() -> scheduleFor(bridgeOn("bitcoin-mainnet")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bitcoin-mainnet")
        .hasMessageContaining("mainnet, testnet or regtest");
  }

  @Test
  void aChainWithABridgeMayNotPayABlockReward() {
    // A block reward has no counterpart debit, so it would issue coins no bitcoin backs.
    assertThatThrownBy(
            () -> scheduleFor(bridgeOn("regtest"), spec -> spec.blockReward(Wei.of(1))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot pay a block reward")
        .hasMessageContaining("Set the block reward to zero");
  }

  @Test
  void aChainWithoutABridgeMayPayWhateverItLikes() {
    assertThat(scheduleFor(new StubGenesisConfigOptions(), Function.identity())).isNotNull();
  }

  @Test
  void aProofOfWorkChainCannotHaveABridgeAtAll() {
    // Nothing zeroes the reward on a bare mainnet schedule, so the guard refuses it outright.
    assertThatThrownBy(() -> scheduleFor(bridgeOn("regtest"), Function.identity()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot pay a block reward");
  }

  @Test
  void theBridgeMustHoldTheWholeSupplyAtGenesis() {
    // 21,000,000 coins, each standing for bitcoin not yet locked.
    final Wei whole = Wei.of(new java.math.BigInteger("21000000").multiply(java.math.BigInteger.TEN.pow(18)));

    assertThatThrownBy(() -> BridgeRegistration.checkGenesisSupply(genesisAllocating(whole.subtract(Wei.ONE))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must hold the whole supply");
    assertThatThrownBy(() -> BridgeRegistration.checkGenesisSupply(genesisAllocating(Wei.ZERO)))
        .isInstanceOf(IllegalStateException.class);

    BridgeRegistration.checkGenesisSupply(genesisAllocating(whole));
  }

  @Test
  void aChainWithoutABridgeMayAllocateAsItPleases() {
    BridgeRegistration.checkGenesisSupply(
        GenesisConfig.fromConfig(
            "{\"config\":{},\"alloc\":{\"0000000000000000000000000000000001000006\":{\"balance\":\"1\"}}}"));
  }

  private static GenesisConfig genesisAllocating(final Wei toTheBridge) {
    return GenesisConfig.fromConfig(
        "{\"config\":{\"bridgeNetwork\":\"regtest\"},\"alloc\":{"
            + "\"0000000000000000000000000000000001000006\":{\"balance\":\""
            + toTheBridge.toBigInteger()
            + "\"}}}");
  }

  private static StubGenesisConfigOptions bridgeOn(final String network) {
    final StubGenesisConfigOptions options = new StubGenesisConfigOptions();
    options.bridgeNetwork(Optional.of(network));
    return options;
  }

  private static PrecompiledContract bridgeIn(final ProtocolSchedule schedule, final long number) {
    return schedule
        .getByBlockHeader(new BlockHeaderTestFixture().number(number).buildHeader())
        .getPrecompileContractRegistry()
        .get(BRIDGE);
  }

  private static ProtocolSpec specFor(final StubGenesisConfigOptions options) {
    return scheduleFor(options)
        .getByBlockHeader(new BlockHeaderTestFixture().number(0).buildHeader());
  }

  /**
   * Besu's mainnet specs carry Ethereum's proof-of-work block rewards, and a consensus engine
   * replaces them: QBFT sets whatever the genesis file asks for, which is zero unless told otherwise.
   * These tests stand in for that, since a chain with a bridge cannot start without it.
   */
  private static ProtocolSchedule scheduleFor(final StubGenesisConfigOptions options) {
    return scheduleFor(options, spec -> spec.blockReward(Wei.ZERO));
  }

  private static ProtocolSchedule scheduleFor(
      final StubGenesisConfigOptions options,
      final Function<ProtocolSpecBuilder, ProtocolSpecBuilder> modifier) {
    return new ProtocolScheduleBuilder(
            options,
            Optional.of(BigInteger.ONE),
            ProtocolSpecAdapters.create(0, modifier),
            false,
            EvmConfiguration.DEFAULT,
            MiningConfiguration.MINING_DISABLED,
            new BadBlockManager(),
            false,
            BalConfiguration.DEFAULT,
            new NoOpMetricsSystem())
        .createProtocolSchedule();
  }
}
