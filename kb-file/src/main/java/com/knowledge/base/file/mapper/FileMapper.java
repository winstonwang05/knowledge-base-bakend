package com.knowledge.base.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledge.base.file.entity.FileInfo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件Mapper接口
 *
 * @author Winston
 * @since 1.0.0
 */
@Mapper
public interface FileMapper extends BaseMapper<FileInfo> {

}
