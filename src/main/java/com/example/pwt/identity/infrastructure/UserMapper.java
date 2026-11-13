package com.example.pwt.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper。
 *
 * @author pwt
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
