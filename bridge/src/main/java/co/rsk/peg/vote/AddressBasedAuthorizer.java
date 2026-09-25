/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
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
package co.rsk.peg.vote;

import co.rsk.peg.utils.PublicKeys;

import org.hyperledger.besu.datatypes.Address;
import co.rsk.bitcoinj.core.BtcECKey;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Authorizes an operation based
 * on an RSK address.
 *
 * @author Ariel Mendelzon
 */
public class AddressBasedAuthorizer {
    public enum MinimumRequiredCalculation { ONE, MAJORITY, ALL }

    protected List<byte[]> authorizedAddresses;
    protected MinimumRequiredCalculation requiredCalculation;

    public AddressBasedAuthorizer(List<BtcECKey> authorizedKeys, MinimumRequiredCalculation requiredCalculation) {
        this.authorizedAddresses = authorizedKeys.stream().map(key -> PublicKeys.addressOf(key).getBytes().toArrayUnsafe()).collect(Collectors.toList());
        this.requiredCalculation = requiredCalculation;
    }

    public boolean isAuthorized(Address sender) {
        return authorizedAddresses.stream()
            .anyMatch(address -> Arrays.equals(address, sender.getBytes().toArrayUnsafe()));
    }

    /** The addresses this authorizer accepts. The node needs them to recognise bootstrap senders. */
    public List<Address> getAuthorizedAddresses() {
        return authorizedAddresses.stream()
            .map(address -> Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(address)))
            .collect(Collectors.toList());
    }

    public int getNumberOfAuthorizedKeys() {
        return authorizedAddresses.size();
    }

    public int getRequiredAuthorizedKeys() {
        switch (requiredCalculation) {
            case ONE:
                return 1;
            case MAJORITY:
                return getNumberOfAuthorizedKeys() / 2 + 1;
            case ALL:
            default:
                return getNumberOfAuthorizedKeys();
        }
    }
}
