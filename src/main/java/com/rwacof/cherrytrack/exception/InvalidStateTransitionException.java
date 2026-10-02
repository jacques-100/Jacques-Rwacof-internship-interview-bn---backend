package com.rwacof.cherrytrack.exception;

import org.springframework.http.HttpStatus;

public class InvalidStateTransitionException extends ApiException {

    public InvalidStateTransitionException(String message) {
        super(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", message);
    }
}
