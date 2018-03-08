# Updates node 1
source `dirname $0`/../../functions.sh '' '' $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "token": "abc123",
  "name": "rpi1",
  "pattern": "'$EXCHANGE_ORG'/p1",
  "registeredServices": [
    {
      "url": "https://bluehorizon.network/services/sdr",
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
          "name": "version",
          "value": "1.0",
          "propType": "version",
          "op": "in"
        }
      ]
    },
    {
      "url": "https://bluehorizon.network/services/netspeed",
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
  "softwareVersions": {"horizon": "3.2.1"},
  "publicKey": "ABC"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/nodes/n1 | $parse
