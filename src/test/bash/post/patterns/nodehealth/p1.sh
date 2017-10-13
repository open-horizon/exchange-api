# Adds a workload
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_AGBOTAUTH" -d '{
  "lastTime": "2017-11-01T12:25:38.767Z[UTC]"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/patterns/p1/nodehealth | $parse
