# Adds device 3 as root
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '{
  "token": "def",
  "name": "rpi3-from-root",
  "registeredMicroservices": [
    {
      "url": "https://bluehorizon.network/documentation/sdr-device-api",
      "numAgreements": 1,
      "policy": "{json policy for rpi3 sdr}",
      "properties": [
        {
          "name": "arch",
          "value": "arm",
          "propType": "string",
          "op": "in"
        },
        {
          "name": "memory",
          "value": "300",
          "propType": "int",
          "op": ">="
        },
        {
          "name": "version",
          "value": "2.0.0",
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
}' $EXCHANGE_URL_ROOT/v1/devices/3 | $parse
