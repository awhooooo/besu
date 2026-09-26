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

import co.rsk.federate.signing.config.SignerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the signer a key's configuration asks for.
 *
 * <p>Only key files are built here. A hardware device is the better answer and the configuration
 * already names it, but no protocol is implemented yet: RSK's own device authenticates the chain it
 * signs for by checking merge-mining proofs, which this chain does not produce. Rather than pretend
 * otherwise, {@code hsm} fails with what it would take to support one.
 */
public class ECDSASignerFactory {

    private static final Logger logger = LoggerFactory.getLogger(ECDSASignerFactory.class);

    public ECDSASigner buildFromConfig(SignerConfig config) throws SignerException {
        if (config == null) {
            throw new SignerException("No signer configuration was given");
        }

        logger.debug("[buildFromConfig] Building a {} signer for {}", config.getSignerType(), config.getId());

        switch (config.getSignerType()) {
            case KEYFILE:
                return new ECDSASignerFromFileKey(
                    new KeyId(config.getId()),
                    config.getConfig().getString("path"));
            case HSM:
                throw new SignerException(
                    String.format(
                        "Key %s asks for a hardware device, and none is implemented yet. A device needs an"
                            + " ECDSASigner that signs a 32-byte digest with a named key: PKCS#11 C_Sign, or a"
                            + " cloud key service signing a pre-hashed message. Configure keyFile until then.",
                        config.getId()));
            default:
                throw new SignerException(
                    String.format("Unsupported signer type: %s", config.getSignerType()));
        }
    }
}
