# Tries to get a node that does not exist
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" $EXCHANGE_URL_ROOT/v1/nodes/9999 | $parse