package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.shared.DomainException;

/**
 * Approval invariants of decision D3, enforced in the domain whatever roles the caller holds (HTTP 403).
 */
public class ApprovalForbiddenException extends DomainException {

    public enum Reason {
        /** Only HUMAN principals approve: never an AI agent or a service. */
        NON_HUMAN,
        /** The approver authored or last edited the definition (separation of duties). */
        SELF_APPROVAL
    }

    private final Reason reason;

    public ApprovalForbiddenException(Reason reason) {
        super(reason == Reason.NON_HUMAN ? "Only human principals can approve definitions"
                : "The author or last editor of a definition cannot approve it");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
