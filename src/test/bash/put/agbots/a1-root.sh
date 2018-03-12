# Adds agbot a1 to exchange
source `dirname $0`/../../functions.sh '' '' $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_ROOTPW" -d '{
  "token": "abcdef",
  "name": "agbot1",
  "msgEndPoint": "",
  "publicKey": "AGBOTABC"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/agbots/a1 | $parse
