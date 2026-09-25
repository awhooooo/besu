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

package co.rsk.peg.federation;

import co.rsk.peg.utils.PublicKeys;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.peg.utils.Printable;
import org.bouncycastle.util.encoders.Hex;
import co.rsk.peg.utils.RskRlp;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable representation of an RSK Federation member.
 *
 * It's composed of three public keys: one for the RSK network, one for the
 * BTC network and one called MST of yet undefined usage.
 *
 * @author Ariel Mendelzon
 */
public final class FederationMember {
    public static final FederationMemberPubKeysComparator BTC_RSK_MST_PUBKEYS_COMPARATOR = new FederationMemberPubKeysComparator();
    private static final int KEYS_QUANTITY = 3;
    private static final int BTC_KEY_INDEX = 0;
    private static final int RSK_KEY_INDEX = 1;
    private static final int MST_KEY_INDEX = 2;
    private final BtcECKey btcPublicKey;
    private final BtcECKey rskPublicKey;
    private final BtcECKey mstPublicKey;

    public FederationMember(BtcECKey btcPublicKey, BtcECKey rskPublicKey, BtcECKey mstPublicKey) {
        // Copy public keys to ensure effective immutability
        // Make sure we always use compressed versions of public keys
        this.btcPublicKey = BtcECKey.fromPublicOnly(btcPublicKey.getPubKeyPoint().getEncoded(true));
        // RSK and MST keys are held in uncompressed form: RSKj's Ethereum key type always reported the uncompressed
        // encoding from getPubKey(), and that encoding is what comparisons, events and getters expose.
        this.rskPublicKey = PublicKeys.asUncompressedKey(rskPublicKey);
        this.mstPublicKey = PublicKeys.asUncompressedKey(mstPublicKey);
    }

    public enum KeyType {
        BTC("btc"),
        RSK("rsk"),
        MST("mst");

        private final String value;

        KeyType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static KeyType byValue(String value) {
            switch (value) {
                case "rsk":
                    return KeyType.RSK;
                case "mst":
                    return KeyType.MST;
                case "btc":
                    return KeyType.BTC;
                default:
                    throw new IllegalArgumentException(String.format("Invalid value for FederationMember.KeyType: %s", Printable.trim(value)));
            }
        }
    }

    // To be removed when different keys per federation member feature is implemented. These are just helper
    // methods to make it easier w.r.t. compatibility with the current approach

    public static FederationMember getFederationMemberFromKey(BtcECKey pk) {
        return new FederationMember(pk, pk, pk);
    }

    public static List<FederationMember> getFederationMembersFromKeys(List<BtcECKey> pks) {
        return pks.stream().map(FederationMember::getFederationMemberFromKey).collect(Collectors.toList());
    }

    public BtcECKey getBtcPublicKey() {
        // Return a copy
        return BtcECKey.fromPublicOnly(btcPublicKey.getPubKey());
    }

    public BtcECKey getRskPublicKey() {
        // Return a copy, uncompressed
        return PublicKeys.asUncompressedKey(rskPublicKey);
    }

    public BtcECKey getMstPublicKey() {
        // Return a copy, uncompressed
        return PublicKeys.asUncompressedKey(mstPublicKey);
    }

    public BtcECKey getPublicKey(KeyType keyType) {
        switch (keyType) {
            case RSK:
                return getRskPublicKey();
            case MST:
                return getMstPublicKey();
            case BTC:
            default:
                return PublicKeys.asUncompressedKey(btcPublicKey);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "<BTC-%s, RSK-%s, MST-%s> federation member",
                Hex.toHexString(btcPublicKey.getPubKey()),
                Hex.toHexString(rskPublicKey.getPubKey()),
                Hex.toHexString(mstPublicKey.getPubKey())
        );
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || this.getClass() != other.getClass()) {
            return false;
        }

        FederationMember otherFederationMember = (FederationMember) other;

        return Arrays.equals(btcPublicKey.getPubKey(), otherFederationMember.btcPublicKey.getPubKey()) &&
                Arrays.equals(rskPublicKey.getPubKey(), otherFederationMember.rskPublicKey.getPubKey()) &&
                Arrays.equals(mstPublicKey.getPubKey(), otherFederationMember.mstPublicKey.getPubKey());
    }

    @Override
    public int hashCode() {
        // Can use java.util.Objects.hash since BtcECKey has
        // well-defined hashCode(s).
        return Objects.hash(
                btcPublicKey,
                rskPublicKey,
                mstPublicKey
        );
    }

    /** A member is serialized as the list [btcPublicKey, rskPublicKey, mstPublicKey], every key compressed. */
    public byte[] serialize() {
        byte[][] keys = new byte[KEYS_QUANTITY][];
        keys[BTC_KEY_INDEX] = this.getBtcPublicKey().getPubKeyPoint().getEncoded(true);
        keys[RSK_KEY_INDEX] = PublicKeys.compressed(this.getRskPublicKey());
        keys[MST_KEY_INDEX] = PublicKeys.compressed(this.getMstPublicKey());

        return RskRlp.encode(out -> {
            out.startList();
            for (byte[] key : keys) {
                RskRlp.writeElement(out, key);
            }
            out.endList();
        });
    }

    public static FederationMember deserialize(byte[] data) {
        RLPInput in = RskRlp.input(data);
        int size = in.enterList();
        if (size != KEYS_QUANTITY) {
            throw new RuntimeException(String.format(
                "Invalid serialized FederationMember. Expected %d elements but got %d", KEYS_QUANTITY, size)
            );
        }

        byte[][] keys = new byte[KEYS_QUANTITY][];
        for (int k = 0; k < KEYS_QUANTITY; k++) {
            keys[k] = RskRlp.readData(in);
        }
        in.leaveList();
        byte[] btcKeyData = keys[BTC_KEY_INDEX];
        byte[] rskKeyData = keys[RSK_KEY_INDEX];
        byte[] mstKeyData = keys[MST_KEY_INDEX];

        BtcECKey btcKey = BtcECKey.fromPublicOnly(btcKeyData);
        BtcECKey rskKey = BtcECKey.fromPublicOnly(rskKeyData);
        BtcECKey mstKey = BtcECKey.fromPublicOnly(mstKeyData);
        return new FederationMember(btcKey, rskKey, mstKey);
    }
}
