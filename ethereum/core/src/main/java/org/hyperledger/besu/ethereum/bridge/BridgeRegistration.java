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

import org.hyperledger.besu.config.GenesisAccount;
import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecBuilder;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

import java.util.Map;
import java.util.Optional;

import co.rsk.peg.BootstrapWindow;
import co.rsk.peg.BridgeAddresses;
import co.rsk.peg.host.BridgePrecompiles;

/**
 * Puts the PowPeg bridge on every protocol spec of a chain whose genesis asks for one.
 *
 * <p>A chain names its Bitcoin network in the genesis file and gets the bridge; a chain that names
 * none gets an ordinary Besu protocol schedule, with nothing at the address the bridge would have
 * occupied. The name is read once, at startup, so a genesis file naming a network that does not
 * exist fails there rather than on the first block that reaches the bridge.
 *
 * <p>One instance serves the whole schedule. That matters: Besu builds a {@link ProtocolSpecBuilder}
 * per milestone, and the bridge keeps a cache of recently stored Bitcoin headers that every spec has
 * to share. Building it per spec would give each milestone a cache of its own, all but one of them
 * cold.
 *
 * <p>Nothing of Bitcoin appears here. The bridge module hands back a contract and an address, and
 * this class knows no more about it than that.
 */
public final class BridgeRegistration {

  private final Map<Address, PrecompiledContract> bridge;

  private BridgeRegistration(final String network, final BootstrapWindow bootstrapWindow) {
    this.bridge = BridgePrecompiles.forNetwork(network, bootstrapWindow);
  }

  /**
   * Reads the chain's genesis to decide whether it has a bridge.
   *
   * @param config the genesis configuration
   * @return the registration to apply to every spec, or empty when the chain has no bridge
   * @throws IllegalArgumentException if the genesis names a Bitcoin network that does not exist
   */
  public static Optional<BridgeRegistration> forGenesis(final GenesisConfigOptions config) {
    final BootstrapWindow bootstrapWindow =
        new BootstrapWindow(config.getBridgeTxsPaidBlock().orElse(0L));
    return config.getBridgeNetwork().map(network -> new BridgeRegistration(network, bootstrapWindow));
  }

  /**
   * Adds the bridge to one protocol spec, leaving the Ethereum precompiles it already has alone.
   *
   * @param builder the spec being built
   */
  public void register(final ProtocolSpecBuilder builder) {
    builder.additionalPrecompiles(bridge);
  }

  /**
   * Refuses a genesis file whose bridge does not hold the whole supply.
   *
   * <p>Every coin stands for bitcoin the federation holds, and at genesis no bitcoin has been locked
   * yet, so every coin must still be in the bridge. A coin allocated anywhere else is one the peg
   * cannot honour, and a bridge holding less than the total is a peg that will run out before the
   * last holder is paid. Neither is recoverable once the chain has started, so it is refused here.
   *
   * @param genesisConfig the genesis file
   * @throws IllegalStateException if the bridge does not hold exactly the total supply
   */
  public static void checkGenesisSupply(final GenesisConfig genesisConfig) {
    final Optional<String> network = genesisConfig.getConfigOptions().getBridgeNetwork();
    if (network.isEmpty()) {
      return;
    }

    final Wei expected = BridgePrecompiles.totalSupplyFor(network.get());
    final Wei allocated =
        genesisConfig
            .streamAllocations()
            .filter(account -> BridgeAddresses.BRIDGE.equals(account.address()))
            .map(GenesisAccount::balance)
            .findFirst()
            .orElse(Wei.ZERO);

    if (!expected.equals(allocated)) {
      throw new IllegalStateException(
          "The bridge must hold the whole supply at genesis. "
              + BridgeAddresses.BRIDGE
              + " is allocated "
              + allocated
              + " wei, and the "
              + network.get()
              + " peg is worth "
              + expected
              + " wei.");
    }
  }

  /**
   * Refuses a chain that would mint coins.
   *
   * <p>Every coin on a pegged chain stands for bitcoin the federation is holding, and the supply is
   * fixed at genesis for exactly that reason. A block reward has no counterpart debit, so a chain
   * that pays one issues coins nothing is backing, and the peg stops meaning what it says. It is a
   * single number in the genesis file, so it is checked once, at startup, on every milestone.
   *
   * @param spec the spec just built
   * @throws IllegalStateException if the milestone would pay a block reward
   */
  public void checkMintsNothing(final ProtocolSpec spec) {
    if (!spec.getBlockReward().isZero()) {
      throw new IllegalStateException(
          "A chain with a bridge cannot pay a block reward: "
              + spec.getHardforkId()
              + " would mint "
              + spec.getBlockReward()
              + " per block, which no bitcoin backs. Set the block reward to zero.");
    }
  }
}
