# Adds agbot a1 to exchange
source `dirname $0`/../../functions.sh GET $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "type:person" -H "id:feuser" -H "orgid:$EXCHANGE_ORG" -H "issuer:IBM_ID" -d '{
  "token": "abcdef",
  "name": "fe-agbot",
  "patterns": [{ "orgid": "myorg", "pattern": "mypattern" }],
  "msgEndPoint": "whisper-id",
  "publicKey": "AGBOTABC"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/agbots/feagbot | $parse
