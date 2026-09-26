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

/**
 * The keys a federation member holds.
 *
 * <p>BTC signs bitcoin sighashes, so that the federation can spend its own multisig. RSK signs the
 * transactions that carry those signatures, and everything else, to the bridge.
 *
 * <p>MST is kept because a federation member is still registered with three keys and the bridge
 * still reports three. Nothing here signs with it: it existed for merge mining, which this chain
 * does not do. It is configured like the others so that a member's key material stays in the shape
 * the peg expects, and so that one key never has to stand in for another.
 */
public enum SequencerKeyId {
    BTC(new KeyId("BTC")),
    RSK(new KeyId("RSK")),
    MST(new KeyId("MST"));

    private final KeyId keyId;

    SequencerKeyId(KeyId keyId) {
        this.keyId = keyId;
    }

    public KeyId getKeyId() {
        return keyId;
    }

    public String getId() {
        return keyId.getId();
    }
}
