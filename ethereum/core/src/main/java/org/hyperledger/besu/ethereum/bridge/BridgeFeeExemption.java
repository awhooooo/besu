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

import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.Transaction;

import java.util.Set;

import co.rsk.peg.BootstrapWindow;
import co.rsk.peg.BridgeAddresses;
import co.rsk.peg.host.BridgePrecompiles;

/**
 * Which transactions may be sent for nothing while a chain brings its peg to life.
 *
 * <p>At genesis the bridge holds every coin and the federation holds none, so the federation cannot
 * pay for the transactions that would release the first of them. Without a way out of that, the whole
 * supply stays locked forever. The way out is narrow on purpose: for a bounded number of blocks, a
 * fixed set of addresses may send valueless transactions to the bridge without meeting the fee floor.
 *
 * <p>Every part of that is checked. Another sender pays. A transaction to anywhere else pays. A
 * transaction carrying value pays, which also means the exemption can never move money: the only
 * thing it can do is let a peg operator pay no fee. And it all ends at a block named in the genesis
 * file.
 *
 * <p>A chain with no bridge, or with no window, gets {@link #NONE}, which exempts nothing.
 */
public final class BridgeFeeExemption {

  /** Exempts nothing. */
  public static final BridgeFeeExemption NONE =
      new BridgeFeeExemption(Set.of(), BootstrapWindow.CLOSED);

  private final Set<Address> bootstrapSenders;
  private final BootstrapWindow window;

  private BridgeFeeExemption(final Set<Address> bootstrapSenders, final BootstrapWindow window) {
    this.bootstrapSenders = bootstrapSenders;
    this.window = window;
  }

  /**
   * Reads the chain's genesis to decide who, if anyone, may send bridge transactions for nothing.
   *
   * @param config the genesis configuration
   * @return the exemption, which exempts nothing unless the chain has both a bridge and a window
   */
  public static BridgeFeeExemption forGenesis(final GenesisConfigOptions config) {
    return config
        .getBridgeNetwork()
        .map(
            network ->
                new BridgeFeeExemption(
                    BridgePrecompiles.bootstrapSendersFor(network),
                    new BootstrapWindow(config.getBridgeTxsPaidBlock().orElse(0L))))
        .orElse(NONE);
  }

  /**
   * Whether this transaction may be sent without meeting the fee floor at this height.
   *
   * @param transaction the transaction being validated or executed
   * @param blockNumber the block it would be part of
   * @return true only for a valueless bridge transaction from a peg operator, inside the window
   */
  public boolean covers(final Transaction transaction, final long blockNumber) {
    if (bootstrapSenders.isEmpty() || !window.isOpenAt(blockNumber)) {
      return false;
    }
    if (transaction.getTo().filter(BridgeAddresses.BRIDGE::equals).isEmpty()) {
      return false;
    }
    if (!transaction.getValue().isZero()) {
      return false;
    }
    try {
      return bootstrapSenders.contains(transaction.getSender());
    } catch (final RuntimeException cannotRecoverSender) {
      // Validation runs before the sender is known precisely because a transaction may not be
      // signed well enough to name one. Such a transaction is not exempt, and ordinary validation
      // will report what is actually wrong with it.
      return false;
    }
  }

  /**
   * Whether this chain exempts anything at all, for callers deciding whether to look further.
   *
   * @return true when some transaction could be exempt
   */
  public boolean exemptsAnything() {
    return !bootstrapSenders.isEmpty() && window.bridgeTxsPaidBlock() > 0;
  }
}
