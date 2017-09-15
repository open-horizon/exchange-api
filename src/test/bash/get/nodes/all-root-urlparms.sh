# Gets all nodes as root
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/nodes'?id=root&token='$EXCHANGE_ROOTPW | $parse