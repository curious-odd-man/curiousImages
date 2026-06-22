ALTER TABLE photo
    ADD COLUMN orientation INT NOT NULL DEFAULT 0;
ALTER TABLE photo
    ADD COLUMN camera_make VARCHAR(100);
ALTER TABLE photo
    ADD COLUMN camera_model VARCHAR(100);
ALTER TABLE photo
    ADD COLUMN lens_model VARCHAR(150);
ALTER TABLE photo
    ADD COLUMN exif_extra JSON;
