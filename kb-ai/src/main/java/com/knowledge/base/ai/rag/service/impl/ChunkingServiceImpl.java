package com.knowledge.base.ai.rag.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.knowledge.base.ai.config.RagProperties;
import com.knowledge.base.ai.rag.entity.DocumentChunk;
import com.knowledge.base.ai.rag.service.ChunkingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description: 文档切块服务类
 * <p>采用段落感知 + 固定大小的混合分块策略：
 * <ol>
 *   <li>按 Markdown 标题（# ## ###）划分章节</li>
 *   <li>每个章节内按空行（\n\n）分段落</li>
 *   <li>段落按token数合并/分割，达到目标chunkSize</li>
 *   <li>每个块包含最近的标题作为上下文</li>
 * </ol></p>
 * @author: Winston
 * @date: 2026/11/14 10:37
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingServiceImpl implements ChunkingService {

    private final RagProperties ragProperties;

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /** 约1个token = 0.75个中文字符，取保守估计 1 token ≈ 2 字符（中英文混合） */
    private static final double CHARS_PER_TOKEN = 2.0;

    /** {@inheritDoc} */
    @Override
    public List<DocumentChunk> chunk(String content, Long documentId, String documentTitle,
                                     Long categoryId, Long authorId, Long teamId, Integer docStatus,
                                     String publishTime) {
        if (content == null || content.isEmpty()) {
            log.warn("文档内容为空，跳过分块：documentId={}", documentId);
            return List.of();
        }
        // 1.先进行章节拆分，然后遍历每一个章节的内容
        int chunkSize = ragProperties.getChunking().getChunkSize();
        int chunkOverlap = ragProperties.getChunking().getChunkOverlap();
        int maxChars = (int) (chunkSize * CHARS_PER_TOKEN);
        int overlapChars = (int) (chunkOverlap * CHARS_PER_TOKEN);

        List<Section> sections = splitByHeadings(content);
        List<DocumentChunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            // 2.根据每一个章节再次进行空行拆分
            List<String> paragraphs = splitParagraphs(section.content);
            // 3.再进行1024标准拆分的结果遍历进行叠加操作
            List<String> merged = mergeParagraphs(paragraphs, maxChars);
            for (int i = 0; i < merged.size(); i++) {
                String chunkContent = (section.heading != null ? section.heading + "\n\n" : "") + merged.get(i);
                // 添加重叠
                if (i > 0 && overlapChars > 0) {
                    String prevContent = merged.get(i - 1);
                    if (prevContent.length() > overlapChars) {
                        chunkContent = prevContent.substring(prevContent.length() - overlapChars) + "\n" + chunkContent;
                    }
                }
                chunks.add(DocumentChunk.builder()
                        .chunkId(DigestUtil.sha256Hex(documentId + ":" + chunks.size()))
                        .documentId(documentId)
                        .documentTitle(documentTitle)
                        .content(chunkContent)
                        .heading(section.heading)
                        .chunkIndex(chunks.size())
                        .categoryId(categoryId)
                        .authorId(authorId)
                        .teamId(teamId)
                        .docStatus(docStatus)
                        .publishTime(publishTime)
                        .build());
            }



        }

        // 设置 totalChunks
        final int total = chunks.size();
        chunks.forEach(c -> c.setTotalChunks(total));

        log.debug("文档分块完成：documentId={}, totalChunks={}", documentId, total);
        return chunks;


    }


    /**
     * 按MarkDown标题划分章节，通过表达式匹配 #
     * @param content 文档内容
     * @return 返回每一个章节
     */
    private List<Section> splitByHeadings(String content) {
        Matcher matcher = HEADING_PATTERN.matcher(content);
        List<Section> sections = new ArrayList<>();

        int end = 0;
        String currentHeading = null;
        // 1.匹配，如果找到了，封装Section
        while (matcher.find()) {
            // 循环结束的条件就是找不到下一个标题
            if (matcher.start() > end) {
                // 截取
                String sectionContent = content.substring(end, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    sections.add(new Section(currentHeading, sectionContent));
                }
            }
            currentHeading = matcher.group(2);
            end = matcher.end();
        }
        // 2.如果是最后一段，直接通过上一段末尾截取到末尾
        if (end < content.length()) {
            String sectionContent = content.substring(end).trim();
            if (!sectionContent.isEmpty()) {
                sections.add(new Section(currentHeading, sectionContent));
            }
        }
        // 3.如果完全匹配不到 #，直接封装整个内容
        if (sections.isEmpty()) {
            sections.add(new Section(null, content.trim()));
        }

        return sections;
    }

    /**
     * 将超长段落按句子分割
     */
    private List<String> splitLongParagraph(String paragraph, int maxChars) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // 按句号、问号、感叹号、换行分割
        String[] sentences = paragraph.split("(?<=[。！？\\.!?\\n])");

        // 1.遍历
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 2.如果当前句加上已有的大于1024，且已有的长度大于0，那么直接当前句作为current
            if (trimmed.length() + current.length() > maxChars) {
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                    current = new StringBuilder();
                }
                // 3.如果本身句就大于1024，硬切
                if (trimmed.length() > maxChars) {
                    for (int i = 0; i < trimmed.length(); i += maxChars) {
                        int end = Math.min(i + maxChars, trimmed.length());
                        result.add(trimmed.substring(i, end));
                    }
                } else {
                    current = new StringBuilder(trimmed);
                }
            } else {
                // 4.如果小于，那么拼接
                if (current.length() > 0) {
                    current.append(" ");
                }
                current.append(trimmed);
            }

        }

        if (current.length() > 0) {
            result.add(current.toString().trim());
        }

        return result;

    }

    /**
     * 按空行分段落,视觉效果就是中间空一行
     */
    private List<String> splitParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        for (String para : content.split("\n\n")) {
            String trimmed = para.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    /**
     * 合并段落达到目标大小
     * 以1024为基准，如果上一个段落加上当前段落大于标准，那么将上一个段落收尾，新的段落重新开始拼接，宁可少不可多
     */
    private List<String> mergeParagraphs(List<String> paragraphs, int maxChars) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // 1.遍历每一个段落进行拼接
        for (String para : paragraphs) {
            // 2.如果段落本身就大于1024，将当前拼接的先放入结果集，然后进行更详细的句子分割
            if (para.length() > maxChars) {
                // 前提是current是存在的
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                    current = new StringBuilder();
                }
                // 进行详细拆分
                List<String> subParts = splitLongParagraph(para, maxChars);
                result.addAll(subParts);
                continue;
            }
            // 3.如果拼接大于1024，新的段落重新拼接
            if (current.length() + para.length() + 2 > maxChars) {
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                }
                current = new StringBuilder(para);
            } else {
                // 4.反之直接拼接后添加到结果集
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(para);
            }
        }

        if (current.length() > 0) {
            result.add(current.toString().trim());
        }

        return result;

    }

    /**
     * 章节内部类
     */
    private record Section(String heading, String content) {}
}
