# Gets all users as root
source `dirname $0`/../../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_ROOTPW" $HZN_EXCHANGE_URL/admin/dropdb/token | $parse