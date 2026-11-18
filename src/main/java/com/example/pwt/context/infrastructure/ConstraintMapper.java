package com.example.pwt.context.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 约束表 Mapper。
 *
 * <p>只用条件构造器：本表无 JSON 列，也没有 MySQL 专有语法，不需要注解 SQL。
 *
 * @author pwt
 */
@Mapper
public interface ConstraintMapper extends BaseMapper<ConstraintEntity> {
}
