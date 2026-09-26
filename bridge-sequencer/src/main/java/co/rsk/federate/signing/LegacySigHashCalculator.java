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

import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.peg.bitcoin.BitcoinUtils;

/**
 * The pre-segwit digest, which does not commit to the value being spent.
 *
 * <p>Computed by the bridge's own function rather than by an equivalent one. The bridge checks a
 * signature against the digest it derives itself, so anything that merely agrees today is a second
 * implementation free to stop agreeing later, and the symptom would be signatures the bridge
 * silently refuses.
 */
public class LegacySigHashCalculator implements SigHashCalculator {

    @Override
    public Sha256Hash calculate(BtcTransaction btcTx, int inputIndex) {
        return BitcoinUtils.generateSigHashForLegacyTransactionInput(btcTx, inputIndex);
    }
}
