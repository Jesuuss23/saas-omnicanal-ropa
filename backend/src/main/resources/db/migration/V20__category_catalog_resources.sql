CREATE TABLE category_catalog_resource (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    category VARCHAR(50) NOT NULL,
    link_url VARCHAR(2048),
    pdf_url VARCHAR(2048),
    image_url VARCHAR(2048),
    active VARCHAR(8) NOT NULL CHECK (active IN ('NONE','LINK','PDF','IMAGE')),
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (tenant_id, category),
    CHECK (active='NONE' OR active='LINK' AND link_url IS NOT NULL
        OR active='PDF' AND pdf_url IS NOT NULL OR active='IMAGE' AND image_url IS NOT NULL)
);
