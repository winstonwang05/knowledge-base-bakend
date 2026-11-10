package com.knowledge.base.document.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.knowledge.base.common.exception.BusinessException;
import com.knowledge.base.common.utils.SnowflakeIdGenerator;
import com.knowledge.base.common.utils.UserContextUtil;
import com.knowledge.base.document.dto.DocumentDTO;
import com.knowledge.base.document.entity.Category;
import com.knowledge.base.document.entity.Document;
import com.knowledge.base.document.mapper.CategoryMapper;
import com.knowledge.base.document.mapper.DocumentMapper;
import com.knowledge.base.document.service.DocumentContentService;
import com.knowledge.base.document.service.DocumentService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * @description: 文档服务业务层
 * @author: Winston
 * @date: 2026/10/28 15:55
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document> implements DocumentService {

    private final DocumentMapper documentMapper;

    private final CategoryMapper categoryMapper;

    private final DocumentContentService documentContentService;


    /**
     * 创建文档，默认以草稿箱状态 = 0存储
     * @param documentDTO 文档信息
     * @return 返回
     */
    @Override
    @Transactional
    public Long createDocument(DocumentDTO documentDTO) {
        // 1.构建实体类
        Document document = new Document();

        // 2.设置属性 TODO.需要排除content赋值
        BeanUtils.copyProperties(documentDTO, document);
        document.setId(SnowflakeIdGenerator.getInstance().nextId());

        // 设置默认值
        if (document.getDocumentType() == null) {
            document.setDocumentType(1);
        }
        if (document.getStatus() == null) {
            document.setStatus(0);
        }
        if (document.getIsTop() == null) {
            document.setIsTop(0);
        }
        if (document.getIsRecommend() == null) {
            document.setIsRecommend(0);
        }
        if (document.getSource() == null) {
            document.setSource(1);
        }
        if (document.getAllowComment() == null) {
            document.setAllowComment(1);
        }
        if (document.getSort() == null) {
            document.setSort(0);
        }
        if (document.getViewCount() == null) {
            document.setViewCount(0L);
        }
        if (document.getLikeCount() == null) {
            document.setLikeCount(0L);
        }
        if (document.getFavoriteCount() == null) {
            document.setFavoriteCount(0L);
        }
        if (document.getCommentCount() == null) {
            document.setCommentCount(0L);
        }
        // 3.如果类型是文件，也就是上传的形式，设置默认的种类和可见性
        if (Objects.equals(document.getDocumentType(), 2)) {
            Category category = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                    .eq(Category::getCategoryName, "技术文档"));
            if (category != null) {
                document.setCategoryId(category.getId());
                log.info("导入文档设置默认分类：categoryId={}, categoryName=技术文档", category.getId());
            }
        }
        // 默认设置为团队可见
        if (document.getIsPublic() == null) {
            document.setIsPublic(0);
        }

        // 4.设置用户上下文信息
        document.setAuthorId(UserContextUtil.getUserId());
        document.setAuthorName(UserContextUtil.getUsername());

        // 5.如果是发布状态，设置发布时间
        if (Objects.equals(document.getStatus(), 1)) {
            document.setPublishTime(LocalDateTime.now());
        }

        // 6.增加文档的数量
        if (document.getCategoryId() != null) {
            categoryMapper.incrementDocumentCount(document.getCategoryId());
        }
        // 7.插入数据库
        int count = documentMapper.insert(document);
        if (count <= 0) {
            throw new BusinessException("创建文档失败");
        }
        // 同步保存内容到MongoDB
        boolean hasContent = StrUtil.isNotBlank(documentDTO.getContent());
        if (hasContent) {
            try {
                String contentId = documentContentService.saveContent(document.getId(), documentDTO.getContent());
                // 计算内容长度（字符数）和文件大小（UTF-8字节数）
                String content = documentDTO.getContent();
                int contentLength = content.length();
                long fileSize = content.getBytes(StandardCharsets.UTF_8).length;
                // 更新MySQL记录的contentId、contentLength、fileSize
                Document doc = new Document();
                doc.setId(document.getId());
                doc.setContentId(contentId);
                doc.setContentLength(contentLength);
                doc.setFileSize(fileSize);
                documentMapper.updateById(doc);
                log.info("文档内容保存成功：documentId={}, contentId={}, contentLength={}, fileSize={}", document.getId(), contentId, contentLength, fileSize);
            } catch (Exception e) {
                log.error("保存文档内容到MongoDB失败：documentId={}, error={}", document.getId(), e.getMessage());
                throw new BusinessException("保存文档内容失败：" + e.getMessage());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDocument(DocumentDTO documentDTO) {
        log.info("更新文档：documentId={}", documentDTO.getId());

        if (documentDTO.getId() == null) {
            throw new BusinessException("文档ID不能为空");
        }
        // 构建实体
        Document document = new Document();
        // 设置属性
        BeanUtils.copyProperties(documentDTO, document);

        // 如果状态由草稿变为发布，设置发布时间
        Document existDocument = documentMapper.selectById(documentDTO.getId());
        if (Objects.equals(existDocument.getStatus(), 0)
        && Objects.equals(documentDTO.getDocumentType(), 1)) {
            document.setPublishTime(LocalDateTime.now());
        }

        // 更新
        int count = documentMapper.updateById(document);
        // 如果文档的种类发生变化，更新对应的种类数量
        Long newCategoryId = documentDTO.getCategoryId();
        Long existCategoryId = existDocument.getCategoryId();
        if (count > 0 && !Objects.equals(newCategoryId, existCategoryId)) {
            if (newCategoryId != null) {
                categoryMapper.incrementDocumentCount(existCategoryId);
            }
            if (existCategoryId != null) {
                categoryMapper.incrementDocumentCount(existCategoryId);
            }
        }

        return count > 0;
    }

    /**
     * 删除文档，并删除对应的种类数量
     * @param documentId 文档ID
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteDocument(Long documentId) {
        log.info("删除文档：documentId={}", documentId);

        if (documentId == null) {
            throw new BusinessException("文档ID不能为空");
        }
        // 1.根据id查询对应的文档
        Document document = documentMapper.selectById(documentId);
        // 2.删除指定的文档
        int count = documentMapper.deleteById(documentId);
        // 3.删除文档对应的种类数量 -1
        if (count > 0) {
            categoryMapper.incrementDocumentCount(document.getCategoryId());
        }

        return count > 0;
    }


}
