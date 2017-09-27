# Updates node 1
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "token": "abc123",
  "name": "rpi1",
  "pattern": "'$EXCHANGE_ORG'/mypat",
  "registeredMicroservices": [],
  "msgEndPoint": "whisper-id",
  "softwareVersions": {},
  "publicKey": "ABC"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/nodes/my@node | $parse
