# Adds device 2
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "token": "abcdef",
  "name": "rpi2",
  "registeredMicroservices": [
    {
      "url": "https://bluehorizon.network/documentation/sdr-device-api",
      "numAgreements": 1,
      "policy": "{json policy for rpi2 sdr}",
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
          "value": "1.0",
          "propType": "version",
          "op": "in"
        },
        {
          "name": "agreementProtocols",
          "value": "ExchangeManualTest",
          "propType": "list",
          "op": "in"
        },
        {
          "name": "dataVerification",
          "value": "true",
          "propType": "boolean",
          "op": "="
        }
      ]
    }
  ],
  "msgEndPoint": "whisper-id",
  "softwareVersions": {},
  "publicKey": "DEF"
}' $EXCHANGE_URL_ROOT/v1/devices/2 | $parse
