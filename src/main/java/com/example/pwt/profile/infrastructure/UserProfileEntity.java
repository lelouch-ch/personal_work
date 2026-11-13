package com.example.pwt.profile.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户画像持久化对象，映射 {@code pt_user_profile}。
 *
 * <p>该表按 user_id 唯一，一个用户一行；{@code updated_at} 由数据库 ON UPDATE 维护。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName(value = "pt_user_profile", autoResultMap = true)
public class UserProfileEntity {

    /** 记录 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户，唯一。 */
    private String userId;

    /** 一句话定位。 */
    private String headline;

    /** 画像摘要。 */
    private String summary;

    /** 结构化画像，键由业务约定。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> profileJson;

    /** 可见性：private / agent / public。 */
    private String visibility;

    /** 更新时间，由数据库维护。 */
    private Instant updatedAt;
}
