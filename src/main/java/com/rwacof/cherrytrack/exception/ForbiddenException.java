package com.rwacof.cherrytrack.exception;

import org.springframework.http.HttpStatus;

/** The caller is authenticated but not allowed to do this (for example, a station they are not assigned to). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String code, String message) {
        super(HttpStatus.FORBIDDEN, code, message);
    }
}
