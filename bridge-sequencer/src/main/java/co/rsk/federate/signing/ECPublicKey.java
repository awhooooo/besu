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
package co.rsk.federate.signing;

import java.util.Arrays;
import java.util.Objects;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.peg.utils.PublicKeys;
import org.hyperledger.besu.datatypes.Address;

/**
 * A federator's public key, held compressed.
 *
 * <p>The same thirty-three bytes serve both chains: bitcoin wants the compressed point to build the
 * redeem script, and the peg derives a member's RSK address from it exactly as it derives one from
 * any other key. Keeping only the compressed form means there is one representation to compare,
 * which matters because a federation member is recognised by equality against what the bridge
 * reports.
 */
public final class ECPublicKey {

    private final byte[] compressedKeyBytes;

    public ECPublicKey(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        this.compressedKeyBytes = validateAndCompress(bytes);
    }

    public byte[] getCompressedKeyBytes() {
        return compressedKeyBytes.clone();
    }

    public BtcECKey toBtcKey() {
        return BtcECKey.fromPublicOnly(compressedKeyBytes.clone());
    }

    /** The RSK address this key belongs to, derived as the peg derives it. */
    public Address toAddress() {
        return PublicKeys.addressOf(toBtcKey());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }
        return Arrays.equals(this.compressedKeyBytes, ((ECPublicKey) o).compressedKeyBytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(compressedKeyBytes);
    }

    @Override
    public String toString() {
        return toBtcKey().getPublicKeyAsHex();
    }

    private static byte[] validateAndCompress(byte[] bytes) {
        // parse rejects anything that is not a point on the curve.
        return PublicKeys.compressed(PublicKeys.parse(bytes));
    }
}
