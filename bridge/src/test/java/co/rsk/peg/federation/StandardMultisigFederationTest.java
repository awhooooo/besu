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
import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptOpCodes;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.utils.PublicKeys;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.*;

class StandardMultisigFederationTest {
    private Federation federation;
    private NetworkParameters networkParameters;

    private List<BtcECKey> sortedPublicKeys;
    private List<byte[]> rskAddresses;
    private final BridgeConstants bridgeMainNetConstants = BridgeMainNetConstants.getInstance();

    @BeforeEach
    void setUp() {
        networkParameters = bridgeMainNetConstants.getBtcParams();
        federation = FederationTestUtils.getGenesisFederation(bridgeMainNetConstants.getFederationConstants());

        List<BtcECKey> keys = federation.getBtcPublicKeys();
        sortedPublicKeys = keys.stream()
            .sorted(BtcECKey.PUBKEY_COMPARATOR).toList();

        rskAddresses = sortedPublicKeys
            .stream()
            .map(PublicKeys::addressOf)
            .map(address -> address.getBytes().toArrayUnsafe())
            .toList();
    }

    @Test
    void membersImmutable() {
        boolean exception = false;
        try {
            federation.getMembers().add(new FederationMember(new BtcECKey(), new BtcECKey(), new BtcECKey()));
        } catch (Exception e) {
            exception = true;
        }
        assertTrue(exception);

        exception = false;
        try {
            federation.getMembers().remove(0);
        } catch (Exception e) {
            exception = true;
        }
        assertTrue(exception);
    }

    @Test
    void testEquals_basic() {
        assertEquals(federation, federation);

        assertNotEquals(null, federation);
        assertNotEquals(new Object(), federation);
        assertNotEquals("something else", federation);
    }

    @Test
    void testEquals_same() {
        FederationArgs federationArgs = new FederationArgs(
            federation.getMembers(),
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            federation.getBtcParams()
        );
        Federation otherFederation = FederationFactory.buildStandardMultiSigFederation(
            federationArgs
        );

        assertEquals(federation, otherFederation);
    }

    @Test
    void values_from_federationArgs_equal_values_from_federation() {
        List<FederationMember> federationMembers = federation.getMembers();
        Instant creationTime = federation.getCreationTime();
        long creationBlockNumber = federation.getCreationBlockNumber();
        NetworkParameters btcParams = federation.getBtcParams();
        FederationArgs federationArgsFromValues = new FederationArgs(federationMembers, creationTime, creationBlockNumber, btcParams);

        FederationArgs federationArgs = federation.getArgs();
        assertEquals(federationArgs, federationArgsFromValues);
    }

    @Test
    void federation_from_federationArgs_equals_federation() {
        FederationArgs federationArgs = federation.getArgs();
        Federation federationFromFederationArgs = FederationFactory.buildStandardMultiSigFederation(federationArgs);

        assertEquals(federation, federationFromFederationArgs);
    }

    @Test
    void testEquals_differentCreationTime() {
        FederationArgs federationArgs = new FederationArgs(
            federation.getMembers(),
            federation.getCreationTime().plus(1, ChronoUnit.MILLIS),
            federation.getCreationBlockNumber(),
            networkParameters
        );
        Federation otherFederation = FederationFactory.buildStandardMultiSigFederation(
            federationArgs
        );
        assertEquals(federation, otherFederation);
    }

    @Test
    void testEquals_differentCreationBlockNumber() {
        FederationArgs federationArgs = new FederationArgs(
            federation.getMembers(),
            federation.getCreationTime(),
            federation.getCreationBlockNumber() + 1,
            networkParameters
        );
        Federation otherFederation = FederationFactory.buildStandardMultiSigFederation(
            federationArgs
        );
        assertEquals(federation, otherFederation);
    }

    @Test
    void testEquals_differentNetworkParameters() {
        FederationArgs federationArgs = new FederationArgs(
            federation.getMembers(),
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            NetworkParameters.fromID(NetworkParameters.ID_REGTEST)
        );
        Federation otherFederation = FederationFactory.buildStandardMultiSigFederation(
            federationArgs
        );
        // Different network parameters will result in a different address
        assertNotEquals(federation, otherFederation);
    }

