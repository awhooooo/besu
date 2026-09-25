package co.rsk.peg.pegininstructions;

import org.hyperledger.besu.datatypes.Address;

public interface PeginInstructions {

    Address getRskDestinationAddress();

    int getProtocolVersion();
}
