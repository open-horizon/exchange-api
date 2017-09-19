# Run rest api methods as node
source `dirname $0`/functions.sh $1 ${@:3}

resource=${2#/}     # remove leading slash in case there, because we will add it below
curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_NODEAUTH" $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/$resource | $parse