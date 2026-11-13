package com.example.pwt.audit.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计表 Mapper。
 *
 * @author pwt
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
