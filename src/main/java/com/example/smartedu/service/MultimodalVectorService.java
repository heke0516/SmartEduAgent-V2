package com.example.smartedu.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MultimodalVectorService {

    // 简化的文本向量化实现
    public float[] vectorizeText(String text) {
        // 简单的基于字符频率的向量化，实际应用中应该使用更合适的模型
        float[] vector = new float[128]; // 128维向量
        
        // 基于字符频率计算向量
        for (char c : text.toCharArray()) {
            int index = (int) (c % 128);
            vector[index] += 1.0f;
        }
        
        // 归一化
        float norm = 0.0f;
        for (float f : vector) {
            norm += f * f;
        }
        norm = (float) Math.sqrt(norm);
        
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        
        return vector;
    }

    // 批量文本向量化
    public List<float[]> vectorizeTexts(List<String> texts) {
        List<float[]> vectors = new ArrayList<>();
        for (String text : texts) {
            vectors.add(vectorizeText(text));
        }
        return vectors;
    }

    // 计算两个向量的相似度
    public double calculateSimilarity(float[] vector1, float[] vector2) {
        if (vector1.length != vector2.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            norm1 += vector1[i] * vector1[i];
            norm2 += vector2[i] * vector2[i];
        }
        
        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
