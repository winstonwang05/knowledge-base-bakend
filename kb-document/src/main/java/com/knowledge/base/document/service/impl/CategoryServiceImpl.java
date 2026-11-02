package com.knowledge.base.document.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.knowledge.base.common.exception.BusinessException;
import com.knowledge.base.common.utils.SnowflakeIdGenerator;
import com.knowledge.base.document.dto.CategoryDTO;
import com.knowledge.base.document.entity.Category;
import com.knowledge.base.document.mapper.CategoryMapper;
import com.knowledge.base.document.service.CategoryService;
import com.knowledge.base.document.vo.CategoryVO;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @description: 分类业务层
 * @author: Winston
 * @date: 2026/10/29 15:27
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    @Resource
    private final CategoryMapper categoryMapper;

    /**
     * 创建分类，包括父分类和子分类，区别在与是否传入parentId
     * @param categoryDTO 分类信息
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "categoryTree", key = "'tree'", cacheManager = "caffeineCacheManager")
    public Long createCategory(CategoryDTO categoryDTO) {
        log.info("创建分类：categoryName={}", categoryDTO.getName());
        // 1.查询需要增加的分类是存在
        Category existCategory = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                .eq(Category::getCategoryName, categoryDTO.getName()));

        if (existCategory != null) {
            throw new BusinessException("分类名称已存在");
        }
        // 2.查询父分类是否存在,如果不存在，创建的就是顶级分类，反之子分类
        long parentId = categoryDTO.getParentId() != null ? categoryDTO.getParentId() : 0L;
        if (parentId > 0) {
            Category parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException("父分类不存在");
            }
        }
        // 3.设置属性
        Category category = new Category();
        String categoryCode = StringUtils.hasText(categoryDTO.getName())
                ? generateCategoryCode(categoryDTO.getName())
                : "CATEGORY_" + System.currentTimeMillis();

        category.setCategoryCode(categoryCode);
        category.setId(SnowflakeIdGenerator.getInstance().nextId());
        category.setParentId(parentId);
        category.setCategoryName(categoryDTO.getName());
        category.setDescription(categoryDTO.getDescription());
        category.setIcon(categoryDTO.getIcon());
        category.setSort(categoryDTO.getSortOrder() != null ? categoryDTO.getSortOrder() : 0);
        category.setStatus(1);
        category.setDocumentCount(0);

        // 4.落库
        int count = categoryMapper.insert(category);
        if (count <= 0) {
            throw new BusinessException("创建分类失败");
        }

        return category.getId();
    }

    /**
     * 生成分类编码
     *
     * @param categoryName 分类名称
     * @return 分类编码
     */
    private String generateCategoryCode(String categoryName) {
        // 简单的拼音首字母或缩写生成逻辑
        // 实际项目中可以使用拼音转换库
        return "CAT_" + categoryName.toUpperCase()
                .replaceAll("[^A-Z0-9]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "") + "_" + System.currentTimeMillis();
    }

    /**
     * 更新分类
     * @param categoryDTO 分类信息
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "categoryTree", key = "'tree'", cacheManager = "caffeineCacheManager")
    public Boolean updateCategory(CategoryDTO categoryDTO) {
        log.info("更新分类：categoryId={}", categoryDTO.getId());
        if (categoryDTO.getId() == null) {
            throw new BusinessException("分类ID不能为空");
        }
        // 1.判断需要更新的分类是否存在
        Category existCategory = categoryMapper.selectById(categoryDTO.getId());
        if (existCategory == null) {
            throw new BusinessException("分类不存在");
        }
        // 2.判断更新后的分类名是否存在，如果存在需要排除
        if (StringUtils.hasText(categoryDTO.getName())
                && !existCategory.getCategoryName().equals(categoryDTO.getName())) {
            Category category = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                    .eq(Category::getCategoryName, categoryDTO.getName()));
            if (category != null && !category.getId().equals(categoryDTO.getId())) {
                throw new BusinessException("分类名称已被使用");
            }
        }
        // 3.判断是否存在父类名，更新后的分类名不得是父类名
        if (categoryDTO.getParentId() != null) {
            if (categoryDTO.getParentId().equals(existCategory.getId())) {
                throw new BusinessException("父分类不能是自己");
            }
            if (categoryDTO.getParentId() > 0) {
                Category parentCategory = categoryMapper.selectById(categoryDTO.getParentId());
                if (parentCategory == null) {
                    throw new BusinessException("父分类不存在");
                }
            }
        }
        // 4.update
        Category category = new Category();
        category.setId(categoryDTO.getId());
        if (StringUtils.hasText(categoryDTO.getName())) {
            category.setCategoryName(categoryDTO.getName());
        }
        category.setDescription(categoryDTO.getDescription());
        if (categoryDTO.getParentId() != null) {
            category.setParentId(categoryDTO.getParentId());
        }
        if (categoryDTO.getIcon() != null) {
            category.setIcon(categoryDTO.getIcon());
        }
        if (categoryDTO.getSortOrder() != null) {
            category.setSort(categoryDTO.getSortOrder());
        }

        int count = categoryMapper.updateById(category);
        return count > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "categoryTree", key = "'tree'", cacheManager = "caffeineCacheManager")
    public Boolean deleteCategory(Long categoryId) {

        log.info("删除分类：categoryId={}", categoryId);

        if (categoryId == null) {
            throw new BusinessException("分类ID不能为空");
        }
        // 1.查询需要删除的分类是否存在
        Category category = categoryMapper.selectById(categoryId);

        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        // 2.如果需要删除的分类存在子分类，不得删除
        // 查询条件就是parentId是自己，能够查询到其分类是否存在
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<Category>()
                .eq(Category::getParentId, categoryId)
                .eq(Category::getStatus, 1);
        Long childrenCategoryCount = categoryMapper.selectCount(wrapper);
        if (childrenCategoryCount > 0) {
            throw new BusinessException("该分类下有子分类，不能删除");
        }
        // 3.执行删除
        int count = categoryMapper.deleteById(categoryId);
        return count > 0;
    }

    @Override
    public CategoryVO getCategoryById(Long categoryId) {
        if (categoryId == null) {
            throw new BusinessException("分类ID不能为空");
        }

        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException("分类不存在");
        }

        return convertToVO(category);
    }

    @Override
    @Cacheable(value = "categoryTree", key = "'tree'", cacheManager = "caffeineCacheManager")
    public List<CategoryVO> getCategoryTree() {
        // 1.查询所有的分类
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<Category>()
                .eq(Category::getStatus, 1)
                .orderByAsc(Category::getSort);
        List<Category> categories = categoryMapper.selectList(wrapper);

        // 2.封装为VO
        List<CategoryVO> categoryVOList = categories
                .stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        // 3.构建tree返回
        return buildCategoryTree(categoryVOList, 0L);
    }

    @Override
    public List<CategoryVO> getChildren(Long parentId) {
        if (parentId == null) {
            parentId = 0L;
        }

        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<Category>()
                .eq(Category::getParentId, parentId)
                .eq(Category::getStatus, 1)
                .orderByAsc(Category::getSort);
        List<Category> categories = categoryMapper.selectList(wrapper);

        return categories.stream().map(this::convertToVO).toList();
    }

    @Override
    public List<CategoryVO> getAllCategories() {
        List<Category> categories = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getStatus, 1)
                .orderByAsc(Category::getSort));

        return categories.stream().map(this::convertToVO).toList();
    }

    /**
     * 移动分类位置，仅需更换父分类id
     * @param categoryId   分类ID
     * @param newParentId 新父分类ID
     * @return 返回更换结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "categoryTree", key = "'tree'", cacheManager = "caffeineCacheManager")
    public Boolean moveCategory(Long categoryId, Long newParentId) {
        log.info("移动分类：categoryId={}, newParentId={}", categoryId, newParentId);
        // 1.检查当前分类是否存在
        if (categoryId == null) {
            throw new BusinessException("分类ID不能为空");
        }
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        // 2.检查是否移动到目前本身分类
        if (newParentId.equals(categoryId)) {
            throw new BusinessException("不能移动到自己");
        }
        // 3.检查新父分类是否存在, 0为顶级父类
        if (newParentId != null && newParentId > 0) {
            Category newCategory = categoryMapper.selectById(newParentId);
            if (newCategory == null) {
                throw new BusinessException("父分类不存在");
            }
            // 4.检查是否是移动到当前分类的子分类
            if (isDescendant(categoryId, newParentId)) {
                throw new BusinessException("不能移动到自己的子分类下");
            }
        }
        // 5.更换父分类
        Category updateCategory = new Category();
        updateCategory.setId(categoryId);
        updateCategory.setParentId(newParentId != null ? newParentId : 0L);
        int count = categoryMapper.updateById(updateCategory);
        return count > 0;
    }

    /**
     * 检查是否是后代节点,循环检查后代节点的parentId是否是祖先节点就行，如果是，那么就是后代
     *
     * @param ancestorId 祖先节点ID
     * @param descendantId 后代节点ID
     * @return 是否是后代
     */
    private boolean isDescendant(Long ancestorId, Long descendantId) {
        Category descendantCategory = categoryMapper.selectById(descendantId);
        while (descendantCategory != null
                && descendantCategory.getParentId() != null
                && descendantCategory.getParentId() > 0) {
            if (descendantCategory.getParentId().equals(ancestorId)) {
                return true;
            }
            descendantCategory = categoryMapper.selectById(descendantCategory.getParentId());
        }
        return false;
    }

    /**
     * 递归构建树
     * @param categories 所有种类
     * @param parentId 父分类id
     * @return 返回递归集合
     */
    public List<CategoryVO> buildCategoryTree(List<CategoryVO> categories, Long parentId) {
        List<CategoryVO> categoryTree = new ArrayList<>();
        for (CategoryVO categoryVO : categories) {
            if (parentId.equals(categoryVO.getParentId())) {
                categoryVO.setChildren(buildCategoryTree(categories, categoryVO.getId()));
                categoryTree.add(categoryVO);
            }
        }
        return categoryTree;
    }

    /**
     * 转换为VO
     *
     * @param category 分类实体
     * @return 分类VO
     */
    private CategoryVO convertToVO(Category category) {
        return CategoryVO.builder()
                .id(category.getId())
                .name(category.getCategoryName())
                .description(category.getDescription())
                .parentId(category.getParentId())
                .sortOrder(category.getSort())
                .icon(category.getIcon())
                .documentCount(category.getDocumentCount() != null ? category.getDocumentCount().longValue() : 0L)
                .build();
    }





}
