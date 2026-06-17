package com.oldwei.isup.model;

/**
 * HTTP status codes.
 *
 * @author Lion Li
 */
public interface HttpStatus {
    /**
     * Success.
     */
    int SUCCESS = 200;

    /**
     * Created.
     */
    int CREATED = 201;

    /**
     * Accepted.
     */
    int ACCEPTED = 202;

    /**
     * No content.
     */
    int NO_CONTENT = 204;

    /**
     * Moved permanently.
     */
    int MOVED_PERM = 301;

    /**
     * See other.
     */
    int SEE_OTHER = 303;

    /**
     * Not modified.
     */
    int NOT_MODIFIED = 304;

    /**
     * Bad request.
     */
    int BAD_REQUEST = 400;

    /**
     * Unauthorized.
     */
    int UNAUTHORIZED = 401;

    /**
     * Forbidden.
     */
    int FORBIDDEN = 403;

    /**
     * Resource or service not found.
     */
    int NOT_FOUND = 404;

    /**
     * HTTP method not allowed.
     */
    int BAD_METHOD = 405;

    /**
     * Conflict or locked resource.
     */
    int CONFLICT = 409;

    /**
     * Unsupported media type.
     */
    int UNSUPPORTED_TYPE = 415;

    /**
     * Internal server error.
     */
    int ERROR = 500;

    /**
     * Not implemented.
     */
    int NOT_IMPLEMENTED = 501;

    /**
     * Service unavailable.
     */
    int SERVICE_UNAVAILABLE = 503;

    /**
     * Warning.
     */
    int WARN = 601;
}
