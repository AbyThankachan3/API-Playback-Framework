package com.ProxyInterceptor.HTTPInterceptor.Repository;

import com.ProxyInterceptor.HTTPInterceptor.Model.ApiLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiLogRepository extends JpaRepository<ApiLog, Long>
{
    List<ApiLog> findByMethod(String method);
    List<ApiLog> findByEndpoint(String endpoint);
    List<ApiLog> findByStatusCode(Integer code);
    List<ApiLog> findByMethodAndEndpoint(String method, String endpoint);

    @Query(
            value = "SELECT a.id FROM api_log a WHERE a.method = :method AND a.endpoint = :endpoint",
            nativeQuery = true
    )
    List<Long> findIdsByMethodAndEndpoint(@Param("method") String method, @Param("endpoint") String endpoint);

    Optional<ApiLog> findById(Long id);
    @Query(
            value = "SELECT a.id FROM api_log a WHERE a.id IN :ids AND a.parameters @> CAST(:queryParams AS jsonb)",
            nativeQuery = true
    )
    List<Long> filterIdsByQueryParams(@Param("ids") List<Long> ids, @Param("queryParams") String queryParamsJson);

    @Query(
            value = "SELECT a.id FROM api_log a WHERE a.id IN :ids AND a.headers @> CAST(:headers AS jsonb)",
            nativeQuery = true
    )
    List<Long> filterIdsByHeaders(@Param("ids") List<Long> ids, @Param("headers") String headersJson);

    @Query(
            value = "SELECT id FROM api_log WHERE id IN (:ids) AND request_body = CAST(:body AS jsonb)",
            nativeQuery = true
    )
    List<Long> filterIdsByExactBody(@Param("ids") List<Long> ids, @Param("body") String bodyJson);

    @Query(
            value = "SELECT * FROM api_log WHERE id IN :ids ORDER BY created_at DESC",
            nativeQuery = true
    )
    List<ApiLog> fetchFullLogsByIds(@Param("ids") List<Long> ids);

    @Query(
            value = "Select * from api_log where (:match_method IS FALSE OR method = :method) AND (:match_endpoint is FALSE OR endpoint = :endpoint) AND (:match_headers IS FALSE OR headers @> CAST(:headers AS jsonb)) and (:match_request_body IS FALSE OR request_body = CAST(:request_body AS jsonb))  ORDER BY created_at DESC",
            nativeQuery = true
    )
    List<ApiLog> findMatchingLogs(@Param("match_method") boolean matchMethod,
                                  @Param("method") String method,
                                  @Param("match_endpoint") boolean matchEndpoint,
                                  @Param("endpoint") String endpoint,
                                  @Param("match_headers") boolean matchHeaders,
                                  @Param("headers") String headersJson,
                                  @Param("match_request_body") boolean matchRequestBody,
                                  @Param("request_body") String requestBodyJson);

    @Query(
            value = "SELECT id FROM api_log  WHERE id IN :ids AND status_code = :status",
            nativeQuery = true
    )
    List<Long> filterIdsByStatusCode(@Param("ids") List<Long> ids, @Param("status") int statusCode);
}