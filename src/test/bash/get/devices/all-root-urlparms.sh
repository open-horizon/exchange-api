# Gets all devices as root
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/devices'?id=root&token='$EXCHANGE_ROOTPW | $parse