# Adds node 3
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "token": "def",
  "name": "rpi3",
  "desiredServices": [
    {
      "url": "https://bluehorizon.network/documentation/sdr-node-api",
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
}' $EXCHANGE_URL_ROOT/v1/nodes/3 | $parse
