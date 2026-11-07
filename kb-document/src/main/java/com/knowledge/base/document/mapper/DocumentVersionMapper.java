package com.knowledge.base.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledge.base.document.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档版本Mapper接口
 *
 * @author Winston
 * @since 1.0.0
 */
@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {

}