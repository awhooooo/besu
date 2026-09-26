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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import co.rsk.bitcoinj.core.BtcECKey;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Several single-key signers, presented as one.
 *
 * <p>Routing is by key rather than by configuration: whichever signer says it holds the key gets the
 * work, and if none does, nothing is signed. That is what keeps a member's keys from standing in for
 * each other when one of them is on a device and another is in a file.
 */
public class ECDSACompositeSigner implements ECDSASigner {

    private final List<ECDSASigner> signers = new ArrayList<>();

    public ECDSACompositeSigner addSigner(ECDSASigner signer) {
        signers.add(signer);
        return this;
    }

    @Override
    public boolean canSignWith(KeyId keyId) {
        return signers.stream().anyMatch(signer -> signer.canSignWith(keyId));
    }

    @Override
    public List<String> check() {
        return signers.stream().flatMap(signer -> signer.check().stream()).collect(Collectors.toList());
    }

    @Override
    public ECPublicKey getPublicKey(KeyId keyId) throws SignerException {
        return signerFor(keyId).getPublicKey(keyId);
    }

    @Override
    public BtcECKey.ECDSASignature sign(KeyId keyId, Bytes32 digest) throws SignerException {
        return signerFor(keyId).sign(keyId, digest);
    }

    @Override
    public String describe() {
        return signers.stream().map(ECDSASigner::describe).collect(Collectors.joining("; "));
    }

    private ECDSASigner signerFor(KeyId keyId) throws SignerException {
        return signers.stream()
            .filter(signer -> signer.canSignWith(keyId))
            .findFirst()
            .orElseThrow(() -> new SignerException(String.format("No signer holds %s", keyId)));
    }
}
