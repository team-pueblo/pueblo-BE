package com.pueblo.common.response;

import com.pueblo.common.exception.ErrorCode;
import com.pueblo.common.logging.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

public final class ApiErrorResponseFactory {
    private final ApiMessageResolver resolver;
    public ApiErrorResponseFactory(ApiMessageResolver resolver) { this.resolver=resolver; }
    public ApiErrorResponse create(ErrorCode code,HttpServletRequest request) { return create(code,request,List.of()); }
    public ApiErrorResponse create(ErrorCode code,HttpServletRequest request,List<ApiErrorDetail> details) {
        return new ApiErrorResponse(new ApiErrorResponse.Error(code.code(),resolver.resolve(code.messageKey(),resolver.locale(request)),
                details.stream().limit(100).toList()),RequestIdFilter.id(request));
    }
}
