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
import org.hyperledger.besu.ethereum.core.Transaction;

import co.rsk.peg.host.BridgePrecompiles;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

/**
 * The exemption is the only thing standing between a chain and a peg that can never start, so it has
 * to be exactly as wide as that job and no wider.
 */
class BridgeFeeExemptionTest {

  private static final Address BRIDGE =
      Address.fromHexString("0x0000000000000000000000000000000001000006");
  private static final Address SOMEWHERE_ELSE =
      Address.fromHexString("0x00000000000000000000000000000000000000ff");
  private static final long WINDOW_CLOSES_AT = 100L;

  @Test
  void aChainWithoutABridgeExemptsNothing() {
    assertThat(BridgeFeeExemption.forGenesis(new StubGenesisConfigOptions()).exemptsAnything())
        .isFalse();
  }

  @Test
  void aBridgeWithoutAWindowExemptsNothing() {
    final StubGenesisConfigOptions options = new StubGenesisConfigOptions();
    options.bridgeNetwork(Optional.of("regtest"));

    final BridgeFeeExemption exemption = BridgeFeeExemption.forGenesis(options);

    assertThat(exemption.exemptsAnything()).isFalse();
    assertThat(exemption.covers(bridgeCallFrom(aBootstrapSender()), 0)).isFalse();
  }

  @Test
  void aBootstrapSenderMaySendAValuelessBridgeCallInsideTheWindow() {
    final BridgeFeeExemption exemption = withWindow();

    assertThat(exemption.exemptsAnything()).isTrue();
    assertThat(exemption.covers(bridgeCallFrom(aBootstrapSender()), 0)).isTrue();
    assertThat(exemption.covers(bridgeCallFrom(aBootstrapSender()), WINDOW_CLOSES_AT - 1)).isTrue();
  }

  @Test
  void theWindowCloses() {
    final BridgeFeeExemption exemption = withWindow();

    assertThat(exemption.covers(bridgeCallFrom(aBootstrapSender()), WINDOW_CLOSES_AT)).isFalse();
    assertThat(exemption.covers(bridgeCallFrom(aBootstrapSender()), WINDOW_CLOSES_AT + 1)).isFalse();
  }

  @Test
  void anyoneElsePays() {
    final KeyPair stranger = SignatureAlgorithmFactory.getInstance().generateKeyPair();

    assertThat(withWindow().covers(bridgeCallFrom(stranger), 0)).isFalse();
  }

  @Test
  void aCallToAnywhereElsePays() {
    final Transaction elsewhere =
        Transaction.builder()
            .type(TransactionType.FRONTIER)
            .nonce(0)
            .gasPrice(Wei.ZERO)
            .gasLimit(100_000)
            .to(SOMEWHERE_ELSE)
            .value(Wei.ZERO)
            .payload(Bytes.EMPTY)
            .signAndBuild(aBootstrapSender());

    assertThat(withWindow().covers(elsewhere, 0)).isFalse();
  }

  @Test
  void aCallCarryingValuePays() {
    // The exemption must never be able to move money: the most it can do is waive a fee.
    final Transaction withValue =
        Transaction.builder()
            .type(TransactionType.FRONTIER)
            .nonce(0)
            .gasPrice(Wei.ZERO)
            .gasLimit(100_000)
            .to(BRIDGE)
            .value(Wei.ONE)
            .payload(Bytes.EMPTY)
            .signAndBuild(aBootstrapSender());

    assertThat(withWindow().covers(withValue, 0)).isFalse();
  }

  @Test
  void aContractCreationPays() {
    final Transaction creation =
        Transaction.builder()
            .type(TransactionType.FRONTIER)
            .nonce(0)
            .gasPrice(Wei.ZERO)
            .gasLimit(100_000)
            .value(Wei.ZERO)
            .payload(Bytes.of(1, 2, 3))
            .signAndBuild(aBootstrapSender());

    assertThat(withWindow().covers(creation, 0)).isFalse();
  }

  private static BridgeFeeExemption withWindow() {
    final StubGenesisConfigOptions options = new StubGenesisConfigOptions();
    options.bridgeNetwork(Optional.of("regtest"));
    options.bridgeTxsPaidBlock(WINDOW_CLOSES_AT);
    return BridgeFeeExemption.forGenesis(options);
  }

  /**
   * The first of regtest's three genesis federation members. On regtest the bitcoin and RSK keys are
   * the same key, so signing a Besu transaction with it produces the very address the peg trusts.
   */
  private static KeyPair aBootstrapSender() {
    return SignatureAlgorithmFactory.getInstance()
        .createKeyPair(
            SignatureAlgorithmFactory.getInstance()
                .createPrivateKey(
                    new BigInteger(
                        "45c5b07fc1a6f58892615b7c31dca6c96db58c4bbc538a6b8a22999aaa860c32", 16)));
  }

  @Test
  void theBootstrapSendersAreThePegsOwnOperators() {
    final Set<Address> senders = BridgePrecompiles.bootstrapSendersFor("regtest");

    // three genesis federation members, five federation-change authorizers, and the fee-per-kB and
    // locking-cap authorizers on top
    assertThat(senders).contains(Address.extract(aBootstrapSender().getPublicKey()));
    assertThat(senders).hasSizeGreaterThan(3);
  }

  private static Transaction bridgeCallFrom(final KeyPair sender) {
    return Transaction.builder()
        .type(TransactionType.FRONTIER)
        .nonce(0)
        .gasPrice(Wei.ZERO)
        .gasLimit(100_000)
        .to(BRIDGE)
        .value(Wei.ZERO)
        .payload(Bytes.EMPTY)
        .signAndBuild(sender);
  }
}
