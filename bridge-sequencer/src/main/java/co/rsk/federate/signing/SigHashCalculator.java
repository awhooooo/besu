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

/**
 * The digest a federator actually signs for one input of a peg-out.
 *
 * <p>There are two of these because segwit changed what a signature commits to. Signing the wrong
 * one produces a signature that is valid arithmetic over the wrong message: the bridge counts it,
 * the threshold is reached, and the transaction bitcoin sees is unspendable.
 */
public interface SigHashCalculator {
    Sha256Hash calculate(BtcTransaction btcTx, int inputIndex);
}
