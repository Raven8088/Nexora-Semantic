package com.nexora.semantic.domain.shared;

import java.util.Objects;

/**
 * Who performs an action: the identity provider subject plus its kind.
 *
 * @param id subject identifier as issued by the identity provider
 */
public record PrincipalRef(String id, PrincipalType type) {

    public static final int MAX_ID_LENGTH = 255;

    public PrincipalRef {
        id = TextRules.requireText(id, "principal", MAX_ID_LENGTH);
        Objects.requireNonNull(type, "type");
    }

    public boolean isHuman() {
        return type == PrincipalType.HUMAN;
    }
}
