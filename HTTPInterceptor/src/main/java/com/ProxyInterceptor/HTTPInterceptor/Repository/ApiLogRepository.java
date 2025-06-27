package com.ProxyInterceptor.HTTPInterceptor.Repository;

import com.ProxyInterceptor.HTTPInterceptor.Model.ApiLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiLogRepository extends JpaRepository<ApiLog, Long>
{
    List<ApiLog> findByMethod(String method);
    List<ApiLog> findByEndpoint(String endpoint);
    List<ApiLog> findByStatusCode(Integer code);
    Optional<ApiLog> findByMethodAndEndpoint(String method, String endpoint);
}