package co.rsk.peg.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.rsk.bitcoinj.core.Coin;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

class WeisTest {

    @Test
    void oneSatoshiIsTenToTheTenWei() {
        assertEquals(Wei.of(BigInteger.TEN.pow(10)), Weis.fromSatoshis(Coin.SATOSHI));
        assertEquals(Wei.of(BigInteger.TEN.pow(18)), Weis.fromSatoshis(Coin.COIN));
    }

    @Test
    void conversionBackTruncatesLikeRskj() {
        Wei oneSatoshiAndDust = Wei.of(BigInteger.TEN.pow(10).add(BigInteger.valueOf(999)));
        assertEquals(Coin.SATOSHI, Weis.toSatoshis(oneSatoshiAndDust));
        assertEquals(Coin.ZERO, Weis.toSatoshis(Wei.of(999)));
        assertEquals(Coin.valueOf(12345), Weis.toSatoshis(Weis.fromSatoshis(Coin.valueOf(12345))));
    }
}
