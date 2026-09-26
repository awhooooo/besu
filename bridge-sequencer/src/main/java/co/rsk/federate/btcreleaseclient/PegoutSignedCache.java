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
package co.rsk.federate.btcreleaseclient;

import org.hyperledger.besu.datatypes.Hash;

/**
 * Remembers which peg-outs this federator has already signed.
 *
 * <p>A signature does not take effect the instant it is sent. Until the bridge has included it, the
 * peg-out still appears in the state as waiting, so without this the next turn would sign it again
 * and pay for a transaction that changes nothing. Entries expire because the memory only has to
 * outlast the gap between sending a signature and the bridge counting it.
 */
public interface PegoutSignedCache {

    boolean hasAlreadyBeenSigned(Hash pegoutCreationRskTxHash);

    void put(Hash pegoutCreationRskTxHash);
}
