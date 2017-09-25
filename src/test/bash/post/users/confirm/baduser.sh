# Tries to confirm a user's pw using bad creds
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic 123456789fddasdfddsffdsdfdfssdf" $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/users/$EXCHANGE_USER/confirm | $parse
