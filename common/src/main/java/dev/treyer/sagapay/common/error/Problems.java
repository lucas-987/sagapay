package dev.treyer.sagapay.common.error;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * ProblemDetail factory (RFC 9457, media type application/problem+json) carrying
 * an application-level "code" field in addition to the standard type/title/status/detail fields.
 */
public final class Problems {

    private Problems() {}

    public static ProblemDetail of(HttpStatusCode status, AppErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code.name());
        return pd;
    }
}