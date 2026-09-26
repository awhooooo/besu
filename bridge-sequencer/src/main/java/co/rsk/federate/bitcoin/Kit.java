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
package co.rsk.federate.bitcoin;

import java.io.File;
import java.util.Objects;

import org.bitcoinj.core.Context;
import org.bitcoinj.core.listeners.BlocksDownloadedEventListener;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * bitcoinj's own wiring of a peer group, a chain and a wallet, adjusted for this use.
 *
 * <p>The block store is an {@link SPVBlockStore} sized to hold the whole chain rather than the
 * default few thousand blocks. The client walks backwards from the chain head looking for the last
 * block the bridge also has, and a store that has forgotten those blocks cannot answer. Powpeg used
 * LevelDB for the same reason; its native library was built in 2013 for x86 and will not load on
 * anything else, and this store is pure Java.
 *
 * <p>Transaction dependency downloading is off: the wallet watches an address rather than spending,
 * so the inputs of the transactions it sees are of no interest, and fetching them would be a
 * request per input to no purpose.
 */
public class Kit extends WalletAppKit {

    private static final Logger logger = LoggerFactory.getLogger(Kit.class);

    /**
     * Room for this many headers. Bitcoin mainnet is short of 900,000 blocks and gains about 52,500
     * a year; at {@link SPVBlockStore}'s record size this is a file of roughly 200 MB.
     */
    private static final int BLOCK_STORE_CAPACITY = 2_000_000;

    private final Context btcContext;

    private BlocksDownloadedEventListener blockListener;
    private WalletCoinsReceivedEventListener coinsReceivedListener;
    private WalletCoinsSentEventListener coinsSentListener;

    public Kit(Context btcContext, File directory, String filePrefix, long earliestKeyTimeSeconds) {
        super(btcContext.getParams(), directory, filePrefix, earliestKeyTimeSeconds);
        this.btcContext = Objects.requireNonNull(btcContext, "btcContext");
    }

    public void setup(
        BlocksDownloadedEventListener blockListener,
        WalletCoinsReceivedEventListener coinsReceivedListener,
        WalletCoinsSentEventListener coinsSentListener) {
        this.blockListener = Objects.requireNonNull(blockListener, "blockListener");
        this.coinsReceivedListener = Objects.requireNonNull(coinsReceivedListener, "coinsReceivedListener");
        this.coinsSentListener = Objects.requireNonNull(coinsSentListener, "coinsSentListener");
    }

    @Override
    protected void onSetupCompleted() {
        logger.debug("[onSetupCompleted] Bitcoin peer ready");
        Context.propagate(btcContext);
        vPeerGroup.addBlocksDownloadedEventListener(blockListener);
        if (!vWallet.isConsistent()) {
            logger.warn("[onSetupCompleted] The wallet is inconsistent; resetting it so it rescans");
            vWallet.reset();
        }
        vWallet.addCoinsReceivedEventListener(coinsReceivedListener);
        vWallet.addCoinsSentEventListener(coinsSentListener);
        vPeerGroup.setDownloadTxDependencies(0);
    }

    @Override
    protected BlockStore provideBlockStore(File file) throws BlockStoreException {
        return new SPVBlockStore(params, file, BLOCK_STORE_CAPACITY, true);
    }
}
