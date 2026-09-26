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

import java.util.Arrays;
import java.util.List;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.federate.signing.keyfile.KeyFileChecker;
import co.rsk.federate.signing.keyfile.KeyFileHandler;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signs with a key read from a file.
 *
 * <p>The key is read for each signature and cleared afterwards, so that it is in memory for as short
 * a time as this can manage. That is the most a process holding its own key can do, and it is why a
 * hardware device is the better answer where one is available.
 */
public class ECDSASignerFromFileKey implements ECDSASigner {

    private static final Logger logger = LoggerFactory.getLogger(ECDSASignerFromFileKey.class);

    private final KeyId keyId;
    private final String keyPath;

    public ECDSASignerFromFileKey(KeyId keyId, String keyPath) {
        this.keyId = keyId;
        this.keyPath = keyPath;
        logger.warn(
            "[ECDSASignerFromFileKey] Key {} is held in a file on this host. A hardware device keeps it out of this process's memory.",
            keyId);
    }

    @Override
    public boolean canSignWith(KeyId keyId) {
        return this.keyId.equals(keyId);
    }

    @Override
    public List<String> check() {
        return new KeyFileChecker(keyPath).check();
    }

    @Override
    public ECPublicKey getPublicKey(KeyId keyId) throws SignerException {
        requireOurKey(keyId);
        byte[] privateKey = null;
        try {
            privateKey = new KeyFileHandler(keyPath).privateKey();
            return new ECPublicKey(BtcECKey.fromPrivate(privateKey).getPubKey());
        } catch (Exception e) {
            throw new SignerException(String.format("Could not read the key for %s", keyId), e);
        } finally {
            clear(privateKey);
        }
    }

    @Override
    public BtcECKey.ECDSASignature sign(KeyId keyId, Bytes32 digest) throws SignerException {
        requireOurKey(keyId);
        byte[] privateKey = null;
        try {
            privateKey = new KeyFileHandler(keyPath).privateKey();
            return BtcECKey.fromPrivate(privateKey).sign(Sha256Hash.wrap(digest.toArrayUnsafe()));
        } catch (Exception e) {
            throw new SignerException(String.format("Could not sign with %s", keyId), e);
        } finally {
            clear(privateKey);
        }
    }

    @Override
    public String describe() {
        return String.format("key file %s for %s", keyPath, keyId);
    }

    private void requireOurKey(KeyId requested) throws SignerException {
        if (!canSignWith(requested)) {
            throw new SignerException(
                String.format("This signer holds %s and was asked for %s", keyId, requested));
        }
    }

    private static void clear(byte[] key) {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }
}
