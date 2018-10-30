# Uses wildcars to searches for all nodes in the exchange
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic a1:abcdef" -d '{
  "desiredServices": [
    {
      "url": "https://bluehorizon.network/documentation/netspeed-node-api",
      "properties": [
        {
          "name": "arch",
          "value": "*",
          "propType": "string",
          "op": "in"
        },
        {
          "name": "agreementProtocols",
          "value": "ExchangeManualTest",
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
