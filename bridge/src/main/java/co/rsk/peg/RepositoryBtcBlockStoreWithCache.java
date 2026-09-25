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

package co.rsk.peg;

import co.rsk.peg.host.BridgeHost;

import co.rsk.peg.utils.StorageKeys;

import static co.rsk.bitcoinj.core.StoredBlock.deserializeCompactV2;

import co.rsk.bitcoinj.core.BtcBlock;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.StoredBlock;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.peg.constants.BridgeConstants;
import java.util.Optional;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Implementation of a bitcoinj blockstore that persists to RSK's Repository
 *
 * @author Oscar Guindzberg
 *
 * <p>Ported over the {@link BridgeHost}. Blocks are stored in the V2 compact format only, with a 32-byte chain
 * work; the 12-byte format RSKj wrote before RSKIP454 never existed on this chain, and a stored block of that
 * size is rejected as corrupt state.
 */
public class RepositoryBtcBlockStoreWithCache implements BtcBlockStoreWithCache {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryBtcBlockStoreWithCache.class);

    private static final String BLOCK_STORE_CHAIN_HEAD_KEY = "blockStoreChainHead";
    private static final int DEFAULT_MAX_DEPTH_BLOCK_CACHE = 5_000;
    private static final int DEFAULT_MAX_SIZE_BLOCK_CACHE = 10_000;

    private final BridgeHost host;
    private final NetworkParameters btcNetworkParams;
    private final BridgeConstants bridgeConstants;
    private final BridgeStorageProvider bridgeStorageProvider;
    private final int maxDepthBlockCache;
    private final Map<Sha256Hash, StoredBlock> cacheBlocks;

    /**
     * The chain head as this store last saw it, so that {@link #put} can tell whether a block is recent enough to
     * cache without reading the head back from storage for every block it stores. Storing ten headers read it ten
     * times, and on 32-byte slots a stored block is five slots, so that was fifty slot reads for a value the caller
     * was about to hand over anyway.
     *
     * <p>It is a hint, not the head. {@link #getChainHead} still reads storage, because a store may be built over
     * state another one wrote. The cache this feeds is an accelerator whose miss falls through to storage and whose
     * keys are block hashes, so a stale hint can at worst admit a block that is too deep or skip a recent one; it
     * cannot produce a wrong block. Within one bridge call it cannot even be stale: one store serves the call and
     * {@link #setChainHead} is the only writer of that key.
     */
    private StoredBlock lastSeenChainHead;

    public RepositoryBtcBlockStoreWithCache(
        NetworkParameters btcNetworkParams,
        BridgeHost host,
        Map<Sha256Hash, StoredBlock> cacheBlocks,
        BridgeConstants bridgeConstants,
        BridgeStorageProvider bridgeStorageProvider) {

        this(
            btcNetworkParams,
            host,
            cacheBlocks,
            bridgeConstants,
            bridgeStorageProvider,
            DEFAULT_MAX_DEPTH_BLOCK_CACHE
        );
    }

    public RepositoryBtcBlockStoreWithCache(
        NetworkParameters btcNetworkParams,
        BridgeHost host,
        Map<Sha256Hash, StoredBlock> cacheBlocks,
        BridgeConstants bridgeConstants,
        BridgeStorageProvider bridgeStorageProvider,
        int maxDepthBlockCache) {

        this.cacheBlocks = cacheBlocks;
        this.host = host;
        this.btcNetworkParams = btcNetworkParams;
        this.bridgeConstants = bridgeConstants;
        this.bridgeStorageProvider = bridgeStorageProvider;
        this.maxDepthBlockCache = maxDepthBlockCache;

        checkIfInitialized();
    }

    @Override
    public synchronized void put(StoredBlock storedBlock) {
        Sha256Hash hash = storedBlock.getHeader().getHash();
        byte[] ba = storedBlockToByteArray(storedBlock);
        host.putStorage(StorageKeys.of(hash), ba);
        if (cacheBlocks != null) {
            StoredBlock chainHead = lastSeenChainHead != null ? lastSeenChainHead : getChainHead();
            if (chainHead == null || chainHead.getHeight() - storedBlock.getHeight() < this.maxDepthBlockCache) {
                cacheBlocks.put(storedBlock.getHeader().getHash(), storedBlock);
            }
        }
    }

    @Override
    public synchronized StoredBlock get(Sha256Hash hash) {
        logger.trace("[get] Looking in storage for block with hash {}", hash);
        byte[] ba = host.getStorage(StorageKeys.of(hash));
        if (ba == null) {
            logger.trace("[get] Block with hash {} not found in storage", hash);
            return null;
        }
        return byteArrayToStoredBlock(ba);
    }

    @Override
    public synchronized StoredBlock getChainHead() {
        byte[] ba = host.getStorage(StorageKeys.name(BLOCK_STORE_CHAIN_HEAD_KEY));
        if (ba == null) {
            return null;
        }
        lastSeenChainHead = byteArrayToStoredBlock(ba);
        return lastSeenChainHead;
    }

    @Override
    public synchronized void setChainHead(StoredBlock newChainHead) {
        logger.trace("Set new chain head with height: {}.", newChainHead.getHeight());
        byte[] ba = storedBlockToByteArray(newChainHead);
        host.putStorage(StorageKeys.name(BLOCK_STORE_CHAIN_HEAD_KEY), ba);
        lastSeenChainHead = newChainHead;
        if (cacheBlocks != null) {
            populateCache(newChainHead);
        }
        setMainChainBlock(newChainHead.getHeight(), newChainHead.getHeader().getHash());
    }

    @Override
    public Optional<StoredBlock> getInMainchain(int height) {
        Optional<Sha256Hash> bestBlockHash = bridgeStorageProvider.getBtcBestBlockHashByHeight(height);
        if (!bestBlockHash.isPresent()) {
            logger.trace("[getInMainchain] Block at height {} not present in storage", height);
            return Optional.empty();
        }

        StoredBlock block = get(bestBlockHash.get());
        if (block == null) {
            logger.trace("[getInMainchain] Block with hash {} not found in storage", bestBlockHash.get());
            return Optional.empty();
        }

        logger.trace("[getInMainchain] Found block with hash {} at height {}", bestBlockHash.get(), height);
        return Optional.of(block);
    }

    @Override
    public void setMainChainBlock(int height, Sha256Hash blockHash) {
        logger.trace("[setMainChainBlock] Set block with hash {} at height {}", blockHash, height);
        bridgeStorageProvider.setBtcBestBlockHashByHeight(height, blockHash);
    }

    @Override
    public void close() {
    }

    @Override
    public NetworkParameters getParams() {
        return btcNetworkParams;
    }

    @Override
    public StoredBlock getFromCache(Sha256Hash branchBlockHash) {
        if (cacheBlocks == null) {
            logger.trace("[getFromCache] Block with hash {} not found in cache", branchBlockHash);
            return null;
        }
        return cacheBlocks.get(branchBlockHash);
    }

    @Override
    public StoredBlock getStoredBlockAtMainChainHeight(int height) throws BlockStoreException {
        StoredBlock chainHead = getChainHead();
        int depth = chainHead.getHeight() - height;
        logger.trace("Getting btc block at depth: {}", depth);

        if (depth < 0) {
            String message = String.format(
                "Height provided is higher than chain head. provided: %s. chain head: %s",
                height,
                chainHead.getHeight()
            );
            logger.trace("[getStoredBlockAtMainChainHeight] {}", message);
            throw new BlockStoreException(message);
        }

        int btcHeightWhenBlockIndexActivates = this.bridgeConstants.getBtcHeightWhenBlockIndexActivates();
        int maxDepthToSearch = this.bridgeConstants.getMaxDepthToSearchBlocksBelowIndexActivation();
        int limit;
        if (chainHead.getHeight() - btcHeightWhenBlockIndexActivates > maxDepthToSearch) {
            limit = btcHeightWhenBlockIndexActivates;
        } else {
            limit = chainHead.getHeight() - maxDepthToSearch;
        }
        logger.trace("[getStoredBlockAtMainChainHeight] Chain head height is {} and the depth limit {}", chainHead.getHeight(), limit);

        if (height < limit) {
            String message = String.format(
                "Height provided is lower than the depth limit defined to search for blocks. Provided: %n, limit: %n",
                height,
                limit
            );
            logger.trace("[getStoredBlockAtMainChainHeight] {}", message);
            throw new BlockStoreException(message);
        }

        StoredBlock block;
        Optional<StoredBlock> blockOptional = getInMainchain(height);
        if (blockOptional.isPresent()) {
            block = blockOptional.get();
        } else {
            block = getStoredBlockAtMainChainDepth(depth);
        }

        return block;
    }

    private synchronized void populateCache(StoredBlock chainHead) {
        logger.trace("Populating BTC Block Store Cache.");
        if (this.btcNetworkParams.getGenesisBlock().equals(chainHead.getHeader())) {
            return;
        }
        cacheBlocks.put(chainHead.getHeader().getHash(), chainHead);
        Sha256Hash blockHash = chainHead.getHeader().getPrevBlockHash();
        int depth = this.maxDepthBlockCache - 1;
        while (blockHash != null && depth > 0) {
            if (cacheBlocks.get(blockHash) != null) {
                break;
            }
            StoredBlock currentBlock = get(blockHash);
            if (currentBlock == null) {
                break;
            }
            cacheBlocks.put(currentBlock.getHeader().getHash(), currentBlock);
            depth--;
            blockHash = currentBlock.getHeader().getPrevBlockHash();
        }
        logger.trace("END Populating BTC Block Store Cache.");
    }

    @Override
    @Deprecated
    public StoredBlock getStoredBlockAtMainChainDepth(int depth) throws BlockStoreException {
        logger.trace("[getStoredBlockAtMainChainDepth] Looking for block at depth {}", depth);
        StoredBlock chainHead = getChainHead();
        Sha256Hash blockHash = chainHead.getHeader().getHash();

        for (int i = 0; i < depth && blockHash != null; i++) {
            //If its older than cache go to disk
            StoredBlock currentBlock = getFromCache(blockHash);
            if (currentBlock == null) {
                logger.trace("[getStoredBlockAtMainChainDepth] Block with hash {} not in cache, getting from disk", blockHash);
                currentBlock = get(blockHash);
                if (currentBlock == null) {
                    return null;
                }
            }
            blockHash = currentBlock.getHeader().getPrevBlockHash();
        }

        if (blockHash == null) {
            logger.trace("[getStoredBlockAtMainChainDepth] Block not found");
            return null;
        }
        StoredBlock block = getFromCache(blockHash);
        if (block == null) {
            block = get(blockHash);
        }
        int expectedHeight = chainHead.getHeight() - depth;
        if (block != null && block.getHeight() != expectedHeight) {
            String message = String.format("Block %s at depth %d Height is %d but should be %d",
                block.getHeader().getHash(),
                depth,
                block.getHeight(),
                expectedHeight
            );
            logger.trace("[getStoredBlockAtMainChainDepth] {}", message);
            throw new BlockStoreException(message);
        }

        return block;
    }

    private byte[] storedBlockToByteArray(StoredBlock block) {
        ByteBuffer byteBuffer = serializeBlock(block);
        byte[] ba = new byte[byteBuffer.position()];
        byteBuffer.flip();
        byteBuffer.get(ba);
        return ba;
    }

    private ByteBuffer serializeBlock(StoredBlock block) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE_V2);
        block.serializeCompactV2(byteBuffer);
        return byteBuffer;
    }

    private StoredBlock byteArrayToStoredBlock(byte[] ba) {
        if (ba.length != StoredBlock.COMPACT_SERIALIZED_SIZE_V2) {
            throw new IllegalStateException(String.format(
                "A stored block must be %d bytes long, found %d",
                StoredBlock.COMPACT_SERIALIZED_SIZE_V2,
                ba.length
            ));
        }

        return deserializeCompactV2(btcNetworkParams, ByteBuffer.wrap(ba));
    }

    private void checkIfInitialized() {
        if (getChainHead() == null) {
            BtcBlock genesisHeader = this.btcNetworkParams.getGenesisBlock().cloneAsHeader();
            StoredBlock storedGenesis = new StoredBlock(genesisHeader, genesisHeader.getWork(), 0);
            put(storedGenesis);
            setChainHead(storedGenesis);
        }
    }

    public static class Factory implements BtcBlockStoreWithCache.Factory {

        private final int maxSizeBlockCache;
        /**
         * One cache serves every store instance the node creates, on every thread that executes bridge calls:
         * Besu pre-executes the transactions of a block in parallel and serves JSON-RPC calls beside block
         * import. It is an accelerator only (a miss falls through to storage, and a block hash names exactly
         * one stored block), so any bounded, thread-safe map will do; this is the one Besu caches with.
         */
        private final Map<Sha256Hash, StoredBlock> cacheBlocks;
        private final NetworkParameters btcNetworkParams;
        private final int maxDepthBlockCache;

        @VisibleForTesting
        public Factory(NetworkParameters btcNetworkParams) {
            this(btcNetworkParams, DEFAULT_MAX_DEPTH_BLOCK_CACHE, DEFAULT_MAX_SIZE_BLOCK_CACHE);
        }

        public Factory(NetworkParameters btcNetworkParams, int maxDepthBlockCache, int maxSizeBlockCache) {
            this.btcNetworkParams = btcNetworkParams;
            this.maxDepthBlockCache = maxDepthBlockCache;
            this.maxSizeBlockCache = maxSizeBlockCache;
            Cache<Sha256Hash, StoredBlock> cache = Caffeine.newBuilder().maximumSize(this.maxSizeBlockCache).build();
            this.cacheBlocks = cache.asMap();

            if (this.maxDepthBlockCache > this.maxSizeBlockCache) {
                logger.warn("Max depth ({}) is greater than Max Size ({}). This could lead to a misbehaviour.", this.maxDepthBlockCache, this.maxSizeBlockCache);
            }

            if (this.maxDepthBlockCache < DEFAULT_MAX_DEPTH_BLOCK_CACHE) {
                logger.warn("Max depth ({}) is lower than the default ({}). This could lead to a misbehaviour.", this.maxDepthBlockCache, DEFAULT_MAX_DEPTH_BLOCK_CACHE);
            }
        }

        @Override
        public BtcBlockStoreWithCache newInstance(
            BridgeHost host,
            BridgeConstants bridgeConstants,
            BridgeStorageProvider bridgeStorageProvider) {

            return new RepositoryBtcBlockStoreWithCache(
                btcNetworkParams,
                host,
                cacheBlocks,
                bridgeConstants,
                bridgeStorageProvider,
                this.maxDepthBlockCache
            );
        }
    }
}
