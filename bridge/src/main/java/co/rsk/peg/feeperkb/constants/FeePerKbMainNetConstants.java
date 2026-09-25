package co.rsk.peg.feeperkb.constants;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import org.bouncycastle.util.encoders.Hex;
import co.rsk.bitcoinj.core.BtcECKey;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class FeePerKbMainNetConstants extends FeePerKbConstants {

    private static final FeePerKbMainNetConstants instance = new FeePerKbMainNetConstants();

    private FeePerKbMainNetConstants() {
        List<BtcECKey> feePerKbAuthorizedKeys = Arrays.stream(new String[]{
            "04d0d9f7a7b49ccf4ae5b95b0c34247daa59caeee392be3db6fb8c20dd7734d19bdb043fbd9b57b451a418b00aa4fc41f5bd7d6f813dae7733fe5e38f96bb0bd2c",
            "04fcd9b443608df6e2350d9dc9259210050ca3c3648798165caa6bfc5fa37b1061d0b0f8decf940b1c3c285941dc1e38b7b4960d431471a039dfcd598629c1fbf2",
            "048ab86490eb8a0b4c77c22966bfd78d42fbd31bfa38956869dc62794cf38b858ea0a7ec3e1d4ae4f40395b9cf3e4efbd1e249f803b775ca1561a09c70a4cee077"
        }).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList());

        feePerKbChangeAuthorizer = new AddressBasedAuthorizer(
            feePerKbAuthorizedKeys,
            AddressBasedAuthorizer.MinimumRequiredCalculation.MAJORITY
        );

        genesisFeePerKb = Coin.MILLICOIN.multiply(5);

        maxFeePerKb = Coin.valueOf(5_000_000L);
    }

    public static FeePerKbMainNetConstants getInstance() {
        return instance;
    }
}
