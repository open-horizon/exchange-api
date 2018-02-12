# Gets node 1
source `dirname $0`/../../functions.sh '' '' $*

curl $copts -X GET -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/nodes/n1'?id=n1&token=abc123' | $parse
