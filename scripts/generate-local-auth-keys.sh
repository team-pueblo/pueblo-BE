#!/bin/sh
set -eu
umask 077
key_dir="${AUTH_KEY_DIRECTORY:-.local/auth}"
mkdir -p "$key_dir"
if [ -s "$key_dir/mail.key" ]; then
    echo 'Existing mail encryption key preserved.'
    exit 0
fi
if [ -e "$key_dir/mail.key" ]; then
    echo 'Empty mail key; inspect it before generating a replacement.' >&2
    exit 1
fi
openssl rand -base64 32 > "$key_dir/mail.key"
echo 'Local mail encryption key generated; set JWT_TOKEN separately in .env.'
