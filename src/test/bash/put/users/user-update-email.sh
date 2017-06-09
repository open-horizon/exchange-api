# Updates user
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "password": "",
  "email": "brucemp7@gmail.com"
}' $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER | $parse
