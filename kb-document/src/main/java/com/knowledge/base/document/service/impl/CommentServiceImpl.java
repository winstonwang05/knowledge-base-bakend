package com.knowledge.base.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.knowledge.base.common.exception.BusinessException;
import com.knowledge.base.common.result.PageResult;
import com.knowledge.base.common.utils.SnowflakeIdGenerator;
import com.knowledge.base.document.constants.CommentConstants;
import com.knowledge.base.document.constants.LikeTargetTypeConstants;
import com.knowledge.base.document.dto.CommentCreateDTO;
import com.knowledge.base.document.dto.CommentQueryDTO;
import com.knowledge.base.document.entity.Comment;
import com.knowledge.base.document.entity.Document;
import com.knowledge.base.document.entity.Like;
import com.knowledge.base.document.mapper.CommentMapper;
import com.knowledge.base.document.mapper.DocumentMapper;
import com.knowledge.base.document.mapper.LikeMapper;
import com.knowledge.base.document.service.CommentService;
import com.knowledge.base.document.service.LikeService;
import com.knowledge.base.document.utils.UserContext;
import com.knowledge.base.document.vo.CommentVO;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @description: 评论服务
 * <p>数据访问统一走 Mapper 层：查询用 {@code LambdaQueryWrapper}，列名跟随实体
 * {@code @TableField} 映射；需要数据库端原子完成的计数增减用 XML 中的 UPDATE。
 * 不要改成在 Java 里拼 SQL 字符串——那会绕开 {@code @TableLogic} 与实体列名映射，
 * 列名一旦与表结构脱节，只有运行时才会暴露。</p>
 *
 * <p><b>评论层级：链式 + root_id</b>，两列分工明确：</p>
 * <ul>
 *   <li>{@code parent_id}——回复链，精确指向「我回复的那一条」，顶层评论为 0；</li>
 *   <li>{@code root_id}——所属树的根评论ID，顶层评论等于自身 id（不为 null），
 *       因此 {@code WHERE root_id IN (...)} 一次即可捞出整棵子树，无需递归查询。</li>
 * </ul>
 *
 * <p>{@code reply_to_id / reply_to_name} 不参与层级，只是被回复者作者的展示冗余：
 * 省一次 join，且被回复的评论删除后仍知道当初回复的是谁。注意存的是<b>用户ID</b>
 * 而非评论ID——评论ID 由 {@code parent_id} 承担。</p>
 *
 * <p>{@code reply_count} 只维护在根评论上，语义是「该根下的后代总数」，
 * 子评论恒为 0。这是刻意设计，不是数据不一致。</p>
 * @author: Winston
 * @date: 2026/10/31 10:07
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    @Resource
    private final CommentMapper commentMapper;

    @Resource
    private final DocumentMapper documentMapper;

    @Resource
    private final LikeService likeService;

    @Resource
    private final LikeMapper likeMapper;

    /**
     * 创建评论
     * @param dto 创建DTO
     * @return 返回对应的评论id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createComment(CommentCreateDTO dto) {
        log.info("创建评论：documentId={}, parentId={}", dto.getDocumentId(), dto.getParentId());

        Long userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();

        // 1.检验评论对应的文档是否存在,同时取出统计事件需要的标题，避免额外一次查询
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .select(Document::getId, Document::getTitle)
                .eq(Document::getId, dto.getDocumentId()));

        if (document == null) {
            throw new BusinessException("文档不存在");
        }

        Long commentId = SnowflakeIdGenerator.getInstance().nextId();
        // 2.检验是否有父评论，并设置rootId以及parentId
        Long parentId = CommentConstants.ROOT_PARENT_ID;
        // 顶层评论的 root_id 等于自身 id
        Long rootId = commentId;
        Long replyToUserId = null;
        String replyToUserName = null;
        if (dto.getParentId() != null && dto.getParentId() > 0) {
            Comment parentComment = commentMapper.selectById(dto.getParentId());
            if (parentComment == null) {
                throw new BusinessException("父评论不存在");
            }
            if (!parentComment.getDocumentId().equals(dto.getDocumentId())) {
                throw new BusinessException("父评论不属于该文档");
            }
            parentId = parentComment.getId();
            // 根id就从判断当前创建的评论的父评论的父评论是否是0，是0就是根
            rootId = isRoot(parentComment) ? parentComment.getId() : parentComment.getRootId();
            replyToUserId = parentComment.getCommenterId();
            replyToUserName = parentComment.getCommenterName();
        }

        String userAvatar = UserContext.getCurrentUserAvatar();
        if (!StringUtils.hasText(userAvatar)) {
            // TODO
            /*userAvatar = userServiceClient.getUserAvatar(userId);*/
        }
        // 3.设置属性
        Comment comment = new Comment();
        comment.setId(commentId);
        comment.setDocumentId(dto.getDocumentId());
        comment.setParentId(parentId);
        comment.setRootId(rootId);
        comment.setContent(dto.getContent());
        comment.setCommenterId(userId);
        comment.setCommenterName(userName);
        comment.setCommenterAvatar(userAvatar);
        comment.setReplyToUserId(replyToUserId);
        comment.setReplyToUserName(replyToUserName);
        comment.setStatus(CommentConstants.STATUS_NORMAL);
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setDeleted(0);

        if (commentMapper.insert(comment) <= 0) {
            throw new BusinessException("创建评论失败");
        }

        // 回复数记在根评论上，语义是「该根下的后代总数」，与展示口径一致
        if (!CommentConstants.ROOT_PARENT_ID.equals(parentId)) {
            commentMapper.incrementReplyCount(rootId);
        }

        documentMapper.incrementCommentCount(dto.getDocumentId());

        String documentTitle = document.getTitle();
        // TODO
