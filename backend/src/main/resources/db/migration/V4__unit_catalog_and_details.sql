ALTER TABLE municipality ADD COLUMN ibge_code varchar(7) UNIQUE;
ALTER TABLE municipality ADD CONSTRAINT municipality_ibge_format CHECK (ibge_code ~ '^[0-9]{7}$');
CREATE TABLE municipality_catalog (
  state_code char(2) PRIMARY KEY,
  synced_at timestamptz NOT NULL,
  municipality_count integer NOT NULL CHECK (municipality_count > 0)
);
-- A catalog row is written only after a complete, atomic import succeeds.
ALTER TABLE defensoria_unit ADD COLUMN postal_code varchar(8),
  ADD COLUMN street varchar(150), ADD COLUMN street_number varchar(20),
  ADD COLUMN complement varchar(100), ADD COLUMN neighborhood varchar(100),
  ADD COLUMN email varchar(254);
ALTER TABLE defensoria_unit ADD CONSTRAINT unit_postal_format CHECK (postal_code ~ '^[0-9]{8}$');
CREATE TABLE unit_area (
  unit_id uuid NOT NULL REFERENCES defensoria_unit(id),
  area varchar(10) NOT NULL CHECK (area IN ('CIVEL','CRIMINAL')),
  PRIMARY KEY(unit_id,area)
);
INSERT INTO unit_area SELECT DISTINCT unit_id,area FROM affiliation;
-- Stop safely on legacy normalized duplicates; never merge or discard records.
CREATE UNIQUE INDEX unit_normalized_name ON defensoria_unit
  (municipality_id, lower(regexp_replace(btrim(name), '\s+', ' ', 'g')));
