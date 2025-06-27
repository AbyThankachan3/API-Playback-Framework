package com.ProxyInterceptor.HTTPInterceptor.Proxy;

import com.ProxyInterceptor.HTTPInterceptor.Model.ApiLog;
import com.ProxyInterceptor.HTTPInterceptor.Repository.ApiLogRepository;
import com.ProxyInterceptor.HTTPInterceptor.Service.ProxyStateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class SimpleProxyServer {

    @Autowired
    private ApiLogRepository apiLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                context.setContextPath("/");

                // Create servlet instance with dependencies
                BodyCapturingProxyServlet proxyServlet = new BodyCapturingProxyServlet();
                proxyServlet.setApiLogRepository(apiLogRepository);
                org.eclipse.jetty.ee10.servlet.ServletHolder servletHolder = new org.eclipse.jetty.ee10.servlet.ServletHolder(proxyServlet);
                context.addServlet(servletHolder, "/*");

                // 4. ConnectHandler wraps context
                proxy.setHandler(context);
                server.setHandler(proxy);

                server.start();
                System.out.println("Jetty Forward Proxy started on http://localhost:8080");
                server.join();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Advanced-Jetty-Forward-Proxy").start();
    }

    public class BodyCapturingProxyServlet extends ProxyServlet {

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

            if (ProxyStateService.INSTANCE.isRecording()) {
                // Handle recording mode with custom logic
                handleRecordingMode(request, response);
            }
            else if(ProxyStateService.INSTANCE.isReplaying()){
                // Handle replay mode with custom logic
                handleReplayMode(request, response);
            }
            else {
                // Normal proxy mode
                super.service(request, response);
            }
        }

        private void handleReplayMode(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            try {
                // 1. Extract method and endpoint from request
                String method = request.getMethod();
                String endpoint = buildTargetUrl(request);

                if (endpoint == null) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid target URL");
                    return;
                }

                System.out.println("Replaying request: " + method + " " + endpoint);

                // 2. Search for matching API log in database
                Optional<ApiLog> apiLogOpt = apiLogRepository.findByMethodAndEndpoint(method, endpoint);

                if (apiLogOpt.isPresent()) {
                    ApiLog apiLog = apiLogOpt.get();
                    System.out.println("Found matching API log for replay: " + apiLog.getId());

                    // 3. Replay the stored response
                    replayStoredResponse(apiLog, response);
                } else {
                    System.out.println("No matching API log found for: " + method + " " + endpoint);
                    // Return 404 or fall back to normal proxy mode
                    response.sendError(HttpServletResponse.SC_NOT_FOUND,
                            "No recorded response found for " + method + " " + endpoint);
                }

            } catch (Exception e) {
                System.err.println("Error in replay mode: " + e.getMessage());
                e.printStackTrace();
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Replay error");
            }
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
                System.out.println("Successfully replayed response for: " + apiLog.getMethod() + " " + apiLog.getEndpoint());

            } catch (IOException e) {
                System.err.println("Error writing replay response: " + e.getMessage());
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
                System.err.println("Error setting response headers from JSON: " + e.getMessage());
            }
        }
        private void handleRecordingMode(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {

            try {
                // 1. Capture request
                CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
                String requestId = ProxyStateService.INSTANCE.recordRequestWithBody(wrappedRequest);

                if (requestId == null) {
                    // If recording failed, fall back to normal proxy
                    super.service(request, response);
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
                                System.err.println("Error processing response: " + e.getMessage());
                                responseBodyFuture.completeExceptionally(e);
                            }
                        } else {
                            System.err.println("Request failed: " + result.getFailure().getMessage());
                            responseBodyFuture.completeExceptionally(result.getFailure());
                        }
                    }
                });

                // Wait for response (with timeout)
                try {
                    responseBodyFuture.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    System.err.println("Timeout or error waiting for response: " + e.getMessage());
                    response.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, "Proxy timeout");
                }

            } catch (Exception e) {
                System.err.println("Error in recording mode: " + e.getMessage());
                e.printStackTrace();
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
                System.err.println("Error copying response to client: " + e.getMessage());
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
            System.out.println("Proxying (non-recording): " + clientRequest.getMethod() + " " + clientRequest.getRequestURL());
            super.sendProxyRequest(clientRequest, proxyResponse, proxyRequest);
        }

        @Override
        protected void onProxyResponseSuccess(HttpServletRequest clientRequest,
                                              HttpServletResponse proxyResponse,
                                              Response serverResponse) {
            System.out.println("Response (non-recording): " + serverResponse.getStatus());
            super.onProxyResponseSuccess(clientRequest, proxyResponse, serverResponse);
        }

        @Override
        protected void onProxyResponseFailure(HttpServletRequest clientRequest,
                                              HttpServletResponse proxyResponse,
                                              Response serverResponse,
                                              Throwable failure) {
            System.out.println("Proxy response failed: " + failure.getMessage());
            super.onProxyResponseFailure(clientRequest, proxyResponse, serverResponse, failure);
        }

        public void setApiLogRepository(ApiLogRepository apiLogRepository) {
            this.apiLogRepository = apiLogRepository;
        }
    }
}