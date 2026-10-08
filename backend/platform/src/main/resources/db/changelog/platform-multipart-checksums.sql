--liquibase formatted sql
--changeset repricer:platform-multipart-checksums-001
ALTER TABLE platform_file_part ADD COLUMN checksum_crc32 text
  CHECK(checksum_crc32 IS NULL OR checksum_crc32 ~ '^[A-Za-z0-9+/]{6}==$');
COMMENT ON COLUMN platform_file_part.checksum_crc32 IS 'Confirmed per-part CRC32 carried unchanged into CompleteMultipartUpload; earlier completed evidence may lack this field';
