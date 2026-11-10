package com.knowledge.base.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.knowledge.base.common.exception.BusinessException;
import com.knowledge.base.common.utils.SnowflakeIdGenerator;
import com.knowledge.base.document.entity.Document;
import com.knowledge.base.document.entity.UserFavorite;
import com.knowledge.base.document.mapper.DocumentMapper;
import com.knowledge.base.document.mapper.UserFavoriteMapper;
import com.knowledge.base.document.service.UserFavoriteService;
import com.knowledge.base.document.vo.UserFavoriteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 收藏服务业务逻辑
 * @author: Winston
 * @date: 2026/11/10 13:51
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserFavoriteServiceImpl
        extends ServiceImpl<UserFavoriteMapper, UserFavorite>
        implements UserFavoriteService {

    private final UserFavoriteMapper userFavoriteMapper;

    private final DocumentMapper documentMapper;

    /**
     * 添加收藏，校验文档是否存在，用户是否收藏过，不得重复收藏
     * 具体文档收藏数量+1，并保存收藏记录到对应表
     * @param userId 用户ID
     * @param documentId 文档ID
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addFavorite(Long userId, Long documentId) {
        log.info("🆕 [UserFavoriteService] addFavorite 开始：userId={}, documentId={}", userId, documentId);

        // 验证文档是否存在
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            log.error("❌ [UserFavoriteService] 文档不存在：documentId={}", documentId);
            throw new BusinessException("文档不存在");
        }
        log.info("✅ [UserFavoriteService] 文档存在：title={}", document.getTitle());

        // 检查是否已收藏
        UserFavorite existingFavorite = userFavoriteMapper.findByUserAndDocument(userId, documentId);
        if (existingFavorite != null) {
            log.warn("⚠️ [UserFavoriteService] 用户已收藏该文档：userId={}, documentId={}, existingFavorite.deleted={}", userId, documentId, existingFavorite.getDeleted());
            return true;
        }
        log.info("✅ [UserFavoriteService] 未发现收藏记录，准备创建新记录");

        // 创建收藏记录
        UserFavorite favorite = new UserFavorite();
        favorite.setId(SnowflakeIdGenerator.getInstance().nextId());
        favorite.setUserId(userId);
        favorite.setDocumentId(documentId);
        favorite.setDocumentTitle(document.getTitle());
        favorite.setDocumentCategoryId(document.getCategoryId());
        log.info("📝 [UserFavoriteService] 创建收藏记录：id={}, userId={}, documentId={}", favorite.getId(), userId, documentId);

        int count = userFavoriteMapper.insert(favorite);
        log.info("🔢 [UserFavoriteService] 插入结果：count={}", count);

        if (count > 0) {
            // 更新文档收藏数量
            documentMapper.incrementFavoriteCount(documentId);
            log.info("✅ [UserFavoriteService] 添加收藏成功，返回 true");
            return true;
        } else {
            log.error("❌ [UserFavoriteService] 插入失败，返回 false");
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeFavorite(Long userId, Long documentId) {
        log.info("❌ [UserFavoriteService] removeFavorite 开始：userId={}, documentId={}", userId, documentId);

        // 使用物理删除方法，避免逻辑删除的唯一约束冲突
        log.info("🗑️ [UserFavoriteService] 执行物理删除");

        int count = userFavoriteMapper.physicalDelete(userId, documentId);
        log.info("🔢 [UserFavoriteService] 物理删除结果：count={}", count);

        if (count > 0) {
            // 减少文档收藏数量
            documentMapper.decrementFavoriteCount(documentId);
            log.info("✅ [UserFavoriteService] 取消收藏成功，返回 true");
            return true;
        } else {
            log.warn("⚠️ [UserFavoriteService] 没有找到要删除的记录，返回 false");
            return false;
        }
    }

    @Override
    public Boolean isFavorited(Long userId, Long documentId) {
        UserFavorite favorite = userFavoriteMapper.findByUserAndDocument(userId, documentId);
        return favorite != null;
    }

    @Override
    public List<UserFavoriteVO> getUserFavorites(Long userId) {
        log.info("获取用户收藏列表：userId={}", userId);

        List<UserFavorite> favorites = userFavoriteMapper.getUserFavorites(userId);
        List<UserFavoriteVO> result = new ArrayList<>();

        for (UserFavorite favorite : favorites) {
            UserFavoriteVO vo = new UserFavoriteVO();
            vo.setId(favorite.getId());
            vo.setUserId(favorite.getUserId());
            vo.setDocumentId(favorite.getDocumentId());
            vo.setDocumentTitle(favorite.getDocumentTitle());
            vo.setDocumentSummary(favorite.getDocumentSummary());
            vo.setDocumentCategoryId(favorite.getDocumentCategoryId());
            vo.setDocumentAuthorName(favorite.getDocumentAuthorName());
            vo.setFavoriteTime(favorite.getFavoriteTime());
            result.add(vo);
        }

        return result;
    }

    @Override
    public Long getFavoriteCount(Long documentId) {
        Integer count = userFavoriteMapper.countByDocumentId(documentId);
        return count != null ? count.longValue() : 0L;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean toggleFavorite(Long userId, Long documentId) {
        log.info("🔄 [UserFavoriteService] toggleFavorite 开始：userId={}, documentId={}", userId, documentId);

        boolean isFavorited = isFavorited(userId, documentId);
        log.info("📊 [UserFavoriteService] 当前收藏状态：isFavorited={}", isFavorited);

        boolean newStatus;
        if (isFavorited) {
            log.info("❌ [UserFavoriteService] 执行取消收藏操作");
            boolean success = removeFavorite(userId, documentId);
            newStatus = false; // 取消收藏后，状态为未收藏
            log.info("✅ [UserFavoriteService] 取消收藏完成：操作成功={}, 新状态=未收藏({})", success, newStatus);
        } else {
            log.info("⭐ [UserFavoriteService] 执行添加收藏操作");
            boolean success = addFavorite(userId, documentId);
            newStatus = true; // 添加收藏后，状态为已收藏
            log.info("✅ [UserFavoriteService] 添加收藏完成：操作成功={}, 新状态=已收藏({})", success, newStatus);
        }

        log.info("🎯 [UserFavoriteService] toggleFavorite 最终返回新状态：newStatus={}", newStatus);
        return newStatus;
    }


}
