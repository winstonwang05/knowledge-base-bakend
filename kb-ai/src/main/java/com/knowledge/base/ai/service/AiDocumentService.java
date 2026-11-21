package com.knowledge.base.ai.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI文档处理服务接口
 *
 * <p>提供纯文本粒度的文档智能加工能力：大纲生成、内容扩写、表达优化、示例添加与文档摘要。</p>
 *
 * @author Winston
 * @since 1.0.0
 */
public interface AiDocumentService {

    /**
     * 生成文档摘要（非流式）
     *
     * @param content 文档内容
     * @param title   文档标题（可为空）
     * @param length  摘要目标字数
     * @return 摘要文本
     */
    String generateSummary(String content, String title, Integer length);

    /**
     * 流式生成文档摘要
     *
     * @param content 文档内容
     * @param title   文档标题（可为空）
     * @param length  摘要目标字数
     * @param userId  操作用户ID
     * @return SSE 事件发射器
     */
    SseEmitter generateSummaryStream(String content, String title, Integer length, Long userId);

    /**
     * 生成文档大纲
     *
     * @param content 文档内容或标题
     * @param level   大纲详细层级（1~3级）
     * @return Markdown 格式的结构化大纲
     */
    String generateOutline(String content, Integer level);

    /**
     * 扩展内容
     *
     * @param content 文档正文
     * @param expType 扩展类型（detail/example/theory/practice）
     * @return 深度扩写后的正文模块
     */
    String expandContent(String content, String expType);

    /**
     * 优化表达
     *
     * @param content 文档正文
     * @param target  优化目标（readability/professional/seo）
     * @return 润色优化后的精炼文本
     */
    String optimizeContent(String content, String target);

    /**
     * 添加示例
     *
     * @param content 文档正文
     * @param expType 示例类型（code/case/data）
     * @return 针对性增补的示例模块
     */
    String addExample(String content, String expType);
}
