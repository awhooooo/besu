/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
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

package co.rsk.peg;

import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.federation.FederationStorageProvider;
import co.rsk.peg.federation.FederationStorageProviderImpl;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.SortedMap;

/**
 * Created by ajlopez on 16/04/2017.
 */
class BridgeStateTest {
    @Test
    void recreateFromEmptyStorageProvider() throws IOException {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        BridgeConstants bridgeConstants = new BridgeRegTestConstants();

        NetworkParameters networkParameters = bridgeConstants.getBtcParams();

        StorageAccessor bridgeStorageAccessor = new BridgeStorageAccessorImpl(host);
        BridgeStorageProvider bridgeStorageProvider = new BridgeStorageProvider(bridgeStorageAccessor, networkParameters);
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(bridgeStorageAccessor);

        int btcBlockchainBestChainHeight = 42;
        long nextPegoutCreationBlockNumber = bridgeStorageProvider.getNextPegoutHeight().get();
        List<UTXO> activeFederationBtcUTXOs = federationStorageProvider.getNewFederationBtcUTXOs();
        SortedMap<Hash, BtcTransaction> rskTxsWaitingForSignatures = bridgeStorageProvider.getPegoutsWaitingForSignatures();
        ReleaseRequestQueue releaseRequestQueue = bridgeStorageProvider.getReleaseRequestQueue();
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = bridgeStorageProvider.getPegoutsWaitingForConfirmations();

        BridgeState state = new BridgeState(btcBlockchainBestChainHeight, nextPegoutCreationBlockNumber, activeFederationBtcUTXOs, rskTxsWaitingForSignatures, releaseRequestQueue, pegoutsWaitingForConfirmations);

        BridgeState clone = BridgeState.create(bridgeConstants, state.getEncoded());

        Assertions.assertNotNull(clone);
        Assertions.assertEquals(42, clone.getBtcBlockchainBestChainHeight());
        Assertions.assertEquals(0, clone.getNextPegoutCreationBlockNumber());
        Assertions.assertTrue(clone.getActiveFederationBtcUTXOs().isEmpty());
        Assertions.assertTrue(clone.getRskTxsWaitingForSignatures().isEmpty());
    }
}
