package com.ledgermind.ledger.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Raw HTTP for API tests: {@code URI.create} keeps percent-encoding as sent; the key header is optional. */
final class ApiTestHttp {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;

    ApiTestHttp(int port) {
        this.port = port;
    }

    HttpResponse<String> send(String method, String rawPath, String apiKey, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath));
        if (apiKey != null) {
            b.header("X-API-Key", apiKey);
        }
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }
}
