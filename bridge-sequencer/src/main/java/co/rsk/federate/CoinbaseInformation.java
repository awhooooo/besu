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

import java.util.Objects;

import org.bitcoinj.core.PartialMerkleTree;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;

/**
 * What the bridge needs in order to believe a segwit peg-in.
 *
 * <p>A segwit transaction is identified by its wtxid, and a block header commits to wtxids only
 * indirectly: through a witness merkle root that the coinbase carries in an output, and a reserved
 * value in the coinbase's own witness. So proving a segwit peg-in means first proving the coinbase,
 * which is what this carries.
 *
 * <p>{@code readyToInform} is false until the block holding it has been given to the bridge, since
 * the bridge cannot check a coinbase against a header it has not got.
 */
public class CoinbaseInformation {

    private final Transaction coinbaseTransaction;
    private final Sha256Hash witnessRoot;
    private final Sha256Hash blockHash;
    private final PartialMerkleTree pmt;

    private boolean readyToInform;

    public CoinbaseInformation(
        Transaction coinbaseTransaction, Sha256Hash witnessRoot, Sha256Hash blockHash, PartialMerkleTree pmt) {
        this(coinbaseTransaction, witnessRoot, blockHash, pmt, false);
    }

    public CoinbaseInformation(
        Transaction coinbaseTransaction,
        Sha256Hash witnessRoot,
        Sha256Hash blockHash,
        PartialMerkleTree pmt,
        boolean readyToInform) {
        Objects.requireNonNull(coinbaseTransaction, "coinbaseTransaction");
        if (!coinbaseTransaction.isCoinBase()) {
            throw new IllegalArgumentException("Transaction " + coinbaseTransaction.getTxId() + " is not a coinbase");
        }
        if (!coinbaseTransaction.hasWitnesses()) {
            throw new IllegalArgumentException(
                "Coinbase " + coinbaseTransaction.getTxId() + " has no witness, so it commits to no witness root");
        }
        this.coinbaseTransaction = coinbaseTransaction;
        this.witnessRoot = Objects.requireNonNull(witnessRoot, "witnessRoot");
        this.blockHash = Objects.requireNonNull(blockHash, "blockHash");
        this.pmt = Objects.requireNonNull(pmt, "pmt");
        this.readyToInform = readyToInform;
    }

    /**
     * The coinbase as the bridge wants it: without the witness.
     *
     * <p>The witness is what the bridge is checking the commitment against, so passing it back would
     * be circular; the bridge reconstructs it from the reserved value and the witness root instead.
     */
    public byte[] getSerializedCoinbaseTransactionWithoutWitness() {
        Transaction withoutWitness =
            new Transaction(coinbaseTransaction.getParams(), coinbaseTransaction.bitcoinSerialize());
        withoutWitness.getInput(0).setWitness(null);
        return withoutWitness.bitcoinSerialize();
    }

    public Transaction getCoinbaseTransaction() {
        return coinbaseTransaction;
    }

    public Sha256Hash getWitnessRoot() {
        return witnessRoot;
    }

    public Sha256Hash getBlockHash() {
        return blockHash;
    }

    public PartialMerkleTree getPmt() {
        return pmt;
    }

    public boolean isReadyToInform() {
        return readyToInform;
    }

    public void setReadyToInform(boolean readyToInform) {
        this.readyToInform = readyToInform;
    }

    /**
     * The 32 bytes the miner put in the coinbase's witness, or null if it is not 32 bytes.
     *
     * <p>Together with the witness merkle root this is what hashes to the commitment in the
     * coinbase's output, so a value of any other length cannot be part of a valid commitment.
     */
    public byte[] getCoinbaseWitnessReservedValue() {
        if (coinbaseTransaction.getInput(0).getWitness().getPushCount() == 0) {
            return null;
        }
        byte[] reserved = coinbaseTransaction.getInput(0).getWitness().getPush(0);
        return reserved != null && reserved.length == 32 ? reserved : null;
    }
}
