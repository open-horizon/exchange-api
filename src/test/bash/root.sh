# Run rest api methods as root
source `dirname $0`/functions.sh $1 ${@:3}

resource=${2#/}     # remove leading slash in case there, because we will add it below
curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_ROOTPW" $EXCHANGE_URL_ROOT/v1/$resource | $parse

# Some examples of custom jq parsing:
# jq .users.bp
# jq '.devices[] | {token,name}'
# jq '.devices[] | .token'
# jq '.devices | .["1"]'
# jq .devices.d2.token