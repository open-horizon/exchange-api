# Adds a microservice
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '{
  "orgId": "IBM",
  "label": "International Business Machines Inc.",
  "description": "blah blah"
}' $EXCHANGE_URL_ROOT/v1/orgs | $parse
