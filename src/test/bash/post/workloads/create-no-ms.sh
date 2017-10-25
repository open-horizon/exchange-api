# Adds a workload
source `dirname $0`/../../functions.sh POST '' $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "Location for x86_64",
  "description": "blah blah",
  "public": true,
  "workloadUrl": "https://bluehorizon.network/workloads/netspeed3",
  "version": "1.0.0",
  "arch": "amd64",
  "downloadUrl": "",
  "apiSpec": [],
  "userInput": [],
  "workloads": []
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/workloads | $parse
