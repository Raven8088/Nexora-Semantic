package com.nexora.semantic.domain.shared;

/**
 * The resource does not exist or belongs to another tenant. Both cases are reported the same way so that
 * identifiers of other tenants cannot be enumerated.
 */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resource) {
        super(resource + " not found");
    }
}
