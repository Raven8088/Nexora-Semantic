package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.EngineCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lifecycle (D19) and approval invariants (D3). */
class SemanticDefinitionTest {

    private static final Instant T0 = Instant.parse("2026-10-20T08:00:00Z");
    private static final PrincipalRef EDITOR = new PrincipalRef("editor", PrincipalType.HUMAN);
    private static final PrincipalRef APPROVER = new PrincipalRef("approver", PrincipalType.HUMAN);
    private static final PrincipalRef QWEN = new PrincipalRef("ai:qwen", PrincipalType.AI);

    private static SemanticDefinition draft(PrincipalRef author) {
        return SemanticDefinition.create(UUID.randomUUID(), TenantId.newId(), DataSourceId.newId(),
                new DefinitionKey("pedido"), "Pedido", "Pedidos de venta",
                new DefinitionSpec.ConceptSpec(UUID.randomUUID()), author, T0);
    }

    @Test
    void createdDefinitionsAreDraftsWithTheirOrigin() {
        SemanticDefinition manual = draft(EDITOR);
        assertThat(manual.status()).isEqualTo(DefinitionStatus.DRAFT);
        assertThat(manual.origin()).isEqualTo(DefinitionOrigin.MANUAL);
        assertThat(manual.kind()).isEqualTo(DefinitionKind.CONCEPT);
        assertThat(draft(QWEN).origin()).isEqualTo(DefinitionOrigin.AI);
    }

