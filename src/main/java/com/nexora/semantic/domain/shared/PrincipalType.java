package com.nexora.semantic.domain.shared;

/** Kind of authenticated principal. Only {@link #HUMAN} principals may ever approve definitions. */
public enum PrincipalType {
    HUMAN,
    SERVICE,
    AI
}
