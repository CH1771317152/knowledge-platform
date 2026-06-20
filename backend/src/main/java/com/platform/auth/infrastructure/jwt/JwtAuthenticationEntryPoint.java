package com.platform.auth.infrastructure.jwt;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Writes a 401 JSON envelope ({@link ApiResponse#fail}) whenever a protected endpoint is reached
 * without (or with invalid) authentication. Honors any {@link PlatformException} the
 * {@link JwtAuthenticationFilter} stashed on the request so an expired token is reported as
 * {@code AUTH_TOKEN_EXPIRED} rather than a generic unauthorized.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Servlet-async-safe; ObjectMapper is thread-safe after configuration. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ErrorCode code = resolveCode(request);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        OBJECT_MAPPER.writeValue(response.getOutputStream(), ApiResponse.fail(code.name()));
    }

    private static ErrorCode resolveCode(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR);
        if (attribute instanceof PlatformException platformException) {
            return platformException.errorCode();
        }
        return ErrorCode.AUTH_TOKEN_INVALID;
    }
}
