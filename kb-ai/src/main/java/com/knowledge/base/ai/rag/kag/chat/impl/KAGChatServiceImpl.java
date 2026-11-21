package com.knowledge.base.ai.rag.kag.chat.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowledge.base.ai.config.ModelProvider;
import com.knowledge.base.ai.dto.ChatRequestDTO;
import com.knowledge.base.ai.entity.Conversation;
import com.knowledge.base.ai.entity.Message;
import com.knowledge.base.ai.mapper.MessageMapper;
import com.knowledge.base.ai.rag.kag.chat.KAGChatService;
import com.knowledge.base.ai.rag.kag.retrieval.GraphContext;
import com.knowledge.base.ai.rag.kag.retrieval.HybridRetrievalService;
import com.knowledge.base.ai.service.AiConversationService;
import com.knowledge.base.ai.vo.ChatResponseVO;
import com.knowledge.base.ai.vo.CitationVO;
import com.knowledge.base.ai.vo.RagSearchResultVO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @description: KAG 增强对话服务实现
 * <p>融合 RAG 文本检索和 KAG 图谱推理，构建包含结构化知识上下文和
 * 文档片段的增强提示词，生成有据可查的AI回答。</p>
 *
 * <p>与 {@code RagChatServiceImpl} 保持一致的会话契约：
 * <ul>
 *   <li>创建/校验对话，落库用户消息与AI回复，累计 Token</li>
 *   <li>携带最近 20 条历史消息，支持多轮对话记忆</li>
 *   <li>流式接口按 token 推送 message 事件，结束时推送携带
 *       conversationId / messageId / citations / graphContext 的 done 事件</li>
 * </ul>
 * 这样前端 {@code /api/ai/chat/stream} 打开「图谱推理」开关后，
 * 拿到的响应结构与「知识库增强」完全一致，只是多了 graphContext。</p>
 * @author: Winston
 * @date: 2026/11/20 13:17
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KAGChatServiceImpl implements KAGChatService {

    private final HybridRetrievalService hybridRetrievalService;
    private final ModelProvider modelProvider;
    private final MessageMapper messageMapper;
    private final AiConversationService conversationService;

    @Resource
    private ThreadPoolTaskExecutor asyncTaskExecutor;

    /** {@inheritDoc} */
    // 非流式
    @Override
    public ChatResponseVO chatWithKnowledgeGraph(ChatRequestDTO requestDTO, Long userId) {
        log.info("KAG chat started: query='{}', userId={}", requestDTO.getContent(), userId);
        String query = requestDTO.getContent();
        String modelName = resolveModelName(requestDTO.getModel());
        int topK = 5;
        boolean enableRerank = true;
        boolean enableKAG = requestDTO.isEnableKag();

        // 1.创建会话，有就复用
        Long conversationId = prepareConversation(requestDTO, userId);
        // 2.调用KAG和RAG混合检索
        HybridRetrievalService.HybridResult hybridResult = safeRetrieveHybrid(query, topK, enableRerank, enableKAG);
        // 3.构建增强提示词（KAG + RAG）
        String prompt = buildKAGPrompt(query, hybridResult);
        // 4.构建上下文提示词，提取最近20条历史对话
        List<ChatMessage> chatMessages = buildChatHistory(conversationId);

        // 5.保存用户query到Message
        Message userMsgEntity = Message.builder()
                .conversationId(conversationId)
                .role("user")
                .content(query)
                .tokens(estimateTokens(query))
                .build();
        messageMapper.insert(userMsgEntity);
        // 6.构建模型调用LLM
        chatMessages.add(UserMessage.from(prompt));
        ChatLanguageModel model = getModel(modelName);
        String answer = model.generate(chatMessages).content().text();
        // 7.构建引用

        List<CitationVO> citations = buildCitations(hybridResult.fusedResults());
        GraphContext graphContext = hybridResult.kagContext();
        // 8.响应结果
        // Step 7: 持久化 AI 回复并累计 Token
        Message aiMsgEntity = Message.builder()
                .conversationId(conversationId)
                .role("assistant")
                .content(answer)
                .tokens(estimateTokens(answer))
                .build();
        messageMapper.insert(aiMsgEntity);
        conversationService.updateTokens(conversationId,
                userMsgEntity.getTokens() + aiMsgEntity.getTokens());

        Conversation conv = conversationService.getById(conversationId);

        // Step 8: Build response
        ChatResponseVO response = ChatResponseVO.builder()
                .conversationId(conversationId)
                .messageId(aiMsgEntity.getId())
                .content(answer)
                .tokens(userMsgEntity.getTokens() + aiMsgEntity.getTokens())
                .title(conv != null ? conv.getTitle() : "新对话")
                .citations(citations)
                .fromKnowledgeBase(isFromKnowledgeBase(hybridResult))
                .graphContext(graphContext)
                .build();

        log.info("KAG chat completed: conversationId={}, hasGraph={}, citations={}",
                conversationId, hybridResult.knowledgeGraphEnhanced(), citations.size());
        return response;
    }

    /** {@inheritDoc} */
    @Override
    public SseEmitter chatWithKnowledgeGraphStream(ChatRequestDTO requestDTO, Long userId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30min timeout

        try {
            String query = requestDTO.getContent();
            String modelName = resolveModelName(requestDTO.getModel());
            boolean enableKAG = requestDTO.isEnableKag();

            // 1. 创建/校验对话
            Long conversationId = prepareConversation(requestDTO, userId);

            // 2. 混合检索（RAG + KAG）
            HybridRetrievalService.HybridResult hybridResult = safeRetrieveHybrid(query, 5, true, enableKAG);
            String prompt = buildKAGPrompt(query, hybridResult);

            // 3. 在异步执行前准备好不可变入参
            final Long finalConversationId = conversationId;
            final List<CitationVO> citations = buildCitations(hybridResult.fusedResults());
            final GraphContext graphContext = hybridResult.kagContext();
            final boolean fromKnowledgeBase = isFromKnowledgeBase(hybridResult);

            CompletableFuture.runAsync(() -> {
                try {
                    StreamingChatLanguageModel streamingModel =
                            modelProvider.getStreamingModel(modelName);

                    // 构建历史上下文，实现多轮对话记忆
                    List<ChatMessage> chatMessages = buildChatHistory(finalConversationId);

                    // 先保存用户消息
                    Message userMsgEntity = Message.builder()
                            .conversationId(finalConversationId)
                            .role("user")
                            .content(query)
                            .tokens(estimateTokens(query))
                            .build();
                    messageMapper.insert(userMsgEntity);

                    chatMessages.add(UserMessage.from(prompt));

                    StringBuilder fullResponseBuilder = new StringBuilder();

                    streamingModel.generate(chatMessages, new StreamingResponseHandler<AiMessage>() {
                        @Override
                        public void onNext(String token) {
                            fullResponseBuilder.append(token);
                            try {
                                emitter.send(SseEmitter.event().name("message").data(token));
                            } catch (IOException e) {
                                log.warn("KAG流式：发送token失败（客户端可能已断开）: {}", e.getMessage());
                            }
                        }

                        @Override
                        public void onComplete(Response<AiMessage> response) {
                            try {
                                String fullAnswer = fullResponseBuilder.toString();

                                Message aiMsgEntity = Message.builder()
                                        .conversationId(finalConversationId)
                                        .role("assistant")
                                        .content(fullAnswer)
                                        .tokens(estimateTokens(fullAnswer))
                                        .build();
                                messageMapper.insert(aiMsgEntity);

                                AtomicInteger totalTokens = new AtomicInteger(userMsgEntity.getTokens());
                                totalTokens.addAndGet(aiMsgEntity.getTokens());
                                conversationService.updateTokens(finalConversationId, totalTokens.get());

                                Conversation conv = conversationService.getById(finalConversationId);

                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(ChatResponseVO.builder()
                                                .conversationId(finalConversationId)
                                                .messageId(aiMsgEntity.getId())
                                                .content(fullAnswer)
                                                .tokens(totalTokens.get())
                                                .title(conv != null ? conv.getTitle() : "新对话")
                                                .citations(citations)
                                                .fromKnowledgeBase(fromKnowledgeBase)
                                                .graphContext(graphContext)
                                                .build()));
                                emitter.complete();

                                log.info("KAG流式对话完成：conversationId={}, citations={}, hasGraph={}",
                                        finalConversationId, citations.size(),
                                        graphContext != null && graphContext.isHasResults());
                            } catch (IOException e) {
                                log.warn("KAG流式：发送完成事件失败（客户端可能已断开）: {}", e.getMessage());
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            log.error("KAG流式对话失败：{}", error.getMessage(), error);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(error.getMessage() != null ? error.getMessage() : "AI服务调用失败"));
                                emitter.complete();
                            } catch (IOException ioException) {
                                log.error("KAG流式：发送错误事件失败：{}", ioException.getMessage());
                            }
                        }
                    });

                } catch (Exception e) {
                    log.error("KAG流式对话失败：{}", e.getMessage(), e);
                    try {
                        emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                        emitter.complete();
                    } catch (IOException ioException) {
                        log.error("KAG流式：发送错误事件失败：{}", ioException.getMessage());
                    }
                }
            }, asyncTaskExecutor);

        } catch (Exception e) {
            log.error("创建KAG SSE发射器失败：{}", e.getMessage(), e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    // ==================== Private Methods ====================

    /**
     * 是否命中了知识库（文档片段或图谱任一命中即视为来自知识库）
     */
    private boolean isFromKnowledgeBase(HybridRetrievalService.HybridResult hybridResult) {
        boolean hasChunks = hybridResult.fusedResults() != null && !hybridResult.fusedResults().isEmpty();
        GraphContext ctx = hybridResult.kagContext();
        boolean hasGraph = ctx != null && ctx.isHasResults();
        return hasChunks || hasGraph;
    }

    private List<CitationVO> buildCitations(List<RagSearchResultVO> results) {
        if (results == null || results.isEmpty()) return List.of();

        List<CitationVO> citations = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            RagSearchResultVO result = results.get(i);
            String excerpt = result.getContent() != null &&
                    result.getContent().length() > 100
                    ? result.getContent().substring(0, 100) + "..."
                    : result.getContent();

            citations.add(CitationVO.builder()
                    .index(i + 1)
                    .documentId(result.getDocumentId())
                    .documentTitle(result.getDocumentTitle())
                    .excerpt(excerpt)
                    .relevanceScore(result.getScore())
                    .build());
        }
        return citations;
    }

    private ChatLanguageModel getModel(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            return modelProvider.getModel(modelName);
        }
        return modelProvider.getDefaultModel();
    }
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chineseChars = text.replaceAll("[^\\u4e00-\\u9fa5]", "").length();
        int otherChars = text.length() - chineseChars;
        return chineseChars + (otherChars / 4);
    }
    /**
     * 加载最近 20 条历史消息，构建 LangChain4j 消息上下文（多轮对话记忆）
     */
    private List<ChatMessage> buildChatHistory(Long conversationId) {
        LambdaQueryWrapper<Message> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getCreatedAt)
                .last("LIMIT 20");
        List<Message> historyMessages = messageMapper.selectList(queryWrapper);
        Collections.reverse(historyMessages);

        List<ChatMessage> chatMessages = new ArrayList<>();
        for (Message msg : historyMessages) {
            if ("user".equals(msg.getRole())) {
                chatMessages.add(UserMessage.from(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                chatMessages.add(AiMessage.from(msg.getContent()));
            }
        }
        return chatMessages;
    }
    /**
     * 安全执行混合检索：检索异常时降级为空结果，保证仍能给出普通LLM回答
     * （与 {@code RagChatServiceImpl#safeRetrieve} 的降级策略一致）
     */
    private HybridRetrievalService.HybridResult safeRetrieveHybrid(String query, int topK,
                                                                   boolean enableRerank, boolean enableKAG) {
        try {
            return hybridRetrievalService.retrieveHybrid(query, topK, enableRerank, enableKAG);
        } catch (Exception e) {
            log.warn("KAG混合检索失败，降级为纯LLM回答：{}", e.getMessage());
            return new HybridRetrievalService.HybridResult(List.of(), null, List.of(), false);
        }
    }
    /**
     * 准备对话（创建新对话或校验已有对话归属）
     */
    private Long prepareConversation(ChatRequestDTO requestDTO, Long userId) {
        ChatRequestDTO createDTO = ChatRequestDTO.builder()
                .content(requestDTO.getContent())
                .model(requestDTO.getModel())
                .systemPrompt(requestDTO.getSystemPrompt())
                .build();

        if (requestDTO.getConversationId() == null) {
            return conversationService.createConversation(createDTO, userId);
        }
        Conversation existing = conversationService.getById(requestDTO.getConversationId());
        if (existing == null || !existing.getUserId().equals(userId)) {
            log.warn("KAG对话：对话不存在或无权访问，创建新对话：conversationId={}, userId={}",
                    requestDTO.getConversationId(), userId);
            return conversationService.createConversation(createDTO, userId);
        }
        return existing.getId();
    }

    /**
     * 构建KAG增强Prompt（包含知识图谱推理路径 + 文档片段）
     */
    private String buildKAGPrompt(String query, HybridRetrievalService.HybridResult hybridResult) {
        StringBuilder sb = new StringBuilder();

        // System instructions
        sb.append("你是一个企业知识库助手。请综合参考以下两类知识来回答用户问题。\n");
        sb.append("回答时，请注意以下规则：\n");
        sb.append("1. 优先基于知识图谱中最直接的推理路径进行解答\n");
        sb.append("2. 参考文档片段提供具体细节和验证\n");
        sb.append("3. 引用参考文档时标注编号 [1]、[2]\n");
        sb.append("4. 如果知识库中没有相关信息，请明确说明\n\n");

        // KAG Graph Context
        GraphContext kagContext = hybridResult.kagContext();
        if (kagContext != null && kagContext.isHasResults()) {
            // Matched entities
            if (kagContext.getMatchedEntities() != null && !kagContext.getMatchedEntities().isEmpty()) {
                sb.append("=== 知识图谱检索到的关键实体 ===\n");
                for (GraphContext.GraphEntity entity : kagContext.getMatchedEntities()) {
                    sb.append(String.format("- %s [%s] %s（关联%d个实体）\n",
                            entity.getName(), entity.getType(),
                            entity.getDescription() != null ? "- " + entity.getDescription() : "",
                            entity.getConnectionCount()));
                }
                sb.append("\n");
            }

            // Reasoning paths
            if (kagContext.getReasoningPaths() != null && !kagContext.getReasoningPaths().isEmpty()) {
                sb.append("=== 知识图谱推理路径 ===\n");
                int pathIdx = 1;
                for (GraphContext.GraphPath path : kagContext.getReasoningPaths()) {
                    if (path.getNodes() == null || path.getNodes().size() < 2) continue;
                    sb.append("[路径").append(pathIdx).append("] ");
                    for (int i = 0; i < path.getNodes().size(); i++) {
                        sb.append(path.getNodes().get(i));
                        if (i < path.getRelations().size()) {
                            sb.append(" →(").append(path.getRelations().get(i)).append(")→ ");
                        }
                    }
                    sb.append("（").append(path.getHops()).append("跳）\n");
                    pathIdx++;
                }
                sb.append("\n");
            }
        }

        // RAG/KAG text chunks
        List<RagSearchResultVO> fusedResults = hybridResult.fusedResults();
        if (fusedResults != null && !fusedResults.isEmpty()) {
            sb.append("=== 相关文档片段 ===\n");
            for (int i = 0; i < fusedResults.size(); i++) {
                RagSearchResultVO chunk = fusedResults.get(i);
                sb.append(String.format("[%d] 来源文档：%s", i + 1, chunk.getDocumentTitle()));
                if (chunk.getHeading() != null && !chunk.getHeading().isEmpty()) {
                    sb.append(" | 章节：").append(chunk.getHeading());
                }
                sb.append(String.format(" | 相关度：%.2f", chunk.getScore()));
                sb.append("\n").append(chunk.getContent()).append("\n\n");
            }
        }

        // User query
        sb.append("=== 用户问题 ===\n").append(query);

        return sb.toString();
    }

    /**
     * 解析实际使用的模型名，未显式指定时使用默认模型
     */
    private String resolveModelName(String modelName) {
        return modelName != null && !modelName.isBlank()
                ? modelName
                : modelProvider.getDefaultModelName();
    }
}
