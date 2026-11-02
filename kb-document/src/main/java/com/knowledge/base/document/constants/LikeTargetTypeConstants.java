package com.knowledge.base.document.constants;

/**
 * 点赞目标类型常量。
 *
 * <p>对应 {@code tb_like.target_type} 的取值语义，集中维护避免在文档、评论等
 * 多个服务里分散硬编码同一个魔法值。</p>
 *
 * @author Winston
 * @since 1.0.0
 */
public final class LikeTargetTypeConstants {

    private LikeTargetTypeConstants() {
    }

    /**
     * 目标为文档（kb_document.id）。
     */
    public static final Integer DOCUMENT = 1;

    /**
     * 目标为评论（tb_comment.id）。
     */
    public static final Integer COMMENT = 2;
}