    @Test
    void testEquals_differentNumberOfMembers() {
        // remove federator8
        List<BtcECKey> newKeys = federation.getBtcPublicKeys();
        newKeys.remove(8);
        List<FederationMember> newMembers = FederationTestUtils.getFederationMembersWithKeys(newKeys);
        FederationArgs federationArgs = new FederationArgs(
            newMembers,
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            federation.getBtcParams()
        );

        Federation otherFederation = FederationFactory.buildStandardMultiSigFederation(
            federationArgs
        );

        assertNotEquals(federation, otherFederation);
    }

    @Test
    void testEquals_differentMembers() {
        // replace federator7 with another pkey
        BtcECKey anotherPublicKey = BtcECKey.fromPublicOnly(
            Hex.decode("03b65694ccccda83cbb1e56b31308acd08e993114c33f66a456b627c2c1c68bed7")
        );
        List<BtcECKey> newKeys = federation.getBtcPublicKeys();
        newKeys.remove(8);
        newKeys.add(anotherPublicKey);
        List<FederationMember> differentMembers = FederationTestUtils.getFederationMembersWithKeys(newKeys);

        Instant creationTime = federation.getCreationTime();
        long creationBlockNumber = federation.getCreationBlockNumber();
        FederationArgs federationArgs = new FederationArgs(
            differentMembers,
            creationTime,
            creationBlockNumber,
            networkParameters
        );

        Federation otherFederation = FederationFactory.buildStandardMultiSigFederation(
            federationArgs
        );

        assertNotEquals(federation, otherFederation);
    }

    @Test
    void getP2SHScriptAndAddress() {
        Script p2shScript = federation.getP2SHScript();
        Address address = federation.getAddress();

        String expectedProgram = "a914331224ec506c8af8d708c6bfc2d3a002d9ee15e687";
        Address expectedAddress = Address.fromBase58(
            networkParameters,
            "36M481Z8R9yR1Jxrf7Uq5msEV572BXpY7A"
        );

        assertEquals(expectedProgram, Hex.toHexString(p2shScript.getProgram()));
        assertEquals(3, p2shScript.getChunks().size());
        assertEquals(
            address,
            p2shScript.getToAddress(networkParameters)
        );
        assertEquals(expectedAddress, address);
    }

    @Test
    void getRedeemScript() {
        Script expectedScript = new Script(Hex.decode("5521023847d84835723306c459bc5882c68e2d3f27a0612098e3755afb61c2f8c3eb56210239b0f123dccacce105361f41be1f0cbbbcc2d6fefed518df497452e5bc736a1d2102ad7ba4fc5137534c5e39db6d860c66d13cf17a6a2575b391b80ac3ece86f0a8f2102c5960b526d975567d5b8524bac1d4b4e0580b4df24de7bf1c34d177e6573c81921031878a41184afb1bca4057f202c29a69a8d4f43b638d8c2a25ca7bd6a52be07e521031d4f6ce1dfc3a16689cb411b994f16245f2e4017f42897924dc9508d9f13951721034b62a52b97d9e0c129a75ffa7853ad59a5a522aa53c2e68d005162e5a5bad1942103636b01933d4bb272b844e991b26a8f4f33935c88720b4205629e6ef4ff44024f21039e8b85f66ea7c6fd5b05b4e3a4521f7f8106aae6c63171e8aa87c3697b84af4359ae"));
        Script redeemScript = federation.getRedeemScript();
        assertEquals(expectedScript, redeemScript);

        int expectedChunks = sortedPublicKeys.size() + 3; // + 3 opcodes (OP_M, OP_N, OP_CHECKMULTISIG)
        assertEquals(expectedChunks, redeemScript.getChunks().size());

        int opM = ScriptOpCodes.getOpCode("" + federation.getNumberOfSignaturesRequired());
        assertEquals(opM, redeemScript.getChunks().get(0).opcode);

        for (int i = 0; i < sortedPublicKeys.size(); i++) {
            assertArrayEquals(sortedPublicKeys.get(i).getPubKey(), redeemScript.getChunks().get(i+1).data);
        }

        int opN = ScriptOpCodes.getOpCode("" + federation.getSize());
        assertEquals(opN, redeemScript.getChunks().get(redeemScript.getChunks().size() - 2).opcode);
        assertEquals(ScriptOpCodes.OP_CHECKMULTISIG, redeemScript.getChunks().get(redeemScript.getChunks().size() - 1).opcode);
    }

