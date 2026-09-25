package co.rsk.peg.pegininstructions;

import org.hyperledger.besu.datatypes.Address;
import java.util.Arrays;
import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class PeginInstructionsBase implements PeginInstructions {

    private static final Logger logger = LoggerFactory.getLogger(PeginInstructionsBase.class);
    private final int protocolVersion;
    protected Address rskDestinationAddress;

    protected PeginInstructionsBase(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    private Address getRskDestinationAddressFromData(byte[] data) {
        byte[] rskDestinationAddressBytes = Arrays.copyOfRange(data, 5, 25);
        return Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(rskDestinationAddressBytes));
    }

    protected abstract void validateDataLength(byte[] data) throws PeginInstructionsParseException;

    protected abstract void parseAdditionalData(byte[] data) throws PeginInstructionsParseException;

    public static int extractProtocolVersion(byte[] data) throws PeginInstructionsParseException {
        if (data == null || data.length < 5) {
            String message;

            if (data == null) {
                message = "Provided data is null";
            } else {
                message = String.format("Invalid data given. Expected at least 5 bytes, " +
                    "received %d", data.length);
            }

            logger.debug("[extractProtocolVersion] {}", message);
            throw new PeginInstructionsParseException(message);
        }

        byte[] protocolVersionBytes = Arrays.copyOfRange(data, 4, 5);
        return new BigInteger(1, protocolVersionBytes).intValue();
    }

    @Override
    public Address getRskDestinationAddress() {
        return this.rskDestinationAddress;
    }

    @Override
    public int getProtocolVersion() {
        return this.protocolVersion;
    }

    public void parse(byte[] data) throws PeginInstructionsParseException {
        validateDataLength(data);
        this.rskDestinationAddress = getRskDestinationAddressFromData(data);
        parseAdditionalData(data);
    }
}
