package com.akamai.miniwsa.service;

import com.akamai.miniwsa.api.dto.SecurityEventRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Wraps Bean Validation so individual events can be validated one at a time — required
 * because {@code @Valid @RequestBody} would short-circuit the whole batch on the first
 * failing event, which conflicts with per-event error reporting.
 */
@Component
public class SecurityEventValidator {

    private final Validator validator;

    public SecurityEventValidator(Validator validator) {
        this.validator = validator;
    }

    /** Returns "path: message" strings for each constraint violation; empty if valid. */
    public List<String> validate(SecurityEventRequest request) {
        Set<ConstraintViolation<SecurityEventRequest>> violations = validator.validate(request);
        List<String> errors = new ArrayList<>(violations.size());
        for (ConstraintViolation<SecurityEventRequest> violation : violations) {
            errors.add(violation.getPropertyPath() + ": " + violation.getMessage());
        }
        errors.sort(String::compareTo);
        return errors;
    }
}
