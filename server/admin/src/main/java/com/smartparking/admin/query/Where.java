package com.smartparking.admin.query;

import java.util.ArrayList;
import java.util.List;

/** 拼接可选的筛选条件 */
final class Where {

    private final List<String> clauses = new ArrayList<>();
    private final List<Object> params = new ArrayList<>();

    Where and(String clause, Object value) {
        if (value != null) {
            clauses.add(clause);
            params.add(value);
        }
        return this;
    }

    String sql() {
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    List<Object> params() {
        return new ArrayList<>(params);
    }

    /** 参数后追加 LIMIT / OFFSET */
    List<Object> paged(int page, int size) {
        List<Object> all = params();
        all.add(size);
        all.add((long) (page - 1) * size);
        return all;
    }
}
