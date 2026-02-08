package com.example.smartedu.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.PriorityQueue;

@Service
public class RagService {

    @Autowired
    private MultimodalVectorService vectorService;

    @Autowired
    private AiAgentService aiService;

    // 简化的嵌入存储结构
    private static class EmbeddedSegment {
        String text;
        float[] embedding;
        Map<String, String> metadata;
        
        EmbeddedSegment(String text, float[] embedding, Map<String, String> metadata) {
            this.text = text;
            this.embedding = embedding;
            this.metadata = metadata;
        }
    }

    private List<EmbeddedSegment> embeddingStore;

    public RagService() {
        // 初始化内存嵌入存储
        this.embeddingStore = new ArrayList<>();
    }

    // 添加文档到嵌入存储
    public void addDocument(String content, String metadata) {
        // 分割文档为段落
        List<String> paragraphs = splitDocument(content);
        
        // 为每个段落创建文本段并添加到存储
        for (int i = 0; i < paragraphs.size(); i++) {
            String paragraph = paragraphs.get(i);
            if (!paragraph.trim().isEmpty()) {
                // 获取嵌入
                float[] embedding = vectorService.vectorizeText(paragraph);
                
                // 创建元数据
                Map<String, String> meta = new HashMap<>();
                meta.put("metadata", metadata);
                meta.put("paragraphIndex", String.valueOf(i));
                
                // 添加到存储
                embeddingStore.add(new EmbeddedSegment(paragraph, embedding, meta));
            }
        }
    }

    // 分割文档为段落
    private List<String> splitDocument(String content) {
        List<String> paragraphs = new ArrayList<>();
        String[] splits = content.split("\\n\\s*\\n");
        for (String split : splits) {
            if (!split.trim().isEmpty()) {
                paragraphs.add(split.trim());
            }
        }
        return paragraphs;
    }

    // 基于RAG的问答
    public String ragQuery(String query) {
        // 向量化查询
        float[] queryEmbedding = vectorService.vectorizeText(query);
        
        // 检索相关内容
        List<EmbeddedSegment> relevantSegments = findRelevant(queryEmbedding, 5);
        
        // 构建上下文
        StringBuilder context = new StringBuilder();
        context.append("相关资料：\n");
        for (EmbeddedSegment segment : relevantSegments) {
            context.append(segment.text).append("\n\n");
        }
        
        // 构建提示词
        String prompt = "请根据以下相关资料，回答用户的问题：\n\n" +
                       context.toString() +
                       "用户问题：" + query;
        
        // 使用AI服务生成回答
        return aiService.chat(prompt, "你是一个智能笔记内化助手，善于基于提供的资料回答用户问题。");
    }

    // 查找相关内容
    private List<EmbeddedSegment> findRelevant(float[] queryEmbedding, int topK) {
        // 使用优先队列按相似度排序
        PriorityQueue<Map.Entry<Double, EmbeddedSegment>> queue = new PriorityQueue<>(
            (a, b) -> a.getKey().compareTo(b.getKey())
        );
        
        for (EmbeddedSegment segment : embeddingStore) {
            double similarity = vectorService.calculateSimilarity(queryEmbedding, segment.embedding);
            
            queue.offer(Map.entry(similarity, segment));
            if (queue.size() > topK) {
                queue.poll();
            }
        }
        
        // 转换为列表并反转（相似度从高到低）
        List<EmbeddedSegment> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            result.add(0, queue.poll().getValue());
        }
        
        return result;
    }

    // 清空嵌入存储
    public void clearEmbeddings() {
        this.embeddingStore = new ArrayList<>();
    }
}
