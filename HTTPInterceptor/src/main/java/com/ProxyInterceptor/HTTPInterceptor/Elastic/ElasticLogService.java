package com.ProxyInterceptor.HTTPInterceptor.Elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;

import com.ProxyInterceptor.HTTPInterceptor.Model.ApiLog;
import org.springframework.stereotype.Service;

@Service
public class ElasticLogService
{
    private final ElasticsearchClient esClient ;

    public ElasticLogService(ElasticsearchClient client)
    {
        this.esClient = client;
    }

    public void saveToElastic(ApiLog apilog)
    {
        try
        {
            esClient.index(IndexRequest.of(i -> i
                    .index("api-logs")  // choose your index name
                    .document(apilog)
            ));
            System.out.println("Indexed log in Elastic");
        }
        catch (Exception e)
        {
            System.out.println("Error occurred in elastic entry");
            e.printStackTrace();
        }
    }



}
