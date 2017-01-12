# Adds agreement 123 of device 1 as the device
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic 1:abc123" -d '{
  "microservice": "https://bluehorizon.network/documentation/sdr-device-api",
  "state": "negotiating"
}' $EXCHANGE_URL_ROOT/v1/devices/1/agreements/123 | $parse