    @Test
    void anotherHumanApprovesADraft() {
        SemanticDefinition approved = draft(EDITOR).approve(APPROVER, T0.plusSeconds(60));
        assertThat(approved.status()).isEqualTo(DefinitionStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("approver");
        assertThat(approved.approvedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(approved.updatedBy()).isEqualTo(EDITOR);
    }

    @Test
    void authorsCannotApproveTheirOwnDefinitions() {
        assertThatThrownBy(() -> draft(EDITOR).approve(EDITOR, T0))
                .isInstanceOf(ApprovalForbiddenException.class)
                .extracting("reason").isEqualTo(ApprovalForbiddenException.Reason.SELF_APPROVAL);
    }

    @Test
    void lastEditorCannotApproveEither() {
        SemanticDefinition revised = draft(EDITOR).revise("Pedido", null,
                new DefinitionSpec.ConceptSpec(UUID.randomUUID()), APPROVER, T0);
        assertThatThrownBy(() -> revised.approve(APPROVER, T0))
                .isInstanceOf(ApprovalForbiddenException.class)
                .extracting("reason").isEqualTo(ApprovalForbiddenException.Reason.SELF_APPROVAL);
    }

    @ParameterizedTest
    @CsvSource({"AI", "SERVICE"})
    void nonHumanPrincipalsNeverApprove(PrincipalType type) {
        PrincipalRef machine = new PrincipalRef("machine", type);
        assertThatThrownBy(() -> draft(EDITOR).approve(machine, T0))
                .isInstanceOf(ApprovalForbiddenException.class)
                .extracting("reason").isEqualTo(ApprovalForbiddenException.Reason.NON_HUMAN);
    }

    @Test
    void anAiDraftNeedsAHumanApprover() {
        SemanticDefinition aiDraft = draft(QWEN);
        assertThatThrownBy(() -> aiDraft.approve(QWEN, T0)).isInstanceOf(ApprovalForbiddenException.class);
        assertThat(aiDraft.approve(APPROVER, T0).status()).isEqualTo(DefinitionStatus.APPROVED);
    }

    @Test
    void revisingAnApprovedDefinitionReturnsItToDraft() {
        SemanticDefinition approved = draft(EDITOR).approve(APPROVER, T0);
        SemanticDefinition revised = approved.revise("Pedido de venta", "x",
                new DefinitionSpec.ConceptSpec(UUID.randomUUID()), EDITOR, T0.plusSeconds(5));
        assertThat(revised.status()).isEqualTo(DefinitionStatus.DRAFT);
        assertThat(revised.approvedBy()).isNull();
        assertThat(revised.approvedAt()).isNull();
        assertThat(revised.name()).isEqualTo("Pedido de venta");
    }

    @Test
    void theKindCannotChange() {
        assertThatThrownBy(() -> draft(EDITOR).revise("Pedido", null,
                new DefinitionSpec.MetricSpec(UUID.randomUUID(), Aggregation.COUNT, null), EDITOR, T0))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    void onlyDraftsCanBeApproved() {
        SemanticDefinition approved = draft(EDITOR).approve(APPROVER, T0);
        assertThatThrownBy(() -> approved.approve(APPROVER, T0)).isInstanceOf(InvalidStateTransitionException.class);
        SemanticDefinition inactive = approved.deactivate(APPROVER, "no longer valid", T0);
        assertThatThrownBy(() -> inactive.approve(APPROVER, T0)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void deactivationRequiresAReasonAndKeepsTheLastApproval() {
        SemanticDefinition approved = draft(EDITOR).approve(APPROVER, T0);
        assertThatThrownBy(() -> approved.deactivate(APPROVER, " ", T0)).isInstanceOf(InvalidValueException.class);

        SemanticDefinition inactive = approved.deactivate(APPROVER, "Column dropped", T0);
        assertThat(inactive.status()).isEqualTo(DefinitionStatus.INACTIVE);
        assertThat(inactive.inactiveReason()).isEqualTo("Column dropped");
        assertThat(inactive.approvedBy()).isEqualTo("approver");
        assertThatThrownBy(() -> inactive.deactivate(APPROVER, "again", T0))
                .isInstanceOf(InvalidStateTransitionException.class);

        SemanticDefinition revived = inactive.revise("Pedido", null,
                new DefinitionSpec.ConceptSpec(UUID.randomUUID()), EDITOR, T0);
        assertThat(revived.status()).isEqualTo(DefinitionStatus.DRAFT);
        assertThat(revived.inactiveReason()).isNull();
    }

    @Test
    void onlyApprovedDefinitionsCanBeInvalidated() {
        assertThatThrownBy(() -> draft(EDITOR).invalidate("x", EDITOR, T0))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(draft(EDITOR).approve(APPROVER, T0).invalidate("metadata", EDITOR, T0).status())
                .isEqualTo(DefinitionStatus.INACTIVE);
    }

    @Test
    void keysAreValidated() {
        assertThatThrownBy(() -> new DefinitionKey("Ventas Netas")).isInstanceOf(InvalidValueException.class);
        assertThat(new DefinitionKey("ventas_netas").value()).isEqualTo("ventas_netas");
    }

    @Test
    void typeCategoriesOfPostgresqlTypesReportedByDiscovery() {
        EngineCode postgres = new EngineCode("postgresql");
        assertThat(TypeCategory.of(postgres, "numeric")).isEqualTo(TypeCategory.NUMERIC);
        assertThat(TypeCategory.of(postgres, "bigserial")).isEqualTo(TypeCategory.NUMERIC);
        assertThat(TypeCategory.of(postgres, "float4")).isEqualTo(TypeCategory.NUMERIC);
        assertThat(TypeCategory.of(postgres, "bpchar")).isEqualTo(TypeCategory.TEXT);
        assertThat(TypeCategory.of(postgres, "date")).isEqualTo(TypeCategory.DATE);
        assertThat(TypeCategory.of(postgres, "timestamptz")).isEqualTo(TypeCategory.TIMESTAMP_TZ);
        assertThat(TypeCategory.of(postgres, "timestamp")).isEqualTo(TypeCategory.TIMESTAMP);
        assertThat(TypeCategory.of(postgres, "jsonb")).isEqualTo(TypeCategory.OTHER);
        assertThat(TypeCategory.of(new EngineCode("oracle"), "NUMBER")).isEqualTo(TypeCategory.OTHER);
    }
}
