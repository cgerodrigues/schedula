CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE staff_user (
 id uuid PRIMARY KEY, username varchar(120) UNIQUE NOT NULL, password_hash varchar(100) NOT NULL,
 name varchar(150) NOT NULL, role varchar(20) NOT NULL CHECK(role IN ('RH','DEFENSOR'))
);
CREATE TABLE affiliation (
 id uuid PRIMARY KEY, defender_id uuid NOT NULL REFERENCES staff_user(id), unit_id uuid NOT NULL REFERENCES defensoria_unit(id),
 area varchar(20) NOT NULL CHECK(area IN ('CIVEL','CRIMINAL')), UNIQUE(defender_id,unit_id,area)
);
CREATE TABLE availability_series (
 id uuid PRIMARY KEY, defender_id uuid NOT NULL REFERENCES staff_user(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE slot (
 id uuid PRIMARY KEY, affiliation_id uuid NOT NULL REFERENCES affiliation(id), defender_id uuid NOT NULL REFERENCES staff_user(id),
 series_id uuid NOT NULL REFERENCES availability_series(id), starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 published boolean NOT NULL DEFAULT true, CHECK(ends_at>starts_at),
 EXCLUDE USING gist (defender_id WITH =, tstzrange(starts_at,ends_at,'[)') WITH &&) WHERE (published)
);
CREATE INDEX slot_future ON slot(starts_at) WHERE published;
CREATE TABLE schedule_block (
 id uuid PRIMARY KEY, defender_id uuid NOT NULL REFERENCES staff_user(id), starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 reason varchar(250) NOT NULL, CHECK(ends_at>starts_at)
);
CREATE TABLE citizen (
 id uuid PRIMARY KEY, phone varchar(20) NOT NULL, first_name varchar(100) NOT NULL, last_name varchar(150) NOT NULL,
 identity_key varchar(300) NOT NULL, UNIQUE(phone,identity_key)
);
CREATE TABLE appointment (
 id uuid PRIMARY KEY, protocol uuid UNIQUE NOT NULL, slot_id uuid NOT NULL REFERENCES slot(id), citizen_id uuid NOT NULL REFERENCES citizen(id),
 area varchar(20) NOT NULL CHECK(area IN ('CIVEL','CRIMINAL')),
 status varchar(30) NOT NULL CHECK(status IN ('CONFIRMADO','REALIZADO','NAO_COMPARECEU','CANCELADO_CIDADAO','CANCELADO_DEFENSOR','REMARCADO')),
 previous_id uuid UNIQUE REFERENCES appointment(id), attended_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_active_slot ON appointment(slot_id) WHERE status='CONFIRMADO';
CREATE UNIQUE INDEX one_active_citizen_area ON appointment(citizen_id,area) WHERE status='CONFIRMADO';
CREATE TABLE audit_event (
 id bigserial PRIMARY KEY, actor varchar(120) NOT NULL, action varchar(80) NOT NULL, resource_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE whatsapp_conversation (
 phone varchar(20) PRIMARY KEY, state varchar(30) NOT NULL DEFAULT 'MUNICIPALITY', municipality_id uuid, unit_id uuid,
 area varchar(20), slot_id uuid, first_name varchar(100), last_name varchar(150), options text NOT NULL DEFAULT '[]',
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE whatsapp_inbox (message_id varchar(250) PRIMARY KEY, processed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE notification_outbox (
 id uuid PRIMARY KEY, phone varchar(20) NOT NULL, body text NOT NULL, template boolean NOT NULL DEFAULT false,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENT','FAILED')),
 attempts int NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now(), meta_message_id varchar(250),
 delivery_status varchar(30), created_at timestamptz NOT NULL DEFAULT now()
);