    @Test
    void getBtcPublicKeyIndex() {
        for (int i = 0; i < federation.getBtcPublicKeys().size(); i++) {
            Optional<Integer> index = federation.getBtcPublicKeyIndex(sortedPublicKeys.get(i));
            assertTrue(index.isPresent());
            assertEquals(i, index.get().intValue());
        }
        assertFalse(federation.getBtcPublicKeyIndex(BtcECKey.fromPrivate(BigInteger.valueOf(1234))).isPresent());
    }

    @Test
    void hasBtcPublicKey() {
        for (int i = 0; i < federation.getBtcPublicKeys().size(); i++) {
            assertTrue(federation.hasBtcPublicKey(sortedPublicKeys.get(i)));
        }
        assertFalse(federation.hasBtcPublicKey(BtcECKey.fromPrivate(BigInteger.valueOf(1234))));
    }

    @Test
    void hasMemberWithRskAddress() {
        for (int i = 0; i < federation.getBtcPublicKeys().size(); i++) {
            assertTrue(federation.hasMemberWithRskAddress(rskAddresses.get(i)));
        }

        byte[] nonFederateRskAddress = PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(1234))).getBytes().toArrayUnsafe();
        assertFalse(federation.hasMemberWithRskAddress(nonFederateRskAddress));
    }

    @Test
    void testToString() {
        assertEquals(
            "Got 5 of 9 signatures federation with address 36M481Z8R9yR1Jxrf7Uq5msEV572BXpY7A",
            federation.toString()
        );
    }

    @Test
    void isMember(){
        //Both valid params
        FederationMember federationMember = federation.getMembers().get(0);
        assertTrue(federation.isMember(federationMember));

        byte[] b = RskTestUtils.generateBytes("b", 20);

        BtcECKey invalidRskKey = BtcECKey.fromPrivate(b);
        BtcECKey invalidBtcKey = BtcECKey.fromPrivate(b);

        // Valid PubKey, invalid rskAddress
        FederationMember invalidRskPubKey = new FederationMember(
            federationMember.getBtcPublicKey(),
            invalidRskKey,
            federationMember.getMstPublicKey()
        );
        assertFalse(federation.isMember(invalidRskPubKey));

        //Invalid PubKey, valid rskAddress
        FederationMember invalidBtcPubKey = new FederationMember(
            invalidBtcKey,
            federationMember.getRskPublicKey(),
            federationMember.getMstPublicKey()
        );
        assertFalse(federation.isMember(invalidBtcPubKey));

        //Valid btcKey & valid rskAddress, invalid mstKey
        FederationMember invalidMstPubKey = new FederationMember(
            federationMember.getBtcPublicKey(),
            federationMember.getRskPublicKey(),
            invalidRskKey
        );
        assertFalse(federation.isMember(invalidMstPubKey));

        //All invalid params
        FederationMember invalidPubKeys = new FederationMember(invalidBtcKey, invalidRskKey, invalidRskKey);
        assertFalse(federation.isMember(invalidPubKeys));
    }

    @Test
    void getMemberByBtcPublicKey_passing_existing_btcPublicKey_should_return_found_member(){
        BtcECKey existingMemberBtcPublicKey = sortedPublicKeys.get(0);
        Optional<FederationMember> foundMember = federation.getMemberByBtcPublicKey(existingMemberBtcPublicKey);
        assertTrue(foundMember.isPresent());
        assertEquals(existingMemberBtcPublicKey, foundMember.get().getBtcPublicKey());
    }

    @Test
    void getMemberByBtcPublicKey_passing_non_existing_btcPublicKey_should_return_empty(){
        BtcECKey noExistingBtcPublicKey = new BtcECKey();
        Optional<FederationMember> foundMember = federation.getMemberByBtcPublicKey(noExistingBtcPublicKey);
        assertFalse(foundMember.isPresent());
    }

    @Test
    void getMemberByBtcPublicKey_passing_null_btcPublicKey_should_return_empty(){
        Optional<FederationMember> foundMember = federation.getMemberByBtcPublicKey(null);
        assertFalse(foundMember.isPresent());
    }
}
