package com.rwacof.cherrytrack.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the station this request operates on, already checked against the caller's assignments.
 * The station comes from the X-Station-Id header; when absent, the user's first accessible station is used.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentStation {}