/*        publishAfterCommit(() -> statisticsEventPublisher.publishCommentEvent(
                userId, userName, dto.getDocumentId(), documentTitle));*/

        return comment.getId();

    }
    private boolean isRoot(Comment comment) {
        return comment.getParentId() == null || CommentConstants.ROOT_PARENT_ID.equals(comment.getParentId());
    }

    /**
     * 删除评论
     * @param commentId 评论ID
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteComment(Long commentId) {
        log.info("删除评论：commentId={}", commentId);
        // 校验需要删除的评论
        Comment comment = requireComment(commentId);
        // 1.判断是否是自己的评论，不得误删除
        Long currentUserId = UserContext.getCurrentUserId();
        if (!currentUserId.equals(comment.getCommenterId())) {
            throw new BusinessException("只能删除自己的评论");
        }
        // 2.判断是否有子评论，将parentId赋值为当前评论看得到子评论
        Long childCount = commentMapper.selectCount(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getParentId, commentId)
        );
        if (childCount > 0) {
            throw new BusinessException("该评论下有回复，不能删除");
        }

        // 逻辑删除返回 0 说明已被删除，此时不能再扣计数，否则会重复递减
        // 删除成功返回1
        if (commentMapper.deleteById(commentId) <= 0) {
            return false;
        }

        // 3.减少对应文档数量，如果是根评论，减少文档回复数量，如果不是根评论，回复数量减少，文档回复数量也减少
        if (!isRoot(comment)) {
            commentMapper.decrementLikeCount(commentId);
        }

        documentMapper.decrementCommentCount(comment.getDocumentId());

        return true;
    }

    /**
     * 点赞服务
     * @param commentId 评论ID
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean likeComment(Long commentId) {
        log.info("点赞评论：commentId={}", commentId);
        // 1.校验评论的存在性
        Comment comment = requireComment(commentId);
        if (!comment.getStatus().equals(CommentConstants.STATUS_NORMAL)) {
            throw new BusinessException("评论已隐藏，无法点赞");
        }
        // 2.业务层不作任何检查是否点赞，检查和插入数据库必须是原子性，若在业务层检查，并发下会存在问题
        // 直接插入即可，数据库层面有uk_target_user_type 唯一索引
        Long currentUserId = UserContext.getCurrentUserId();
        likeService.like(commentId, currentUserId, LikeTargetTypeConstants.COMMENT);

        // 如果前者插入失败，事务回滚这条也失败
        commentMapper.incrementLikeCount(commentId);

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean unlikeComment(Long commentId) {
        log.info("取消点赞评论：commentId={}", commentId);

        requireComment(commentId);
        Long userId = UserContext.getCurrentUserId();

        // 刻意不复用 LikeService.unlike：它在删不到记录时抛 BusinessException，
        // 而本方法的既有契约是返回 false，改掉会影响前端。
        int count = likeMapper.deleteByTargetAndUser(commentId, userId, LikeTargetTypeConstants.COMMENT);

        if (count > 0) {
            commentMapper.decrementLikeCount(commentId);
        }

        return count > 0;
    }

    /**
     * 查询指定文档下的所有评论，当前未作分页查询，公司内部评论使用 人多情况下，前端设计“展示更多评论”
     * 时候可以引入分页
     * @param documentId 文档ID
     * @param dto 查询DTO
     * @return
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<CommentVO> pageDocumentComments(Long documentId, CommentQueryDTO dto) {
        // 1.先获取该文档下所有的顶级评论
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getDocumentId, documentId)
                .eq(Comment::getParentId, CommentConstants.ROOT_PARENT_ID) // parentId等于0就是顶级评论
                .eq(Comment::getStatus, CommentConstants.STATUS_NORMAL);
        if (StringUtils.hasText(dto.getSortBy())) {
            if ("like_count".equals(dto.getSortBy())) {
                wrapper.orderByDesc(Comment::getLikeCount);
            } else if ("asc".equals(dto.getSortOrder())) {
                wrapper.orderByAsc(Comment::getCreatedAt);
            } else {
                wrapper.orderByDesc(Comment::getCreatedAt);
            }
        } else {
            wrapper.orderByDesc(Comment::getCreatedAt);
        }

        Page<Comment> commentPage = commentMapper
                .selectPage(new Page<>(dto.getCurrent(), dto.getSize()), wrapper);
        List<Comment> roots = commentPage.getRecords();
        if (roots.isEmpty()) {
            return buildPage(commentPage, Collections.emptyList());
        }
        // 获取所有的顶级评论id
        List<Long> rootIds = roots.stream()
                .map(Comment::getId)
                .collect(Collectors.toList());
        // 2.再获取顶级评论下所有的子评论
        LambdaQueryWrapper<Comment> commentLambdaQueryWrapper = new LambdaQueryWrapper<Comment>()
                .in(Comment::getParentId, rootIds)
                .gt(Comment::getParentId, CommentConstants.ROOT_PARENT_ID) // parentId大于0代表是子评论
                .eq(Comment::getStatus, CommentConstants.STATUS_NORMAL)
                .orderByAsc(Comment::getCreatedAt);
        List<Comment> descendants = commentMapper.selectList(commentLambdaQueryWrapper);
        Map<Long, List<Comment>> childrenIds = descendants.stream().collect(Collectors.groupingBy(Comment::getParentId));
        // 3.获取当前登录用户在这些所有评论中所点赞的CommentId

        HashSet<Long> allCommentIds = new HashSet<>(rootIds);
        descendants.forEach(comment -> {allCommentIds.add(comment.getId());});
        Set<Long> likedIds = queryLikedCommentIds(getCurrentUserIdSafely(), allCommentIds);
        // 4.递归构建树返回
        // 遍历每一个顶级评论查看
        List<CommentVO> commentVOList = roots.stream()
                .map(root -> buildReplyTree(root, childrenIds, likedIds))
                .collect(Collectors.toList());
        return buildPage(commentPage, commentVOList);
    }

    /**
     * 查看指定父评论下的所有评论
     * @param parentCommentId 父评论ID
     * @return
     */
    @Override
    @Transactional(readOnly = true)
    public List<CommentVO> getCommentReplies(Long parentCommentId) {
        if (parentCommentId == null || parentCommentId <= 0) {
            return Collections.emptyList();
        }
        // 1.查询所有父评论下的子评论
        List<Comment> commentList = commentMapper.selectList(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getParentId, parentCommentId)
                        .eq(Comment::getStatus, CommentConstants.STATUS_NORMAL)
                        .orderByAsc(Comment::getCreatedAt)
        );
        if (commentList.isEmpty()) {
            return Collections.emptyList();
        }
        // 2.查询在这些子评论中，统计用户所点赞的子评论
        Set<Long> commentIds = commentList.stream().map(Comment::getId).collect(Collectors.toSet());
        Set<Long> likedCommentIds = queryLikedCommentIds(getCurrentUserIdSafely(), commentIds);
        // 3.封装vo返回
        return commentList.stream()
                .map(comment -> {
                    CommentVO commentVO = convertToVO(comment);
                    commentVO.setIsLiked(likedCommentIds.contains(comment.getId()));
                    return commentVO;
                })
                .toList();
    }

    /**
     * 按 parent_id 递归组装回复树
     *
     * <p>写入侧保证 parent 必须先于 child 存在且 parent_id 之后不再变更，
     * 因此不会出现环。递归深度等于评论链深度。</p>
     */
    private CommentVO buildReplyTree(Comment node,
                                     Map<Long, List<Comment>> childrenByParent,
                                     Set<Long> likedIds) {
        CommentVO commentVO = convertToVO(node);
        commentVO.setIsLiked(likedIds.contains(node.getId()));
        // 递归遍历
        commentVO.setReplies(childrenByParent.getOrDefault(node.getId(), Collections.emptyList())
                .stream()
                .map(child -> buildReplyTree(child, childrenByParent, likedIds))
                .toList());
        return commentVO;
    }

    /**
     * 读路径上安全获取当前用户ID：未登录返回 null，让点赞状态降级为「未点赞」，
     * 而不是让整个评论列表查询失败。写路径不使用本方法，需显式失败。
     */
    private Long getCurrentUserIdSafely() {
        try {
            return UserContext.getCurrentUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 转换为VO
     *
     * @param comment 评论实体
     * @return 评论VO
     */
    private CommentVO convertToVO(Comment comment) {
        return CommentVO.builder()
                .id(comment.getId())
                .documentId(comment.getDocumentId())
                .parentId(comment.getParentId())
                .rootId(comment.getRootId())
                .content(comment.getContent())
                .commenterId(comment.getCommenterId())
                .commenterName(comment.getCommenterName())
                .commenterAvatar(comment.getCommenterAvatar())
                .replyToUserId(comment.getReplyToUserId())
                .replyToUserName(comment.getReplyToUserName())
                .status(comment.getStatus())
                .likeCount(comment.getLikeCount() != null ? comment.getLikeCount() : 0)
                .replyCount(comment.getReplyCount() != null ? comment.getReplyCount() : 0)
                .createdAt(comment.getCreatedAt())
                .build();
    }

    /**
     * 批量查询评论点赞状态
     *
     * @param userId     当前用户ID（null表示未登录）
     * @param commentIds 评论ID集合
     * @return 已点赞的评论ID集合
     */
    private Set<Long> queryLikedCommentIds(Long userId, Set<Long> commentIds) {
        if (userId == null || commentIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<Like> likes = likeMapper.selectList(
                new LambdaQueryWrapper<Like>()
                        .select(Like::getTargetId)
                        .eq(Like::getUserId, userId)
                        .eq(Like::getTargetType, LikeTargetTypeConstants.COMMENT)
                        .in(Like::getTargetId, commentIds)

        );
        return likes.stream().map(Like::getTargetId).collect(Collectors.toSet());
    }

    /**
     * 查询评论并校验存在性
     */
    private Comment requireComment(Long commentId) {
        if (commentId == null) {
            throw new BusinessException("评论ID不能为空");
        }
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }
        return comment;
    }

    private PageResult<CommentVO> buildPage(IPage<Comment> page, List<CommentVO> records) {
        return PageResult.<CommentVO>builder()
                .records(records)
                .total(page.getTotal())
                .current(page.getCurrent())
                .size(page.getSize())
                .build();
    }
}
