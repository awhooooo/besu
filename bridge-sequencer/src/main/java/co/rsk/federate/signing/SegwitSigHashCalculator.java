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

import java.util.List;
import java.util.Objects;

import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.peg.bitcoin.BitcoinUtils;

/**
 * The BIP143 digest, which commits to the value of the output being spent.
 *
 * <p>That value is not in the transaction. A peg-out arrives from the bridge as bytes, and bytes
 * carry outpoints but not what those outpoints held, so the values are brought from the event the
 * bridge emitted when it built the peg-out and passed in here.
 *
 * <p>As with the legacy digest, the arithmetic is the bridge's own, so that the sequencer cannot
 * drift from what will be checked.
 */
public class SegwitSigHashCalculator implements SigHashCalculator {

    private final List<Coin> outpointValues;

    public SegwitSigHashCalculator(List<Coin> outpointValues) {
        this.outpointValues = List.copyOf(Objects.requireNonNull(outpointValues, "outpointValues"));
    }

    @Override
    public Sha256Hash calculate(BtcTransaction btcTx, int inputIndex) {
        if (inputIndex >= outpointValues.size()) {
            throw new IllegalStateException(String.format(
                "Peg-out %s has %d inputs but the bridge announced only %d outpoint values; input %d "
                    + "cannot be signed",
                btcTx.getHash(), btcTx.getInputs().size(), outpointValues.size(), inputIndex));
        }
        return BitcoinUtils.generateSigHashForSegwitTransactionInput(
            btcTx, inputIndex, outpointValues.get(inputIndex));
    }
}
