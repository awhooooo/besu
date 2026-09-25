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

import org.apache.tuweni.bytes.Bytes;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.NetworkParameters;
import org.hyperledger.besu.datatypes.Hash;
import co.rsk.peg.federation.constants.FederationConstants;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import co.rsk.peg.utils.RskRlp;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable representation of an RSK Pending Federation.
 * A pending federation is one that is being actively
 * voted by the current federation to potentially become
 * the new active federation.
 *
 * @author Ariel Mendelzon
 */
public final class PendingFederation {
    private static final Logger logger = LoggerFactory.getLogger("PendingFederation");
    private static final int MIN_MEMBERS_REQUIRED = 2;
    private final List<FederationMember> members;

    public PendingFederation(List<FederationMember> members) {
        // Sorting members ensures same order for members
        // Immutability provides protection against unwanted modification, thus making the Pending Federation instance
        // effectively immutable
        this.members = Collections.unmodifiableList(
            members.stream()
                .sorted(FederationMember.BTC_RSK_MST_PUBKEYS_COMPARATOR)
                .collect(Collectors.toList())
        );
    }

    public List<FederationMember> getMembers() {
        // Safe to return instance since both the list and instances are immutable
        return members;
    }

    public int getSize() {
        return members.size();
    }

    public List<BtcECKey> getBtcPublicKeys() {
        // Copy keys since we don't control immutability of BtcECKey(s)
        return members.stream()
            .map(FederationMember::getBtcPublicKey)
            .collect(Collectors.toList());
    }

    public boolean isComplete() {
        return this.members.size() >= MIN_MEMBERS_REQUIRED;
    }

    /**
     * Creates a new PendingFederation with the additional specified member
     * @param member the new federation member
     * @return a new PendingFederation with the added member
     */
    public PendingFederation addMember(FederationMember member) {
        List<FederationMember> newMembers = new ArrayList<>(members);
        newMembers.add(member);
        return new PendingFederation(newMembers);
    }

    /**
     * Builds a Federation from this PendingFederation
     * @param creationTime the creation time for the new Federation
     * @param federationConstants to get the bitcoin parameters for the new Federation,
     * and the keys for creating an ERP Federation
     * @return a P2SH-P2WSH ERP Federation, the only kind a pending federation builds on this chain
     */
    public Federation buildFederation(
        Instant creationTime,
        long creationBlockNumber,
        FederationConstants federationConstants
    ) {
        if (!this.isComplete()) {
            throw new IllegalStateException("PendingFederation is incomplete");
        }

        NetworkParameters btcParams = federationConstants.getBtcParams();
        FederationArgs federationArgs = new FederationArgs(members, creationTime, creationBlockNumber, btcParams);

        List<BtcECKey> erpPubKeys = federationConstants.getErpFedPubKeysList();
        long activationDelay = federationConstants.getErpFedActivationDelay();

        logger.info("[buildFederation] Going to create a P2SH-P2WSH ERP Federation");
        return FederationFactory.buildP2shP2wshErpFederation(federationArgs, erpPubKeys, activationDelay);
    }

    @Override
    public String toString() {
        return String.format("%d signatures pending federation (%s)", members.size(), isComplete() ? "complete" : "incomplete");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || this.getClass() != other.getClass()) {
            return false;
        }

        return this.members.equals(((PendingFederation) other).members);
    }

    public Hash getHash() {
        byte[] encoded = this.serializeOnlyBtcKeys();
        return Hash.hash(org.apache.tuweni.bytes.Bytes.wrap(encoded));
    }

    @Override
    public int hashCode() {
        // Can use java.util.Objects.hash since List<BtcECKey> has a
        // well-defined hashCode()
        return Objects.hash(getBtcPublicKeys());
    }

    public byte[] serialize() {
        return serializeFromMembers();
    }

    /**
     * A pending federation is serialized as the
     * public keys conforming it.
     * A list of btc public keys is serialized as
     * [pubkey1, pubkey2, ..., pubkeyn], sorted
     * using the lexicographical order of the public keys
     * (see BtcECKey.PUBKEY_COMPARATOR).
     * This is the pre-image of the pending federation hash; it is never stored.
     */
    private byte[] serializeOnlyBtcKeys() {
        List<BtcECKey> sortedKeys = this.getBtcPublicKeys().stream()
            .sorted(BtcECKey.PUBKEY_COMPARATOR)
            .collect(Collectors.toList());
        return RskRlp.encode(out -> {
            out.startList();
            for (BtcECKey key : sortedKeys) {
                RskRlp.writeElement(out, key.getPubKey());
            }
            out.endList();
        });
    }

    /**
     * A pending federation is serialized as the
     * list of its sorted members serialized.
     * A FederationMember is serialized as a list in the following order:
     * - BTC public key
     * - RSK public key
     * - MST public key
     * All keys are stored in their COMPRESSED versions.
     * Unlike a Federation, whose members are wrapped as elements, the member lists are nested directly.
     */
    private byte[] serializeFromMembers() {
        List<byte[]> encodedMembers = this.getMembers().stream()
            .sorted(FederationMember.BTC_RSK_MST_PUBKEYS_COMPARATOR)
            .map(FederationMember::serialize)
            .collect(Collectors.toList());
        return RskRlp.encode(out -> {
            out.startList();
            for (byte[] encodedMember : encodedMembers) {
                out.writeRLPBytes(Bytes.wrap(encodedMember));
            }
            out.endList();
        });
    }

    public static PendingFederation deserialize(byte[] data) {
        RLPInput in = RskRlp.input(data);
        int size = in.enterList();
        List<FederationMember> deserializedMembers = new ArrayList<>();

        for (int k = 0; k < size; k++) {
            FederationMember member = FederationMember.deserialize(RskRlp.readData(in));
            deserializedMembers.add(member);
        }
        in.leaveList();

        return new PendingFederation(deserializedMembers);
    }

}
