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
package org.hyperledger.besu.evm.frame;

import static java.util.Objects.requireNonNull;

import org.hyperledger.besu.datatypes.Hash;

import java.util.Optional;
import java.util.function.Supplier;

import org.apache.tuweni.bytes.Bytes;

/**
 * What a precompiled contract may know about the transaction it is running under, beyond what the
 * EVM exposes to ordinary code.
 *
 * <p>The EVM has no opcode for either fact, and deployed code cannot reach them here: they are
 * carried on {@link TxValues} for the native contracts a node ships. A transaction's hash covers
 * its signature, so code that branched on it would behave differently for two signatures of the
 * same intent; and the sender's public key is reduced to an address before any code runs, and
 * never restored.
 *
 * <p>Absent for executions that no transaction started, such as the system calls of EIP-2935 and
 * EIP-4788.
 *
 * <p>Both facts arrive as suppliers, because the frame that carries them is built for every
 * transaction the node executes and almost none of them ask. Hashing a transaction means encoding
 * it and taking a keccak, and recovering a public key costs an elliptic curve operation; neither is
 * work to do on the chance that somebody wants it. Whether a supplier memoises is its own
 * business: a hash is cheap to keep, a public key costs more in footprint than it saves.
 *
 * @param hashSupplier supplies the hash of the transaction being executed
 * @param senderPublicKeySupplier recovers the sender's public key, in the 64-byte X||Y encoding, or
 *     returns empty when it cannot be recovered
 */
public record PrecompiledContractTransaction(
    Supplier<Hash> hashSupplier, Supplier<Optional<Bytes>> senderPublicKeySupplier) {

  /**
   * Creates a new PrecompiledContractTransaction.
   *
   * @param hashSupplier supplies the hash of the transaction being executed
   * @param senderPublicKeySupplier recovers the sender's public key
   */
  public PrecompiledContractTransaction(
      final Supplier<Hash> hashSupplier,
      final Supplier<Optional<Bytes>> senderPublicKeySupplier) {
    this.hashSupplier = requireNonNull(hashSupplier, "hashSupplier");
    this.senderPublicKeySupplier =
        requireNonNull(senderPublicKeySupplier, "senderPublicKeySupplier");
  }

  /**
   * The hash of the transaction being executed, computed on first use.
   *
   * @return the transaction hash, never null
   */
  public Hash hash() {
    return requireNonNull(hashSupplier.get(), "the transaction being executed supplied no hash");
  }

  /**
   * The sender's public key in the 64-byte X||Y encoding, recovered from the transaction signature
   * on first use.
   *
   * @return the sender's public key, or empty when it cannot be recovered
   */
  public Optional<Bytes> senderPublicKey() {
    return senderPublicKeySupplier.get();
  }
}
