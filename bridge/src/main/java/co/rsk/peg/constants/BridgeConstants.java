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

package co.rsk.peg.constants;


import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import org.hyperledger.besu.datatypes.Hash;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.constants.FeePerKbConstants;
import co.rsk.peg.lockingcap.constants.LockingCapConstants;

public abstract class BridgeConstants {
    protected String btcParamsString;

    protected FeePerKbConstants feePerKbConstants;
    protected FederationConstants federationConstants;
    protected LockingCapConstants lockingCapConstants;

    protected int btc2RskMinimumAcceptableConfirmations;
    protected int rsk2BtcMinimumAcceptableConfirmations;

    protected Coin minimumPeginTxValue;
    protected Coin minimumPegoutTxValue;
    protected Coin svpFundTxOutputsValue;

    protected int btcHeightWhenBlockIndexActivates;
    protected int maxDepthToSearchBlocksBelowIndexActivation;
    protected long minSecondsBetweenCallsReceiveHeader;

    protected int maxDepthBlockchainAccepted;

    protected int minimumPegoutValuePercentageToReceiveAfterFee;

    protected int maxInputsPerPegoutTransaction;

    protected int numberOfBlocksBetweenPegouts;


    public NetworkParameters getBtcParams() {
        return NetworkParameters.fromID(btcParamsString);
    }

    public FeePerKbConstants getFeePerKbConstants() { return feePerKbConstants; }


    public FederationConstants getFederationConstants() { return federationConstants; }

    public LockingCapConstants getLockingCapConstants() { return lockingCapConstants; }

    public String getBtcParamsString() {
        return btcParamsString;
    }

    public int getBtc2RskMinimumAcceptableConfirmations() {
        return btc2RskMinimumAcceptableConfirmations;
    }

    public int getRsk2BtcMinimumAcceptableConfirmations() {
        return rsk2BtcMinimumAcceptableConfirmations;
    }

    public Coin getMinimumPeginTxValue() { return minimumPeginTxValue; }

    public Coin getMinimumPegoutTxValue() { return minimumPegoutTxValue; }

    public Coin getSvpFundTxOutputsValue() { return svpFundTxOutputsValue; }

    public Hash getProposedFederationFlyoverPrefix() { return Hash.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000001"); }

    public Coin getMaxRbtc() { return Coin.valueOf(21_000_000, 0); }

    public int getBtcHeightWhenBlockIndexActivates() { return btcHeightWhenBlockIndexActivates; }

    public int getMaxDepthToSearchBlocksBelowIndexActivation() { return maxDepthToSearchBlocksBelowIndexActivation; }

    public long getMinSecondsBetweenCallsToReceiveHeader() { return minSecondsBetweenCallsReceiveHeader; }

    public int getMaxDepthBlockchainAccepted() { return maxDepthBlockchainAccepted; }

    public int getMinimumPegoutValuePercentageToReceiveAfterFee() {
        return minimumPegoutValuePercentageToReceiveAfterFee;
    }

    public int getMaxInputsPerPegoutTransaction() {
        return maxInputsPerPegoutTransaction;
    }

    public int getNumberOfBlocksBetweenPegouts() {
        return numberOfBlocksBetweenPegouts;
    }


}
