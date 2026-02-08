package com.example.smartedu.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import com.example.smartedu.entity.LearningTask;
import com.example.smartedu.entity.LearningChapter;
import com.example.smartedu.entity.LearningProgress;
import com.example.smartedu.repository.LearningTaskRepository;
import com.example.smartedu.repository.LearningChapterRepository;
import com.example.smartedu.repository.LearningProgressRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class AiAgentService {

    @Value("${langchain4j.dashscope.api-key}")
    private String apiKey;

    private ChatLanguageModel chatModel;

    @Autowired
    private LearningTaskRepository taskRepository;

    @Autowired
    private LearningChapterRepository chapterRepository;

    @Autowired
    private LearningProgressRepository progressRepository;

    // 存储用户当前的学习状态
    private Map<Long, LearningContext> userLearningContexts = new HashMap<>();

    @PostConstruct
    public void init() {
        // 初始化 Qwen-Max
        this.chatModel = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-max")
                .build();
    }

    public String chat(String userMessage, String systemPrompt) {
        try {
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                return chatModel.generate(SystemMessage.from(systemPrompt), UserMessage.from(userMessage)).content().text();
            }
            return chatModel.generate(UserMessage.from(userMessage)).content().text();
        } catch (Exception e) {
            // 如果AI模型调用失败，返回一个默认的错误响应
            System.err.println("AI模型调用失败: " + e.getMessage());
            return "## 生成失败\n\n由于API调用失败，无法生成笔记内容。请稍后重试或检查网络连接。\n\n错误信息: " + e.getMessage().substring(0, Math.min(e.getMessage().length(), 200));
        }
    }

    // 智能教学助手核心方法
    public String teach(Long userId, String userInput, Long taskId) {
        LearningContext context = userLearningContexts.get(userId);
        if (context == null || (taskId != null && !taskId.equals(context.getTaskId()))) {
            context = createLearningContext(userId, taskId);
            userLearningContexts.put(userId, context);
        }

        // 检查用户是否表示不会做
        if (containsCannotDo(userInput)) {
            context.setLastAction("RETEACHING");
            return reteachCurrentChapter(context);
        }

        // 根据当前状态处理用户输入
        switch (context.getLastAction()) {
            case "INIT":
                return startLearning(context);
            case "TEACHING":
            case "RETEACHING":
                // 检查是否是个性化请求而非答案
                if (isCustomRequest(userInput)) {
                    String response = handleUserRequest(userInput);
                    context.setLastUserInput(userInput);
                    return response + "\n\n请继续回答上面的选择题（输入A/B/C/D）：";
                }
                return checkAnswer(context, userInput);
            case "QUESTIONING":
                // 检查是否是个性化请求
                if (isCustomRequest(userInput)) {
                    String response = handleUserRequest(userInput);
                    context.setLastUserInput(userInput);
                    return response + "\n\n请继续回答上面的选择题（输入A/B/C/D）：";
                }
                return checkAnswer(context, userInput);
            case "COMPLETED":
                return "恭喜！课程已经全部完成。如果想学习其他内容，可以创建新的学习会话。";
            default:
                return startLearning(context);
        }
    }
    
    // 判断是否为个性化请求（非选择题答案）
    private boolean isCustomRequest(String input) {
        if (input == null || input.trim().isEmpty()) return false;
        String trimmed = input.trim();
        // 如果只是单个字母A/B/C/D，认为是选择题答案
        if (trimmed.length() == 1 && "ABCDabcd".contains(trimmed)) {
            return false;
        }
        // 如果包含请求性关键词，认为是个性化请求
        String lower = trimmed.toLowerCase();
        return lower.contains("代码") || lower.contains("示例") || lower.contains("例子") ||
               lower.contains("解释") || lower.contains("详细") || lower.contains("怎么") ||
               lower.contains("为什么") || lower.contains("如何") || lower.contains("请") ||
               lower.contains("帮我") || lower.contains("给我") || lower.contains("能否") ||
               (trimmed.length() > 3 && !trimmed.matches("[A-Da-d]"));
    }

    // 创建学习上下文
    private LearningContext createLearningContext(Long userId, Long taskId) {
        LearningContext context = new LearningContext();
        context.setUserId(userId);
        context.setTaskId(taskId);
        context.setLastAction("INIT");

        // 获取或创建学习进度
        LearningProgress progress = progressRepository.findByUserIdAndTaskId(userId, taskId)
                .orElseGet(() -> createNewProgress(userId, taskId));
        context.setProgress(progress);

        // 获取学习任务和章节
        LearningTask task = taskRepository.findById(taskId).orElse(null);
        if (task != null) {
            context.setTask(task);
            List<LearningChapter> chapters = chapterRepository.findByTaskIdOrderByChapterOrderAsc(taskId);
            context.setChapters(chapters);
            
            // 设置当前章节
            if (progress.getCurrentChapterId() != null) {
                for (LearningChapter chapter : chapters) {
                    if (chapter.getId().equals(progress.getCurrentChapterId())) {
                        context.setCurrentChapter(chapter);
                        break;
                    }
                }
            } else if (!chapters.isEmpty()) {
                // 从第一章开始
                context.setCurrentChapter(chapters.get(0));
                progress.setCurrentChapterId(chapters.get(0).getId());
                progress.setCurrentChapterOrder(1);
                progressRepository.save(progress);
            }
        }

        return context;
    }

    // 创建新的学习进度
    private LearningProgress createNewProgress(Long userId, Long taskId) {
        LearningProgress progress = new LearningProgress();
        progress.setUserId(userId);
        progress.setTaskId(taskId);
        progress.setCompleted(false);
        return progressRepository.save(progress);
    }

    // 开始学习
    private String startLearning(LearningContext context) {
        if (context.getTask() == null || context.getChapters().isEmpty()) {
            return "未找到学习任务或章节，请先创建学习任务。";
        }

        if (context.getProgress().isCompleted()) {
            context.setLastAction("COMPLETED");
            return "恭喜！课程已全部完成";
        }

        // 展示学习计划（使用纯文本markdown格式，避免emoji乱码）
        StringBuilder planDisplay = new StringBuilder();
        planDisplay.append("## ").append(context.getTask().getTitle()).append("\n\n");
        planDisplay.append("根据您的学习需求，我已为您规划了以下学习章节：\n\n");
        
        List<LearningChapter> chapters = context.getChapters();
        for (int i = 0; i < chapters.size(); i++) {
            LearningChapter ch = chapters.get(i);
            if (i == 0) {
                planDisplay.append("- **[当前] 章节").append(i + 1).append("：").append(ch.getTitle()).append("**\n");
            } else {
                planDisplay.append("- [待学] 章节").append(i + 1).append("：").append(ch.getTitle()).append("\n");
            }
        }
        
        planDisplay.append("\n---\n\n");
        planDisplay.append("### 开始学习章节 ").append(context.getProgress().getCurrentChapterOrder()).append("\n\n");
        
        return planDisplay.toString() + teachCurrentChapter(context);
    }

    // 教授当前章节
    private String teachCurrentChapter(LearningContext context) {
        LearningChapter chapter = context.getCurrentChapter();
        if (chapter == null) {
            return "未找到当前学习章节。";
        }

        context.setLastAction("TEACHING");
        String teachingContent = chapter.getContent();
        
        // 生成教学内容
        String prompt = "你是一个智能教学助手，请根据以下章节内容，用通俗易懂的方式讲解给学生：\n" + teachingContent;
        String response = chat(prompt, "你是一个专业的智能教学助手，善于用通俗易懂的方式讲解知识。");
        
        // 保存当前状态
        context.setLastTeachingContent(response);
        
        // 教学后生成题目
        return response + "\n\n" + generateQuestion(context);
    }

    // 重新教授当前章节
    private String reteachCurrentChapter(LearningContext context) {
        LearningChapter chapter = context.getCurrentChapter();
        if (chapter == null) {
            return "未找到当前学习章节。";
        }

        context.setLastAction("RETEACHING");
        String teachingContent = chapter.getContent();
        
        // 生成更详细的教学内容
        String prompt = "学生表示没有理解当前章节内容，请根据以下章节内容，用更详细、更易懂的方式重新讲解：\n" + teachingContent;
        String response = chat(prompt, "你是一个专业的智能教学助手，善于用通俗易懂的方式讲解知识，特别擅长针对学生的疑惑进行详细解释。");
        
        // 保存当前状态
        context.setLastTeachingContent(response);
        
        // 教学后生成题目
        return response + "\n\n" + generateQuestion(context);
    }

    // 生成四选一选择题
    private String generateQuestion(LearningContext context) {
        LearningChapter chapter = context.getCurrentChapter();
        if (chapter == null) {
            return "未找到当前学习章节。";
        }

        context.setLastAction("QUESTIONING");
        
        String prompt = "根据以下教学内容，生成一个简单的四选一选择题。\n\n" +
                "教学内容：" + chapter.getContent() + "\n\n" +
                "要求：题目简单直接，只输出JSON格式：\n" +
                "{\"question\":\"题目\",\"options\":{\"A\":\"选项A\",\"B\":\"选项B\",\"C\":\"选项C\",\"D\":\"选项D\"},\"answer\":\"A\"}";
        
        String aiResponse = chat(prompt, "你是教育专家，生成简单直接的选择题。");
        
        try {
            String jsonStr = aiResponse;
            int start = aiResponse.indexOf("{");
            int end = aiResponse.lastIndexOf("}");
            if (start >= 0 && end > start) {
                jsonStr = aiResponse.substring(start, end + 1);
            }
            
            com.alibaba.fastjson.JSONObject jsonObj = com.alibaba.fastjson.JSON.parseObject(jsonStr);
            String questionText = jsonObj.getString("question");
            com.alibaba.fastjson.JSONObject options = jsonObj.getJSONObject("options");
            String correctAnswer = jsonObj.getString("answer").toUpperCase();
            
            context.setCurrentQuestion(questionText);
            context.setCorrectAnswer(correctAnswer);
            
            StringBuilder sb = new StringBuilder();
            sb.append("### 章节检验\n\n");
            sb.append("**").append(questionText).append("**\n\n");
            sb.append("- **A.** ").append(options.getString("A")).append("\n");
            sb.append("- **B.** ").append(options.getString("B")).append("\n");
            sb.append("- **C.** ").append(options.getString("C")).append("\n");
            sb.append("- **D.** ").append(options.getString("D")).append("\n\n");
            sb.append("> 请输入您的答案（A/B/C/D），或输入其他问题我会优先解答。");
            
            return sb.toString();
        } catch (Exception e) {
            context.setCurrentQuestion("请简述本章节的核心概念");
            context.setCorrectAnswer("A");
            return "【章节检验】\n\n" + aiResponse + "\n\n请输入您的答案或提出其他问题。";
        }
    }
    
    // 检查答案
    private String checkAnswer(LearningContext context, String userAnswer) {
        String correctAnswer = context.getCorrectAnswer();
        if (correctAnswer == null) {
            return "未找到当前题目，请重新开始学习。";
        }

        String normalizedAnswer = userAnswer.trim().toUpperCase();
        if (normalizedAnswer.length() > 1) {
            normalizedAnswer = normalizedAnswer.substring(0, 1);
        }
        
        boolean isCorrect = normalizedAnswer.equals(correctAnswer);

        if (isCorrect) {
            if (moveToNextChapter(context)) {
                int nextChapterOrder = context.getProgress().getCurrentChapterOrder();
                return "### 回答正确！\n\n" + 
                       "太棒了！进入下一章节的学习。\n\n---\n\n" +
                       "### 开始学习章节 " + nextChapterOrder + "\n\n" +
                       teachCurrentChapter(context);
            } else {
                context.setLastAction("COMPLETED");
                LearningProgress progress = context.getProgress();
                progress.setCompleted(true);
                progressRepository.save(progress);
                return "### 回答正确！\n\n## 恭喜！课程已全部完成\n\n您已成功完成所有章节的学习，继续加油！";
            }
        } else {
            context.setLastAction("RETEACHING");
            return "### 回答错误\n\n" +
                   "> 正确答案是：**" + correctAnswer + "**\n\n" +
                   "让我们调整策略，重新学习这部分内容：\n\n" +
                   reteachCurrentChapter(context);
        }
    }

    // 移动到下一章节
    private boolean moveToNextChapter(LearningContext context) {
        List<LearningChapter> chapters = context.getChapters();
        LearningChapter currentChapter = context.getCurrentChapter();
        if (chapters == null || currentChapter == null) {
            return false;
        }

        int currentIndex = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getId().equals(currentChapter.getId())) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex < 0 || currentIndex >= chapters.size() - 1) {
            // 已经是最后一章
            return false;
        }

        // 移动到下一章节
        LearningChapter nextChapter = chapters.get(currentIndex + 1);
        context.setCurrentChapter(nextChapter);

        // 更新学习进度
        LearningProgress progress = context.getProgress();
        progress.setCurrentChapterId(nextChapter.getId());
        progress.setCurrentChapterOrder(nextChapter.getChapterOrder());
        progressRepository.save(progress);

        return true;
    }

    // 处理用户的教学请求
    private String handleUserRequest(String userInput) {
        String prompt = "请根据学生的请求，提供相关的教学内容：\n" + userInput;
        return chat(prompt, "你是一个专业的智能教学助手，善于根据学生的请求提供针对性的教学内容。");
    }

    // 检查用户输入是否包含额外的教学请求（在学习过程中）
    private boolean containsExtraTeachingRequest(String input) {
        String lowerInput = input.toLowerCase();
        // 只检测明确的额外教学请求，而非初始学习需求
        return lowerInput.contains("请讲解") || lowerInput.contains("帮我解释") || 
               lowerInput.contains("再详细说说") || lowerInput.contains("能展开说说") ||
               lowerInput.contains("举个例子") || lowerInput.contains("具体说一下");
    }

    // 检查用户输入是否表示不会做
    private boolean containsCannotDo(String input) {
        String lowerInput = input.toLowerCase();
        return lowerInput.contains("不会做") || lowerInput.contains("不懂") || lowerInput.contains("不明白") ||
               lowerInput.contains("不会") || lowerInput.contains("再讲一遍") || lowerInput.contains("重新讲");
    }

    // 检查答案是否正确
    private boolean isAnswerCorrect(String evaluation) {
        String lowerEval = evaluation.toLowerCase();
        
        // 先检查是否包含明确的错误标识
        if (lowerEval.contains("错误") || lowerEval.contains("不正确") || 
            lowerEval.contains("不对") || lowerEval.contains("有误") ||
            lowerEval.contains("不准确") || lowerEval.contains("需要改进")) {
            return false;
        }
        
        // 再检查是否包含正确标识
        return lowerEval.contains("正确") || lowerEval.contains("对的") || 
               lowerEval.contains("准确") || lowerEval.contains("很好") || 
               lowerEval.contains("不错") || lowerEval.contains("优秀") ||
               lowerEval.contains("完全正确") || lowerEval.contains("回答得很好");
    }

    // 创建学习任务
    public LearningTask createLearningTask(String title, String description, List<Map<String, String>> chapters) {
        LearningTask task = new LearningTask();
        task.setTitle(title);
        task.setDescription(description);
        task = taskRepository.save(task);

        // 创建章节
        List<LearningChapter> learningChapters = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            Map<String, String> chapterData = chapters.get(i);
            LearningChapter chapter = new LearningChapter();
            chapter.setTaskId(task.getId());
            chapter.setTitle(chapterData.get("title"));
            chapter.setContent(chapterData.get("content"));
            chapter.setChapterOrder(i + 1);
            chapter = chapterRepository.save(chapter);
            learningChapters.add(chapter);
        }

        task.setChapters(learningChapters);
        return task;
    }

    // 获取所有学习任务
    public List<LearningTask> getAllLearningTasks() {
        return taskRepository.findAll();
    }

    // 获取用户的学习进度
    public LearningProgress getLearningProgress(Long userId, Long taskId) {
        return progressRepository.findByUserIdAndTaskId(userId, taskId).orElse(null);
    }

    // 自动分解学习任务
    public LearningTask autoDecomposeLearningTask(String userInput) {
        // 使用AI将用户输入分解为学习任务和章节
        String prompt = "请根据用户的学习需求，将其分解为一个完整的学习任务，包含标题、描述和多个学习章节。\n\n" +
                "用户需求：" + userInput + "\n\n" +
                "请按照以下JSON格式输出（只输出JSON，不要有其他文字）：\n" +
                "{\n" +
                "  \"title\": \"学习任务标题\",\n" +
                "  \"description\": \"学习任务描述\",\n" +
                "  \"chapters\": [\n" +
                "    {\"title\": \"第一章标题\", \"content\": \"第一章详细教学内容\"},\n" +
                "    {\"title\": \"第二章标题\", \"content\": \"第二章详细教学内容\"}\n" +
                "  ]\n" +
                "}\n\n" +
                "要求：\n" +
                "1. 将学习内容合理分解为3-5个章节\n" +
                "2. 每个章节的content要包含详细的教学内容，不少于200字\n" +
                "3. 章节之间要有逻辑递进关系\n" +
                "4. 确保输出是有效的JSON格式";

        String aiResponse = chat(prompt, "你是一个专业的教学设计专家，擅长将学习内容分解为系统化的学习路径。");
        
        try {
            // 解析AI返回的JSON
            com.alibaba.fastjson.JSONObject jsonObject = com.alibaba.fastjson.JSON.parseObject(aiResponse);
            String title = jsonObject.getString("title");
            String description = jsonObject.getString("description");
            com.alibaba.fastjson.JSONArray chaptersArray = jsonObject.getJSONArray("chapters");
            
            List<Map<String, String>> chapters = new ArrayList<>();
            for (int i = 0; i < chaptersArray.size(); i++) {
                com.alibaba.fastjson.JSONObject chapterObj = chaptersArray.getJSONObject(i);
                Map<String, String> chapter = new HashMap<>();
                chapter.put("title", chapterObj.getString("title"));
                chapter.put("content", chapterObj.getString("content"));
                chapters.add(chapter);
            }
            
            return createLearningTask(title, description, chapters);
        } catch (Exception e) {
            // 如果解析失败，创建一个默认的学习任务
            String title = "学习任务：" + userInput.substring(0, Math.min(20, userInput.length()));
            String description = userInput;
            
            List<Map<String, String>> chapters = new ArrayList<>();
            Map<String, String> chapter = new HashMap<>();
            chapter.put("title", "基础学习");
            chapter.put("content", userInput);
            chapters.add(chapter);
            
            return createLearningTask(title, description, chapters);
        }
    }

    // 学习上下文类
    private static class LearningContext {
        private Long userId;
        private Long taskId;
        private LearningTask task;
        private List<LearningChapter> chapters;
        private LearningChapter currentChapter;
        private LearningProgress progress;
        private String lastAction;
        private String lastUserInput;
        private String lastTeachingContent;
        private String currentQuestion;
        private String correctAnswer;

        // Getters and Setters
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public LearningTask getTask() { return task; }
        public void setTask(LearningTask task) { this.task = task; }
        public List<LearningChapter> getChapters() { return chapters; }
        public void setChapters(List<LearningChapter> chapters) { this.chapters = chapters; }
        public LearningChapter getCurrentChapter() { return currentChapter; }
        public void setCurrentChapter(LearningChapter currentChapter) { this.currentChapter = currentChapter; }
        public LearningProgress getProgress() { return progress; }
        public void setProgress(LearningProgress progress) { this.progress = progress; }
        public String getLastAction() { return lastAction; }
        public void setLastAction(String lastAction) { this.lastAction = lastAction; }
        public String getLastUserInput() { return lastUserInput; }
        public void setLastUserInput(String lastUserInput) { this.lastUserInput = lastUserInput; }
        public String getLastTeachingContent() { return lastTeachingContent; }
        public void setLastTeachingContent(String lastTeachingContent) { this.lastTeachingContent = lastTeachingContent; }
        public String getCurrentQuestion() { return currentQuestion; }
        public void setCurrentQuestion(String currentQuestion) { this.currentQuestion = currentQuestion; }
        public String getCorrectAnswer() { return correctAnswer; }
        public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }
    }
}