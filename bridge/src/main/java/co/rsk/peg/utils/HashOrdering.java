package co.rsk.peg.utils;

import org.hyperledger.besu.datatypes.Hash;

import java.util.Comparator;

/**
 * The ordering RSKj's Keccak256 imposed on sorted maps of transaction hashes: unsigned comparison
 * starting from the last byte. It is consensus visible, since serialized maps and federator-facing
 * state follow it, so it is kept verbatim.
 */
public final class HashOrdering {

    public static final Comparator<Hash> RSK = HashOrdering::compare;

    private HashOrdering() {
    }

    private static int compare(Hash a, Hash b) {
        byte[] x = a.getBytes().toArrayUnsafe();
        byte[] y = b.getBytes().toArrayUnsafe();
        for (int i = x.length - 1; i >= 0; i--) {
            int xb = x[i] & 0xff;
            int yb = y[i] & 0xff;
            if (xb > yb) {
                return 1;
            }
            if (xb < yb) {
                return -1;
            }
        }
        return 0;
    }
}
