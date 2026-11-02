package com.knowledge.base.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.knowledge.base.common.config.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 评论实体
 *
 * @author 苏三
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tb_comment")
@Schema(description = "评论实体")
public class Comment extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 评论ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "评论ID")
    private Long id;

    /**
     * 文档ID
     */
    @Schema(description = "文档ID")
    private Long documentId;

    /**
     * 父评论ID（回复链，精确指向被回复的那条评论）
     *
     * <p>顶层评论为 0。</p>
     */
    @Schema(description = "父评论ID")
    private Long parentId;

    /**
     * 根评论ID（所属树的根）
     *
     * <p>顶层评论等于自身 id，而非 null——这样 {@code WHERE root_id = ?}
     * 一条查询即可取到完整线程（含根），不必再与根评论结果做合并。</p>
     *
     * <p>与 {@code parentId} 的分工：{@code parentId} 表达「我回复的是哪一条」，
     * {@code rootId} 表达「我属于哪棵树」，后者用于一次捞出整棵子树，避免递归查询。</p>
     */
    @Schema(description = "根评论ID")
    private Long rootId;

    /**
     * 评论内容
     */
    @Schema(description = "评论内容")
    private String content;

    /**
     * 评论人ID
     */
    @TableField("user_id")
    @Schema(description = "评论人ID")
    private Long commenterId;

    /**
     * 评论人姓名
     */
    @TableField("user_name")
    @Schema(description = "评论人姓名")
    private String commenterName;

    /**
     * 评论人头像
     */
    @TableField("user_avatar")
    @Schema(description = "评论人头像")
    private String commenterAvatar;

    /**
     * 回复给谁（用户ID）
     */
    @TableField("reply_to_id")
    @Schema(description = "回复给谁")
    private Long replyToUserId;

    /**
     * 回复给谁（用户姓名）
     */
    @TableField("reply_to_name")
    @Schema(description = "回复给谁")
    private String replyToUserName;

    /**
     * 状态：0-隐藏，1-正常
     */
    @Schema(description = "状态")
    private Integer status;

    /**
     * 点赞数
     */
    @Schema(description = "点赞数")
    private Integer likeCount;

    /**
     * 回复数
     */
    @Schema(description = "回复数")
    private Integer replyCount;

    /**
     * 删除标记
     */
    @TableLogic
    @Schema(description = "删除标记")
    private Integer deleted;
}
