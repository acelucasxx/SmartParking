package com.smartparking.common.web;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import java.util.List;

/** 分页响应（契约 PageMeta + items）：{@code page} 从 1 开始，{@code size} 默认 20、最大 100 */
public record PageResult<T>(long total, int page, int size, List<T> items) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /** 校验分页参数，非法时 10001 */
    public static int[] normalize(Integer page, Integer size) {
        int p = page == null ? 1 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 1 || s < 1 || s > MAX_SIZE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "page 从 1 开始，size 为 1–" + MAX_SIZE);
        }
        return new int[] {p, s};
    }
}
