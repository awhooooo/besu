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
package co.rsk.federate.signing.keyfile;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads a hex-encoded private key from a file, one key to a file. */
public class KeyFileHandler {

    private static final Logger logger = LoggerFactory.getLogger(KeyFileHandler.class);

    private final String filePath;

    public KeyFileHandler(String filePath) {
        this.filePath = filePath;
    }

    /**
     * The key, as bytes. The caller owns the array and should clear it when done: this returns a
     * fresh copy every time rather than holding one, so that nothing here keeps key material alive
     * longer than whoever asked for it.
     */
    public byte[] privateKey() throws FileNotFoundException {
        if (StringUtils.isBlank(filePath) || !Paths.get(filePath).toFile().exists()) {
            logger.error("[privateKey] Key file not found: {}", filePath);
            throw new FileNotFoundException(String.format("Error accessing key file %s", filePath));
        }

        char[] line = null;
        try (FileReader reader = new FileReader(filePath, StandardCharsets.UTF_8);
             BufferedReader buffered = new BufferedReader(reader)) {
            String read = buffered.readLine();
            if (read == null) {
                throw new IllegalStateException("Key file is empty");
            }
            line = StringUtils.trim(read).toCharArray();
            return Hex.decode(new String(line));
        } catch (Exception e) {
            logger.error("[privateKey] Error while reading key file", e);
            throw new IllegalStateException("Error while reading key file", e);
        } finally {
            if (line != null) {
                Arrays.fill(line, '\0');
            }
        }
    }
}
