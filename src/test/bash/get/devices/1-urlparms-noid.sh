# Gets device 1
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/devices/1'?token=abc123' | $parse
