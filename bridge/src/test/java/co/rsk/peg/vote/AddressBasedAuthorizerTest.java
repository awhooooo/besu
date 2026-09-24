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

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.peg.utils.PublicKeys;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

class AddressBasedAuthorizerTest {
    @Test
    void numberOfKeys_one() {
        AddressBasedAuthorizer auth = new AddressBasedAuthorizer(Arrays.asList(
            new BtcECKey(),
            new BtcECKey(),
            new BtcECKey(),
            new BtcECKey()
        ), AddressBasedAuthorizer.MinimumRequiredCalculation.ONE);

        Assertions.assertEquals(4, auth.getNumberOfAuthorizedKeys());
        Assertions.assertEquals(1, auth.getRequiredAuthorizedKeys());
    }

    @Test
    void numberOfKeys_majority() {
        AddressBasedAuthorizer auth = new AddressBasedAuthorizer(Arrays.asList(
            new BtcECKey(),
            new BtcECKey(),
            new BtcECKey(),
            new BtcECKey()
        ), AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY);

        Assertions.assertEquals(4, auth.getNumberOfAuthorizedKeys());
        Assertions.assertEquals(3, auth.getRequiredAuthorizedKeys());
    }

    @Test
    void numberOfKeys_all() {
        AddressBasedAuthorizer auth = new AddressBasedAuthorizer(Arrays.asList(
            new BtcECKey(),
            new BtcECKey(),
            new BtcECKey(),
            new BtcECKey()
        ), AddressBasedAuthorizer.MinimumRequiredCalculation.ALL);

        Assertions.assertEquals(4, auth.getNumberOfAuthorizedKeys());
        Assertions.assertEquals(4, auth.getRequiredAuthorizedKeys());
    }

    @Test
    void isAuthorized() {
        AddressBasedAuthorizer auth = new AddressBasedAuthorizer(Arrays.asList(
            BtcECKey.fromPrivate(BigInteger.valueOf(100L)),
            BtcECKey.fromPrivate(BigInteger.valueOf(101L)),
            BtcECKey.fromPrivate(BigInteger.valueOf(102L))
        ), AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY);

        for (long n = 100L; n <= 102L; n++) {
            Assertions.assertTrue(auth.isAuthorized(PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(n)))));
        }

        Assertions.assertFalse(auth.isAuthorized(PublicKeys.addressOf(BtcECKey.fromPrivate(BigInteger.valueOf(50L)))));
    }
}
