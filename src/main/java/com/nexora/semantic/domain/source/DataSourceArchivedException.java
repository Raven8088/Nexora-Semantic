package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.DomainException;

/** Metadata can only be imported into ACTIVE data sources. */
public class DataSourceArchivedException extends DomainException {

    public DataSourceArchivedException() {
        super("Data source is archived");
    }
}
