CREATE TABLE municipality (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    state_code CHAR(2) NOT NULL,
    CONSTRAINT uk_municipality UNIQUE (name, state_code)
);

CREATE TABLE defensoria_unit (
    id UUID PRIMARY KEY,
    municipality_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    address VARCHAR(255),
    phone VARCHAR(20),
    active BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT fk_unit_municipality
        FOREIGN KEY (municipality_id)
        REFERENCES municipality(id),

    CONSTRAINT uk_unit_municipality
        UNIQUE (municipality_id, name)
);
