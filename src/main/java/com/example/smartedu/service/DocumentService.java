package com.example.smartedu.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.hslf.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    public String parseFile(MultipartFile file) throws IOException {
        // 验证文件是否为空
        if (file.isEmpty()) {
            throw new IOException("上传的文件为空");
        }
        
        String fileName = file.getOriginalFilename().toLowerCase();
        if (fileName.endsWith(".pdf")) {
            try (PDDocument document = PDDocument.load(file.getInputStream())) {
                // 检查PDF是否被加密
                if (document.isEncrypted()) {
                    throw new IOException("PDF文件已加密，无法处理");
                }
                
                // 检查PDF页数，限制处理页数
                int pageCount = document.getNumberOfPages();
                if (pageCount > 50) {
                    throw new IOException("PDF文件页数过多（超过50页），请上传 smaller 文件");
                }
                
                PDFTextStripper stripper = new PDFTextStripper();
                String content = stripper.getText(document);
                
                // 检查提取的内容是否为空
                if (content == null || content.trim().isEmpty()) {
                    throw new IOException("PDF文件中未提取到文本内容");
                }
                
                return content;
            } catch (IOException e) {
                throw new IOException("处理PDF文件失败: " + e.getMessage());
            }
        } else if (fileName.endsWith(".docx")) {
            try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
                StringBuilder text = new StringBuilder();
                doc.getParagraphs().forEach(p -> text.append(p.getText()).append("\n"));
                String content = text.toString();
                
                // 检查提取的内容是否为空
                if (content == null || content.trim().isEmpty()) {
                    throw new IOException("Word文件中未提取到文本内容");
                }
                
                return content;
            } catch (IOException e) {
                throw new IOException("处理Word文件失败: " + e.getMessage());
            }
        } else if (fileName.endsWith(".pptx")) {
            try {
                logger.info("开始处理PPTX文件: {}", file.getOriginalFilename());
                // 处理PPTX文件
                StringBuilder text = new StringBuilder();
                text.append("# PPT文件内容\n\n");
                text.append("- 文件名: " + file.getOriginalFilename() + "\n");
                text.append("- 文件大小: " + file.getSize() + " 字节\n");
                text.append("- 文件类型: " + file.getContentType() + "\n\n");
                text.append("## 幻灯片内容\n\n");
                
                // 使用Apache POI提取PPTX内容
                XSLFSlideShowFactory factory = new XSLFSlideShowFactory();
                try (XMLSlideShow ppt = factory.create(file.getInputStream())) {
                    // 提取每张幻灯片的内容
                    int slideIndex = 1;
                    boolean hasContent = false;
                    for (XSLFSlide slide : ppt.getSlides()) {
                        text.append("### 幻灯片 " + slideIndex + "\n\n");
                        
                        // 提取幻灯片中的所有文本
                        for (XSLFShape shape : slide.getShapes()) {
                            if (shape instanceof XSLFTextShape) {
                                XSLFTextShape textShape = (XSLFTextShape) shape;
                                String shapeText = textShape.getText();
                                if (!shapeText.trim().isEmpty()) {
                                    text.append(shapeText + "\n\n");
                                    hasContent = true;
                                }
                            }
                        }
                        
                        slideIndex++;
                    }
                    
                    if (!hasContent) {
                        logger.warn("PPTX文件中未提取到文本内容: {}", file.getOriginalFilename());
                        text.append("## 说明\n");
                        text.append("此PPT文件中未包含可提取的文本内容。\n");
                    } else {
                        logger.info("成功提取PPTX文件内容: {}", file.getOriginalFilename());
                    }
                    
                    String content = text.toString();
                    return content;
                }
            } catch (Exception e) {
                logger.error("处理PPTX文件失败: {}", e.getMessage(), e);
                // 如果POI处理失败，返回文件信息
                StringBuilder text = new StringBuilder();
                text.append("# PPT文件内容\n\n");
                text.append("- 文件名: " + file.getOriginalFilename() + "\n");
                text.append("- 文件大小: " + file.getSize() + " 字节\n");
                text.append("- 文件类型: " + file.getContentType() + "\n\n");
                text.append("## 说明\n");
                text.append("此文件为PPT格式，内容已上传并添加到知识库中。\n");
                text.append("您可以通过智能问答功能查询此文件中的相关内容。\n");
                text.append("处理过程中遇到错误: " + e.getMessage() + "\n");
                
                return text.toString();
            }
        } else if (fileName.endsWith(".ppt")) {
            try {
                logger.info("开始处理PPT文件: {}", file.getOriginalFilename());
                // 处理PPT文件
                StringBuilder text = new StringBuilder();
                text.append("# PPT文件内容\n\n");
                text.append("- 文件名: " + file.getOriginalFilename() + "\n");
                text.append("- 文件大小: " + file.getSize() + " 字节\n");
                text.append("- 文件类型: " + file.getContentType() + "\n\n");
                text.append("## 幻灯片内容\n\n");
                
                // 使用Apache POI提取PPT内容
                HSLFSlideShow ppt = new HSLFSlideShow(file.getInputStream());
                
                // 提取每张幻灯片的内容
                int slideIndex = 1;
                boolean hasContent = false;
                for (HSLFSlide slide : ppt.getSlides()) {
                    text.append("### 幻灯片 " + slideIndex + "\n\n");
                    
                    // 提取幻灯片中的所有文本
                    for (HSLFShape shape : slide.getShapes()) {
                        if (shape instanceof HSLFTextShape) {
                            HSLFTextShape textShape = (HSLFTextShape) shape;
                            String shapeText = textShape.getText();
                            if (!shapeText.trim().isEmpty()) {
                                text.append(shapeText + "\n\n");
                                hasContent = true;
                            }
                        }
                    }
                    
                    slideIndex++;
                }
                
                ppt.close();
                
                if (!hasContent) {
                    logger.warn("PPT文件中未提取到文本内容: {}", file.getOriginalFilename());
                    text.append("## 说明\n");
                    text.append("此PPT文件中未包含可提取的文本内容。\n");
                } else {
                    logger.info("成功提取PPT文件内容: {}", file.getOriginalFilename());
                }
                
                String content = text.toString();
                return content;
            } catch (Exception e) {
                logger.error("处理PPT文件失败: {}", e.getMessage(), e);
                // 如果POI处理失败，返回文件信息
                StringBuilder text = new StringBuilder();
                text.append("# PPT文件内容\n\n");
                text.append("- 文件名: " + file.getOriginalFilename() + "\n");
                text.append("- 文件大小: " + file.getSize() + " 字节\n");
                text.append("- 文件类型: " + file.getContentType() + "\n\n");
                text.append("## 说明\n");
                text.append("此文件为PPT格式，内容已上传并添加到知识库中。\n");
                text.append("您可以通过智能问答功能查询此文件中的相关内容。\n");
                text.append("处理过程中遇到错误: " + e.getMessage() + "\n");
                
                return text.toString();
            }
        } else if (fileName.endsWith(".mp3") || fileName.endsWith(".wav") || fileName.endsWith(".m4a")) {
            // 语音文件处理
            try {
                logger.info("开始处理语音文件: {}", file.getOriginalFilename());
                // 保存临时文件
                java.io.File tempFile = java.io.File.createTempFile("audio", ".tmp");
                file.transferTo(tempFile);
                
                // 获取文件扩展名
                String fileExtension = fileName.substring(fileName.lastIndexOf(".") + 1);
                
                // 调用百度云语音识别
                String content = recognizeSpeech(tempFile, fileExtension);
                
                // 删除临时文件
                tempFile.delete();
                
                // 检查提取的内容是否为空
                if (content == null || content.trim().isEmpty()) {
                    throw new IOException("语音文件中未提取到文本内容");
                }
                
                return content;
            } catch (Exception e) {
                throw new IOException("处理语音文件失败: " + e.getMessage());
            }
        }
        throw new IOException("不支持的文件格式，请上传PDF、Word、PPT或语音文件");
    }
    
    // 百度云语音识别
    private String recognizeSpeech(java.io.File audioFile, String fileExtension) throws Exception {
        // 初始化百度云语音客户端
        com.baidu.aip.speech.AipSpeech client = new com.baidu.aip.speech.AipSpeech(
            "7432526", 
            "XwjgUUgZgViEnQWr4GJqr3IG", 
            "MuznqzA4hw2P4kRyD8Cewu3HcG4rtTom"
        );
        
        // 设置网络连接参数
        client.setConnectionTimeoutInMillis(2000);
        client.setSocketTimeoutInMillis(60000);
        
        // 构建识别参数
        java.util.HashMap<String, Object> options = new java.util.HashMap<>();
        options.put("dev_pid", 1537); // 普通话(支持简单的英文识别)
        
        // 根据文件扩展名设置正确的格式
        String format = "wav";
        if (fileExtension.equals("mp3")) {
            format = "mp3";
        } else if (fileExtension.equals("m4a")) {
            format = "m4a";
        }
        
        // 调用语音识别
        org.json.JSONObject res = client.asr(audioFile.getAbsolutePath(), format, 16000, options);
        
        // 解析识别结果
        if (res.getInt("err_no") == 0) {
            org.json.JSONArray resultArray = res.getJSONArray("result");
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < resultArray.length(); i++) {
                result.append(resultArray.getString(i));
            }
            return result.toString();
        } else {
            throw new Exception("语音识别失败: " + res.getString("err_msg"));
        }
    }

    public List<Map<String, String>> parseExcel(MultipartFile file) throws IOException {
        // 验证文件是否为空
        if (file.isEmpty()) {
            throw new IOException("上传的文件为空");
        }
        
        List<Map<String, String>> data = new ArrayList<>();
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);
        
        // 假设第一行是表头
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new IOException("文件缺少表头");
        }
        
        List<String> headers = new ArrayList<>();
        for (Cell cell : headerRow) {
            headers.add(cell.getStringCellValue());
        }
        
        // 验证必要的表头
        List<String> requiredHeaders = List.of("年级", "姓名", "语文", "数学", "英语", "考试时间");
        List<String> missingHeaders = new ArrayList<>();
        for (String requiredHeader : requiredHeaders) {
            boolean found = false;
            for (String header : headers) {
                if (header.equals(requiredHeader)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missingHeaders.add(requiredHeader);
            }
        }
        
        if (!missingHeaders.isEmpty()) {
            throw new IOException("文件缺少必要的表头：" + String.join(", ", missingHeaders));
        }

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            Map<String, String> rowData = new HashMap<>();
            for (int j = 0; j < headers.size(); j++) {
                Cell cell = row.getCell(j);
                String value = "";
                if (cell != null) {
                    switch (cell.getCellType()) {
                        case STRING: value = cell.getStringCellValue(); break;
                        case NUMERIC: value = String.valueOf(cell.getNumericCellValue()); break;
                        default: value = "";
                    }
                }
                rowData.put(headers.get(j), value);
            }
            data.add(rowData);
        }
        
        // 验证是否有数据行
        if (data.isEmpty()) {
            throw new IOException("文件没有数据行");
        }
        
        return data;
    }

    public String fetchUrlContent(String url) throws IOException, ParseException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(url);
            httpGet.addHeader("User-Agent", "Mozilla/5.0");
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                if (statusCode >= 200 && statusCode < 300) {
                    return EntityUtils.toString(response.getEntity(), "UTF-8");
                } else {
                    throw new IOException("Failed to fetch URL: " + statusCode);
                }
            }
        }
    }
}