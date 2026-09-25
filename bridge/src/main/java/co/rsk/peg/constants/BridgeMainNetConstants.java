package co.rsk.peg.constants;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.federation.constants.FederationMainNetConstants;
import co.rsk.peg.feeperkb.constants.FeePerKbMainNetConstants;
import co.rsk.peg.lockingcap.constants.LockingCapMainNetConstants;

public class BridgeMainNetConstants extends BridgeConstants {
    private static final BridgeMainNetConstants instance = new BridgeMainNetConstants();

    private BridgeMainNetConstants() {
        btcParamsString = NetworkParameters.ID_MAINNET;
        feePerKbConstants = FeePerKbMainNetConstants.getInstance();
        federationConstants = FederationMainNetConstants.getInstance();
        lockingCapConstants = LockingCapMainNetConstants.getInstance();

        btc2RskMinimumAcceptableConfirmations = 100;
        rsk2BtcMinimumAcceptableConfirmations = 30_000;
        minimumPeginTxValue = Coin.FIFTY_COINS.multiply(10);
        minimumPegoutTxValue = Coin.FIFTY_COINS.multiply(10);
        svpFundTxOutputsValue = Coin.COIN.multiply(2);

        btcHeightWhenBlockIndexActivates = 0;
        maxDepthToSearchBlocksBelowIndexActivation = 4_320; // 30 days in BTC blocks (considering 1 block every 10 minutes)

        minSecondsBetweenCallsReceiveHeader = 300;  // 5 minutes in seconds
        maxDepthBlockchainAccepted = 25;

        minimumPegoutValuePercentageToReceiveAfterFee = 80;

        maxInputsPerPegoutTransaction = 50;

        numberOfBlocksBetweenPegouts = 3_600; // 3 hours of RSK blocks (considering 1 block every 3 seconds)
    }

    public static BridgeMainNetConstants getInstance() {
        return instance;
    }
}
