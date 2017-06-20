# Tries to get a hash of a pw as user (not allowed)
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "password": "'$EXCHANGE_ROOTPW'"
}' $EXCHANGE_URL_ROOT/v1/admin/hashpw | $parse
