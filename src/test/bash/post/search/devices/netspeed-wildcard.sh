# Uses wildcars to searches for all devices in the exchange
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "desiredMicroservices": [
    {
      "url": "https://bluehorizon.network/documentation/netspeed-device-api",
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
}' $EXCHANGE_URL_ROOT/v1/search/devices | $parse
