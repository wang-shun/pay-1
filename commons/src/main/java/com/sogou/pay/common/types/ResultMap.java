package com.sogou.pay.common.types;

import java.util.Map;

public class ResultMap<T> extends Result<T> {

    private PMap<String, Object> data = new PMap<>();

    protected ResultMap(ResultStatus status, String message) {
        super(status, message);
    }

    public static <T> ResultMap<T> build() {
        return new ResultMap<>(ResultStatus.SUCCESS, null);
    }

    public static <T> ResultMap<T> build(ResultStatus resultStatus) {
        return new ResultMap<>(resultStatus, null);
    }

    public ResultMap<T> addItems(Map<String, ?> data) {
        this.data.putAll(data);
        return this;
    }

    public ResultMap<T> addItem(String key, Object value) {
        this.data.put(key, value);
        return this;
    }

    public Object getItem(String key){
        return data.get(key);
    }

    @Override
    public PMap<String, Object> getData() {
        return data;
    }
}
