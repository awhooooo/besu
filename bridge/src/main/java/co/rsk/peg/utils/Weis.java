package co.rsk.peg.utils;


import co.rsk.bitcoinj.core.Coin;
import org.hyperledger.besu.datatypes.Wei;

import java.math.BigInteger;

/** Conversions between the chain's native unit and satoshis, as RSKj's co.rsk.core.Coin did them. */
public final class Weis {

    /** 1 satoshi = 10^10 wei. */
    public static final BigInteger WEIS_PER_SATOSHI = BigInteger.TEN.pow(10);

    private Weis() {
    }

    /** Equivalent of {@code co.rsk.core.Coin.fromBitcoin(satoshis)}. */
    public static Wei fromSatoshis(Coin satoshis) {
        return Wei.of(BigInteger.valueOf(satoshis.getValue()).multiply(WEIS_PER_SATOSHI));
    }

    /** Equivalent of {@code weis.toBitcoin()}: truncating division, then RSKj's narrowing to a long. */
    public static Coin toSatoshis(Wei weis) {
        return Coin.valueOf(weis.toBigInteger().divide(WEIS_PER_SATOSHI).longValue());
    }
}
