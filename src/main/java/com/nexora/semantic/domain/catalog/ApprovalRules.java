package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.shared.PrincipalRef;

/**
 * Approval invariants of decision D3, shared by every approvable catalog element (definitions, relationships):
 * only HUMAN principals approve, and never the author or the last editor. Enforced in the domain, independently of
 * the roles carried by the caller's token.
 */
public final class ApprovalRules {

    private ApprovalRules() {
    }

    /** @throws ApprovalForbiddenException if {@code approver} may not approve an element of this authorship */
    public static void requireIndependentHuman(PrincipalRef approver, PrincipalRef author, PrincipalRef lastEditor) {
        if (!approver.isHuman()) {
            throw new ApprovalForbiddenException(ApprovalForbiddenException.Reason.NON_HUMAN);
        }
        if (approver.id().equals(author.id()) || approver.id().equals(lastEditor.id())) {
            throw new ApprovalForbiddenException(ApprovalForbiddenException.Reason.SELF_APPROVAL);
        }
    }
}
