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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * Says what is wrong with a key file before anything tries to sign with it.
 *
 * <p>Permissions are part of that. A key a federation depends on should be readable by its owner and
 * by nobody else, and a file that anyone on the host can read is worth refusing to start over rather
 * than discovering later.
 */
public class KeyFileChecker {

    public static final int KEY_LENGTH = 32;

    private final String filePath;

    public KeyFileChecker(String filePath) {
        this.filePath = filePath;
    }

    /** Everything wrong with the file, empty when there is nothing. */
    public List<String> check() {
        // A file that is not there has no permissions worth reporting, and saying both makes the
        // real problem harder to pick out of the list.
        String keyFile = checkKeyFile();
        if (StringUtils.isNotEmpty(keyFile)) {
            return List.of(keyFile);
        }
        String permissions = checkFilePermissions();
        return StringUtils.isNotEmpty(permissions) ? List.of(permissions) : List.of();
    }

    public String checkKeyFile() {
        if (StringUtils.isBlank(filePath)) {
            return "Invalid key file name";
        }
        if (!Paths.get(filePath).toFile().exists()) {
            return "Key file '" + filePath + "' does not exist";
        }
        byte[] key = null;
        try {
            key = new KeyFileHandler(filePath).privateKey();
            if (key.length != KEY_LENGTH) {
                return "Invalid key size";
            }
        } catch (Exception e) {
            return "Error reading key file '" + filePath + "'";
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
        return "";
    }

    public String checkFilePermissions() {
        final String errorMessage = "Invalid key file permissions: a key file must be readable by its owner and nobody else";
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(Paths.get(filePath));
            return permissions.equals(Set.of(PosixFilePermission.OWNER_READ)) ? "" : errorMessage;
        } catch (IOException | UnsupportedOperationException e) {
            return errorMessage;
        }
    }
}
