# Tries to get an agreement of node 1 that does not exist
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" $EXCHANGE_URL_ROOT/v1/nodes/1/agreements/9999 | $parse