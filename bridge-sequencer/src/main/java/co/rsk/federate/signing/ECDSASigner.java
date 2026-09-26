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

import co.rsk.bitcoinj.core.BtcECKey;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Knows how to sign a digest with one of a federator's keys.
 *
 * <p>Deliberately narrow: a digest in, a signature out. That is all a hardware device offers — a
 * PKCS#11 {@code C_Sign} or a cloud key service signing a pre-hashed message — so anything wider
 * would be a shape only a file key could satisfy. What is being signed, and why it is safe to sign
 * it, is the caller's business.
 *
 * <p>An implementation holds exactly one key. Several of them are composed by {@link
 * ECDSACompositeSigner}, which routes by {@link #canSignWith}, so that a member's bitcoin key is
 * never asked to do the RSK key's work.
 */
public interface ECDSASigner {

    /** Whether this signer holds that key. */
    boolean canSignWith(KeyId keyId);

    /** Everything wrong with this signer's configuration, empty when there is nothing. */
    List<String> check();

    ECPublicKey getPublicKey(KeyId keyId) throws SignerException;

    /**
     * Signs a digest. Thirty-two bytes, already hashed: a bitcoin sighash, or the hash a chain
     * requires of a transaction's signing preimage.
     *
     * <p>The signature is returned as it came from the key. Callers that need a canonical one, as
     * bitcoin does, canonicalise it themselves, because whether low-S is required depends on what
     * the signature is for and not on where it came from.
     */
    BtcECKey.ECDSASignature sign(KeyId keyId, Bytes32 digest) throws SignerException;

    /** For logs, so an operator can tell from a line which key and which mechanism answered. */
    String describe();
}
