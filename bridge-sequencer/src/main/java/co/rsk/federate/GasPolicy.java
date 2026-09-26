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

/**
 * What a bridge transaction pays.
 *
 * <p>While a chain is bringing its peg to life, its operators hold nothing and the bridge lets them
 * send valueless calls to it without meeting the fee floor. The exemption waives the floor and pays
 * the miner nothing; it does not waive the up-front debit of {@code gasPrice × gasLimit}. So a
 * federator with an empty account must offer a gas price of exactly zero, or its own transaction is
 * rejected for a balance it was never going to need. That is the whole reason this is a policy and
 * not a constant.
 *
 * <p>The block the window closes at is read from the node rather than configured here, so that it
 * cannot drift from the genesis file that decides it.
 */
public final class GasPolicy {

    private final long bridgeTxsPaidFromBlock;
    private final BigInteger gasPriceOncePaid;

    public GasPolicy(long bridgeTxsPaidFromBlock, BigInteger gasPriceOncePaid) {
        if (bridgeTxsPaidFromBlock < 0) {
            throw new IllegalArgumentException("A bootstrap window cannot end before genesis");
        }
        if (gasPriceOncePaid == null || gasPriceOncePaid.signum() < 0) {
            throw new IllegalArgumentException("A gas price cannot be negative");
        }
        this.bridgeTxsPaidFromBlock = bridgeTxsPaidFromBlock;
        this.gasPriceOncePaid = gasPriceOncePaid;
    }

    /** A chain with no bootstrap window, where every bridge transaction pays from the first block. */
    public static GasPolicy alwaysPaid(BigInteger gasPrice) {
        return new GasPolicy(0, gasPrice);
    }

    /**
     * The gas price a transaction being built for the block after {@code chainHeight} should carry.
     *
     * <p>Asked about the next block rather than the current one, because that is where the
     * transaction will land, and a transaction built at the last block of the window would otherwise
     * be free in a block that no longer allows it.
     */
    public BigInteger gasPriceFor(long chainHeight) {
        return isWithinBootstrapWindow(chainHeight) ? BigInteger.ZERO : gasPriceOncePaid;
    }

    public boolean isWithinBootstrapWindow(long chainHeight) {
        return chainHeight + 1 < bridgeTxsPaidFromBlock;
    }

    public long bridgeTxsPaidFromBlock() {
        return bridgeTxsPaidFromBlock;
    }
}
