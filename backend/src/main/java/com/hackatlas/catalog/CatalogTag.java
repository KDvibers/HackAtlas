package com.hackatlas.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "catalog_tag", schema = "hackatlas")
class CatalogTag {
    @Id
    @Column(length = 50)
    String id;

    @Column(nullable = false, length = 80)
    String label;

    @Column(name = "interest_allowed", nullable = false)
    boolean interestAllowed;

    protected CatalogTag() {
    }

    CatalogTag(String id, String label, boolean interestAllowed) {
        this.id = id;
        this.label = label;
        this.interestAllowed = interestAllowed;
    }
}
