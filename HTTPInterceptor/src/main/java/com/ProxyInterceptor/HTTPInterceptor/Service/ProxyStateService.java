package com.ProxyInterceptor.HTTPInterceptor.Service;

import com.ProxyInterceptor.HTTPInterceptor.Elastic.ElasticLogService;
import com.ProxyInterceptor.HTTPInterceptor.Model.ApiLog;
import com.ProxyInterceptor.HTTPInterceptor.Model.RecordingState;
import com.ProxyInterceptor.HTTPInterceptor.Proxy.CachedBodyHttpServletRequest;
import com.ProxyInterceptor.HTTPInterceptor.Repository.ApiLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpField;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ProxyStateService {
    public static ProxyStateService INSTANCE;
    private boolean replayMode = false; //false implies db search and true implies vector search
    private RecordingState mode = RecordingState.OFF;
//    private Path outputFolder = Paths.get("recordings");
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Getter
    @Setter
    private List<String> captureFields = new ArrayList<>();

    @Autowired
    private ApiLogRepository apiLogRepository;
    // Store request-response pairs
    private final ConcurrentHashMap<String, ObjectNode> pendingRequests = new ConcurrentHashMap<>();

    @Autowired
    private final ElasticLogService elasticLogService;
    private final EmbeddingModel embeddingModel;


    public ProxyStateService(ElasticLogService elasticLogService, EmbeddingModel embeddingModel) {
        this.elasticLogService = elasticLogService;
        this.embeddingModel = embeddingModel;
        // Set the static instance when Spring creates this bean
        INSTANCE = this;
        // Ensure recordings directory exists
//        try {
//            Files.createDirectories(outputFolder);
//        } catch (Exception e) {
//            log.error("Failed to create recordings directory: {}", e.getMessage(), e);
//        }
        log.info("ProxyStateService initialized - INSTANCE set");
    }

    public synchronized RecordingState getMode() {
        return mode;
    }

    public synchronized void setMode(RecordingState mode) {
        this.mode = mode;
    }

//    public synchronized Path getOutputFolder() {
//        return outputFolder;
//    }

//    public synchronized void setOutputFolder(String folder) {
//        this.outputFolder = Paths.get(folder);
//        try {
//            Files.createDirectories(this.outputFolder);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to create folder: " + folder, e);
//        }
//    }

    public boolean isRecording() {
        return this.mode == RecordingState.RECORD;
    }

    public boolean isReplaying() {
        return this.mode == RecordingState.REPLAY;
    }

    public boolean shouldCaptureField(String fieldName) {
        return captureFields.contains(fieldName);
    }

    private void addFormattedBody(ObjectNode parentNode, String body, String contentType, String bodyFieldName) {
        if (body == null || body.trim().isEmpty()) {
            parentNode.put(bodyFieldName, NullNode.getInstance());
            return;
        }

        // Check if content type indicates JSON
        if (contentType != null && contentType.toLowerCase().contains("application/json")) {
            try {
                // Parse JSON and add as a proper JSON object/array
                JsonNode jsonNode = objectMapper.readTree(body);
                parentNode.set(bodyFieldName, jsonNode);
            } catch (JsonProcessingException e) {
                log.debug("Could not parse body as JSON, storing as string: {}", e.getMessage());
                parentNode.put(bodyFieldName, body);
            }
        } else {
            // For non-JSON content, store as string
            parentNode.put(bodyFieldName, body);
        }
    }


    public String recordRequestWithBody(CachedBodyHttpServletRequest req) {
        try {
            log.debug("recordRequestWithBody called for: {} {}", req.getMethod(), req.getRequestURL());

            String requestId = UUID.randomUUID().toString();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

            ObjectNode requestJson = objectMapper.createObjectNode();

            // Basic request info
            requestJson.put("timestamp", LocalDateTime.now().toString());
            requestJson.put("method", req.getMethod());
            requestJson.put("url", req.getRequestURL().toString());
            requestJson.put("queryString", req.getQueryString());
            requestJson.put("protocol", req.getProtocol());
            requestJson.put("remoteAddr", req.getRemoteAddr());
            requestJson.put("contentType", req.getContentType());
            requestJson.put("contentLength", req.getContentLength());

            // Headers
            ObjectNode headers = objectMapper.createObjectNode();
            Enumeration<String> headerNames = req.getHeaderNames();
            if (headerNames != null) {
                while (headerNames.hasMoreElements()) {
                    String headerName = headerNames.nextElement();
                    ArrayNode headerValues = objectMapper.createArrayNode();
                    Enumeration<String> values = req.getHeaders(headerName);
                    while (values.hasMoreElements()) {
                        headerValues.add(values.nextElement());
                    }
                    headers.set(headerName, headerValues);
                }
            }
            requestJson.set("headers", headers);

            // Parameters
            ObjectNode parameters = objectMapper.createObjectNode();
            Enumeration<String> paramNames = req.getParameterNames();
            if (paramNames != null) {
                while (paramNames.hasMoreElements()) {
                    String paramName = paramNames.nextElement();
                    String[] paramValues = req.getParameterValues(paramName);
                    if (paramValues.length == 1) {
                        parameters.put(paramName, paramValues[0]);
                    } else {
                        ArrayNode paramArray = objectMapper.createArrayNode();
                        for (String value : paramValues) {
                            paramArray.add(value);
                        }
                        parameters.set(paramName, paramArray);
                    }
                }
            }
            requestJson.set("parameters", parameters);

            // Request body - Format as proper JSON if it's JSON content
            String requestBody = req.getBody();
            addFormattedBody(requestJson, requestBody, req.getContentType(), "body");

            // Store the request data with requestId for later pairing with response
            pendingRequests.put(requestId, requestJson);

            log.debug("Request with body stored with ID: {}", requestId);
            return requestId;

        } catch (Exception e) {
            log.error("Error recording request with body: {}", e.getMessage(), e);
            return null;
        }
    }

    public void recordResponseWithBody(Response res, String requestId, String responseBody) {
        try {
            log.debug("recordResponseWithBody called for request ID: {}", requestId);

            ObjectNode requestJson = pendingRequests.remove(requestId);
            if (requestJson == null) {
                log.error("No pending request found for ID: {}", requestId);
                return;
            }

            // Create the complete transaction object
            ObjectNode transactionJson = objectMapper.createObjectNode();

            // Add request data
            transactionJson.set("request", requestJson);

            // Add response data
            ObjectNode responseJson = objectMapper.createObjectNode();
            responseJson.put("timestamp", LocalDateTime.now().toString());
            responseJson.put("status", res.getStatus());
            responseJson.put("reason", res.getReason());
            responseJson.put("version", res.getVersion().toString());

            // Response Headers
            ObjectNode responseHeaders = objectMapper.createObjectNode();
            String responseContentType = null;
            for (HttpField field : res.getHeaders()) {
                String headerName = field.getName();
                String headerValue = field.getValue();

                // Capture content type for response body formatting
                if ("Content-Type".equalsIgnoreCase(headerName)) {
                    responseContentType = headerValue;
                }

                if (responseHeaders.has(headerName)) {
                    // If header already exists, convert to array or add to existing array
                    if (responseHeaders.get(headerName).isArray()) {
                        ((ArrayNode) responseHeaders.get(headerName)).add(headerValue);
                    } else {
                        ArrayNode headerArray = objectMapper.createArrayNode();
                        headerArray.add(responseHeaders.get(headerName).asText());
                        headerArray.add(headerValue);
                        responseHeaders.set(headerName, headerArray);
                    }
                } else {
                    responseHeaders.put(headerName, headerValue);
                }
            }
            responseJson.set("headers", responseHeaders);

            // Response body - Format as proper JSON if it's JSON content
            addFormattedBody(responseJson, responseBody, responseContentType, "body");

            transactionJson.set("response", responseJson);

            // Save to file
//            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
//            String method = requestJson.get("method").asText();
//            String name = "transaction_" + method + "_" + timestamp + "_" + requestId.substring(0, 8) + ".json";
//            Path filePath = outputFolder.resolve(name);
//
//            try (FileWriter writer = new FileWriter(filePath.toFile(), StandardCharsets.UTF_8)) {
//                writer.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(transactionJson));
//            }
//
//            log.info("Saved transaction with bodies: {}", filePath);

            ApiLog apiLog = new ApiLog();
            apiLog.setMethod(requestJson.get("method").asText());
            apiLog.setEndpoint(requestJson.get("url").asText());
            apiLog.setStatusCode(res.getStatus());
            apiLog.setCreatedAt(Instant.now());

            apiLog.setRequestBody(requestJson.get("body"));
            apiLog.setResponseBody(responseJson.get("body"));
            apiLog.setParameters(requestJson.get("parameters"));
            apiLog.setHeaders(requestJson.get("headers"));
            apiLog.setResponseHeaders(responseJson.get("headers"));

            float[] vector = embeddingModel.createEmbedding(apiLog);
            apiLog.setEmbedding(vector);
            apiLogRepository.save(apiLog);
            log.info("Saved transaction to database with method: {}, endpoint: {}", apiLog.getMethod(), apiLog.getEndpoint());
            elasticLogService.saveToElastic(apiLog);
            log.info("DB query added to elastic.");

        } catch (Exception e) {
            log.error("Error recording response with body: {}", e.getMessage(), e);
        }
    }

    public void setReplayMode(String vector) {
        if(vector.equals("vector")) {
            this.replayMode = true;
        } else if(vector.equals("db")) {
            this.replayMode = false;
        }
    }

    public String getReplayMode() {
        if (replayMode) {
            return "vector";
        } else {
            return "db";
        }
    }
}