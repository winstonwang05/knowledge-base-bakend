package com.knowledge.base.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledge.base.document.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @description: 种类Mapper层
 * @author: Winston
 * @date: 2026/10/29 10:31
 * @version: 1.0
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {


    /**
     * 根据父分类ID查询子分类列表
     */
    List<Category> selectByParentId(@org.apache.ibatis.annotations.Param("parentId") Long parentId);

    /**
     * 根据分类编码查询分类
     */
    Category selectByCategoryCode(@org.apache.ibatis.annotations.Param("categoryCode") String categoryCode);

    /**
     * 根据状态查询分类列表
     */
    List<Category> selectByStatus(@org.apache.ibatis.annotations.Param("status") Integer status);

    /**
     * 查询所有根分类
     */
    List<Category> selectRootCategories();

    /**
     * 更新文档数量
     */
    int updateDocumentCount(@org.apache.ibatis.annotations.Param("categoryId") Long categoryId, @org.apache.ibatis.annotations.Param("count") Integer count);

    /**
     * 增加文档数量
     */
    int incrementDocumentCount(@org.apache.ibatis.annotations.Param("categoryId") Long categoryId);

    /**
     * 减少文档数量（不小于0）
     */
    int decrementDocumentCount(@org.apache.ibatis.annotations.Param("categoryId") Long categoryId);

    /**
     * 检查分类编码是否存在
     */
    boolean checkCategoryCodeExists(@org.apache.ibatis.annotations.Param("categoryCode") String categoryCode, @Param("id") Long id);
}
