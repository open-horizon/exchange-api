# Gets a user
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:newpw" $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER | $parse