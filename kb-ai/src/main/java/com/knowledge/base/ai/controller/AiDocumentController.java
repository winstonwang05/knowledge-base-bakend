package com.knowledge.base.ai.controller;

import com.knowledge.base.ai.service.AiDocumentService;
import com.knowledge.base.common.result.Result;
import com.knowledge.base.common.utils.UserContextUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * AI文档处理控制器
 *
 * <p>为前端提供文档写作辅助（大纲、扩写、优化表达、添加示例）及文档摘要生成端点。</p>
 *
 * @author Winston
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/document")
@RequiredArgsConstructor
@Tag(name = "AI文档处理", description = "AI文档处理相关接口")
public class AiDocumentController {

    private final AiDocumentService aiDocumentService;

    /**
     * 生成文档摘要（非流式）
     */
    @PostMapping("/summary")
    @Operation(summary = "生成文档摘要", description = "传入文档内容生成摘要")
    public Result<Map<String, String>> generateSummary(@RequestBody Map<String, Object> params) {
        String content = (String) params.get("content");
        if (content == null || content.trim().isEmpty()) {
            return Result.error(400, "文档内容不能为空");
        }
        String title = (String) params.get("title");
        Integer length = params.get("length") != null ? Integer.valueOf(params.get("length").toString()) : 200;
        String summary = aiDocumentService.generateSummary(content, title, length);
        Map<String, String> data = new HashMap<>();
        data.put("content", summary);
        return Result.success("生成摘要成功", data);
    }

    /**
     * 流式生成文档摘要
     */
    @PostMapping("/summary/stream")
    @Operation(summary = "流式生成摘要", description = "传入文档内容流式生成摘要")
    public SseEmitter generateSummaryStream(
            @RequestBody Map<String, Object> params,
            HttpServletRequest request) {
        String content = (String) params.get("content");
        String title = (String) params.get("title");
        Integer length = params.get("length") != null ? Integer.valueOf(params.get("length").toString()) : 200;
        Long userId = UserContextUtil.getUserIdFromHeader(request);
        return aiDocumentService.generateSummaryStream(content, title, length, userId);
    }

    /**
     * 生成文档大纲
     */
    @PostMapping("/outline")
    @Operation(summary = "生成文档大纲", description = "传入文档内容或标题生成层级大纲")
    public Result<Map<String, String>> generateOutline(@RequestBody Map<String, Object> params) {
        String content = (String) params.get("content");
        if (content == null || content.trim().isEmpty()) {
            return Result.error(400, "文档内容或标题不能为空");
        }
        Integer level = params.get("level") != null ? Integer.valueOf(params.get("level").toString()) : 2;
        String outline = aiDocumentService.generateOutline(content, level);
        Map<String, String> data = new HashMap<>();
        data.put("content", outline);
        return Result.success("生成大纲成功", data);
    }

    /**
     * 扩展文档内容
     */
    @PostMapping("/expand")
    @Operation(summary = "扩展文档内容", description = "传入文档内容进行深度扩写")
    public Result<Map<String, String>> expandContent(@RequestBody Map<String, Object> params) {
        String content = (String) params.get("content");
        if (content == null || content.trim().isEmpty()) {
            return Result.error(400, "文档内容不能为空");
        }
        String expType = params.get("expType") != null ? (String) params.get("expType") : "detail";
        String expanded = aiDocumentService.expandContent(content, expType);
        Map<String, String> data = new HashMap<>();
        data.put("content", expanded);
        return Result.success("扩展内容成功", data);
    }

    /**
     * 优化文档表达
     */
    @PostMapping("/optimize")
    @Operation(summary = "优化表达", description = "传入文档内容优化表达与润色")
    public Result<Map<String, String>> optimizeContent(@RequestBody Map<String, Object> params) {
        String content = (String) params.get("content");
        if (content == null || content.trim().isEmpty()) {
            return Result.error(400, "文档内容不能为空");
        }
        String target = params.get("target") != null ? (String) params.get("target") : "readability";
        String optimized = aiDocumentService.optimizeContent(content, target);
        Map<String, String> data = new HashMap<>();
        data.put("content", optimized);
        return Result.success("优化表达成功", data);
    }

    /**
     * 添加示例
     */
    @PostMapping("/example")
    @Operation(summary = "添加示例", description = "传入文档内容提供具象化示例增补")
    public Result<Map<String, String>> addExample(@RequestBody Map<String, Object> params) {
        String content = (String) params.get("content");
        if (content == null || content.trim().isEmpty()) {
            return Result.error(400, "文档内容不能为空");
        }
        String expType = params.get("expType") != null ? (String) params.get("expType") : "code";
        String result = aiDocumentService.addExample(content, expType);
        Map<String, String> data = new HashMap<>();
        data.put("content", result);
        return Result.success("添加示例成功", data);
    }
}
