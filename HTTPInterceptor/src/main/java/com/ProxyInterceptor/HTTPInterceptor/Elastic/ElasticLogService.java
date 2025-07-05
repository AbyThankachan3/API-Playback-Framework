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

    public List<ApiLog> searchFilteredByMethodEndpoint(String method, String endpoint, float[] queryVec, int topK)
    {
        try
        {
            // 1) Box float[] into List<Float>
            List<Float> qvList = new ArrayList<>(queryVec.length);
            for (float v : queryVec)
            {
                qvList.add(v);
            }

            // 2) Wrap the list in JsonData
            JsonData qvJson = JsonData.of(qvList);

            // 3) Build an InlineScript containing your Painless code
            Script simScript = Script.of(s -> s
                    .inline(i -> i
                            .source("cosineSimilarity(params.qv, 'embedding') + 1.0")
                            .lang("painless")
                            .params(Map.of("qv", qvJson))
                    )
            );

            // 4) Compose the hybrid script_score query
            Query hybrid = Query.of(q -> q
                    .scriptScore(ss -> ss
                            // exact-match filter
                            .query(inner -> inner
                                    .bool(BoolQuery.of(b -> b
                                            .must(m -> m.term(t -> t.field("method").value(method)))
                                            .must(m -> m.term(t -> t.field("endpoint").value(endpoint)))
                                    ))
                            )
                            // attach our InlineScript
                            .script(simScript)
                    )
            );

            // 5) Execute it
            SearchRequest sr = SearchRequest.of(r -> r
                    .index("api-logs")
                    .size(topK)
            );
            SearchResponse<ApiLog> resp = esClient.search(sr, ApiLog.class);

            return resp.hits().hits().stream()
                    .map(Hit::source)
                    .toList();

        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch hybrid search failed", e);
        }
    }
}

