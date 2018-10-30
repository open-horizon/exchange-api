# Tries to add a node with an invalid integer property type
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "token": "a",
  "name": "rpi4",
  "desiredServices": [
    {
      "url": "https://bluehorizon.network/documentation/sdr-node-api",
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
          "value": "400MB",
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
}' $EXCHANGE_URL_ROOT/v1/nodes/4 | $parse
