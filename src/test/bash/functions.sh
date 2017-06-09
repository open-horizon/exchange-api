# Meant to be sourced by the exchange manual test scripts

method=$(echo $1 | awk '{print toupper($0)}')

if [[ -z $parse ]]; then
	if [[ $2 == "-r" ]]; then
		parse=cat
		copts='-sS -w %{http_code}'
	elif [[ $2 == "-rr" ]]; then
		parse=cat
		copts='-sS'
	else
		parse="jq ."
		copts='-sS -w %{http_code}'
	fi
else
	copts='-s'
fi