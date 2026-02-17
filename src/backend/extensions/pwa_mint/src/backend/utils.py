from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
import datetime
import os
import json
import logging
import re
from pathlib import Path
import secrets
import string
import base64

from django.conf import settings

logger = logging.getLogger("website.pwa_mint")

# Use BASE_DIR so path is correct whether running from repo root or Docker (working_dir backend)
DATA_DIR = settings.BASE_DIR / "data" / "pwa_mint"
KEYSTORE_PATH = DATA_DIR / "keystore.p12"
INFO_PATH = DATA_DIR / "info.json"

def ensure_data_dir():
    if not DATA_DIR.exists():
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        # Ensure it's not browsable
        with open(DATA_DIR / ".gitignore", "w") as f:
            f.write("*")

def generate_random_password(length=16):
    alphabet = string.ascii_letters + string.digits
    return ''.join(secrets.choice(alphabet) for i in range(length))

def get_keystore_info():
    ensure_data_dir()
    if INFO_PATH.exists() and KEYSTORE_PATH.exists():
        with open(INFO_PATH, 'r') as f:
            return json.load(f)
    
    # Generate new info
    password = generate_random_password()
    alias = "geovault"
    
    # Use native cryptography to generate key and certificate
    private_key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
    )
    
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, u"GeoVault User"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, u"GeoVault"),
        x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, u"SelfHosted"),
        x509.NameAttribute(NameOID.LOCALITY_NAME, u"SelfHosted"),
        x509.NameAttribute(NameOID.STATE_OR_PROVINCE_NAME, u"Private"),
        x509.NameAttribute(NameOID.COUNTRY_NAME, u"US"),
    ])
    
    cert = x509.CertificateBuilder().subject_name(
        subject
    ).issuer_name(
        issuer
    ).public_key(
        private_key.public_key()
    ).serial_number(
        x509.random_serial_number()
    ).not_valid_before(
        datetime.datetime.utcnow()
    ).not_valid_after(
        # 10000 days validity
        datetime.datetime.utcnow() + datetime.timedelta(days=10000)
    ).sign(private_key, hashes.SHA256())

    # Export to PKCS12
    p12_data = pkcs12.serialize_key_and_certificates(
        alias.encode(),
        private_key,
        cert,
        None,
        serialization.BestAvailableEncryption(password.encode())
    )
    
    with open(KEYSTORE_PATH, "wb") as f:
        f.write(p12_data)
        
    # Calculate SHA256 fingerprint of the certificate
    fingerprint = cert.fingerprint(hashes.SHA256()).hex(":").upper()
    
    info = {
        "alias": alias,
        "store_password": password,
        "key_password": password,
        "fingerprint": fingerprint,
        "filename": "keystore.p12"
    }
    
    with open(INFO_PATH, 'w') as f:
        json.dump(info, f, indent=4)
        
    logger.info(f"Generated new PKCS12 keystore at {KEYSTORE_PATH} using native cryptography")
    return info

def get_assetlinks(package_name):
    info = get_keystore_info()
    if not info or not info.get("fingerprint"):
        return []
    
    return [
        {
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {
                "namespace": "android_app",
                "package_name": package_name,
                "sha256_cert_fingerprints": [info["fingerprint"]]
            }
        }
    ]

def get_apk_cache_path(package_id):
    # Sanitize package_id; store in data dir so APK survives restarts and is on mounted volume in Docker
    safe_name = re.sub(r'[^a-zA-Z0-9.-]', '_', package_id)
    return DATA_DIR / f"geovault_pwa_{safe_name}.apk"

def get_keystore_base64():
    if not KEYSTORE_PATH.exists():
        get_keystore_info()
    
    with open(KEYSTORE_PATH, "rb") as f:
        data = f.read()
        b64_data = base64.b64encode(data).decode('ascii')
        return f"data:application/octet-stream;base64,{b64_data}"
