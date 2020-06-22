#!/usr/bin/env bash

set -euo pipefail -x

. "${BASH_SOURCE%/*}/common.sh"

test -v WASB_CONTAINER
test -v WASB_ACCOUNT
test -v WASB_ACCESS_KEY

cleanup_docker_containers
start_docker_containers

# insert Azure credentials
# TODO replace core-site.xml.wasb-template with apply-site-xml-override.sh
exec_in_hadoop_master_container cp /docker/files/core-site.xml.wasb-template /etc/hadoop/conf/core-site.xml
exec_in_hadoop_master_container sed -i \
    -e "s|%WASB_ACCESS_KEY%|${WASB_ACCESS_KEY}|g" \
    -e "s|%WASB_ACCOUNT%|${WASB_ACCOUNT}|g" \
    /etc/hadoop/conf/core-site.xml

# create test table
table_path="wasb://${WASB_CONTAINER}@${WASB_ACCOUNT}.blob.core.windows.net/presto_test_external_fs_v2/"
exec_in_hadoop_master_container hadoop fs -mkdir -p "${table_path}"
exec_in_hadoop_master_container hadoop fs -copyFromLocal -f /tmp/test_table.csv{,.gz,.bz2,.lz4} "${table_path}"
exec_in_hadoop_master_container /usr/bin/hive -e "CREATE EXTERNAL TABLE presto_test_external_fs(t_bigint bigint) LOCATION '${table_path}'"

table_path="wasb://${WASB_CONTAINER}@${WASB_ACCOUNT}.blob.core.windows.net/presto_test_external_fs_with_header/"
exec_in_hadoop_master_container hadoop fs -mkdir -p "${table_path}"
exec_in_hadoop_master_container hadoop fs -copyFromLocal -f /docker/files/test_table_with_header.csv{,.gz,.bz2,.lz4} "${table_path}"
exec_in_hadoop_master_container /usr/bin/hive -e "
    CREATE EXTERNAL TABLE presto_test_external_fs_with_header(t_bigint bigint)
    STORED AS TEXTFILE
    LOCATION '${table_path}'
    TBLPROPERTIES ('skip.header.line.count'='1')"

table_path="wasb://${WASB_CONTAINER}@${WASB_ACCOUNT}.blob.core.windows.net/presto_test_external_fs_with_header_and_footer/"
exec_in_hadoop_master_container hadoop fs -mkdir -p "${table_path}"
exec_in_hadoop_master_container hadoop fs -copyFromLocal -f /docker/files/test_table_with_header_and_footer.csv{,.gz,.bz2,.lz4} "${table_path}"
exec_in_hadoop_master_container /usr/bin/hive -e "
    CREATE EXTERNAL TABLE presto_test_external_fs_with_header_and_footer(t_bigint bigint)
    STORED AS TEXTFILE
    LOCATION '${table_path}'
    TBLPROPERTIES ('skip.header.line.count'='2', 'skip.footer.line.count'='2')"

stop_unnecessary_hadoop_services

# restart hive-metastore to apply wasb changes in core-site.xml
docker exec "$(hadoop_master_container)" supervisorctl restart hive-metastore
retry check_hadoop

pushd $PROJECT_ROOT
set +e
./mvnw -B -pl presto-hive-hadoop2 test -P test-hive-hadoop2-wasb \
    -DHADOOP_USER_NAME=hive \
    -Dhive.hadoop2.metastoreHost=localhost \
    -Dhive.hadoop2.metastorePort=9083 \
    -Dhive.hadoop2.databaseName=default \
    -Dhive.hadoop2.wasb-container=${WASB_CONTAINER} \
    -Dhive.hadoop2.wasb-account=${WASB_ACCOUNT} \
    -Dhive.hadoop2.wasb-access-key=${WASB_ACCESS_KEY}
EXIT_CODE=$?
set -e
popd

exit ${EXIT_CODE}
