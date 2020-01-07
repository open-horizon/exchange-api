#!/bin/bash

# Drives the overall process of scale testing the Exchange. See Usage below for details.

EX_PERF_REPORT_DIR="${EX_PERF_REPORT_DIR:-/tmp/exchangePerf}"
EX_PERF_ORG="${EX_PERF_ORG:-performancenodeagbot}"

function usage {
	cat <<EOF
Usage: $0 <hosts-file> <host-script> [host-script-args ...]

Drives the overall process of scale testing the Exchange by doing these main steps:
- Uses prsync to copy the latest versions of the exchange scale scripts/binaries, host scripts, and certs to /tmp on each scale node
- Removes exchange org $EX_PERF_ORG to eliminate any exchange resources leftover from a previous run (means HZN_EXCHANGE_URL, EXCHANGE_ROOTPW, and possibly EX_PERF_CERT_FILE must be set in this shell)
- Uses pssh to run the specified host script on each of the hosts in the hosts file
- Uses pslurp to copy the output from the hosts $EX_PERF_REPORT_DIR dir to the same dir on this machine (separated into hostname subdirs)
- Inspects all of the error files to report how many errors occurred

This script is dependent on the parallel-ssh suite of commands, see https://www.cyberciti.biz/cloud-computing/how-to-use-pssh-parallel-ssh-program-on-linux-unix/
for installing and using them.

The hosts-file is a simple list of hosts the scale test should be run on. The hosts are in the standard ssh format: <user>@<host-or-ip>

The host-script is a script you create in the current dir that is the top-level script that is run on each scale node.
This script can, for example, run different tests on different scale nodes by checking the scale node hostname.
It is a assumed that the host-script, and any other scripts it calls, are in the current dir. Those will all be
synced to the scale nodes.

Best practices for your host scripts:
- Sensitive info (e.g. exchange creds) should be received via cmd line args. The additional args passed into this scale driver script will be passed along to each host script.
- At the beginning remove possible output files on that scale node leftover from previous runs, so you do not have any confusing data leftover from previous runs
- Verify required software is already installed on that scale node
- Save a summary of the testing on that scale node in a file that ends with .summary
EOF

	exit 1
}

# Check the exit code of the cmd that was run
function checkexitcode {
	if [[ $1 == 0 ]]; then return; fi
    # write error msg to both the summary file and stderr
    echo "===============> command $2 failed with exit code $1, exiting."
    exit 3
    #if [[ "$3" != 'continue' ]]; then exit $1; fi
}

function confirmcmds {
    for c in $*; do
        if ! which $c >/dev/null; then
            echo "Error: $c is not installed but required, exiting"
            exit 2
        fi
    done
}

function linecount {
    wc -l "$1" |  awk '{ print $1 }'
}


if [[ -z $2 ]]; then usage; fi

hostsFile="$1"
hostScript="$2"
if [[ ! -x "$hostScript" ]]; then
    echo "Error: $hostScript must be in the current directory and executable"
    exit 2
fi

confirmcmds pssh prsync pslurp

echo "Copying the exchange scripts to /tmp on each scale node..."
exchScriptDir=$(dirname $0)
#pscp -h $hostsFile $exchScriptDir/* /tmp
prsync -h $hostsFile -r $exchScriptDir/ /tmp/
checkexitcode $? "copying $exchScriptDir"

goos="$GOOS"  # set this explicitly to, for example, drive this process from mac but run the scale instances on linux
if [[ -z "$goos" ]]; then       # then assume the scale instances are the same type as this machine
    if [[ $(uname) == "Darwin" ]]; then
        goos="darwin"
    else
        goos="linux"
    fi
fi
exchBinDir="$exchScriptDir/../../go/$goos"
echo "Building the exchange binaries for $goos and copying them from $exchBinDir to /tmp on each scale node..."
make -C "$exchScriptDir/../../go" $goos/node $goos/agbot
#pscp -h $hostsFile $exchBinDir/* /tmp
prsync -h $hostsFile -r $exchBinDir/ /tmp/
checkexitcode $? "copying $exchBinDir"

echo "Copying the host scripts and certs to /tmp on each scale node..."
prsync -h $hostsFile -r ./ /tmp
checkexitcode $? "copying host scripts"

echo "Removing orgs/$EX_PERF_ORG from exchange '$HZN_EXCHANGE_URL'..."
$exchScriptDir/deleteperforg.sh
checkexitcode $? "remove orgs/ $EX_PERF_ORG"

printf "\nRunning $hostScript on $(linecount $hostsFile) scale nodes...\n"
pssh -h "$hostsFile" -t 0 -P "/tmp/$hostScript" ${@:3}
checkexitcode $? "Running $hostScript on scale nodes"

if [[ $(cat $hostsFile) == "localhost" ]]; then
    # This is just a special case so i can run some small tests right on this machine
    printf "\nScale run completed, moving the output files from $EX_PERF_REPORT_DIR to $EX_PERF_REPORT_DIR/localhost on this host...\n"
    mkdir -p "$EX_PERF_REPORT_DIR/localhost"
    rm -rf "$EX_PERF_REPORT_DIR/localhost/*"
    mv $(/bin/ls -d $EX_PERF_REPORT_DIR/* | grep -v localhost) $EX_PERF_REPORT_DIR/localhost
else
    printf "\nScale run completed, gathering the output files from $EX_PERF_REPORT_DIR on the scale nodes to $EX_PERF_REPORT_DIR on this host...\n"
    rm -rf $EX_PERF_REPORT_DIR/*   # remove output files from previous runs
    pslurp -h "$hostsFile" -r -L "$EX_PERF_REPORT_DIR" "$EX_PERF_REPORT_DIR/*" .
    checkexitcode $? "Gathering output from scale nodes"
fi

printf "\nInspecting the output files in $EX_PERF_REPORT_DIR for errors...\n"
allSummaryFiles=$(/bin/ls $EX_PERF_REPORT_DIR/*/*/*.summary 2>/dev/null)
if [[ -z "$allSummaryFiles" ]]; then
    echo "No errors occurred during the scale run, but no output summaries were produced either."
    exit 2
fi
# the -l flag of grep returns only the file names of files that contain the pattern (not the matched text)
errorFiles=$(grep -l 'Error:==' $EX_PERF_REPORT_DIR/*/*/*.summary 2>/dev/null)
if [[ -z "$errorFiles" ]]; then
    echo "Scale run was 100% successful!"
    echo "Scale node summaries can be viewed with: head -n 100 $EX_PERF_REPORT_DIR/*/*/*.summary"
else
    printf "Errors occurred in the scale run, see files:\n$errorFiles\n"
    echo "View the errors and summaries with: head -n 100 $EX_PERF_REPORT_DIR/*/*/*.summary"
fi
