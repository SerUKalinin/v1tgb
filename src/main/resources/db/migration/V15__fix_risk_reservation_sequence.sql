CREATE SEQUENCE IF NOT EXISTS risk_reservation_seq
    START WITH 1
    INCREMENT BY 1;

ALTER TABLE risk_reservation_log
    ALTER COLUMN sequence_id
        SET DEFAULT nextval('risk_reservation_seq');

SELECT setval(
               'risk_reservation_seq',
               GREATEST(
                       COALESCE((SELECT MAX(sequence_id) FROM risk_reservation_log), 1),
                       1
               )
       );

ALTER TABLE risk_reservation_log
    ALTER COLUMN sequence_id
        SET NOT NULL;