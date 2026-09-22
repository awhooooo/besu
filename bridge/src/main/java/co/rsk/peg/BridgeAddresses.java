package co.rsk.peg;

import org.hyperledger.besu.datatypes.Address;

/** Fixed addresses the bridge protocol refers to. */
public final class BridgeAddresses {

    /** The bridge precompile. Same address as on RSK. */
    public static final Address BRIDGE = Address.fromHexString("0x0000000000000000000000000000000001000006");

    private BridgeAddresses() {
    }
}
