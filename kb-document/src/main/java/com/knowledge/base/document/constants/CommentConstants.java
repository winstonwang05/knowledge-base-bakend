package com.knowledge.base.document.constants;

/**
 * 评论域常量。
 *
 * @author Winston
 * @since 1.0.0
 */
public final class CommentConstants {

    private CommentConstants() {
    }

    /**
     * 评论状态：正常可见（tb_comment.status）。
     */
    public static final Integer STATUS_NORMAL = 1;

    /**
     * 顶层评论的 parent_id 取值（tb_comment.parent_id NOT NULL DEFAULT 0）。
     *
     * <p>注意不是 null：顶层评论用 {@code root_id} 等于自身 id 来表达归属，
     * 用 {@code parent_id = 0} 表达「没有父评论」。</p>
     */
    public static final Long ROOT_PARENT_ID = 0L;
}
