package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.catalog.ApprovalForbiddenException;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.InvalidStateTransitionException;
import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lifecycle, approval rules (D3/D19), immutability of pairs and equivalence of relationships. */
class SemanticRelationshipTest {

    private static final PrincipalRef AUTHOR = new PrincipalRef("author", PrincipalType.HUMAN);
    private static final PrincipalRef EDITOR = new PrincipalRef("editor", PrincipalType.HUMAN);
    private static final PrincipalRef APPROVER = new PrincipalRef("approver", PrincipalType.HUMAN);
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final ColumnPair A = new ColumnPair(UUID.randomUUID(), UUID.randomUUID());
    private static final ColumnPair B = new ColumnPair(UUID.randomUUID(), UUID.randomUUID());

    private static SemanticRelationship draft(PrincipalRef author) {
        return SemanticRelationship.create(UUID.randomUUID(), Metadata.TENANT, Metadata.SOURCE, "Lecturas del equipo",
                null, RelationshipOrigin.SEMANTIC, SOURCE, TARGET, List.of(A, B), null,
                DeclaredCardinality.MANY_TO_ONE, Set.of(JoinType.INNER, JoinType.LEFT), null, author, Metadata.NOW);
    }

    @Test
    void everyNewRelationshipStartsAsDraftIncludingThoseFromForeignKeys() {
        assertThat(draft(AUTHOR).status()).isEqualTo(DefinitionStatus.DRAFT);
        SemanticRelationship fromFk = SemanticRelationship.create(UUID.randomUUID(), Metadata.TENANT, Metadata.SOURCE,
                "fk", null, RelationshipOrigin.DECLARED_FK, SOURCE, TARGET, List.of(A), UUID.randomUUID(),
                DeclaredCardinality.MANY_TO_ONE, Set.of(JoinType.INNER), null, AUTHOR, Metadata.NOW);
        assertThat(fromFk.status()).isEqualTo(DefinitionStatus.DRAFT);
        assertThat(fromFk.approvedBy()).isNull();
    }

