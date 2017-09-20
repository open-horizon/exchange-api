# Confirms the user/pw of a user
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' -H "Authorization:Basic $(echo -n root/root:$EXCHANGE_ROOTPW | base64)" $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/users | $parse
