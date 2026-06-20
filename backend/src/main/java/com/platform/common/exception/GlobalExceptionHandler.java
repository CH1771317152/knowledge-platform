package com.platform.common.exception;

import com.platform.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    ResponseEntity<ApiResponse<String>> handlePlatformException(PlatformException exception) {
        HttpStatus status = switch (exception.errorCode()) {
            case COMMON_UNAUTHORIZED, AUTH_TOKEN_EXPIRED, AUTH_TOKEN_INVALID,
                 AUTH_REFRESH_TOKEN_REUSED -> HttpStatus.UNAUTHORIZED;
            case COMMON_FORBIDDEN, AUTH_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case CONTENT_POST_NOT_FOUND, STORAGE_OBJECT_NOT_FOUND, USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(exception.errorCode().name()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiResponse<String>> handleValidationException(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.COMMON_BAD_REQUEST.name()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<String>> handleAccessDeniedException(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(ErrorCode.AUTH_ACCESS_DENIED.name()));
    }
}
