package co.rsk.peg;

import co.rsk.peg.abi.AbiFunction;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;

import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

/** Ported from RSKj. The topics and data of an event are what AbiFunction encodes for it. */
public class BridgeEventsTestUtils {

    public static List<LogTopic> getEncodedTopics(AbiFunction bridgeEvent, Object... args) {
        return bridgeEvent.encodeEventTopics(args);
    }

    public static Bytes getEncodedData(AbiFunction bridgeEvent, Object... args) {
        return bridgeEvent.encodeEventData(args);
    }

    public static Optional<Log> getLogsTopics(List<Log> logs, List<LogTopic> expectedTopics) {
        return logs.stream()
            .filter(log -> log.getTopics().equals(expectedTopics))
            .findFirst();
    }

    public static Optional<Log> getLogsData(List<Log> logs, Bytes expectedData) {
        return logs.stream()
            .filter(log -> log.getData().equals(expectedData))
            .findFirst();
    }
}
