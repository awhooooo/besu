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
package co.rsk.peg;

import co.rsk.peg.host.BridgeHost;

import co.rsk.bitcoinj.core.Context;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.BtcBlockStoreWithCache.Factory;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.*;
import co.rsk.peg.feeperkb.constants.FeePerKbConstants;
import co.rsk.peg.lockingcap.*;
import co.rsk.peg.lockingcap.constants.LockingCapConstants;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.peg.utils.*;

/**
 * BridgeSupportFactory allows BridgeSupport instantiation.
 */
public class BridgeSupportFactory {

    private final Factory btcBlockStoreFactory;
    private final BridgeConstants bridgeConstants;
    private final BootstrapWindow bootstrapWindow;

    public BridgeSupportFactory(
        Factory btcBlockStoreFactory,
        BridgeConstants bridgeConstants) {

        this(btcBlockStoreFactory, bridgeConstants, BootstrapWindow.CLOSED);
    }

    public BridgeSupportFactory(
        Factory btcBlockStoreFactory,
        BridgeConstants bridgeConstants,
        BootstrapWindow bootstrapWindow) {

        this.btcBlockStoreFactory = btcBlockStoreFactory;
        this.bridgeConstants = bridgeConstants;
        this.bootstrapWindow = bootstrapWindow;
    }

    public BridgeSupport newInstance(BridgeHost host) {
        Context btcContext = new Context(bridgeConstants.getBtcParams());
        NetworkParameters networkParameters = bridgeConstants.getBtcParams();

        StorageAccessor bridgeStorageAccessor = new BridgeStorageAccessorImpl(host);

        BridgeStorageProvider provider = new BridgeStorageProvider(bridgeStorageAccessor, networkParameters);

        FeePerKbSupport feePerKbSupport = getFeePerKbSupportInstance(bridgeStorageAccessor);
        FederationSupport federationSupport = getFederationSupportInstance(bridgeStorageAccessor, host);
        LockingCapSupport lockingCapSupport = getLockingCapSupportInstance(bridgeStorageAccessor);

        BridgeEventLogger eventLogger = new BridgeEventLoggerImpl(bridgeConstants, host);

        BtcLockSenderProvider btcLockSenderProvider = new BtcLockSenderProvider();
        PeginInstructionsProvider peginInstructionsProvider = new PeginInstructionsProvider();

        return new BridgeSupport(
            bridgeConstants,
            provider,
            eventLogger,
            btcLockSenderProvider,
            peginInstructionsProvider,
            host,
            btcContext,
            feePerKbSupport,
            federationSupport,
            lockingCapSupport,
            btcBlockStoreFactory,
            bootstrapWindow
        );
    }

    private FeePerKbSupport getFeePerKbSupportInstance(StorageAccessor bridgeStorageAccessor) {
        FeePerKbConstants feePerKbConstants = bridgeConstants.getFeePerKbConstants();
        FeePerKbStorageProvider feePerKbStorageProvider = new FeePerKbStorageProviderImpl(bridgeStorageAccessor);

        return new FeePerKbSupportImpl(feePerKbConstants, feePerKbStorageProvider);
    }

    private FederationSupport getFederationSupportInstance(StorageAccessor bridgeStorageAccessor, BridgeHost host) {
        FederationConstants federationConstants = bridgeConstants.getFederationConstants();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(bridgeStorageAccessor);
        return new FederationSupportImpl(federationConstants, federationStorageProvider, host);
    }

    private LockingCapSupport getLockingCapSupportInstance(StorageAccessor bridgeStorageAccessor) {
        LockingCapConstants lockingCapConstants = bridgeConstants.getLockingCapConstants();
        LockingCapStorageProvider lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);

        return new LockingCapSupportImpl(lockingCapStorageProvider, lockingCapConstants);
    }
}
