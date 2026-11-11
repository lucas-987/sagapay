package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

/** The identity provider gave no token. The message names the failure, never a credential. */
public class ServiceTokenUnavailableException extends RuntimeException {

    public ServiceTokenUnavailableException(String message) {
        super(message);
    }

    public ServiceTokenUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
