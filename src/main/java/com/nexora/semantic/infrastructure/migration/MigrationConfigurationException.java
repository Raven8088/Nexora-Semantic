package com.nexora.semantic.infrastructure.migration;

/** Invalid or incomplete migration settings. Messages name variables, never their values. */
public class MigrationConfigurationException extends RuntimeException {

    public MigrationConfigurationException(String message) {
        super(message);
    }
}
