/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.mainnet;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

public class TransactionValidationParamsTest {

  @Test
  public void isAllowFutureNonce() {
    assertThat(
            ImmutableTransactionValidationParams.builder()
                .isAllowFutureNonce(true)
                .build()
                .isAllowFutureNonce())
        .isTrue();
    assertThat(
            ImmutableTransactionValidationParams.builder()
                .isAllowFutureNonce(false)
                .build()
                .isAllowFutureNonce())
        .isFalse();
  }

  @Test
  public void checkOnchainPermissions() {
    assertThat(
            ImmutableTransactionValidationParams.builder()
                .checkOnchainPermissions(true)
                .build()
                .checkOnchainPermissions())
        .isTrue();
    assertThat(
            ImmutableTransactionValidationParams.builder()
                .checkOnchainPermissions(false)
                .build()
                .checkOnchainPermissions())
        .isFalse();
  }

  @Test
  public void checkLocalPermissions() {
    assertThat(
            ImmutableTransactionValidationParams.builder()
                .checkLocalPermissions(true)
                .build()
                .checkLocalPermissions())
        .isTrue();
    assertThat(
            ImmutableTransactionValidationParams.builder()
                .checkLocalPermissions(false)
                .build()
                .checkLocalPermissions())
        .isFalse();
  }

  @Test
  public void transactionSimulator() {
    final TransactionValidationParams params = TransactionValidationParams.transactionSimulator();
    assertThat(params.isAllowFutureNonce()).isFalse();
    assertThat(params.checkOnchainPermissions()).isFalse();
    assertThat(params.checkLocalPermissions()).isFalse();
  }

  @Test
  public void processingBlock() {
    final TransactionValidationParams params = TransactionValidationParams.processingBlock();
    assertThat(params.isAllowFutureNonce()).isFalse();
    assertThat(params.checkOnchainPermissions()).isTrue();
    assertThat(params.checkLocalPermissions()).isFalse();
  }

  @Test
  public void transactionPool() {
    final TransactionValidationParams params = TransactionValidationParams.transactionPool();
    assertThat(params.isAllowFutureNonce()).isTrue();
    assertThat(params.checkOnchainPermissions()).isTrue();
    assertThat(params.checkLocalPermissions()).isTrue();
  }

  @Test
  public void mining() {
    final TransactionValidationParams params = TransactionValidationParams.mining();
    assertThat(params.isAllowFutureNonce()).isFalse();
    assertThat(params.checkOnchainPermissions()).isTrue();
    assertThat(params.checkLocalPermissions()).isTrue();
  }

  @Test
  public void blockReplay() {
    final TransactionValidationParams params = TransactionValidationParams.blockReplay();
    assertThat(params.isAllowFutureNonce()).isFalse();
    assertThat(params.checkOnchainPermissions()).isFalse();
    assertThat(params.checkLocalPermissions()).isFalse();
  }

  /**
   * Whether an execution is a simulation decides what a node-native contract may serve, so every
   * constant here has to be deliberately on one side or the other. Reflecting over the declared
   * constants rather than listing them means a newly added one fails this test until somebody
   * decides which side it belongs on.
   *
   * <p>{@code blockSimulatorConsensusStrictParams} is not a simulation on purpose: it exists so
   * that eth_simulateV1 reproduces block-production semantics, and marking it would make it
   * reproduce eth_call semantics instead, which is exactly what it is there to avoid.
   */
  @Test
  public void everyConstantIsDeliberatelyOnOneSideOfTheSimulationLine() throws Exception {
    final Set<String> simulations =
        Set.of(
            "transactionSimulatorParams",
            "transactionSimulatorParamsAllowFutureNonce",
            "transactionSimulatorAllowUnderpricedAndFutureNonceParams",
            "transactionSimulatorAllowExceedingBalanceParams",
            "transactionSimulatorAllowExceedingBalanceAndFutureNonceParams",
            "blockSimulatorStrictParams",
            "blockSimulatorNonStrictParams");

    final Set<String> executions =
        Set.of(
            "processingBlockParams",
            "transactionPoolParams",
            "miningParams",
            "blockReplayParams",
            "blockSimulatorConsensusStrictParams");

    final Set<String> found = new TreeSet<>();
    for (final Field field : TransactionValidationParams.class.getDeclaredFields()) {
      if (!TransactionValidationParams.class.equals(field.getType())
          || !Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      final String name = field.getName();
      found.add(name);

      assertThat(simulations.contains(name) || executions.contains(name))
          .withFailMessage(
              "%s is not classified: decide whether an execution using it can ever become part of"
                  + " a block, then add it to this test",
              name)
          .isTrue();

      assertThat(((TransactionValidationParams) field.get(null)).isSimulation())
          .withFailMessage("%s is on the wrong side of the simulation line", name)
          .isEqualTo(simulations.contains(name));
    }

    final Set<String> classified = new TreeSet<>(simulations);
    classified.addAll(executions);
    assertThat(found)
        .withFailMessage("this test names constants that no longer exist")
        .isEqualTo(classified);
  }

  @Test
  public void anExecutionIsNotASimulationUnlessItSaysSo() {
    assertThat(ImmutableTransactionValidationParams.builder().build().isSimulation()).isFalse();
  }
}
