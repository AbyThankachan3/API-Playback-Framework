package com.ProxyInterceptor.HTTPInterceptor.Proxy;

import com.ProxyInterceptor.HTTPInterceptor.Elastic.ElasticLogService;
import com.ProxyInterceptor.HTTPInterceptor.Model.ApiLog;
import com.ProxyInterceptor.HTTPInterceptor.Repository.ApiLogRepository;
import com.ProxyInterceptor.HTTPInterceptor.Service.EmbeddingModel;
import com.ProxyInterceptor.HTTPInterceptor.Service.ProxyStateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.*;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.ee10.proxy.ProxyServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class SimpleProxyServer {

    private static final Logger logger = LoggerFactory.getLogger(SimpleProxyServer.class);

    @Autowired
    private ApiLogRepository apiLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private ProxyStateService proxyStateService;

    private final EmbeddingModel embeddingModel;
    private final ElasticLogService elasticLogService;

    public SimpleProxyServer(EmbeddingModel embeddingModel, ElasticLogService elasticLogService) {
        this.embeddingModel = embeddingModel;
        this.elasticLogService = elasticLogService;
    }

    @PostConstruct
    public void start() {
        new Thread(() -> {
            try {
                // 1. Create Jetty server with HTTP connector
                Server server = new Server();
                ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());
                connector.setPort(8080);
                server.addConnector(connector);

                // 2. Set up ConnectHandler (for HTTPS tunneling)
                ConnectHandler proxy = new ConnectHandler();

                // 3. Servlet context for HTTP proxying via ProxyServlet
                ServletContextHandler context = new ServletContextHandler();
                context.setContextPath("/"); // to handle all traffic that goes through the proxy

                // Create servlet instance with dependencies
                BodyCapturingProxyServlet proxyServlet = new BodyCapturingProxyServlet();
                proxyServlet.setApiLogRepository(apiLogRepository);
                org.eclipse.jetty.ee10.servlet.ServletHolder servletHolder = new org.eclipse.jetty.ee10.servlet.ServletHolder(proxyServlet);
                context.addServlet(servletHolder, "/*");

                // 4. ConnectHandler wraps context
                proxy.setHandler(context);
                server.setHandler(proxy);

                server.start();
                logger.info("Jetty Forward Proxy started on http://localhost:8080");
                server.join();

            } catch (Exception e) {
                logger.error("Error starting proxy server", e);
            }
        }, "Advanced-Jetty-Forward-Proxy").start();
    }

    public class BodyCapturingProxyServlet extends ProxyServlet {

        private static final Logger servletLogger = LoggerFactory.getLogger(BodyCapturingProxyServlet.class);
        private HttpClient httpClient;
        private ApiLogRepository apiLogRepository;


        @Override
        public void init() throws ServletException {
            super.init();
            // Get access to the HTTP client for custom requests
            this.httpClient = getHttpClient();
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
            if (ProxyStateService.INSTANCE.isRecording()) {
                // Handle recording mode with custom logic
                handleRecordingMode(wrappedRequest, response);
            }
            else if(ProxyStateService.INSTANCE.isReplaying()){
                // Handle replay mode with custom logic
                handleReplayMode(wrappedRequest, response);
            }
            else {
                // Normal proxy mode
                super.service(request, response);
            }
        }

        private void handleReplayMode(CachedBodyHttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            try {
                // 1. Extract method and endpoint from request
                String method = request.getMethod();
                String endpoint = request.getRequestURL().toString();

                if (endpoint == null) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid target URL");
                    return;
                }

                servletLogger.info("Replaying request: {} {}", method, endpoint);

                if(proxyStateService.getReplayMode().equals("db") && proxyStateService.getCaptureFields().isEmpty()){
                    List<Long> ids = apiLogRepository.findIdsByMethodAndEndpoint(method, endpoint);

                    if (ids.isEmpty()) {
                        servletLogger.info("Routing request to backend as no method + endpoint match found.");
                        handleRecordingMode(request, response);
                        //response.sendError(HttpServletResponse.SC_NOT_FOUND, "No recorded response found for " + method + " " + endpoint);
                        return;
                    }
                    else if (ids.size() == 1) {
                        replay(apiLogRepository.findById(ids.get(0)), response);
                        return;
                    }
                    // Step 2: Query parameters
                    String queryParamsJson = convertQueryParamsToJson(request);
//                    servletLogger.debug("QueryParams: {}", queryParamsJson);
                    ids = apiLogRepository.filterIdsByQueryParams(ids, queryParamsJson);
                    if (ids.isEmpty()) {
                        //response.sendError(HttpServletResponse.SC_NOT_FOUND, "No match found after query parameter filtering");
                        servletLogger.info("No recorded response found at query params");
                        performVectorSearch(method, endpoint, extractPartialBodyAsJson(request), request, response);
                        return;
                    }
                    else if (ids.size() == 1) {
                        replay(apiLogRepository.findById(ids.get(0)), response);
                        return;
                    }
                        // Step 3: Headers
                    String headersJson = convertFilteredHeadersToJson(request);
                    ids = apiLogRepository.filterIdsByHeaders(ids, headersJson);

                    if (ids.isEmpty()) {
                        //response.sendError(HttpServletResponse.SC_NOT_FOUND, "No match found after header filtering");
                        servletLogger.info("No recorded response found at headers");
                        performVectorSearch(method, endpoint, extractPartialBodyAsJson(request), request, response);
                        return;
                    }
                    else if(ids.size() == 1) {
                        replay(apiLogRepository.findById(ids.get(0)), response);
                        return;
                    }
                    // Step 4: body
                    JsonNode bodyNode = extractPartialBodyAsJson(request);
                    String bodyJson = bodyNode != null ? bodyNode.toString(): null;
                    ids = apiLogRepository.filterIdsByExactBody(ids, bodyJson);
                    if (ids.isEmpty()) {
                        //response.sendError(HttpServletResponse.SC_NOT_FOUND, "No match found after body filtering");
                        servletLogger.info("No recorded response found at exact body");
                        performVectorSearch(method, endpoint, extractPartialBodyAsJson(request), request, response);
                    } else if (ids.size() == 1) {
                        replay(apiLogRepository.findById(ids.get(0)), response);
                    } else {
                        List<ApiLog> candidates = apiLogRepository.fetchFullLogsByIds(ids);
                        replay(Optional.of(candidates.get(0)), response);
                    }
                }
                else if(proxyStateService.getReplayMode().equals("db") && !proxyStateService.getCaptureFields().isEmpty()) {
                    servletLogger.info("Replaying request for user configured fields: {}", proxyStateService.getCaptureFields());
                    List<String> capturedFields = proxyStateService.getCaptureFields();
                    boolean match_method = capturedFields.contains("method");
                    boolean match_endpoint = capturedFields.contains("endpoint");
                    boolean match_headers = capturedFields.contains("headers");
                    boolean match_request_body = capturedFields.contains("body");
                    boolean match_query_params = capturedFields.contains("parameters");
                    boolean rscode2xx = capturedFields.contains("rscode2xx");
                    boolean rscode3xx = capturedFields.contains("rscode3xx");
                    boolean rscode4xx = capturedFields.contains("rscode4xx");
                    boolean rscode5xx = capturedFields.contains("rscode5xx");
                    String parameters = convertQueryParamsToJson(request);
                    String headers = convertFilteredHeadersToJson(request);
                    String body = extractPartialBodyAsJson(request).toString();
                    List<ApiLog> candidates = apiLogRepository.findMatchingLogs(match_method, method,
                                                                                match_endpoint, endpoint,
                                                                                match_query_params, parameters,
                                                                                match_headers, headers,
                                                                                match_request_body, body,
                                                                                rscode2xx, rscode3xx, rscode4xx, rscode5xx);
                    if (!candidates.isEmpty()){
                        replay(Optional.of(candidates.get(0)), response);
                    }else{
                        //Route to the backend
                        servletLogger.info("Routing the request to backend as no match found.");
                        handleRecordingMode(request, response);
                        //response.sendError(HttpServletResponse.SC_NOT_FOUND, "No match found after body filtering");
                        //servletLogger.info("No match found after filtering based on user specified config.");
                    }
                }
                else if(proxyStateService.getReplayMode().equals("vector")) {
                    performVectorSearch(method, endpoint, extractPartialBodyAsJson(request), request,response);
                }

            } catch (Exception e) {
                servletLogger.error("Error in replay mode: {}", e.getMessage(), e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Replay error");
            }
        }
        private void performVectorSearch(String method, String endpoint, JsonNode bodyJson, CachedBodyHttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            servletLogger.info("Performing vector search for: {} {}", method, endpoint);
            String embedd_string  = method+endpoint+bodyJson.toString();
            ApiLog querylog = new ApiLog();
            querylog.setMethod(method);
            querylog.setEndpoint(endpoint);
            querylog.setCreatedAt(Instant.now());
            querylog.setRequestBody(bodyJson);
//            apiLogRepository.save(querylog); //why ?

            float[] vector_query =embeddingModel.createEmbedding(querylog);
            List<ApiLog> semantic_similar_query =  elasticLogService.searchFilteredByMethodEndpoint(method,endpoint,vector_query,3);
            if (semantic_similar_query.isEmpty()) {
//                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No semantic match found");
                servletLogger.info("No semantic similar query found for: {} {}. Routing to backend.", method, endpoint);
                handleRecordingMode(request, response);
                return;
            }
            replay(Optional.of(semantic_similar_query.get(0)), response);
        }
        private void replay(Optional<ApiLog> maybeLog, HttpServletResponse response) throws IOException {
            if (maybeLog.isPresent()) {
                servletLogger.info("Replaying API Log: {}", maybeLog.get().getId());
                replayStoredResponse(maybeLog.get(), response);
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Matching log not found.");
            }
        }
        private String convertQueryParamsToJson(HttpServletRequest request) throws JsonProcessingException {
            Map<String, String[]> params = request.getParameterMap();
            Map<String, String> flatParams = new HashMap<>();
            for (Map.Entry<String, String[]> entry : params.entrySet()) {
                flatParams.put(entry.getKey(), entry.getValue()[0]);
            }
            return new ObjectMapper().writeValueAsString(flatParams);
        }
        private String convertFilteredHeadersToJson(HttpServletRequest request) throws JsonProcessingException {
            Set<String> ignoredHeaders = Set.of("User-Agent", "Cookie", "Authorization", "connection"
            , "keep-alive", "accept-encoding", "proxy-authenticate", "proxy-authorization",
                    "te", "trailers", "transfer-encoding", "upgrade", "Postman-Token", "Content-Length");
            Map<String, List<String>> headers = new HashMap<>();
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if (!ignoredHeaders.contains(name)) {
                    List<String> values = Collections.list(request.getHeaders(name));
                    headers.put(name, values);
                }
            }
            servletLogger.debug("Filtered headers: {}", new ObjectMapper().writeValueAsString(headers));
            return new ObjectMapper().writeValueAsString(headers);
        }
        private JsonNode extractPartialBodyAsJson(HttpServletRequest request) throws IOException {
            String body = request.getReader().lines()
                    .collect(Collectors.joining(System.lineSeparator()));
            if (body.isEmpty()) {
                return NullNode.getInstance();
            }
            return new ObjectMapper().readTree(body);
        }
        private void replayStoredResponse(ApiLog apiLog, HttpServletResponse response) throws IOException {
            try {
                // Set response status

                response.setStatus(apiLog.getStatusCode());

                // Set response headers from JsonNode
                if (apiLog.getResponseHeaders() != null) {
                    setResponseHeadersFromJson(response, apiLog.getResponseHeaders());
                }

                // Set default content type if not already set
                if (response.getContentType() == null) {
                    response.setContentType("application/json");
                }

                // Write response body from JsonNode
                if (apiLog.getResponseBody() != null) {
                    String responseBodyStr = objectMapper.writeValueAsString(apiLog.getResponseBody());
                    response.getWriter().write(responseBodyStr);
                } else {
                    response.getWriter().write("{}"); // Empty JSON response
                }

                response.getWriter().flush();
                servletLogger.info("Successfully replayed response for: {} {}", apiLog.getMethod(), apiLog.getEndpoint());

            } catch (IOException e) {
                servletLogger.error("Error writing replay response: {}", e.getMessage(), e);
                throw e;
            }
        }
        private void setResponseHeadersFromJson(HttpServletResponse response, JsonNode responseHeaders) {
            try {
                if (responseHeaders.isObject()) {
                    // Iterate through the JSON object fields
                    responseHeaders.fields().forEachRemaining(entry -> {
                        String headerName = entry.getKey();
                        JsonNode headerValue = entry.getValue();

                        // Skip hop-by-hop headers
                        if (!isHopByHopHeader(headerName)) {
                            if (headerValue.isArray()) {
                                // Handle multiple header values
                                headerValue.forEach(value -> {
                                    response.addHeader(headerName, value.asText());
                                });
                            } else {
                                // Single header value
                                response.addHeader(headerName, headerValue.asText());
                            }
                        }
                    });
                }
            } catch (Exception e) {
                servletLogger.error("Error setting response headers from JSON: {}", e.getMessage(), e);
            }
        }
        private void handleRecordingMode(CachedBodyHttpServletRequest wrappedRequest, HttpServletResponse response)
                throws ServletException, IOException {
            servletLogger.info("Handling request in recording mode: {} {}", wrappedRequest.getMethod(), wrappedRequest.getRequestURL());
            try {
                // 1. Capture request
                String requestId = ProxyStateService.INSTANCE.recordRequestWithBody(wrappedRequest);

                if (requestId == null) {
                    // If recording failed, fall back to normal proxy
                    super.service(wrappedRequest, response);
                    return;
                }

                // 2. Build target URL
                String targetUrl = buildTargetUrl(wrappedRequest);
                if (targetUrl == null) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid target URL");
                    return;
                }

                // 3. Create custom request to capture response body
                Request proxyRequest = httpClient.newRequest(targetUrl);

                // Copy method
                proxyRequest.method(wrappedRequest.getMethod());

                // Copy headers (skip host and connection headers)
                Enumeration<String> headerNames = wrappedRequest.getHeaderNames();
                while (headerNames.hasMoreElements()) {
                    String headerName = headerNames.nextElement();
                    if (!isHopByHopHeader(headerName)) {
                        Enumeration<String> headerValues = wrappedRequest.getHeaders(headerName);
                        while (headerValues.hasMoreElements()) {
                            proxyRequest.headers(headers -> headers.add(headerName, headerValues.nextElement()));
                        }
                    }
                }
//
                // Copy body if present
                byte[] bodyBytes = wrappedRequest.getBodyBytes();
                if (bodyBytes != null && bodyBytes.length > 0) {
                    proxyRequest.body(new BytesRequestContent(bodyBytes));
                }

                // 4. Send request and capture response
                CompletableFuture<String> responseBodyFuture = new CompletableFuture<>();

                proxyRequest.send(new BufferingResponseListener() {
                    @Override
                    public void onComplete(Result result) {
                        if (result.isSucceeded()) {
                            try {
                                Response proxyResponse = result.getResponse();
                                String responseBody = getContentAsString(StandardCharsets.UTF_8);

                                // Record the response with body
                                ProxyStateService.INSTANCE.recordResponseWithBody(proxyResponse, requestId, responseBody);

                                // Copy response to client
                                copyResponseToClient(proxyResponse, responseBody, response);
                                responseBodyFuture.complete(responseBody);

                            } catch (Exception e) {
                                servletLogger.error("Error processing response: {}", e.getMessage(), e);
                                responseBodyFuture.completeExceptionally(e);
                            }
                        } else {
                            servletLogger.error("Request failed: {}", result.getFailure().getMessage(), result.getFailure());
                            responseBodyFuture.completeExceptionally(result.getFailure());
                        }
                    }
                });

                // Wait for response (with timeout)
                try {
                    responseBodyFuture.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    servletLogger.error("Timeout or error waiting for response: {}", e.getMessage(), e);
                    response.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, "Proxy timeout");
                }

            } catch (Exception e) {
                servletLogger.error("Error in recording mode: {}", e.getMessage(), e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Proxy error");
            }
        }

        private String buildTargetUrl(HttpServletRequest request) {
            String targetUrl = request.getRequestURL().toString();
            String queryString = request.getQueryString();

            // For a forward proxy, we need to extract the actual target URL
            String requestUri = request.getRequestURI();
            String host = request.getHeader("Host");

            if (host != null && !host.isEmpty()) {
                String scheme = request.isSecure() ? "https" : "http";
                targetUrl = scheme + "://" + host + requestUri;
                if (queryString != null && !queryString.isEmpty()) {
                    targetUrl += "?" + queryString;
                }
            }

            return targetUrl;
        }

        private void copyResponseToClient(Response proxyResponse, String responseBody, HttpServletResponse clientResponse) {
            try {
                // Copy status
                clientResponse.setStatus(proxyResponse.getStatus());

                // Copy headers (skip hop-by-hop headers)
                proxyResponse.getHeaders().forEach(field -> {
                    if (!isHopByHopHeader(field.getName())) {
                        clientResponse.addHeader(field.getName(), field.getValue());
                    }
                });

                // Write body
                if (responseBody != null && !responseBody.isEmpty()) {
                    clientResponse.getWriter().write(responseBody);
                } else {
                    // If no body, still need to close the response
                    clientResponse.getWriter().flush();
                }

            } catch (IOException e) {
                servletLogger.error("Error copying response to client: {}", e.getMessage(), e);
            }
        }

        private boolean isHopByHopHeader(String headerName) {
            String name = headerName.toLowerCase();
            return name.equals("connection") ||
                    name.equals("keep-alive") ||
                    name.equals("proxy-authenticate") ||
                    name.equals("proxy-authorization") ||
                    name.equals("te") ||
                    name.equals("trailers") ||
                    name.equals("transfer-encoding") ||
                    name.equals("upgrade");
        }

        // Fallback methods for non-recording mode
        @Override
        protected void sendProxyRequest(HttpServletRequest clientRequest,
                                        HttpServletResponse proxyResponse,
                                        Request proxyRequest) {
            servletLogger.info("Proxying (non-recording): {} {}", clientRequest.getMethod(), clientRequest.getRequestURL());
            super.sendProxyRequest(clientRequest, proxyResponse, proxyRequest);
        }

        @Override
        protected void onProxyResponseSuccess(HttpServletRequest clientRequest,
                                              HttpServletResponse proxyResponse,
                                              Response serverResponse) {
            servletLogger.info("Response (non-recording): {}", serverResponse.getStatus());
            super.onProxyResponseSuccess(clientRequest, proxyResponse, serverResponse);
        }

        @Override
        protected void onProxyResponseFailure(HttpServletRequest clientRequest,
                                              HttpServletResponse proxyResponse,
                                              Response serverResponse,
                                              Throwable failure) {
            servletLogger.error("Proxy response failed: {}", failure.getMessage(), failure);
            super.onProxyResponseFailure(clientRequest, proxyResponse, serverResponse, failure);
        }

        public void setApiLogRepository(ApiLogRepository apiLogRepository) {
            this.apiLogRepository = apiLogRepository;
        }
    }
}