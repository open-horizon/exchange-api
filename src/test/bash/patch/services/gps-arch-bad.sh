# Adds a service
source `dirname $0`/../../functions.sh '' '' $*

curl $copts -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "arch": "arm"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/services/bluehorizon.network-services-gps_1.2.3_amd64 | $parse
