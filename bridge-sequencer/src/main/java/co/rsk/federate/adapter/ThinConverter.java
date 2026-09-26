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
package co.rsk.federate.adapter;

/**
 * Carries values between the two bitcoinj forks this process holds at once.
 *
 * <p>The sequencer speaks to Bitcoin with the full bitcoinj, which has the peer-to-peer stack, and
 * to the bridge with bitcoinj-thin, which had it removed. The two have the same types under
 * different package names and cannot see each other, so everything crosses as a serialized form:
 * the wire encoding is the one thing both agree on, and a value that fails to survive the trip was
 * never going to be accepted by the bridge either.
 */
public final class ThinConverter {

    private ThinConverter() {
    }

    /** A transaction the Bitcoin peer saw, in the form the bridge's peg code reads. */
    public static co.rsk.bitcoinj.core.BtcTransaction toThin(
        co.rsk.bitcoinj.core.NetworkParameters btcParams, org.bitcoinj.core.Transaction tx) {
        return new co.rsk.bitcoinj.core.BtcTransaction(btcParams, tx.bitcoinSerialize());
    }

    /** The network the bridge names, as the Bitcoin peer knows it. */
    public static org.bitcoinj.core.NetworkParameters toOriginal(String btcParamsId) {
        org.bitcoinj.core.NetworkParameters params = org.bitcoinj.core.NetworkParameters.fromID(btcParamsId);
        if (params == null) {
            throw new IllegalArgumentException("No bitcoin network with id " + btcParamsId);
        }
        return params;
    }

    /** A bridge address, as an address the Bitcoin peer's wallet can watch. */
    public static org.bitcoinj.core.Address toOriginal(
        org.bitcoinj.core.NetworkParameters params, co.rsk.bitcoinj.core.Address address) {
        return org.bitcoinj.core.LegacyAddress.fromBase58(params, address.toBase58());
    }
}
