package co.rsk.peg.lockingcap.constants;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import co.rsk.bitcoinj.core.BtcECKey;

public class LockingCapMainNetConstants extends LockingCapConstants {

    private static final LockingCapMainNetConstants instance = new LockingCapMainNetConstants();

    private LockingCapMainNetConstants() {
        List<BtcECKey> increaseAuthorizedKeys = Collections.unmodifiableList(Stream.of(
            "0498f27cfcb624a30bac2d826013727418ecacf6666f6456a1c20310f2def74596bc48a442749ebe4f074692dd30812565b4160a6d389a8d88b641b108ac9f8d5a",
            "0443fae6b1274fb9721996478a22af85fa43c8baeb0d2126ec2927a030afed4083c60abe0d11a0f52621615d471568498a89ce6b509dfe50523129e093cec29373",
            "044be7320865358340ba325a74212c851595c202e4df6593ec03d5f584e947332e48896c94072727c83876c7be8a49ed26f2b334d3c7a05c4083886109f1abc551"
        ).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).collect(Collectors.toList()));

        increaseAuthorizer = new AddressBasedAuthorizer(
            increaseAuthorizedKeys,
            AddressBasedAuthorizer.MinimumRequiredCalculation.ONE
        );

        initialValue = Coin.COIN.multiply(3000L); // 3000 BTC
        incrementsMultiplier = 2;
    }

    public static LockingCapMainNetConstants getInstance() {
        return instance;
    }
}
