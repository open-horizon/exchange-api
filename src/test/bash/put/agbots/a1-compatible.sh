# Adds agbot a1 to exchange
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "token": "abcdef",
  "name": "agbot1",
  "msgEndPoint": "whisper-id"
}' $EXCHANGE_URL_ROOT/v1/agbots/a1 | $parse
