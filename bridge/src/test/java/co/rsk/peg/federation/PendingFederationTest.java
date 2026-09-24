/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.peg.federation;

import static org.junit.jupiter.api.Assertions.*;

import co.rsk.RskTestUtils;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.peg.RlpTestUtils;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.federation.constants.FederationMainNetConstants;
import co.rsk.peg.federation.constants.FederationTestNetConstants;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PendingFederationTest {
    private static final long FEDERATION_CREATION_BLOCK_NUMBER = 0L;
    private final List<BtcECKey> federationMembersKeys = getFederationMembersKeys(100, 200, 300, 400, 500, 600, 700, 800, 900);
    private final PendingFederation pendingFederation = PendingFederationBuilder.builder()
        .withMembersBtcPublicKeys(federationMembersKeys)
        .build();
    private final List<FederationMember> federationMembers = pendingFederation.getMembers();

    @Test
    void addANewMember_toPendingFederationMembers_shouldThrowAnException() {
        FederationMember newMember = new FederationMember(new BtcECKey(), new BtcECKey(), new BtcECKey());
        List<FederationMember> pendingFederationMembers = pendingFederation.getMembers();
        assertThrows(
            UnsupportedOperationException.class,
            () -> pendingFederationMembers.add(newMember)
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> pendingFederationMembers.remove(0)
        );
    }

    @Test
    void isComplete_withAPendingFederationWithMoreMembersThanRequired_shouldBeTrue() {
        Assertions.assertTrue(pendingFederation.isComplete());
    }

    @Test
    void isComplete_withAPendingFederationWithLessMembersThanRequired_shouldBeFalse() {
        PendingFederation otherPendingFederation = new PendingFederation(FederationTestUtils.getFederationMembersFromPks(200));
        Assertions.assertFalse(otherPendingFederation.isComplete());
    }

    @Test
    void equals_withTheSamePendingFederation_shouldBeTrue() {
        assertEquals(pendingFederation, pendingFederation);
    }

    @Test
    void equals_withNull_shouldBeFalse() {
        Assertions.assertNotEquals(null, pendingFederation);
    }

    @Test
    void equals_withADifferentObject_shouldBeFalse() {
        assertNotEquals(new Object(), pendingFederation);
    }

    @Test
    void equals_withADifferentType_shouldBeFalse() {
        assertNotEquals("something else", pendingFederation);
    }

    @Test
    void equals_withAPendingFederation_withDifferentNumberOfMembers_shouldBeFalse() {
        List<BtcECKey> otherFederationMembersKeys = getFederationMembersKeys(100, 200, 300, 400, 500, 600, 700);
        PendingFederation otherPendingFederation = PendingFederationBuilder.builder().withMembersBtcPublicKeys(otherFederationMembersKeys).build();
        assertNotEquals(pendingFederation, otherPendingFederation);
    }

    @Test
    void equals_withThreePendingFederations_withDifferentMembers_shouldBeFalse() {
        List<BtcECKey> newMembersKeys = getFederationMembersKeys(100, 200, 300, 400, 500, 610);
        PendingFederation otherPendingFederation = PendingFederationBuilder.builder().withMembersBtcPublicKeys(newMembersKeys).build();

        List<BtcECKey> anotherNewMembersKeys = getFederationMembersKeys(100, 200, 300, 400, 500, 620);
        PendingFederation yetOtherPendingFederation = PendingFederationBuilder.builder().withMembersBtcPublicKeys(anotherNewMembersKeys).build();

        assertNotEquals(otherPendingFederation, yetOtherPendingFederation);
        assertNotEquals(pendingFederation, otherPendingFederation);
        assertNotEquals(pendingFederation, yetOtherPendingFederation);
    }

    @Test
    void equals_withTwoPendingFederations_withTheSameMembers_shouldBeTrue() {
        PendingFederation otherPendingFederation = PendingFederationBuilder.builder().withMembersBtcPublicKeys(federationMembersKeys).build();
        assertEquals(pendingFederation, otherPendingFederation);
    }

    @Test
    void toString_withACompleteFederation_shouldPrintTheCorrectMessage() {
        assertEquals("9 signatures pending federation (complete)", pendingFederation.toString());
    }

    @Test
    void toString_withAnIncompleteFederation_shouldPrintTheCorrectMessage() {
        BtcECKey newMemberKey = BtcECKey.fromPrivate(BigInteger.valueOf(100));
        PendingFederation otherPendingFederation = PendingFederationBuilder.builder().withMembersBtcPublicKeys(List.of(newMemberKey)).build();

        assertEquals("1 signatures pending federation (incomplete)", otherPendingFederation.toString());
    }

    /**
     * It's worth to clarify that 102 federation members is just for testing purposes. The redeemScript uses
     * OP_CHECKMULTISIG which has a limit of 20 public keys.
     */
    @ParameterizedTest
    @MethodSource("federationConstants")
    void buildFederation_with102Members_shouldThrowErpFederationCreationException(FederationConstants federationConstants) {
        // Arrange
        Instant federationCreationTime = federationConstants.getGenesisFederationCreationTime();
        int numberOfMembers = 102;
        Integer[] federationMembersValues = new Integer[numberOfMembers];
        for(int i = 1; i <= numberOfMembers; i++) {
            federationMembersValues[i-1] = i * 100;
        }
        List<BtcECKey> federationWith102MembersKeys = getFederationMembersKeys(federationMembersValues);
        PendingFederation pendingFederationWith102Members = PendingFederationBuilder.builder()
            .withMembersBtcPublicKeys(federationWith102MembersKeys)
            .build();

        // Act & assert
        assertThrows(ErpFederationCreationException.class, () -> pendingFederationWith102Members.buildFederation(
            federationCreationTime,
            FEDERATION_CREATION_BLOCK_NUMBER,
            federationConstants
        ));
    }

    @ParameterizedTest
    @MethodSource("federationConstants")
    void buildFederation_with9Members_shouldBuildP2SHP2WSHERPFed(FederationConstants federationConstants) {
        // Arrange
        Instant federationCreationTime = federationConstants.getGenesisFederationCreationTime();
        FederationArgs federationArgs = new FederationArgs(
            federationMembers,
            federationCreationTime,
            FEDERATION_CREATION_BLOCK_NUMBER,
            federationConstants.getBtcParams()
        );
        List<BtcECKey> erpPubKeys = federationConstants.getErpFedPubKeysList();
        long activationDelay = federationConstants.getErpFedActivationDelay();
        Federation expectedFederation = FederationFactory.buildP2shP2wshErpFederation(federationArgs, erpPubKeys, activationDelay);

        // Act
        Federation builtFederation = pendingFederation.buildFederation(
            federationCreationTime,
            FEDERATION_CREATION_BLOCK_NUMBER,
            federationConstants
        );

        // Assert
        assertEquals(expectedFederation, builtFederation);
    }

    @ParameterizedTest
    @MethodSource("federationConstants")
    void buildFederation_with20Members_shouldBuildP2SHP2WSHErpFed(FederationConstants federationConstants) {
        // Arrange
        Integer[] seeds = IntStream.iterate(100, n -> n <= 2000, n -> n + 100)
            .boxed()
            .toArray(Integer[]::new);
        List<BtcECKey> federationWith20MembersKeys = getFederationMembersKeys(seeds);
        PendingFederation pendingFederationWith20Members = PendingFederationBuilder.builder()
            .withMembersBtcPublicKeys(federationWith20MembersKeys)
            .build();
        List<FederationMember> pendingFederationMembers = pendingFederationWith20Members.getMembers();

        Instant federationCreationTime = federationConstants.getGenesisFederationCreationTime();
        FederationArgs federationArgs = new FederationArgs(
            pendingFederationMembers,
            federationCreationTime,
            FEDERATION_CREATION_BLOCK_NUMBER,
            federationConstants.getBtcParams()
        );
        List<BtcECKey> erpPubKeys = federationConstants.getErpFedPubKeysList();
        long activationDelay = federationConstants.getErpFedActivationDelay();
        Federation expectedFederation = FederationFactory.buildP2shP2wshErpFederation(
            federationArgs,
            erpPubKeys,
            activationDelay
        );

        // Act
        Federation builtFederation = pendingFederationWith20Members.buildFederation(
            federationCreationTime,
            FEDERATION_CREATION_BLOCK_NUMBER,
            federationConstants
        );

        // Assert
        assertEquals(expectedFederation, builtFederation);
    }

    @ParameterizedTest
    @MethodSource("federationConstants")
    void buildFederation_with21Members_shouldThrowErpFederationCreationException(FederationConstants federationConstants) {
        // Arrange
        Integer[] seeds = IntStream.iterate(100, n -> n <= 2100, n -> n + 100)
            .boxed()
            .toArray(Integer[]::new);
        List<BtcECKey> federationWith21MembersKeys = getFederationMembersKeys(seeds);
        PendingFederation pendingFederationWith21Members = PendingFederationBuilder.builder()
            .withMembersBtcPublicKeys(federationWith21MembersKeys)
            .build();

        Instant federationCreationTime = federationConstants.getGenesisFederationCreationTime();

        // Act & assert
        assertThrows(ErpFederationCreationException.class, () -> pendingFederationWith21Members.buildFederation(
            federationCreationTime,
            FEDERATION_CREATION_BLOCK_NUMBER,
            federationConstants
        ));
    }

    @Test
    void buildFederation_withLessMembersThanRequired_shouldFailWithIncompleteFederationLog() {
        BtcECKey otherFederationMemberKey = BtcECKey.fromPrivate(BigInteger.valueOf(100));
        PendingFederation otherPendingFederation = PendingFederationBuilder.builder().withMembersBtcPublicKeys(List.of(otherFederationMemberKey)).build();

        Instant creationTime = Instant.ofEpochMilli(12L);
        assertThrows(IllegalStateException.class, () -> otherPendingFederation.buildFederation(
            creationTime,
            0L,
            null
        ));
    }

    @Test
    void getHash() {
        Hash expectedHash = Hash.fromHexString("0x49ca57b7a262f0f10de592eede9f2b10315734308584782ca1d4c13a57d5767e");
        assertEquals(expectedHash, pendingFederation.getHash());
    }

    @Test
    void serializeAndDeserialize_withPendingFederation_shouldGiveTheSameFederation() {
        final int NUM_CASES = 20;

        for (int i = 0; i < NUM_CASES; i++) {
            int numMembers = randomInRange(2, 14);
            List<BtcECKey> membersBtcPubKeys = new ArrayList<>();
            for (int j = 0; j < numMembers; j++) {
                membersBtcPubKeys.add(new BtcECKey());
            }
            PendingFederation testPendingFederation = PendingFederationBuilder.builder().withMembersBtcPublicKeys(membersBtcPubKeys).build();

            byte[] serializedTestPendingFederation = testPendingFederation.serialize();
            PendingFederation deserializedTestPendingFederation = PendingFederation.deserialize(serializedTestPendingFederation);

            assertEquals(testPendingFederation, deserializedTestPendingFederation);
        }
    }

    @Test
    void serializePendingFederation_serializedKeysAreCompressedAndThree() {
        final int NUM_MEMBERS = 10;
        final int EXPECTED_NUM_KEYS = 3;
        final int EXPECTED_PUBLICKEY_SIZE = 33;

        List<BtcECKey> membersBtcPubKeys = new ArrayList<>();
        for (int j = 0; j < NUM_MEMBERS; j++) {
            membersBtcPubKeys.add(new BtcECKey());
        }

        PendingFederation testPendingFederation = PendingFederationBuilder.builder().withMembersBtcPublicKeys(membersBtcPubKeys).build();

        byte[] serializedPendingFederation = testPendingFederation.serialize();

        RLPInput memberList = RLP.input(Bytes.wrap(serializedPendingFederation));
        assertEquals(NUM_MEMBERS, memberList.enterList());

        for (int i = 0; i < NUM_MEMBERS; i++) {
            // each member is a nested list of its keys
            assertEquals(EXPECTED_NUM_KEYS, memberList.enterList());
            for (int j = 0; j < EXPECTED_NUM_KEYS; j++) {
                assertEquals(EXPECTED_PUBLICKEY_SIZE, memberList.readBytes().size());
            }
            memberList.leaveList();
        }
        memberList.leaveList();
    }

    @Test
    void deserializePendingFederation_withInvalidFederationMember_shouldThrowARunTimeException() {
        byte[] serialized = RlpTestUtils.encodeList(
            RlpTestUtils.encodeList(RlpTestUtils.encodeElement(new byte[0]), RlpTestUtils.encodeElement(new byte[0]))
        );

        assertThrows(RuntimeException.class, () -> PendingFederation.deserialize(serialized));
    }

    private static Stream<Arguments> federationConstants() {
        return Stream.of(
            Arguments.of(FederationTestNetConstants.getInstance()),
            Arguments.of(FederationMainNetConstants.getInstance())
        );
    }

    private int randomInRange(int min, int max) {
        return RskTestUtils.generateInt(PendingFederationTest.class.toString(), max - min + 1) + min;
    }

    private List<BtcECKey> getFederationMembersKeys(Integer... values) {
        List<BtcECKey> keys = new ArrayList<>();
        for (Integer v : values) {
            keys.add(BtcECKey.fromPrivate(BigInteger.valueOf(v)));
        }
        return keys.stream().toList();
    }
}
