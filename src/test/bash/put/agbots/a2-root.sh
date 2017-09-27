# Adds agbot a2 as root
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_ROOTPW" -d '{
  "token": "abcdef",
  "name": "agbot2-asroot",
  "patterns": [{ "orgid": "myorg", "pattern": "mypattern" }],
  "msgEndPoint": "whisper-id",
  "publicKey": "AGBOTDEF"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/agbots/a2 | $parse
