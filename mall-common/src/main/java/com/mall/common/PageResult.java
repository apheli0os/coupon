package com.mall.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private long current;
    private long size;
    private long total;
    private long pages;
    private List<T> records;

    public static <T> PageResult<T> of(long current, long size, long total, List<T> records) {
        long pages = size <= 0 ? 0L : (total + size - 1) / size;
        return new PageResult<>(current, size, total, pages, records == null ? List.of() : records);
    }
}
