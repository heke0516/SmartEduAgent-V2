package com.example.smartedu.controller;

import com.example.smartedu.entity.Analysis;
import com.example.smartedu.entity.ChatMessage;
import com.example.smartedu.entity.ChatSession;
import com.example.smartedu.entity.Note;
import com.example.smartedu.entity.LearningTask;
import com.example.smartedu.entity.LearningProgress;
import com.example.smartedu.repository.AnalysisRepository;
import com.example.smartedu.repository.LearningProgressRepository;
import com.example.smartedu.repository.LearningTaskRepository;
import com.example.smartedu.repository.MessageRepository;
import com.example.smartedu.repository.NoteRepository;
import com.example.smartedu.repository.SessionRepository;
import com.example.smartedu.service.AiAgentService;
import com.example.smartedu.service.DocumentService;
import com.example.smartedu.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.hc.core5.http.ParseException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    @Autowired private AiAgentService aiService;
    @Autowired private DocumentService docService;
    @Autowired private RagService ragService;
    @Autowired private SessionRepository sessionRepo;
    @Autowired private MessageRepository msgRepo;
    @Autowired private NoteRepository noteRepo;
    @Autowired private AnalysisRepository analysisRepo;
    @Autowired private LearningTaskRepository taskRepository;
    @Autowired private LearningProgressRepository progressRepository;

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    // 1. 获取所有会话
    @GetMapping("/sessions")
    public List<ChatSession> getSessions() {
        return sessionRepo.findAllByOrderByCreatedAtDesc();
    }

    // 2. 创建新会话
    @PostMapping("/sessions")
    public ChatSession createSession(@RequestParam String title) {
        ChatSession session = new ChatSession();
        session.setTitle(title);
        return sessionRepo.save(session);
    }

    // 3. 获取某会话消息
    @GetMapping("/sessions/{id}/messages")
    public List<ChatMessage> getMessages(@PathVariable Long id) {
        return msgRepo.findBySessionIdOrderByTimestampAsc(id);
    }
    
    // 3.1 删除会话
    @DeleteMapping("/sessions/{id}")
    public void deleteSession(@PathVariable Long id) {
        // 先删除该会话的所有消息
        List<ChatMessage> messages = msgRepo.findBySessionIdOrderByTimestampAsc(id);
        msgRepo.deleteAll(messages);
        // 再删除会话本身
        sessionRepo.deleteById(id);
    }

    // 4. 智能教学助手 (主动学习) - 普通接口
    @PostMapping("/chat")
    public String chat(@RequestParam Long sessionId, @RequestParam String message) {
        // 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("USER");
        userMsg.setContent(message);
        msgRepo.save(userMsg);

        // 使用sessionId作为userId，获取或创建学习任务
        Long userId = sessionId;
        Long taskId = getOrCreateTaskForSession(sessionId, message);
        
        // 调用智能教学助手进行主动学习
        String response = aiService.teach(userId, message, taskId);

        // 保存 AI 消息
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setSessionId(sessionId);
        aiMsg.setRole("AI");
        aiMsg.setContent(response);
        msgRepo.save(aiMsg);

        return response;
    }
    
    // 4.1 智能教学助手 - SSE流式响应接口
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam Long sessionId, @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        
        executorService.execute(() -> {
            try {
                // 保存用户消息
                ChatMessage userMsg = new ChatMessage();
                userMsg.setSessionId(sessionId);
                userMsg.setRole("USER");
                userMsg.setContent(message);
                msgRepo.save(userMsg);

                // 使用sessionId作为userId，获取或创建学习任务
                Long userId = sessionId;
                Long taskId = getOrCreateTaskForSession(sessionId, message);
                
                // 调用智能教学助手进行主动学习
                String response = aiService.teach(userId, message, taskId);

                // 保存 AI 消息
                ChatMessage aiMsg = new ChatMessage();
                aiMsg.setSessionId(sessionId);
                aiMsg.setRole("AI");
                aiMsg.setContent(response);
                msgRepo.save(aiMsg);
                
                // 流式输出：正常速度
                for (int i = 0; i < response.length(); i++) {
                    String chunk = String.valueOf(response.charAt(i));
                    emitter.send(SseEmitter.event()
                            .data(chunk)
                            .id(String.valueOf(i)));
                    Thread.sleep(15);
                }
                
                // 发送完成标记
                emitter.send(SseEmitter.event().data("[DONE]").id("done"));
                emitter.complete();
                
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }
    
    // 为会话获取或创建学习任务
    private Long getOrCreateTaskForSession(Long sessionId, String userInput) {
        // 检查该会话是否已有学习任务（通过userId=sessionId查询）
        List<LearningProgress> progresses = progressRepository.findAll();
        for (LearningProgress progress : progresses) {
            if (progress.getUserId().equals(sessionId)) {
                return progress.getTaskId();
            }
        }
        
        // 如果没有，自动创建学习任务
        LearningTask task = aiService.autoDecomposeLearningTask(userInput);
        
        // 返回新创建的任务ID
        return task.getId();
    }

    // 5. 获取所有笔记
    @GetMapping("/notes")
    public List<Note> getNotes() {
        return noteRepo.findAllByOrderByCreatedAtDesc();
    }

    // 6. 保存笔记
    @PostMapping("/notes")
    public Note saveNote(@RequestBody Note note) {
        return noteRepo.save(note);
    }

    // 6. 笔记助手 (上传文档总结)
    @PostMapping("/note-assistant")
    public Note summarize(@RequestParam("files") MultipartFile[] files) throws IOException {
        try {
            StringBuilder combinedContent = new StringBuilder();
            StringBuilder fileNames = new StringBuilder();
            List<String> fileContents = new ArrayList<>();
            
            // 处理多个文件
            for (int i = 0; i < files.length; i++) {
                MultipartFile file = files[i];
                String content = docService.parseFile(file);
                fileContents.add(content);
                combinedContent.append("# 文件 " + (i + 1) + ": " + file.getOriginalFilename() + "\n\n");
                combinedContent.append(content);
                combinedContent.append("\n\n");
                fileNames.append(file.getOriginalFilename());
                if (i < files.length - 1) {
                    fileNames.append(", ");
                }
            }
            
            String prompt = "请阅读以下多个文档内容，提炼出精华笔记，使用Markdown格式输出：\n\n" +
                           "# 任务要求\n" +
                           "1. 全面分析所有文档内容，提取所有核心知识点\n" +
                           "2. 按照逻辑结构组织笔记，使用清晰的层级标题\n" +
                           "3. 对重要概念和关键信息进行强调\n" +
                           "4. 总结文档的主要内容和核心思想\n" +
                           "5. 输出格式美观，便于阅读和复习\n\n" +
                           "# 文档内容\n" + combinedContent.toString();
            // 截断以防止超出Token限制 (简单处理)
            if (prompt.length() > 25000) prompt = prompt.substring(0, 25000);
            
            String aiResponse = aiService.chat(prompt, "你是一个专业的笔记整理助手，善于从各种文档中提取核心知识点并组织成结构清晰的笔记。");
            
            // 保存笔记
            Note note = new Note();
            if (files.length == 1) {
                note.setTitle(files[0].getOriginalFilename().replaceAll("\\.[^.]+$", ""));
                note.setFileName(files[0].getOriginalFilename());
            } else {
                note.setTitle("多文件笔记汇总");
                note.setFileName(fileNames.toString());
            }
            note.setContent(aiResponse);
            
            // 将文档内容添加到RAG存储
            for (int i = 0; i < files.length; i++) {
                MultipartFile file = files[i];
                String content = fileContents.get(i);
                ragService.addDocument(content, file.getOriginalFilename());
            }
            
            return noteRepo.save(note);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("生成笔记失败: " + e.getMessage(), e);
        }
    }
    
    // 6.1 笔记助手 (RAG问答)
    @PostMapping("/note-assistant/rag")
    public String ragQuery(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        return ragService.ragQuery(query);
    }
    
    // 6.2 智能笔记内化助手 (生成复习材料)
    @PostMapping("/note-assistant/review")
    public Note generateReviewMaterial(@RequestParam Long noteId) {
        // 获取笔记
        Note note = noteRepo.findById(noteId).orElseThrow(() -> 
            new IllegalArgumentException("笔记不存在"));
        
        // 生成复习材料
        String prompt = "请根据以下笔记内容，生成一份详细的复习材料，包括：\n" +
                       "1. 核心知识点总结\n" +
                       "2. 重点难点分析\n" +
                       "3. 练习题及答案\n" +
                       "4. 思维导图（文字描述）\n\n" +
                       "笔记内容：\n" + note.getContent();
        
        String aiResponse = aiService.chat(prompt, "你是一个智能笔记内化助手，善于将笔记转化为有效的复习材料。");
        
        // 保存复习材料为新笔记
        Note reviewNote = new Note();
        reviewNote.setTitle("复习材料：" + note.getTitle());
        reviewNote.setContent(aiResponse);
        reviewNote.setFileName(note.getFileName());
        
        return noteRepo.save(reviewNote);
    }

    // 7. 笔记助手 (链接生成笔记)
    @PostMapping("/note-assistant/url")
    public Note summarizeFromUrl(@RequestBody Map<String, String> request) throws IOException, ParseException {
        String url = request.get("url");
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("URL不能为空");
        }
        
        // 从URL获取内容
        String content = docService.fetchUrlContent(url);
        String prompt = "请阅读以下链接内容，提炼出精华笔记，使用Markdown格式输出：\n\n" + content;
        // 截断以防止超出Token限制 (简单处理)
        if (prompt.length() > 25000) prompt = prompt.substring(0, 25000);
        
        String aiResponse = aiService.chat(prompt, "你是一个专业的笔记整理助手。");
        
        // 保存笔记
        Note note = new Note();
        note.setTitle("从链接生成的笔记");
        note.setContent(aiResponse);
        note.setFileName(url);
        
        // 将链接内容添加到RAG存储
        ragService.addDocument(content, url);
        
        return noteRepo.save(note);
    }

    // 8. 获取所有分析
    @GetMapping("/analysis")
    public List<Analysis> getAnalysisList() {
        return analysisRepo.findAllByOrderByCreatedAtDesc();
    }

    // 8. 学情分析
    @PostMapping("/analysis")
    public List<Analysis> analyzeStudent(@RequestParam("file") MultipartFile file) throws IOException {
        List<Map<String, String>> data = docService.parseExcel(file);
        List<Analysis> analyses = new java.util.ArrayList<>();
        
        for (Map<String, String> studentData : data) {
            StringBuilder prompt = new StringBuilder();
            // 优化提示词，使其更简洁明确
            prompt.append("学生成绩：");
            prompt.append("姓名：").append(studentData.getOrDefault("姓名", "未知"));
            prompt.append("，年级：").append(studentData.getOrDefault("年级", "未知"));
            prompt.append("，语文：").append(studentData.getOrDefault("语文", "0"));
            prompt.append("，数学：").append(studentData.getOrDefault("数学", "0"));
            prompt.append("，英语：").append(studentData.getOrDefault("英语", "0"));
            prompt.append("，考试时间：").append(studentData.getOrDefault("考试时间", "未知"));
            
            prompt.append("\n\n任务：");
            prompt.append("1. 简要分析该学生的学习情况，指出优势和不足");
            prompt.append("2. 给出具体的学习建议");
            prompt.append("3. 根据成绩水平出5道针对性练习题（每科至少1道），用<details>包裹答案");
            
            prompt.append("\n\n要求：");
            prompt.append("- 分析简洁专业，重点突出");
            prompt.append("- 建议具体可行");
            prompt.append("- 题目难度与学生水平匹配");
            prompt.append("- 数学公式用普通文本格式，不用Latex");
            prompt.append("- 总字数控制在1000字以内");

            String aiResponse = aiService.chat(prompt.toString(), "你是教育专家，简洁专业地分析学情并出题，直接给出核心内容，不要有多余的开场白。");
            
            Analysis analysis = new Analysis();
            String studentName = studentData.getOrDefault("姓名", "学生") + "的学情分析";
            analysis.setTitle(studentName);
            analysis.setContent(aiResponse);
            analysis.setFileName(file.getOriginalFilename());
            analyses.add(analysis);
        }
        
        // 批量保存分析结果，减少数据库操作次数
        if (!analyses.isEmpty()) {
            analyses = analysisRepo.saveAll(analyses);
        }
        
        return analyses;
    }
    
    // 9. 保存分析结果
    @PostMapping("/analysis/save")
    public Analysis saveAnalysis(@RequestBody Analysis analysis) {
        return analysisRepo.save(analysis);
    }

    // ==================== 智能教学助手相关接口 ====================

    // 1. 创建学习任务
    @PostMapping("/learning/tasks")
    public LearningTask createLearningTask(@RequestBody Map<String, Object> request) {
        String title = (String) request.get("title");
        String description = (String) request.get("description");
        List<Map<String, String>> chapters = (List<Map<String, String>>) request.get("chapters");
        
        if (title == null || chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("标题和章节不能为空");
        }
        
        return aiService.createLearningTask(title, description, chapters);
    }

    // 2. 获取所有学习任务
    @GetMapping("/learning/tasks")
    public List<LearningTask> getLearningTasks() {
        return aiService.getAllLearningTasks();
    }

    // 3. 智能教学助手交互
    @PostMapping("/learning/teach")
    public String teach(@RequestBody Map<String, Object> request) {
        Long userId = ((Number) request.get("userId")).longValue();
        String userInput = (String) request.get("userInput");
        Long taskId = ((Number) request.get("taskId")).longValue();
        
        if (userId == null || userInput == null || taskId == null) {
            throw new IllegalArgumentException("用户ID、输入内容和任务ID不能为空");
        }
        
        return aiService.teach(userId, userInput, taskId);
    }

    // 4. 获取学习进度
    @GetMapping("/learning/progress")
    public LearningProgress getLearningProgress(@RequestParam Long userId, @RequestParam Long taskId) {
        return aiService.getLearningProgress(userId, taskId);
    }

    // 5. 开始学习（初始化学习）
    @PostMapping("/learning/start")
    public String startLearning(@RequestBody Map<String, Object> request) {
        Long userId = ((Number) request.get("userId")).longValue();
        Long taskId = ((Number) request.get("taskId")).longValue();
        
        if (userId == null || taskId == null) {
            throw new IllegalArgumentException("用户ID和任务ID不能为空");
        }
        
        return aiService.teach(userId, "开始学习", taskId);
    }

    // 6. 智能分解学习任务（从自然语言输入自动创建学习任务）
    @PostMapping("/learning/tasks/auto-create")
    public LearningTask autoCreateLearningTask(@RequestBody Map<String, String> request) {
        String userInput = request.get("input");
        
        if (userInput == null || userInput.isEmpty()) {
            throw new IllegalArgumentException("学习内容不能为空");
        }
        
        return aiService.autoDecomposeLearningTask(userInput);
    }
}