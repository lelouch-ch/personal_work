package com.example.pwt.context.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 风格规则表 Mapper。
 *
 * <p>只用条件构造器，理由同 {@link StyleRuleEntity}：JSON 列在注解 SQL 下拿不到类型处理器。
 *
 * @author pwt
 */
@Mapper
public interface StyleRuleMapper extends BaseMapper<StyleRuleEntity> {
}
