# Changes the logging level
source `dirname $0`/../../../functions.sh PUT $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '{
  "loggingLevel": "INFO"
}' $EXCHANGE_URL_ROOT/v1/admin/loglevel | $parse
