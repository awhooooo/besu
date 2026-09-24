package co.rsk.peg.abi;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.LogTopic;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.web3j.abi.DefaultFunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.AbiTypes;
import org.web3j.abi.datatypes.Array;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.BytesType;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.NumericType;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Int256;
import org.web3j.abi.datatypes.generated.Int64;
import org.web3j.abi.datatypes.generated.Uint256;

/**
 * A bridge function or event: its selector or topic, and the encoding and decoding of its arguments, results,
 * topics and data, on web3j's ABI codec.
 *
 * <p>It keeps the calling convention the bridge inherited from RSKj: values go in as plain Java values (numbers,
 * hex or decimal strings for integers, hex strings or {@link Address} for addresses, byte arrays, strings,
 * booleans and arrays of those), and decoded values come back as {@link BigInteger}, {@code byte[]},
 * {@link String}, {@link Boolean}, {@link Address} or {@code Object[]}, which is what the bridge casts them to.
 * Only the types the bridge declares are supported; anything else is refused rather than guessed.
 */
public final class AbiFunction {

    /** A declared argument, result or event field. */
    public record Param(boolean indexed, String name, String type) {
    }

    private static final int SELECTOR_SIZE = 4;
    private static final DefaultFunctionEncoder ENCODER = new DefaultFunctionEncoder();

    private final String name;
    private final Param[] inputs;
    private final Param[] outputs;
    private final boolean event;

    private AbiFunction(String name, Param[] inputs, Param[] outputs, boolean event) {
        this.name = name;
        this.inputs = inputs;
        this.outputs = outputs;
        this.event = event;
    }

    public static AbiFunction fromSignature(String name, String[] inputTypes, String[] outputTypes) {
        return new AbiFunction(name, params(inputTypes), params(outputTypes), false);
    }

    public static AbiFunction fromEventSignature(String name, Param[] params) {
        return new AbiFunction(name, params.clone(), new Param[0], true);
    }

    private static Param[] params(String[] types) {
        Param[] params = new Param[types.length];
        for (int i = 0; i < types.length; i++) {
            params[i] = new Param(false, "", types[i]);
        }
        return params;
    }

    public String getName() {
        return name;
    }

    public Param[] getInputs() {
        return inputs.clone();
    }

    public Param[] getOutputs() {
        return outputs.clone();
    }

    /** The canonical signature, {@code name(type,...)} over the inputs. */
    public String formatSignature() {
        StringBuilder types = new StringBuilder();
        for (Param input : inputs) {
            if (types.length() > 0) {
                types.append(',');
            }
            types.append(canonical(input.type()));
        }
        return name + "(" + types + ")";
    }

    /** keccak256 of the canonical signature: the event topic. */
    public Hash encodeSignatureLong() {
        return Hash.hash(Bytes.wrap(formatSignature().getBytes(StandardCharsets.UTF_8)));
    }

    /** The first four bytes of {@link #encodeSignatureLong()}: the function selector. */
    public Bytes encodeSignature() {
        return encodeSignatureLong().getBytes().slice(0, SELECTOR_SIZE);
    }

    /** The call data: selector followed by the encoded arguments. */
    public Bytes encode(Object... args) {
        requireFunction();
        return Bytes.concatenate(encodeSignature(), encodeTuple(inputs, args));
    }

    /** The arguments of call data that starts with this function's selector. */
    public Object[] decode(Bytes data) {
        requireFunction();
        if (data.size() < SELECTOR_SIZE) {
            throw new IllegalArgumentException("Call data shorter than a selector: " + data.size() + " bytes");
        }
        return decodeTuple(inputs, data.slice(SELECTOR_SIZE));
    }

    public Bytes encodeOutputs(Object... results) {
        requireFunction();
        return encodeTuple(outputs, results);
    }

    public Object[] decodeResult(Bytes data) {
        requireFunction();
        return decodeTuple(outputs, data);
    }

    /** The event topics: the signature hash, then one topic per indexed field in declaration order. */
    public List<LogTopic> encodeEventTopics(Object... args) {
        requireEvent();
        Param[] indexed = Arrays.stream(inputs).filter(Param::indexed).toArray(Param[]::new);
        requireCount(indexed, args);
        List<LogTopic> topics = new ArrayList<>(indexed.length + 1);
        topics.add(LogTopic.wrap(Bytes32.wrap(encodeSignatureLong().getBytes())));
        for (int i = 0; i < indexed.length; i++) {
            String type = canonical(indexed[i].type());
            Bytes encoded = Bytes.fromHexString(TypeEncoder.encode(toType(type, args[i])));
            // A dynamic value is indexed by its hash
            Bytes32 topic = Bytes32.wrap(isDynamic(type) ? Hash.hash(encoded).getBytes() : encoded);
            topics.add(LogTopic.wrap(topic));
        }
        return topics;
    }

    /** The event data: the non-indexed fields encoded as a tuple, in declaration order. */
    public Bytes encodeEventData(Object... args) {
        requireEvent();
        Param[] data = Arrays.stream(inputs).filter(param -> !param.indexed()).toArray(Param[]::new);
        return encodeTuple(data, args);
    }

    @Override
    public String toString() {
        return formatSignature();
    }

    private void requireFunction() {
        if (event) {
            throw new IllegalStateException(name + " is an event");
        }
    }

    private void requireEvent() {
        if (!event) {
            throw new IllegalStateException(name + " is not an event");
        }
    }

    private static void requireCount(Param[] params, Object[] args) {
        if (args.length != params.length) {
            throw new IllegalArgumentException(
                "Expected " + params.length + " value(s), got " + args.length + " for " + Arrays.toString(params));
        }
    }

