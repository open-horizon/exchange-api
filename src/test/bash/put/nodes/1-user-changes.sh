# Updates node 1
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "token": "abc123",
  "name": "rpi1-modified-micros",
  "registeredMicroservices": [
    {
      "url": "https://bluehorizon.network/documentation/sdr-node-api",
      "numAgreements": 1,
      "policy": "{json policy for rpi1 sdr}",
      "properties": [
        {
          "name": "arch",
          "value": "arm",
          "propType": "string",
          "op": "in"
        },
        {
          "name": "cpus",
          "value": "2",
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
    },
    {
      "url": "https://bluehorizon.network/documentation/netspeed222-node-api",
      "numAgreements": 1,
      "policy": "{json policy for rpi1 netspeed}",
      "properties": [
        {
          "name": "arch",
          "value": "arm",
          "propType": "string",
          "op": "in"
        },
        {
          "name": "version",
          "value": "1.0.0",
          "propType": "version",
          "op": "in"
        }
      ]
    }
  ],
  "msgEndPoint": "whisper-id",
  "softwareVersions": {"horizon": "3.2.1"}
}' $EXCHANGE_URL_ROOT/v1/nodes/n1 | $parse
