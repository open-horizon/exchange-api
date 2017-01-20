# Tries to add a device with an invalid boolean property type
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "token": "a",
  "name": "rpi4",
  "registeredMicroservices": [
    {
      "url": "https://bluehorizon.network/documentation/sdr-device-api",
      "numAgreements": 1,
      "policy": "{json policy for rpi4 sdr}",
      "properties": [
        {
          "name": "arch",
          "value": "arm",
          "propType": "string",
          "op": "in"
        },
        {
          "name": "memory",
          "value": "400",
          "propType": "int",
          "op": ">="
        },
        {
          "name": "version",
          "value": "1.0.0",
          "propType": "version",
          "op": "in"
        },
        {
          "name": "dataVerification",
          "value": "truex",
          "propType": "boolean",
          "op": "="
        }
      ]
    }
  ],
  "msgEndPoint": "whisper-id",
  "softwareVersions": {},
  "publicKey": "DEF"
}' $EXCHANGE_URL_ROOT/v1/devices/4 | $parse
