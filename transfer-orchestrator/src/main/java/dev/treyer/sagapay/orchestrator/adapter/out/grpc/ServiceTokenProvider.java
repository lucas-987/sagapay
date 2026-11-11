package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

/** The orchestrator's own token for the ledger, whether or not a user is behind the call. */
public interface ServiceTokenProvider {

    /** A token valid for at least a short while longer. */
    String token();

    /** Forgets the token the ledger refused, so that the next call asks for a new one. */
    void drop();
}
