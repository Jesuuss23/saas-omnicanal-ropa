ALTER TABLE cart_reservation
    ADD COLUMN guest_name VARCHAR(150),
    ADD COLUMN guest_phone VARCHAR(16),
    ADD CONSTRAINT ck_reservation_guest_contact CHECK (
        (guest_name IS NULL AND guest_phone IS NULL) OR
        (guest_name IS NOT NULL AND length(trim(guest_name)) > 0 AND
         guest_phone IS NOT NULL AND guest_phone ~ '^[+]?[0-9]{7,15}$')
    );
