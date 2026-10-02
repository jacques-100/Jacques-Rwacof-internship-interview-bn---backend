package com.rwacof.cherrytrack.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

    public NotFoundException(String entity, Object id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", entity + " not found: " + id);
    }
}
