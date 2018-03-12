# Adds a workload
source `dirname $0`/../../../functions.sh '' '' $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_AGBOTAUTH" -d '{
  "serviceUrl": "https://bluehorizon.network/services/location",
  "secondsStale": 0,
  "startIndex": 0,
  "numEntries": 0
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/patterns/p1/search | $parse
