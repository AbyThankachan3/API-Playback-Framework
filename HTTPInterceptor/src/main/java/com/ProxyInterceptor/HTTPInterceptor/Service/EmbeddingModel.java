package com.ProxyInterceptor.HTTPInterceptor.Service;


import com.ProxyInterceptor.HTTPInterceptor.Model.ApiLog;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.vertexai.VertexAiEmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingModel
{
    private static final String PROJECT_ID = "request-matching";
    private static final String MODEL_NAME = "text-embedding-004";
    private final VertexAiEmbeddingModel embeddingModel;

    public EmbeddingModel()
    {
         this.embeddingModel = VertexAiEmbeddingModel.builder()
                .project(PROJECT_ID)
                .location("asia-south1")
                .endpoint("asia-south1-aiplatform.googleapis.com:443")
                .publisher("google")
                .modelName(MODEL_NAME)
                .build();
    }

    public float[] createEmbedding(ApiLog apiLog)
    {

        String text_body  = apiLog.getMethod()+ apiLog.getEndpoint()+apiLog.getRequestBody();
        Response<Embedding> response = embeddingModel.embed(text_body);

        Embedding embedding = response.content();

        int dimension = embedding.dimension();
        float[] vector = embedding.vector();

        System.out.println(dimension);
        System.out.println(embedding.vectorAsList());
        return vector;


    }

}
