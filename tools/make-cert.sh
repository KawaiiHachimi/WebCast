#!/usr/bin/env bash
# Generate a self-signed TLS certificate for the TV's HTTPS server.
# The certificate is embedded in the APK assets (LAN-only tool; see README security note).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/assets/tls"
IP="${1:-}"
HOSTNAME="webcast.local"

mkdir -p "$OUT"
if [ -z "$IP" ]; then
  # Best-effort: current Wi-Fi/Ethernet IPv4 of this Mac's LAN, not the TV's.
  IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
fi
[ -n "$IP" ] || IP="192.168.1.100"

cat > "$OUT/openssl.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $HOSTNAME
O = WebCast
OU = LAN Casting
[ext]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:$HOSTNAME, IP:127.0.0.1, IP:$IP
subjectKeyIdentifier = hash
EOF

openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
  -config "$OUT/openssl.cnf" -keyout "$OUT/key.pem" -out "$OUT/cert.pem" >/dev/null 2>&1
openssl pkcs8 -topk8 -nocrypt -in "$OUT/key.pem" -outform DER -out "$OUT/key.pk8"
# PEM cert + DER PKCS#8 key are what the Android app loads (no keystore format issues).
rm -f "$OUT/openssl.cnf" "$OUT/key.pem"
echo "Wrote $OUT/cert.pem and $OUT/key.pk8 (SAN: DNS:$HOSTNAME, IP:$IP)"
openssl x509 -in "$OUT/cert.pem" -noout -subject -ext subjectAltName 2>/dev/null | sed 's/^/  /'
