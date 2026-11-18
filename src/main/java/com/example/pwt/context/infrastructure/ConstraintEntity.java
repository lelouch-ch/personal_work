package com.example.pwt.context.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户约束，映射 {@code pt_constraint}（设计文档 8.3）。
 *
 * <p>放在 context 模块：MVP 只有 Context Builder 读取它（11.5 第 1 优先级），
 * 约束的维护接口在 M7 之后按需要再归入自己的模块，届时把本类一并迁走即可。
 *
 * @author pwt
 */
@Getter
@Setter
@TableName("pt_constraint")
public class ConstraintEntity {

    /** 约束 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属用户。 */
    private String userId;

    /** 约束名称。 */
    private String name;

    /** 约束正文，注入上下文的内容。 */
    private String rule;

    /** 严重程度：low / normal / high / critical。 */
    private String severity;

    /** 作用域，取值由业务约定，本模块只透传。 */
    private String scope;

    /** 状态：active / inactive / archived。 */
    private String status;

    /** 可见性：private / agent / public。 */
    private String visibility;
}
