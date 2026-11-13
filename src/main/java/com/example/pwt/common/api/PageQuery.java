package com.example.pwt.common.api;

import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;

/**
 * 分页入参（设计文档 10.1：page 从 1 开始，size 最大 100）。
 *
 * @param page 页码，从 1 开始
 * @param size 每页条数，1 到 100
 * @author pwt
 */
public record PageQuery(int page, int size) {

    /** 每页条数上限。 */
    public static final int MAX_SIZE = 100;

    /** 默认每页条数。 */
    public static final int DEFAULT_SIZE = 20;

    /**
     * 归一化并校验分页参数。
     *
     * <p>空值按缺省处理（page=1、size=20），越界直接拒绝而不是静默截断，
     * 避免调用方误以为拿到 500 条实际只有 100 条。
     *
     * @param page 请求页码，可为空
     * @param size 请求每页条数，可为空
     * @return 归一化后的分页参数
     * @throws BizException page 小于 1 或 size 超出上限
     */
    public static PageQuery of(Integer page, Integer size) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "page 必须从 1 开始");
        }
        if (resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "size 必须在 1 到 " + MAX_SIZE + " 之间");
        }
        return new PageQuery(resolvedPage, resolvedSize);
    }

    /**
     * 计算偏移量。
     *
     * @return 起始行号
     */
    public long offset() {
        return (long) (page - 1) * size;
    }
}
