package com.memora.presentation.http;

import com.memora.application.photo.PhotoNotFoundException;
import com.memora.application.mural.MuralIdempotencyConflictException;
import com.memora.application.mural.MuralNotFoundException;
import com.memora.application.mural.MuralQueryException;
import com.memora.application.mural.MuralStaleVersionException;
import com.memora.domain.photo.PhotoStateConflictException;
import com.memora.domain.photo.PhotoValidationException;
import com.memora.domain.photo.UploadExpiredException;
import com.memora.domain.mural.MuralValidationException;
import com.memora.domain.mural.MuralNotEditableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import software.amazon.awssdk.core.exception.SdkException;

import java.net.URI;
import java.util.UUID;

@RestControllerAdvice
public class ApiProblemHandler {
    @ExceptionHandler(PhotoValidationException.class)
    ResponseEntity<ProblemDetail> validation(PhotoValidationException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case SIZE_LIMIT_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            case INVALID_SIZE, INVALID_CONTENT -> HttpStatus.BAD_REQUEST;
        };
        return problem(status, exception.code().name().toLowerCase(), exception.getMessage(), request);
    }

    @ExceptionHandler(MuralValidationException.class)
    ResponseEntity<ProblemDetail> muralValidation(MuralValidationException exception, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.UNPROCESSABLE_ENTITY, exception.code(),
                exception.getMessage(), request);
        ProblemDetail body = response.getBody();
        if (body != null) {
            body.setProperty("field", exception.field());
        }
        return response;
    }

    @ExceptionHandler(MuralQueryException.class)
    ResponseEntity<ProblemDetail> invalidMuralQuery(MuralQueryException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_mural_request", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidIfMatchException.class)
    ResponseEntity<ProblemDetail> invalidIfMatch(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_if_match", "O header If-Match é inválido.", request);
    }

    @ExceptionHandler(MissingIfMatchException.class)
    ResponseEntity<ProblemDetail> missingIfMatch(HttpServletRequest request) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "precondition_required",
                "Envie o ETag atual do mural no header If-Match.", request);
    }

    @ExceptionHandler(MuralStaleVersionException.class)
    ResponseEntity<ProblemDetail> staleMuralVersion(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "stale_mural_version",
                "O mural mudou desde a última leitura.", request);
    }

    @ExceptionHandler(MuralNotEditableException.class)
    ResponseEntity<ProblemDetail> muralNotEditable(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "mural_not_editable",
                "Somente rascunhos podem ser alterados.", request);
    }

    @ExceptionHandler(MuralIdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> idempotencyConflict(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "idempotency_conflict",
                "A chave de idempotência já foi usada com outro conteúdo.", request);
    }

    @ExceptionHandler(MuralNotFoundException.class)
    ResponseEntity<ProblemDetail> muralNotFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not_found", "O mural não está disponível.", request);
    }

    @ExceptionHandler({UploadExpiredException.class, PhotoStateConflictException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "state_conflict", exception.getMessage(), request);
    }

    @ExceptionHandler(PhotoNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(PhotoNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not_found", "A fotografia não está disponível.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidRequest(MethodArgumentNotValidException exception, HttpServletRequest request) {
        FieldError firstError = exception.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String detail = firstError == null ? "Os dados enviados são inválidos."
                : "Campo inválido: " + firstError.getField() + ".";
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", detail, request);
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> malformedRequest(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Os dados enviados são inválidos.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Acesso não permitido.", request);
    }

    @ExceptionHandler({SdkException.class, DataAccessException.class})
    ResponseEntity<ProblemDetail> dependencyUnavailable(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable",
                "Uma dependência está temporariamente indisponível.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Não foi possível concluir a solicitação.", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                   HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setProperty("code", code);
        body.setProperty("traceId", UUID.randomUUID().toString());
        return ResponseEntity.status(status)
                .header("X-Trace-Id", body.getProperties().get("traceId").toString())
                .body(body);
    }
}
