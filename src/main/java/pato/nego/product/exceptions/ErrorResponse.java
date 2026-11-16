package pato.nego.product.exceptions;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        Map<String, String> fields) {

    public static ErrorResponse notFound(String message) {
        return new ErrorResponse(HttpStatus.NOT_FOUND.value(), message, null);
    }

    public static ErrorResponse validationFailed(Map<String, String> invalidFields) {
        return new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Validation failed", invalidFields);
    }
}