    @SuppressWarnings("rawtypes")
    private static Bytes encodeTuple(Param[] params, Object[] args) {
        requireCount(params, args);
        List<Type> types = new ArrayList<>(params.length);
        for (int i = 0; i < params.length; i++) {
            types.add(toType(canonical(params[i].type()), args[i]));
        }
        return Bytes.fromHexString(ENCODER.encodeParameters(types));
    }

    @SuppressWarnings("rawtypes")
    private static Object[] decodeTuple(Param[] params, Bytes data) {
        List<TypeReference<Type>> references = new ArrayList<>(params.length);
        for (Param param : params) {
            references.add(reference(canonical(param.type())));
        }
        List<Type> decoded = FunctionReturnDecoder.decode(data.toHexString(), references);
        if (decoded.size() != params.length) {
            throw new IllegalArgumentException(
                "Expected " + params.length + " value(s) in " + data.size() + " bytes, decoded " + decoded.size());
        }
        Object[] values = new Object[decoded.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = toJava(decoded.get(i));
        }
        return values;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeReference<Type> reference(String canonicalType) {
        try {
            return TypeReference.makeTypeReference(canonicalType);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unsupported ABI type " + canonicalType, e);
        }
    }

    /** The name a signature uses: {@code int} is {@code int256} and {@code uint} is {@code uint256}, in arrays too. */
    private static String canonical(String type) {
        int bracket = type.indexOf('[');
        String base = bracket < 0 ? type : type.substring(0, bracket);
        String suffix = bracket < 0 ? "" : type.substring(bracket);
        if (base.equals("int")) {
            base = "int256";
        } else if (base.equals("uint")) {
            base = "uint256";
        }
        return base + suffix;
    }

    private static boolean isDynamic(String canonicalType) {
        return canonicalType.equals("bytes")
            || canonicalType.equals("string")
            || canonicalType.endsWith("]");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Type toType(String canonicalType, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("No value for " + canonicalType);
        }
        if (canonicalType.endsWith("[]")) {
            String elementType = canonicalType.substring(0, canonicalType.length() - 2);
            List<Type> elements = new ArrayList<>();
            for (Object element : toArray(value)) {
                elements.add(toType(elementType, element));
            }
            Class elementClass = AbiTypes.getType(elementType);
            return new DynamicArray(elementClass, elements);
        }
        switch (canonicalType) {
            case "int256":
                return new Int256(toBigInteger(value));
            case "int64":
                return new Int64(toBigInteger(value));
            case "uint256":
                return new Uint256(toBigInteger(value));
            case "bool":
                return new Bool(toBoolean(value));
            case "string":
                return new Utf8String(toText(value));
            case "bytes":
                return new DynamicBytes(toBytes(value));
            case "bytes32":
                byte[] bytes = toBytes(value);
                if (bytes.length != 32) {
                    throw new IllegalArgumentException("A bytes32 value must be 32 bytes long, got " + bytes.length);
                }
                return new org.web3j.abi.datatypes.generated.Bytes32(bytes);
            case "address":
                return new org.web3j.abi.datatypes.Address(toAddressHex(value));
            default:
                throw new IllegalArgumentException("Unsupported ABI type " + canonicalType);
        }
    }

    private static Object toJava(Type<?> decoded) {
        if (decoded instanceof NumericType numeric) {
            return numeric.getValue();
        }
        if (decoded instanceof Bool bool) {
            return bool.getValue();
        }
        if (decoded instanceof Utf8String string) {
            return string.getValue();
        }
        if (decoded instanceof BytesType bytes) {
            return bytes.getValue();
        }
        if (decoded instanceof org.web3j.abi.datatypes.Address address) {
            return Address.fromHexString(address.getValue());
        }
        if (decoded instanceof Array<?> array) {
            return array.getValue().stream().map(AbiFunction::toJava).toArray();
        }
        throw new IllegalArgumentException("Unsupported decoded type " + decoded.getTypeAsString());
    }

    private static Object[] toArray(Object value) {
        if (value instanceof List<?> list) {
            return list.toArray();
        }
        if (value instanceof Object[] array) {
            return array;
        }
        throw new IllegalArgumentException("Expected an array, got " + value.getClass().getName());
    }

    /** RSKj's reading of integer arguments: a number, or a string in hex (with 0x or with hex digits) or decimal. */
    private static BigInteger toBigInteger(Object value) {
        if (value instanceof BigInteger big) {
            return big;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof String text) {
            String s = text.toLowerCase(Locale.ROOT).trim();
            if (s.startsWith("0x")) {
                return new BigInteger(s.substring(2), 16);
            }
            boolean hex = s.chars().anyMatch(c -> c >= 'a' && c <= 'f');
            return new BigInteger(s, hex ? 16 : 10);
        }
        throw new IllegalArgumentException("Expected an integer, got " + value.getClass().getName());
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException("Expected a boolean, got " + value.getClass().getName());
    }

    private static String toText(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("Expected a string, got " + value.getClass().getName());
    }

    private static byte[] toBytes(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof Bytes bytes) {
            return bytes.toArrayUnsafe();
        }
        throw new IllegalArgumentException("Expected bytes, got " + value.getClass().getName());
    }

    private static String toAddressHex(Object value) {
        if (value instanceof Address address) {
            return address.toHexString();
        }
        if (value instanceof String text) {
            return text.startsWith("0x") ? text : "0x" + text;
        }
        if (value instanceof byte[] bytes) {
            return Bytes.wrap(bytes).toHexString();
        }
        throw new IllegalArgumentException("Expected an address, got " + value.getClass().getName());
    }
}
