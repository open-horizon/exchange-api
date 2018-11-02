# Adds node 2 as the node
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic 2:abcdef" -d '{
  "token": "abcdef",
  "name": "rpi2-node",
  "desiredServices": [
    {
      "url": "https://bluehorizon.network/documentation/sdr-node-api",
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
}' $EXCHANGE_URL_ROOT/v1/nodes/2 | $parse
