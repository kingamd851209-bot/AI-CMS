package com.xinhongru.operations;

import com.xinhongru.entity.UserEntity;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class OperationRequestIdentity {
    private OperationRequestIdentity() {}

    public static Long currentOperatorId() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        UserEntity user = TaleUtils.getLoginUser(request);
        return user == null || user.getId() == null ? null : user.getId().longValue();
    }

    public static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
