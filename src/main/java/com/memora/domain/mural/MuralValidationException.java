package com.memora.domain.mural;

import java.util.Objects;

public final class MuralValidationException extends RuntimeException {
    private final String field;
    private final String code;

    public MuralValidationException(String field, String code, String detail) {
        super(detail);
        this.field = Objects.requireNonNull(field, "field");
        this.code = Objects.requireNonNull(code, "code");
    }

    public String field() {
        return field;
    }

    public String code() {
        return code;
    }
}
