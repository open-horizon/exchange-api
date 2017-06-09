# Gets a hash of the specified pw
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '{
  "password": "'$EXCHANGE_ROOTPW'"
}' $EXCHANGE_URL_ROOT/v1/admin/hashpw | $parse
