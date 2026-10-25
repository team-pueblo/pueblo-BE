package com.pueblo.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ApiErrorResponse(Error error, String requestId) {
    public record Error(String code, String message,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ApiErrorDetail> details) {
        public Error { details=List.copyOf(details); }
    }
}
