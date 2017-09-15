# Uses wildcars to searches for all nodes in the exchange
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "desiredMicroservices": [
    {
      "url": "https://bluehorizon.network/documentation/sdr-node-api",
      "properties": [
        {
          "name": "arch",
          "value": "arm",
          "propType": "wildcard",
          "op": "in"
        },
        {
          "name": "agreementProtocols",
          "value": "Citizen Scientist",
          "propType": "list",
          "op": "in"
        },
        {
          "name": "version",
          "value": "*",
          "propType": "version",
          "op": "in"
        }
      ]
    }
  ],
  "secondsStale": 0,
  "propertiesToReturn": [
    "string"
  ],
  "startIndex": 0,
  "numEntries": 0
}' $EXCHANGE_URL_ROOT/v1/search/nodes | $parse
