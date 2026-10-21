package dev.pulseops.monitor.api;

import dev.pulseops.common.InvalidQueryException;
import dev.pulseops.common.TraceIdFilter;
import dev.pulseops.monitor.MonitorNotFoundException;
import dev.pulseops.monitor.config.InvalidMonitorConfigException;
import dev.pulseops.monitor.target.BlockedTargetException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MonitorNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleMonitorNotFound(MonitorNotFoundException ex,
                                                                  HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "MONITOR_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidMonitorConfigException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidConfig(InvalidMonitorConfigException ex,
                                                                HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(BlockedTargetException.class)
    public ResponseEntity<ApiErrorResponse> handleBlockedTarget(BlockedTargetException ex,
                                                                HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_TARGET_URL", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidQuery(InvalidQueryException ex,
                                                               HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                 HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "request body is invalid", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                               HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ex.getName() + " has an invalid value", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                     HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "HTTP method is not supported for this resource", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                        HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "content type is not supported", request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                                                         HttpServletRequest request) {
        return error(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE",
                "requested response type is not supported", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                             HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "resource was not found", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error while handling request, traceId={}",
                TraceIdFilter.currentTraceId(request), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected error", request);
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, message, TraceIdFilter.currentTraceId(request)));
    }
}
