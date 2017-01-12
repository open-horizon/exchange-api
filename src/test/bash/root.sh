# Gets all users as root
source `dirname $0`/functions.sh $1 ${@:3}

curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" $EXCHANGE_URL_ROOT/v1/$2 | $parse

# Some examples of custom jq parsing:
# jq .users.bp
# jq '.devices[] | {token,name}'
# jq '.devices[] | .token'
# jq '.devices | .["1"]'
# jq .devices.d2.token