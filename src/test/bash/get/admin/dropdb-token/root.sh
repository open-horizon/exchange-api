# Gets all users as root
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" $EXCHANGE_URL_ROOT/v1/admin/dropdb/token | $parse