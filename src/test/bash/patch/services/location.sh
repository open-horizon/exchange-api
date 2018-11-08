# Adds a service
source `dirname $0`/../../functions.sh '' '' $*

curl $copts -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $HZN_ORG_ID/$HZN_EXCHANGE_USER_AUTH" -d '{
  "requiredServices": [
    {
      "url": "https://bluehorizon.network/services/gps",
      "org": "'$HZN_ORG_ID'",
      "version": "[1.0.0,INFINITY)",
      "arch": "amd64"
    }
  ]
}' $HZN_EXCHANGE_URL/orgs/$HZN_ORG_ID/services/bluehorizon.network-services-location_4.5.6_amd64 | $parse
