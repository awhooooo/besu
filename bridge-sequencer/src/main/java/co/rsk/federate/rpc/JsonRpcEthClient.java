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
package co.rsk.federate.rpc;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Talks to a Besu node over JSON-RPC.
 *
 * <p>Deliberately plain: the JDK's own HTTP client, one request at a time, no pooling of its own
 * beyond what that gives. The sequencer makes a handful of calls a minute, so there is nothing here
 * worth making complicated, and a small amount of code is easier to be sure about than a framework.
 *
 * <p>Every failure arrives as {@link RpcException}, including the node's own errors, so that a caller
 * never has to tell a transport problem from a refusal by inspecting a return value.
 */
public class JsonRpcEthClient implements EthClient {

    private static final Logger logger = LoggerFactory.getLogger(JsonRpcEthClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI endpoint;
    private final HttpClient http;
    private final Duration timeout;
    private final AtomicLong nextRequestId = new AtomicLong(1);

    public JsonRpcEthClient(URI endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Bytes call(Address to, Bytes callData) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("to", to.toHexString());
        params.put("data", callData.toHexString());
        return Bytes.fromHexString(request("eth_call", params, "latest").asText());
    }

    @Override
    public Bytes32Hash sendRawTransaction(Bytes signedTransaction) {
        return Bytes32Hash.fromHexString(
            request("eth_sendRawTransaction", signedTransaction.toHexString()).asText());
    }

    @Override
    public long blockNumber() {
        return quantity(request("eth_blockNumber"));
    }

    @Override
    public long pendingNonce(Address address) {
        // "pending" rather than "latest": anything this sequencer already sent and the node has not
        // yet mined still consumed a nonce, and reusing it would replace the earlier transaction.
        return quantity(request("eth_getTransactionCount", address.toHexString(), "pending"));
    }

    @Override
    public boolean syncing() {
        JsonNode result = request("eth_syncing");
        // false when caught up; an object describing the gap when not.
        return !result.isBoolean() || result.asBoolean();
    }

    @Override
    public Optional<TransactionReceipt> receipt(Bytes32Hash transactionHash) {
        JsonNode result = request("eth_getTransactionReceipt", transactionHash.toString());
        if (result == null || result.isNull()) {
            return Optional.empty();
        }
        return Optional.of(
            new TransactionReceipt(
                transactionHash,
                quantity(result.get("blockNumber")),
                "0x1".equals(result.get("status").asText())));
    }

    // ------------------------------------------------------------------ plumbing

    private JsonNode request(String method, Object... params) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", nextRequestId.getAndIncrement());
        body.put("method", method);
        ArrayNode paramsNode = body.putArray("params");
        for (Object param : params) {
            if (param instanceof JsonNode node) {
                paramsNode.add(node);
            } else {
                paramsNode.add(String.valueOf(param));
            }
        }

        final String payload;
        try {
            payload = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new RpcException("Could not encode a " + method + " request", e);
        }

        logger.trace("[request] {} -> {}", method, payload);

        final HttpResponse<String> response;
        try {
            response =
                http.send(
                    HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("Interrupted while calling " + method, e);
        } catch (IOException e) {
            throw new RpcException("Could not reach the node at " + endpoint + " for " + method, e);
        }

        if (response.statusCode() != 200) {
            throw new RpcException(
                String.format("The node answered %s with HTTP %d", method, response.statusCode()));
        }

        final JsonNode parsed;
        try {
            parsed = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new RpcException("The node's answer to " + method + " was not JSON", e);
        }

        JsonNode error = parsed.get("error");
        if (error != null && !error.isNull()) {
            JsonNode code = error.get("code");
            String message = error.hasNonNull("message") ? error.get("message").asText() : error.toString();
            throw code == null
                ? new RpcException(method + ": " + message)
                : new RpcException(code.asInt(), method + ": " + message);
        }

        if (!parsed.has("result")) {
            throw new RpcException("The node's answer to " + method + " had no result");
        }
        return parsed.get("result");
    }

    private static long quantity(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new RpcException("Expected a quantity and got nothing");
        }
        String hex = node.asText();
        try {
            return new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new RpcException("Not a quantity this can hold: " + hex, e);
        }
    }
}
