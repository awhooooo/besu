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
package co.rsk.federate.signing.config;

import com.typesafe.config.Config;

/** One key's worth of configuration: which key, held how, and whatever that mechanism needs. */
public class SignerConfig {

    private static final String SIGNER_TYPE_PATH = "type";

    private final String id;
    private final SignerType type;
    private final Config config;

    public SignerConfig(String keyId, Config config) {
        this.id = keyId;
        this.type = SignerType.fromConfigValue(config.getString(SIGNER_TYPE_PATH));
        this.config = config.withoutPath(SIGNER_TYPE_PATH);
    }

    public String getId() {
        return id;
    }

    public SignerType getSignerType() {
        return type;
    }

    public Config getConfig() {
        return config;
    }
}
