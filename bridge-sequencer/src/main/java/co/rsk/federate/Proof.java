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

import java.util.Arrays;
import java.util.Objects;

import org.bitcoinj.core.PartialMerkleTree;
import org.bitcoinj.core.Sha256Hash;

/**
 * One block's proof that a transaction is in it.
 *
 * <p>A transaction can be proved by more than one block while a fork is unresolved, so the client
 * keeps every proof it has seen and picks the one on the best chain when it comes to inform the
 * bridge. Which is why this is a pair rather than a field on the transaction.
 */
public class Proof {

    private final Sha256Hash blockHash;
    private final PartialMerkleTree partialMerkleTree;

    public Proof(Sha256Hash blockHash, PartialMerkleTree partialMerkleTree) {
        this.blockHash = Objects.requireNonNull(blockHash, "blockHash");
        this.partialMerkleTree = Objects.requireNonNull(partialMerkleTree, "partialMerkleTree");
    }

    public Sha256Hash getBlockHash() {
        return blockHash;
    }

    public PartialMerkleTree getPartialMerkleTree() {
        return partialMerkleTree;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Proof other)) {
            return false;
        }
        return blockHash.equals(other.blockHash)
            && Arrays.equals(partialMerkleTree.bitcoinSerialize(), other.partialMerkleTree.bitcoinSerialize());
    }

    @Override
    public int hashCode() {
        return 31 * blockHash.hashCode() + Arrays.hashCode(partialMerkleTree.bitcoinSerialize());
    }

    @Override
    public String toString() {
        return "Proof{block=" + blockHash + "}";
    }
}
