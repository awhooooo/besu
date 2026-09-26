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
import java.util.Optional;

import co.rsk.bitcoinj.core.Address;
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
 * Rebuilds the bridge's federations from what it will tell anyone.
 *
 * <p>There can be three at once. The active one holds the peg; a retiring one still holds coins
 * that have to be migrated out of it; and while a change is being validated there is a proposed
 * one, which holds nothing yet and has to prove it could.
 *
 * <p>Each is read the same way, and none of the getters says which kind of federation its members
 * make — a genesis federation is a plain multisig and one voted in is a P2SH-P2WSH federation with
 * the emergency keys spliced in, and the kind decides the redeem script and so the address. Both
 * are built and the one whose address the bridge agrees with is the real one. A mismatch is an
 * error rather than a guess: watching the wrong address on bitcoin fails silently, with no peg-in
 * ever arriving and nothing to say why.
 *
 * <p>The addresses are readable on their own, because that is all a watcher needs in order to
 * notice a change. Rebuilding a whole federation costs a call per member.
 */
public class FederationProvider {

    private static final Logger logger = LoggerFactory.getLogger(FederationProvider.class);

    /** The bridge answers about each federation with the same five questions under three names. */
    private enum Which {
        ACTIVE(
            BridgeMethods.GET_FEDERATION_ADDRESS,
            BridgeMethods.GET_FEDERATION_SIZE,
            BridgeMethods.GET_FEDERATION_CREATION_TIME,
            BridgeMethods.GET_FEDERATION_CREATION_BLOCK_NUMBER,
            BridgeMethods.GET_FEDERATOR_PUBLIC_KEY_OF_TYPE),
        RETIRING(
            BridgeMethods.GET_RETIRING_FEDERATION_ADDRESS,
            BridgeMethods.GET_RETIRING_FEDERATION_SIZE,
            BridgeMethods.GET_RETIRING_FEDERATION_CREATION_TIME,
            BridgeMethods.GET_RETIRING_FEDERATION_CREATION_BLOCK_NUMBER,
            BridgeMethods.GET_RETIRING_FEDERATOR_PUBLIC_KEY_OF_TYPE),
        PROPOSED(
            BridgeMethods.GET_PROPOSED_FEDERATION_ADDRESS,
            BridgeMethods.GET_PROPOSED_FEDERATION_SIZE,
            BridgeMethods.GET_PROPOSED_FEDERATION_CREATION_TIME,
            BridgeMethods.GET_PROPOSED_FEDERATION_CREATION_BLOCK_NUMBER,
            BridgeMethods.GET_PROPOSED_FEDERATOR_PUBLIC_KEY_OF_TYPE);

        private final BridgeMethods address;
        private final BridgeMethods size;
        private final BridgeMethods creationTime;
        private final BridgeMethods creationBlockNumber;
        private final BridgeMethods publicKeyOfType;

        Which(BridgeMethods address, BridgeMethods size, BridgeMethods creationTime,
              BridgeMethods creationBlockNumber, BridgeMethods publicKeyOfType) {
            this.address = address;
            this.size = size;
            this.creationTime = creationTime;
            this.creationBlockNumber = creationBlockNumber;
            this.publicKeyOfType = publicKeyOfType;
        }
    }

    private final BridgeClient bridge;
    private final BridgeConstants bridgeConstants;

    public FederationProvider(BridgeClient bridge, BridgeConstants bridgeConstants) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.bridgeConstants = Objects.requireNonNull(bridgeConstants, "bridgeConstants");
    }

    /** The address of the federation holding the peg. There is always one. */
    public Address getActiveFederationAddress() {
        return addressOf(Which.ACTIVE).orElseThrow(() -> new IllegalStateException(
            "The bridge reports no active federation, which should not be possible"));
    }

    /** The address of the federation being migrated out of, if a change is under way. */
    public Optional<Address> getRetiringFederationAddress() {
        return addressOf(Which.RETIRING);
    }

    /** The address of a federation awaiting validation, if one has been voted in. */
    public Optional<Address> getProposedFederationAddress() {
        return addressOf(Which.PROPOSED);
    }

    public Federation getActiveFederation() {
        return federation(Which.ACTIVE).orElseThrow(() -> new IllegalStateException(
            "The bridge reports no active federation, which should not be possible"));
    }

    public Optional<Federation> getRetiringFederation() {
        return federation(Which.RETIRING);
    }

    public Optional<Federation> getProposedFederation() {
        return federation(Which.PROPOSED);
    }

    /**
     * The address, or empty when there is no such federation.
     *
     * <p>The bridge says so with an empty string rather than by failing, which is also what its
     * other getters fall back on: a size of -1, a creation time of -1. Reading the address first
     * means none of those sentinels is ever decoded as a real value.
     */
    private Optional<Address> addressOf(Which which) {
        String base58 = bridge.callOne(which.address);
        if (base58 == null || base58.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Address.fromBase58(bridgeConstants.getBtcParams(), base58));
    }

    private Optional<Federation> federation(Which which) {
        Optional<Address> address = addressOf(which);
        if (address.isEmpty()) {
            return Optional.empty();
        }

        int size = this.<BigInteger>callOne(which.size).intValueExact();
        long creationTimeSeconds = this.<BigInteger>callOne(which.creationTime).longValueExact();
        long creationBlockNumber = this.<BigInteger>callOne(which.creationBlockNumber).longValueExact();

        List<FederationMember> members = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            members.add(new FederationMember(
                publicKeyOf(which, index, FederationMember.KeyType.BTC),
                publicKeyOf(which, index, FederationMember.KeyType.RSK),
                publicKeyOf(which, index, FederationMember.KeyType.MST)));
        }

        FederationArgs args = new FederationArgs(
            members,
            Instant.ofEpochSecond(creationTimeSeconds),
            creationBlockNumber,
            bridgeConstants.getBtcParams());

        Federation federation = matching(which, address.get(), args);
        logger.info("[federation] {} federation: {} of {} at {}, created in block {}",
            which, federation.getNumberOfSignaturesRequired(), federation.getSize(),
            federation.getAddress(), creationBlockNumber);
        return Optional.of(federation);
    }

    private Federation matching(Which which, Address expected, FederationArgs args) {
        FederationConstants federationConstants = bridgeConstants.getFederationConstants();

        Federation standard = FederationFactory.buildStandardMultiSigFederation(args);
        if (standard.getAddress().equals(expected)) {
            return standard;
        }

        Federation p2shP2wsh = FederationFactory.buildP2shP2wshErpFederation(
            args, federationConstants.getErpFedPubKeysList(), federationConstants.getErpFedActivationDelay());
        if (p2shP2wsh.getAddress().equals(expected)) {
            return p2shP2wsh;
        }

        throw new IllegalStateException(String.format(
            "The bridge's %s federation is at %s, but its members build neither a standard multisig "
                + "federation (%s) nor a P2SH-P2WSH one (%s). Refusing to watch an address the bridge "
                + "is not using.",
            which, expected, standard.getAddress(), p2shP2wsh.getAddress()));
    }

    private BtcECKey publicKeyOf(Which which, int index, FederationMember.KeyType keyType) {
        byte[] encoded = bridge.callOne(which.publicKeyOfType, BigInteger.valueOf(index), keyType.getValue());
        return BtcECKey.fromPublicOnly(encoded);
    }

    private <T> T callOne(BridgeMethods method) {
        return bridge.callOne(method);
    }
}
