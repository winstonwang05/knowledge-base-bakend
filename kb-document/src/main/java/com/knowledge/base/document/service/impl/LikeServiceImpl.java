package com.knowledge.base.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.knowledge.base.common.exception.BusinessException;
import com.knowledge.base.document.entity.Like;
import com.knowledge.base.document.mapper.LikeMapper;
import com.knowledge.base.document.service.LikeService;
import com.mongodb.DuplicateKeyException;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @description: 点赞服务
 * @author: Winston
 * @date: 2026/11/1 14:07
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeServiceImpl extends ServiceImpl<LikeMapper, Like> implements LikeService {

    @Resource
    private LikeMapper likeMapper;
    /**
     * 取消点赞
     * @param targetId   目标ID（文档ID或评论ID）
     * @param userId     用户ID
     * @param targetType 目标类型：1-文档，2-评论
     */
    @Override
    public void unlike(Long targetId, Long userId, Integer targetType) {
        int count = likeMapper.deleteByTargetAndUser(targetId, userId, targetType);
        if (count == 0) {
            throw new BusinessException("尚未点赞，无法取消");
        }
    }

    /**
     * 查询是否点赞
     * @param targetId   目标ID（文档ID或评论ID）
     * @param userId     用户ID
     * @param targetType 目标类型：1-文档，2-评论
     * @return
     */
    @Override
    public boolean isLiked(Long targetId, Long userId, Integer targetType) {
        Long count = likeMapper.selectCount(new LambdaQueryWrapper<Like>()
                .eq(Like::getTargetId, targetId)
                .eq(Like::getUserId, userId)
                .eq(Like::getTargetType, targetType));
        return count > 0;
    }

    /**
     * 点赞
     * @param targetId   目标ID（文档ID或评论ID）
     * @param userId     用户ID
     * @param targetType 目标类型：1-文档，2-评论
     */
    @Override
    public void like(Long targetId, Long userId, Integer targetType) {
        Like like = new Like();
        like.setTargetId(targetId);
        like.setUserId(userId);
        like.setTargetType(targetType);
        try {
            likeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("已经点赞过了");
        }    }
}