    @Test
    void foreignKeyEvidenceIsSetExactlyForDeclaredForeignKeys() {
        assertThatThrownBy(() -> SemanticRelationship.create(UUID.randomUUID(), Metadata.TENANT, Metadata.SOURCE,
                "x", null, RelationshipOrigin.DECLARED_FK, SOURCE, TARGET, List.of(A), null,
                DeclaredCardinality.MANY_TO_ONE, Set.of(JoinType.INNER), null, AUTHOR, Metadata.NOW))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> SemanticRelationship.create(UUID.randomUUID(), Metadata.TENANT, Metadata.SOURCE,
                "x", null, RelationshipOrigin.SEMANTIC, SOURCE, TARGET, List.of(A), UUID.randomUUID(),
                DeclaredCardinality.MANY_TO_ONE, Set.of(JoinType.INNER), null, AUTHOR, Metadata.NOW))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> SemanticRelationship.create(UUID.randomUUID(), Metadata.TENANT, Metadata.SOURCE,
                "x", null, RelationshipOrigin.SEMANTIC, SOURCE, TARGET, List.of(), null,
                DeclaredCardinality.MANY_TO_ONE, Set.of(JoinType.INNER), null, AUTHOR, Metadata.NOW))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> SemanticRelationship.create(UUID.randomUUID(), Metadata.TENANT, Metadata.SOURCE,
                "x", null, RelationshipOrigin.SEMANTIC, SOURCE, TARGET, List.of(A), null,
                DeclaredCardinality.MANY_TO_ONE, Set.of(), null, AUTHOR, Metadata.NOW))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    void approverMustBeAHumanOtherThanAuthorAndLastEditor() {
        SemanticRelationship draft = draft(AUTHOR);
        assertThatThrownBy(() -> draft.approve(AUTHOR, Metadata.NOW))
                .isInstanceOfSatisfying(ApprovalForbiddenException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApprovalForbiddenException.Reason.SELF_APPROVAL));
        SemanticRelationship edited = draft.revise("Nuevo", null, DeclaredCardinality.MANY_TO_ONE,
                Set.of(JoinType.INNER), EDITOR, Metadata.NOW);
        assertThatThrownBy(() -> edited.approve(EDITOR, Metadata.NOW))
                .isInstanceOf(ApprovalForbiddenException.class);
        for (PrincipalType type : List.of(PrincipalType.AI, PrincipalType.SERVICE)) {
            assertThatThrownBy(() -> draft.approve(new PrincipalRef("machine", type), Metadata.NOW))
                    .isInstanceOfSatisfying(ApprovalForbiddenException.class,
                            e -> assertThat(e.reason()).isEqualTo(ApprovalForbiddenException.Reason.NON_HUMAN));
        }
        SemanticRelationship approved = edited.approve(APPROVER, Metadata.NOW);
        assertThat(approved.status()).isEqualTo(DefinitionStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("approver");
    }

    @Test
    void editingAnyStatusReturnsToDraftAndKeepsPairsAndEndpoints() {
        SemanticRelationship approved = draft(AUTHOR).approve(APPROVER, Metadata.NOW);
        SemanticRelationship revised = approved.revise("Otro nombre", "Descripción",
                DeclaredCardinality.ONE_TO_ONE, Set.of(JoinType.LEFT), EDITOR, Metadata.NOW);
        assertThat(revised.status()).isEqualTo(DefinitionStatus.DRAFT);
        assertThat(revised.approvedBy()).isNull();
        assertThat(revised.pairs()).isEqualTo(approved.pairs());
        assertThat(revised.sourceObjectId()).isEqualTo(SOURCE);
        assertThat(revised.signature()).isEqualTo(approved.signature());
    }

    @Test
    void pairsCannotBeChangedThroughTheModel() {
        SemanticRelationship draft = draft(AUTHOR);
        assertThatThrownBy(() -> draft.pairs().add(B)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Arrays.stream(SemanticRelationship.class.getMethods()).map(java.lang.reflect.Method::getName))
                .as("no operation changes pairs, endpoints or origin")
                .doesNotContain("withPairs", "withSource", "withTarget", "withOrigin");
        assertThat(Arrays.stream(SemanticRelationship.class.getRecordComponents()).map(RecordComponent::getName))
                .contains("pairs", "sourceObjectId", "targetObjectId");
    }

    @Test
    void lifecycleTransitionsFollowD19() {
        SemanticRelationship draft = draft(AUTHOR);
        assertThatThrownBy(() -> draft.invalidate("x", AUTHOR, Metadata.NOW))
                .isInstanceOf(InvalidStateTransitionException.class);
        SemanticRelationship inactive = draft.approve(APPROVER, Metadata.NOW)
                .invalidate("Invalidated by metadata import: FOREIGN_KEY_LOST", APPROVER, Metadata.NOW);
        assertThat(inactive.status()).isEqualTo(DefinitionStatus.INACTIVE);
        assertThat(inactive.approvedBy()).as("approval history is kept").isEqualTo("approver");
        assertThatThrownBy(() -> inactive.approve(APPROVER, Metadata.NOW))
                .as("an invalidated relationship is never reactivated without review")
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> inactive.deactivate(APPROVER, "x", Metadata.NOW))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> draft.deactivate(APPROVER, " ", Metadata.NOW))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    void signatureIgnoresPairOrderAndDirection() {
        String forward = RelationshipSignature.of(SOURCE, TARGET, List.of(A, B));
        String reordered = RelationshipSignature.of(SOURCE, TARGET, List.of(B, A));
        String reversed = RelationshipSignature.of(TARGET, SOURCE, List.of(
                new ColumnPair(A.targetColumnId(), A.sourceColumnId()),
                new ColumnPair(B.targetColumnId(), B.sourceColumnId())));
        assertThat(reordered).isEqualTo(forward);
        assertThat(reversed).isEqualTo(forward);
        assertThat(RelationshipSignature.of(SOURCE, TARGET, List.of(A))).isNotEqualTo(forward);
        assertThat(forward).matches("[0-9a-f]{64}");
    }
}
