package com.ProxyInterceptor.HTTPInterceptor.Elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.IndexRequest;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.ProxyInterceptor.HTTPInterceptor.Model.ApiLog;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class ElasticLogService {
    private final ElasticsearchClient esClient;

    public ElasticLogService(ElasticsearchClient client) {
        this.esClient = client;
    }

    public void saveToElastic(ApiLog apilog) {
        try {
            esClient.index(IndexRequest.of(i -> i
                    .index("api-logs")  // choose your index name
                    .document(apilog)
            ));
            System.out.println("Indexed log in Elastic");
        } catch (Exception e) {
            System.out.println("Error occurred in elastic entry");
            e.printStackTrace();
        }
    }

    public List<ApiLog> searchFilteredByMethodEndpoint(String method, String endpoint, float[] queryVec, int topK) {
        try {
            // Convert float[] to List<Float>
            List<Float> qvList = new ArrayList<>(queryVec.length);
            for (float v : queryVec) {
                qvList.add(v);
            }
            JsonData qvJson = JsonData.of(qvList);

            // Define the similarity script
            Script script = Script.of(s -> s
                    .inline(i -> i
                            .lang("painless")
                            .source("cosineSimilarity(params.qv, 'embedding') + 1.0")
                            .params(Map.of("qv", qvJson))
                    )
            );

            // Build the query using .keyword fields for exact matches
            Query hybridQuery = Query.of(q -> q
                    .scriptScore(ss -> ss
                            .query(q2 -> q2.bool(b -> b
                                    .must(m1 -> m1.term(t -> t.field("method.keyword").value(method)))
                                    .must(m2 -> m2.term(t -> t.field("endpoint.keyword").value(endpoint)))
                            ))
                            .script(script)
                    )
            );

            // Compose the search request
            SearchRequest sr = SearchRequest.of(r -> r
                    .index("api-logs")
                    .query(hybridQuery)
                    .minScore(1.8) // cosine similarity >= 0.8
                    .size(topK)
            );

            // Execute and return results
            SearchResponse<ApiLog> response = esClient.search(sr, ApiLog.class);
            return response.hits().hits().stream()
                    .map(Hit::source)
                    .toList();

        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch hybrid search failed", e);
        }
    }


}

