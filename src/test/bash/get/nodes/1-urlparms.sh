# Gets node 1
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/nodes/1'?id=1&token=abc123' | $parse
