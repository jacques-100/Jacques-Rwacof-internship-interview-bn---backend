package com.rwacof.cherrytrack.exception;

import org.springframework.http.HttpStatus;

public class CapacityExceededException extends ApiException {

    public CapacityExceededException(String message) {
        super(HttpStatus.CONFLICT, "CAPACITY_EXCEEDED", message);
    }
}
