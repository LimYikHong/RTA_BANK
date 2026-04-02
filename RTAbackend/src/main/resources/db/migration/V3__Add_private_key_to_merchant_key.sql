-- V3: Add private_key_pem column to merchant_key table for RSA key pair storage
ALTER TABLE merchant_key ADD COLUMN private_key_pem TEXT AFTER public_key_pem;
