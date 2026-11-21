package com.knowledge.base.ai.service.impl;

import com.knowledge.base.ai.config.ModelProvider;
import com.knowledge.base.ai.service.AiDocumentService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: AI文档处理服务实现类
 * <p>提供轻量、高效的纯文本AI写作辅助与摘要生成能力。</p>
 * @author: Winston
 * @date: 2026/11/21 10:11
 * @version: 1.0
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDocumentServiceImpl implements AiDocumentService {

    private final ModelProvider modelProvider;
    private final StringRedisTemplate redisTemplate;

    private static final String SUMMARY_CACHE_PREFIX = "ai:summary:";
    private static final Duration SUMMARY_CACHE_TTL = Duration.ofDays(7);


    /**
     * 计算内容摘要的缓存Key，基于内容MD5去重
     */
    private String buildSummaryCacheKey(String content) {
        return SUMMARY_CACHE_PREFIX + DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从Redis缓存中获取摘要，缓存命中返回摘要内容，未命中返回null
     */
    private String getCachedSummary(String content) {
        try {
            if (redisTemplate == null) return null;
            return redisTemplate.opsForValue().get(buildSummaryCacheKey(content));
        } catch (Exception e) {
            log.warn("读取摘要缓存失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 将摘要写入Redis缓存
     */
    private void cacheSummary(String content, String summary) {
        try {
            if (redisTemplate == null) return;
            redisTemplate.opsForValue().set(buildSummaryCacheKey(content), summary, SUMMARY_CACHE_TTL);
            log.info("摘要已缓存：key={}", buildSummaryCacheKey(content));
        } catch (Exception e) {
            log.warn("写入摘要缓存失败：{}", e.getMessage());
        }
    }

    /**
     * 构建摘要Prompt
     */
    private String buildSummaryPrompt(String content, String title, int length) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下文档生成一个简洁的摘要，要求：\n");
        sb.append("1. 摘要长度约").append(length).append("字\n");
        sb.append("2. 涵盖文档的核心内容和主要观点\n");
        sb.append("3. 语言简洁明了\n");
        sb.append("4. 不要添加任何额外的解释\n");
        if (title != null && !title.isEmpty()) {
            sb.append("\n文档标题：").append(title).append("\n");
        }
        sb.append("\n文档内容：\n").append(content);
        return sb.toString();
    }

    /**
     * 生成文档摘要（非流式）
     */
    @Override
    public String generateSummary(String content, String title, Integer length) {
        int targetLength = (length == null || length <= 0) ? 200 : length;
        String truncContent = truncateContent(content, 8000);

        String cached = getCachedSummary(truncContent);
        if (cached != null) {
            log.info("摘要缓存命中");
            return cached;
        }

        String prompt = buildSummaryPrompt(truncContent, title, targetLength);
        try {
            UserMessage userMessage = UserMessage.from(prompt);
            Response<AiMessage> response = modelProvider.getDefaultModel().generate(userMessage);
            String summary = response.content().text().trim();
            cacheSummary(truncContent, summary);
            return summary;
        } catch (Exception e) {
            log.error("生成摘要失败：{}", e.getMessage(), e);
            throw new RuntimeException("生成摘要失败: " + e.getMessage());
        }
    }

    /**
     * 流式生成文档摘要
     */
    @Override
    public SseEmitter generateSummaryStream(String content, String title, Integer length, Long userId) {
        log.info("流式生成摘要：userId={}, title={}", userId, title);
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        try {
            String truncContent = truncateContent(content, 8000);
            String cached = getCachedSummary(truncContent);
            final int finalLength = (length == null || length <= 0) ? 200 : length;

            if (cached != null) {
                log.info("摘要缓存命中（流式）");
                new Thread(() -> {
                    try {
                        List<String> chunks = chunkBySentence(cached);
                        for (int i = 0; i < chunks.size(); i++) {
                            emitter.send(SseEmitter.event().name("message").data(chunks.get(i)));
                            if (i < chunks.size() - 1) Thread.sleep(30);
                        }
                        Map<String, Object> result = new HashMap<>();
                        result.put("processedContent", cached);
                        result.put("success", true);
                        result.put("message", "缓存命中");
                        emitter.send(SseEmitter.event().name("done").data(result));
                        emitter.complete();
                    } catch (Exception e) {
                        try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); } catch (IOException ignored) {}
                        emitter.completeWithError(e);
                    }
                }, "summary-stream-cached-" + userId).start();
                return emitter;
            }

            new Thread(() -> {
                try {
                    String prompt = buildSummaryPrompt(truncContent, title, finalLength);
                    UserMessage userMessage = UserMessage.from(prompt);
                    Response<AiMessage> response = modelProvider.getDefaultModel().generate(userMessage);
                    String summary = response.content().text().trim();
                    cacheSummary(truncContent, summary);

                    List<String> chunks = chunkBySentence(summary);
                    for (int i = 0; i < chunks.size(); i++) {
                        emitter.send(SseEmitter.event().name("message").data(chunks.get(i)));
                        if (i < chunks.size() - 1) Thread.sleep(40);
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("processedContent", summary);
                    result.put("success", true);
                    result.put("message", "生成成功");
                    emitter.send(SseEmitter.event().name("done").data(result));
                    emitter.complete();
                } catch (Exception e) {
                    log.error("流式生成摘要失败：{}", e.getMessage(), e);
                    try { emitter.send(SseEmitter.event().name("error").data("生成失败: " + e.getMessage())); } catch (IOException ignored) {}
                    emitter.completeWithError(e);
                }
            }, "summary-stream-" + userId).start();

        } catch (Exception e) {
            log.error("流式生成摘要启动失败：{}", e.getMessage(), e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 生成文档大纲
     */
    @Override
    public String generateOutline(String content, Integer level) {
        log.info("生成文档大纲：contentLength={}, level={}", content.length(), level);
        String trunc = truncateContent(content, 6000);

        int outlineLevel = (level == null || level <= 0 || level > 3) ? 2 : level;
        String prompt = String.format("""
                请为以下文档生成一个结构化的大纲，要求：
                1. 大纲层级为%d级
                2. 使用标准的标题层级格式（如#、##、###）
                3. 体现文档的逻辑结构和主要内容
                4. 每个层级用"一、"、"二、"等标记
                5. 不要添加任何额外的解释

                文档内容：
                %s
                """, outlineLevel, trunc);

        try {
            UserMessage userMessage = UserMessage.from(prompt);
            Response<AiMessage> response = modelProvider.getDefaultModel().generate(userMessage);
            return response.content().text().trim();
        } catch (Exception e) {
            log.error("生成大纲失败：{}", e.getMessage(), e);
            throw new RuntimeException("生成大纲失败: " + e.getMessage());
        }
    }

    /**
     * 扩展内容
     */
    @Override
    public String expandContent(String content, String expType) {
        log.info("扩展内容：contentLength={}, expType={}", content.length(), expType);
        String trunc = truncateContent(content, 4000);

        String typeDesc = getTypeDescription(expType);
        String prompt = String.format("""
                请对以下文档内容进行%s，要求：
                1. 保持原文档的专业风格与语气，针对核心论点与关键要点进行深度扩展
                2. 扩展内容逻辑严密、论据充分、表述详实
                3. 直接输出扩展补充的正文或增补模块（约400-800字），无需机械抄写原文档全文
                4. 不要添加任何额外的客套说明与解释

                原文档内容：
                %s
                """, typeDesc, trunc);

        try {
            UserMessage userMessage = UserMessage.from(prompt);
            Response<AiMessage> response = modelProvider.getDefaultModel().generate(userMessage);
            return response.content().text().trim();
        } catch (Exception e) {
            log.error("扩展内容失败：{}", e.getMessage(), e);
            throw new RuntimeException("扩展内容失败: " + e.getMessage());
        }
    }

    /**
     * 优化表达
     */
    @Override
    public String optimizeContent(String content, String target) {
        log.info("优化内容：contentLength={}, target={}", content.length(), target);
        String trunc = truncateContent(content, 4000);

        String targetDesc = getOptimizationDescription(target);
        String prompt = String.format("""
                请优化以下文档内容，要求：
                1. 优化重点：%s
                2. 保持原文档的核心主旨与关键信息
                3. 优化后的内容要更加专业、精炼、结构清晰、易于阅读
                4. 直接输出润色后的优质文本，文字精炼，避免冗长说明与额外解释

                原文档内容：
                %s
                """, targetDesc, trunc);

        try {
            UserMessage userMessage = UserMessage.from(prompt);
            Response<AiMessage> response = modelProvider.getDefaultModel().generate(userMessage);
            return response.content().text().trim();
        } catch (Exception e) {
            log.error("优化内容失败：{}", e.getMessage(), e);
            throw new RuntimeException("优化内容失败: " + e.getMessage());
        }
    }

    /**
     * 添加示例
     */
    @Override
    public String addExample(String content, String expType) {
        log.info("添加示例：contentLength={}, expType={}", content.length(), expType);
        String trunc = truncateContent(content, 4000);

        String typeDesc = getExampleDescription(expType);
        String prompt = String.format("""
                请为以下文档内容提供针对性的精选示例补充：
                要求：
                1. 示例类型：%s
                2. 示例要与文档主题紧密相关，具体实用、格式规范
                3. 请直接输出新增的示例内容模块（包含示例标题、适用场景说明与详细示例内容）
                4. 无需机械抄写全部原文档，直接返回可供直接追加或插入的完整示例模块
                5. 不要添加任何额外的客套解释

                原文档内容：
                %s
                """, typeDesc, trunc);

        try {
            UserMessage userMessage = UserMessage.from(prompt);
            Response<AiMessage> response = modelProvider.getDefaultModel().generate(userMessage);
            return response.content().text().trim();
        } catch (Exception e) {
            log.error("添加示例失败：{}", e.getMessage(), e);
            throw new RuntimeException("添加示例失败: " + e.getMessage());
        }
    }

    private String getTypeDescription(String expType) {
        return switch (expType != null ? expType.toLowerCase() : "") {
            case "detail" -> "详细阐述每个要点，增加更多细节和说明";
            case "example" -> "为关键概念添加具体的示例说明";
            case "theory" -> "添加相关的理论依据和参考文献";
            case "practice" -> "补充实践案例和应用场景";
            default -> "扩展内容，使其更加完整和丰富";
        };
    }

    private String getOptimizationDescription(String target) {
        return switch (target != null ? target.toLowerCase() : "") {
            case "readability" -> "提高可读性与易懂性（语言通俗简练、句式流畅、适当分段）";
            case "professional" -> "提升专业度与权威性（规范专业术语、严谨格式结构、正式书面语风格）";
            case "seo" -> "优化结构与关键词（合理布局核心关键词、提升主题相关度、优化章节标题）";
            default -> "全面提升整体质量（润色语言表达、改善文档结构、增强信息传递效果）";
        };
    }

    private String getExampleDescription(String expType) {
        return switch (expType != null ? expType.toLowerCase() : "") {
            case "code" -> "代码示例（包含详细行内注释与规范的代码结构）";
            case "case" -> "业务真实落地案例（具有代表性并附带详细情境说明）";
            case "data" -> "数据指标与对照示例（数据真实可信、有理有据并标明来源）";
            default -> "具象化实例（紧密贴合文档主题与论点）";
        };
    }

    /**
     * 内容截断：超长文档取头尾拼接，减少LLM token消耗
     */
    private String truncateContent(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        int headLen = (int) (maxChars * 0.75);
        int tailLen = maxChars - headLen;
        String head = content.substring(0, headLen);
        String tail = content.substring(content.length() - tailLen);
        return head + "\n\n...\n\n" + tail;
    }

    /**
     * 按句号/换行切分文本为句子块，用于流式逐句推送
     */
    private List<String> chunkBySentence(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        StringBuilder current = new StringBuilder();
        for (char c : text.toCharArray()) {
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '.' || c == '!' || c == '?') {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                }
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

}
