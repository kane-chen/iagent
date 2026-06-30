package io.invest.iagent.service.kb.embedding;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MathEmbeddingService implements EmbeddingService {

    private final int dimension;

    public MathEmbeddingService() {
        this(128);
    }

    public MathEmbeddingService(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public List<Float> embed(String text) {
        float[] vector = new float[dimension];
        if(StringUtils.isBlank(text)){
            return normalize(vector);
        }
        for(String token : text.toLowerCase().split("\\s+")){
            int hash = Math.abs(token.hashCode());
            vector[hash % dimension] += 1.0f;
            for(byte b : token.getBytes(StandardCharsets.UTF_8)){
                vector[Math.abs((hash * 31 + b) % dimension)] += 0.1f;
            }
        }
        return normalize(vector);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String model() {
        return "deterministic-hash-" + dimension;
    }

    private List<Float> normalize(float[] vector){
        double norm = 0.0;
        for(float value : vector){
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        List<Float> result = new ArrayList<>(dimension);
        for(float value : vector){
            result.add(norm == 0 ? 0.0f : (float)(value / norm));
        }
        return result;
    }
}
