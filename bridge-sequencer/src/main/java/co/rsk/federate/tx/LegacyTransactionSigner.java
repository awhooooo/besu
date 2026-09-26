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
package co.rsk.federate.tx;

import java.math.BigInteger;
import java.util.Objects;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.federate.signing.ECDSASigner;
import co.rsk.federate.signing.ECPublicKey;
import co.rsk.federate.signing.KeyId;
import co.rsk.federate.signing.SignerException;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;

/**
 * Builds and signs the transactions this process sends to the node.
 *
 * <p>Only one kind: a legacy transaction, replay-protected as EIP-155 describes. That is the whole
 * of what the peg needs, and pinning to one shape means there is exactly one encoding to be right
 * about. A later shape can be added when something asks for one; guessing at several now would only
 * multiply the ways to be wrong.
 *
 * <p>The signature is taken from an {@link ECDSASigner}, which is handed a digest and never a key.
 * That is what lets the signing key live in a device this process cannot read. The two steps that go
 * with signing externally are both here: the signature is made canonical first, because both chains
 * require low-S and because flipping S afterwards would invalidate the recovery id; and the recovery
 * id is then found by recovering the public key and seeing which candidate gives back the key that
 * signed.
 */
public final class LegacyTransactionSigner {

    private final ECDSASigner signer;
    private final KeyId keyId;
    private final BigInteger chainId;
    private final ECPublicKey publicKey;

    public LegacyTransactionSigner(ECDSASigner signer, KeyId keyId, BigInteger chainId)
        throws SignerException {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.publicKey = signer.getPublicKey(keyId);
    }

    /** The address these transactions will come from. */
    public Address senderAddress() {
        return publicKey.toAddress();
    }

    /**
     * The hash an external signer must sign to authorise this transaction.
     *
     * <p>Not the hash of what goes on the wire: EIP-155 signs a list ending in {@code chainId, 0, 0}
     * where the wire form ends in {@code v, r, s}. Getting that wrong produces a transaction that
     * encodes cleanly and recovers the wrong sender, which is why
     * {@code LegacyTransactionSignerTest} decodes what this produces with Besu's own decoder and
     * checks the sender it recovers.
     */
    public Bytes32 signingHash(UnsignedTransaction transaction) {
        BytesValueRLPOutput out = new BytesValueRLPOutput();
        out.startList();
        writeBody(out, transaction);
        out.writeBigIntegerScalar(chainId);
        out.writeLongScalar(0);
        out.writeLongScalar(0);
        out.endList();
        return Hash.keccak256(out.encoded());
    }

    /** Signs and encodes, ready for {@code eth_sendRawTransaction}. */
    public Bytes sign(UnsignedTransaction transaction) throws SignerException {
        Bytes32 hash = signingHash(transaction);
        BtcECKey.ECDSASignature signature = signer.sign(keyId, hash).toCanonicalised();
        int recoveryId = recoveryIdOf(signature, hash);

        BytesValueRLPOutput out = new BytesValueRLPOutput();
        out.startList();
        writeBody(out, transaction);
        // EIP-155: v carries the chain, so a transaction signed for one chain cannot be replayed on
        // another.
        out.writeBigIntegerScalar(
            BigInteger.valueOf(recoveryId).add(chainId.multiply(BigInteger.TWO)).add(BigInteger.valueOf(35)));
        out.writeBigIntegerScalar(signature.r);
        out.writeBigIntegerScalar(signature.s);
        out.endList();
        return out.encoded();
    }

    private static void writeBody(BytesValueRLPOutput out, UnsignedTransaction transaction) {
        out.writeLongScalar(transaction.nonce());
        out.writeBigIntegerScalar(transaction.gasPrice());
        out.writeLongScalar(transaction.gasLimit());
        out.writeBytes(transaction.to().getBytes());
        out.writeBigIntegerScalar(transaction.value());
        out.writeBytes(transaction.payload());
    }

    private int recoveryIdOf(BtcECKey.ECDSASignature signature, Bytes32 hash) throws SignerException {
        Sha256Hash message = Sha256Hash.wrap(hash.toArrayUnsafe());
        for (int candidate = 0; candidate < 4; candidate++) {
            BtcECKey recovered = BtcECKey.recoverFromSignature(candidate, signature, message, true);
            if (recovered != null && new ECPublicKey(recovered.getPubKey()).equals(publicKey)) {
                return candidate;
            }
        }
        throw new SignerException(
            String.format(
                "The signature %s returned for %s does not recover to it. The digest signed was not the"
                    + " one asked for, or the key is not the one it claims.",
                keyId, keyId));
    }

    /** A transaction before it has a signature. */
    public record UnsignedTransaction(
        long nonce, BigInteger gasPrice, long gasLimit, Address to, BigInteger value, Bytes payload) {

        public UnsignedTransaction {
            Objects.requireNonNull(gasPrice, "gasPrice");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(payload, "payload");
            if (nonce < 0 || gasLimit < 0) {
                throw new IllegalArgumentException("A nonce and a gas limit cannot be negative");
            }
            if (gasPrice.signum() < 0 || value.signum() < 0) {
                throw new IllegalArgumentException("A gas price and a value cannot be negative");
            }
        }
    }
}
