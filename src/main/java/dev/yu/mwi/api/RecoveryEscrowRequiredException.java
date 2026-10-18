package dev.yu.mwi.api;

/** A complete, validated adapter source was rejected by the runtime equipment rules. */
public final class RecoveryEscrowRequiredException extends RuntimeException {
    private final RecoveryEscrow escrow;
    public RecoveryEscrowRequiredException(RecoveryEscrow escrow) {
        super("Curios equipment was rejected; its complete source requires transactional escrow");
        this.escrow = java.util.Objects.requireNonNull(escrow);
    }
    public RecoveryEscrow escrow() { return escrow; }
}
