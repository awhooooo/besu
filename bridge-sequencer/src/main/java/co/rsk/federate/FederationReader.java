/*
 * This file is part of RskJ
 * Copyright (C) 2018 RSK Labs Ltd.
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
package co.rsk.federate;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.peg.BridgeMethods;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationArgs;
import co.rsk.peg.federation.FederationFactory;
import co.rsk.peg.federation.FederationMember;
import co.rsk.peg.federation.constants.FederationConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds the active federation from what the bridge will tell anyone.
 *
 * <p>The bridge hands out the members' public keys and the address, but not which kind of
 * federation it is, and the kind decides the redeem script and so the address. A genesis federation
 * is a plain multisig; one the members voted in is a P2SH-P2WSH federation with the emergency keys
 * spliced in. So both are built and the one whose address matches the bridge's is the real one.
 *
 * <p>Getting this wrong would mean watching the wrong address on Bitcoin and seeing no peg-ins at
 * all, quietly, which is why a mismatch is an error rather than a guess.
 */
public class FederationReader {

    private static final Logger logger = LoggerFactory.getLogger(FederationReader.class);

    private final BridgeClient bridge;
    private final BridgeConstants bridgeConstants;

    public FederationReader(BridgeClient bridge, BridgeConstants bridgeConstants) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.bridgeConstants = Objects.requireNonNull(bridgeConstants, "bridgeConstants");
    }

    /** The federation the bridge is currently using. */
    public Federation getActiveFederation() {
        String expectedAddress = bridge.callOne(BridgeMethods.GET_FEDERATION_ADDRESS);
        int size = this.<BigInteger>callOne(BridgeMethods.GET_FEDERATION_SIZE).intValueExact();
        long creationTimeSeconds = this.<BigInteger>callOne(BridgeMethods.GET_FEDERATION_CREATION_TIME).longValueExact();
        long creationBlockNumber =
            this.<BigInteger>callOne(BridgeMethods.GET_FEDERATION_CREATION_BLOCK_NUMBER).longValueExact();

        List<FederationMember> members = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            members.add(new FederationMember(
                publicKeyOf(i, FederationMember.KeyType.BTC),
                publicKeyOf(i, FederationMember.KeyType.RSK),
                publicKeyOf(i, FederationMember.KeyType.MST)));
        }

        FederationArgs args = new FederationArgs(
            members,
            Instant.ofEpochSecond(creationTimeSeconds),
            creationBlockNumber,
            bridgeConstants.getBtcParams());

        Federation federation = matching(expectedAddress, args);
        logger.info("[getActiveFederation] {} of {} members at {}, created in block {}",
            federation.getNumberOfSignaturesRequired(), federation.getSize(),
            federation.getAddress(), creationBlockNumber);
        return federation;
    }

    private Federation matching(String expectedAddress, FederationArgs args) {
        FederationConstants federationConstants = bridgeConstants.getFederationConstants();

        Federation standard = FederationFactory.buildStandardMultiSigFederation(args);
        if (standard.getAddress().toBase58().equals(expectedAddress)) {
            return standard;
        }

        Federation p2shP2wsh = FederationFactory.buildP2shP2wshErpFederation(
            args, federationConstants.getErpFedPubKeysList(), federationConstants.getErpFedActivationDelay());
        if (p2shP2wsh.getAddress().toBase58().equals(expectedAddress)) {
            return p2shP2wsh;
        }

        throw new IllegalStateException(String.format(
            "The bridge's federation is at %s, but its members build neither a standard multisig "
                + "federation (%s) nor a P2SH-P2WSH one (%s). Refusing to watch an address the bridge "
                + "is not using.",
            expectedAddress, standard.getAddress().toBase58(), p2shP2wsh.getAddress().toBase58()));
    }

    private BtcECKey publicKeyOf(int index, FederationMember.KeyType keyType) {
        byte[] encoded = bridge.callOne(
            BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE, BigInteger.valueOf(index), keyType.getValue());
        return BtcECKey.fromPublicOnly(encoded);
    }

    private <T> T callOne(BridgeMethods method) {
        return bridge.callOne(method);
    }
}
