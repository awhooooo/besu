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
package co.rsk.federate.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import co.rsk.federate.CoinbaseInformation;
import co.rsk.federate.Proof;
import org.apache.tuweni.bytes.Bytes;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PartialMerkleTree;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.hyperledger.besu.ethereum.rlp.RLPOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The peg-in client's file, as RLP.
 *
 * <p>The shape is
 * <pre>
 *   [ [ [wtxid, [ [blockHash, pmt], ... ]], ... ],
 *     [ [coinbase, witnessRoot, blockHash, pmt, readyToInform], ... ] ]
 * </pre>
 *
 * <p>Writes go to a temporary file that is then moved over the real one, so a process that dies
 * mid-write leaves the previous contents rather than half of the new ones. What is stored is
 * evidence gathered from blocks that may be far behind the peer's chain head by now, and losing it
 * means a peg-in waits for its transaction to be seen again.
 */
public class BtcToRskClientFileStorageImpl implements BtcToRskClientFileStorage {

    private static final Logger logger = LoggerFactory.getLogger(BtcToRskClientFileStorageImpl.class);

    private final FileStorageInfo storageInfo;

    public BtcToRskClientFileStorageImpl(FileStorageInfo storageInfo) {
        this.storageInfo = Objects.requireNonNull(storageInfo, "storageInfo");
    }

    @Override
    public FileStorageInfo getInfo() {
        return storageInfo;
    }

    @Override
    public void write(BtcToRskClientFileData data) throws IOException {
        if (data == null) {
            throw new IOException("There is no data to write");
        }
        Files.createDirectories(storageInfo.getPegDirectory());

        BytesValueRLPOutput out = new BytesValueRLPOutput();
        out.startList();
        writeProofs(out, data.getTransactionProofs());
        writeCoinbases(out, data.getCoinbaseInformationMap());
        out.endList();

        Path target = storageInfo.getFilePath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, out.encoded().toArrayUnsafe());
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public BtcToRskClientFileReadResult read(NetworkParameters networkParameters) throws IOException {
        Path file = storageInfo.getFilePath();
        if (!Files.exists(file)) {
            // A first start, not a loss.
            return BtcToRskClientFileReadResult.of(new BtcToRskClientFileData());
        }

        byte[] encoded = Files.readAllBytes(file);
        if (encoded.length == 0) {
            return BtcToRskClientFileReadResult.of(new BtcToRskClientFileData());
        }

        try {
            BtcToRskClientFileData data = new BtcToRskClientFileData();
            RLPInput in = RLP.input(Bytes.wrap(encoded));
            in.enterList();
            readProofs(in, networkParameters, data);
            readCoinbases(in, networkParameters, data);
            in.leaveList();
            return BtcToRskClientFileReadResult.of(data);
        } catch (RuntimeException e) {
            logger.error("[read] Could not parse {}: {}", file, e.getMessage(), e);
            return BtcToRskClientFileReadResult.unreadable();
        }
    }

    private static void writeProofs(RLPOutput out, Map<Sha256Hash, List<Proof>> proofs) {
        out.startList();
        for (Map.Entry<Sha256Hash, List<Proof>> entry : proofs.entrySet()) {
            out.startList();
            out.writeBytes(Bytes.wrap(entry.getKey().getBytes()));
            out.startList();
            for (Proof proof : entry.getValue()) {
                out.startList();
                out.writeBytes(Bytes.wrap(proof.getBlockHash().getBytes()));
                out.writeBytes(Bytes.wrap(proof.getPartialMerkleTree().bitcoinSerialize()));
                out.endList();
            }
            out.endList();
            out.endList();
        }
        out.endList();
    }

    private static void readProofs(RLPInput in, NetworkParameters params, BtcToRskClientFileData data) {
        in.enterList();
        while (!in.isEndOfCurrentList()) {
            in.enterList();
            Sha256Hash wtxid = Sha256Hash.wrap(in.readBytes().toArrayUnsafe());
            List<Proof> proofs = new ArrayList<>();
            in.enterList();
            while (!in.isEndOfCurrentList()) {
                in.enterList();
                Sha256Hash blockHash = Sha256Hash.wrap(in.readBytes().toArrayUnsafe());
                PartialMerkleTree pmt = new PartialMerkleTree(params, in.readBytes().toArrayUnsafe(), 0);
                in.leaveList();
                proofs.add(new Proof(blockHash, pmt));
            }
            in.leaveList();
            in.leaveList();
            data.getTransactionProofs().put(wtxid, proofs);
        }
        in.leaveList();
    }

    private static void writeCoinbases(RLPOutput out, Map<Sha256Hash, CoinbaseInformation> coinbases) {
        out.startList();
        for (CoinbaseInformation coinbase : coinbases.values()) {
            out.startList();
            out.writeBytes(Bytes.wrap(coinbase.getCoinbaseTransaction().bitcoinSerialize()));
            out.writeBytes(Bytes.wrap(coinbase.getWitnessRoot().getBytes()));
            out.writeBytes(Bytes.wrap(coinbase.getBlockHash().getBytes()));
            out.writeBytes(Bytes.wrap(coinbase.getPmt().bitcoinSerialize()));
            // Powpeg dropped this on the way to disk, so a restart made every coinbase look
            // uninformed again and re-sent it. It costs a byte to keep.
            out.writeIntScalar(coinbase.isReadyToInform() ? 1 : 0);
            out.endList();
        }
        out.endList();
    }

    private static void readCoinbases(RLPInput in, NetworkParameters params, BtcToRskClientFileData data) {
        in.enterList();
        while (!in.isEndOfCurrentList()) {
            in.enterList();
            Transaction coinbase = new Transaction(params, in.readBytes().toArrayUnsafe());
            Sha256Hash witnessRoot = Sha256Hash.wrap(in.readBytes().toArrayUnsafe());
            Sha256Hash blockHash = Sha256Hash.wrap(in.readBytes().toArrayUnsafe());
            PartialMerkleTree pmt = new PartialMerkleTree(params, in.readBytes().toArrayUnsafe(), 0);
            boolean readyToInform = in.readIntScalar() != 0;
            in.leaveList();
            data.getCoinbaseInformationMap()
                .put(blockHash, new CoinbaseInformation(coinbase, witnessRoot, blockHash, pmt, readyToInform));
        }
        in.leaveList();
    }
}
