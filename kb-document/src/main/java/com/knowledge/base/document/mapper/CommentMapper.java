package com.knowledge.base.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledge.base.document.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 评论Mapper接口
 *
 * <p>查询统一使用 {@code LambdaQueryWrapper}，以便列名跟随实体上的 {@code @TableField} 映射；
 * 本接口只声明需要数据库端原子完成的计数增减，SQL 见 {@code CommentMapper.xml}。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 点赞数 +1
     *
     * @param commentId 评论ID
     * @return 影响行数
     */
    int incrementLikeCount(@Param("commentId") Long commentId);

    /**
     * 点赞数 -1，下限为 0
     *
     * @param commentId 评论ID
     * @return 影响行数
     */
    int decrementLikeCount(@Param("commentId") Long commentId);

    /**
     * 回复数 +1
     *
     * <p>回复数只在根评论上维护，调用方需传入根评论ID（即 {@code Comment.rootId}）。</p>
     *
     * @param commentId 根评论ID
     * @return 影响行数
     */
    int incrementReplyCount(@Param("commentId") Long commentId);

    /**
     * 回复数 -1，下限为 0
     *
     * @param commentId 根评论ID
     * @return 影响行数
     */
    int decrementReplyCount(@Param("commentId") Long commentId);
}
