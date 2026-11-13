package com.example.pwt.common.api;

import java.util.List;

/**
 * 统一分页返回结构（设计文档 10.1）。
 *
 * @param items 当前页数据
 * @param page  页码，从 1 开始
 * @param size  每页条数，最大 100
 * @param total 总条数
 * @param <T>   元素类型
 * @author pwt
 */
public record PageResult<T>(List<T> items, int page, int size, long total) {

    /**
     * 构造分页结果。
     *
     * @param items 当前页数据，非空
     * @param page  页码
     * @param size  每页条数
     * @param total 总条数
     * @param <T>   元素类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> items, int page, int size, long total) {
        return new PageResult<>(List.copyOf(items), page, size, total);
    }
}
