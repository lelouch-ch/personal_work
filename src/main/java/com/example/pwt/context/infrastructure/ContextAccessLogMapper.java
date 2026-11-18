package com.example.pwt.context.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 上下文访问记录 Mapper。
 *
 * <p>只需要写入：MVP 没有查询入口，设计文档 10.2 未定义上下文访问的读取接口，
 * 排查问题时直接查库即可，因此不预留用不上的查询方法。
 *
 * @author pwt
 */
@Mapper
public interface ContextAccessLogMapper extends BaseMapper<ContextAccessLogEntity> {
}
