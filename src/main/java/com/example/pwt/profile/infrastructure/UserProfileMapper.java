package com.example.pwt.profile.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户画像表 Mapper。
 *
 * @author pwt
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfileEntity> {
}
