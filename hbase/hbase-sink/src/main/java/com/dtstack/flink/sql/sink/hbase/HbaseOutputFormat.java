/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.dtstack.flink.sql.sink.hbase;

import com.dtstack.flink.sql.sink.MetricOutputFormat;
import com.dtstack.flink.sql.sink.hbase.utils.HbaseConfigUtils;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.types.Row;
import org.apache.flink.util.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.AuthUtil;
import org.apache.hadoop.hbase.ChoreService;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.ScheduledChore;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.PrivilegedAction;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * author: jingzhen@dtstack.com
 * date: 2017-6-29
 */
public class HbaseOutputFormat extends MetricOutputFormat {

    private static final Logger LOG = LoggerFactory.getLogger(HbaseOutputFormat.class);

    private String host;
    private String zkParent;
    private String[] rowkey;
    private String tableName;
    private String[] columnNames;
    private String[] columnTypes;
    private Map<String, String> columnNameFamily;
    private boolean kerberosAuthEnable;
    private String regionserverKeytabFile;
    private String regionserverPrincipal;
    private String securityKrb5Conf;
    private String zookeeperSaslClient;
    private String[] families;
    private String[] qualifiers;

    private transient Configuration conf;
    private transient Connection conn;
    private transient Table table;

    public final SimpleDateFormat ROWKEY_DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");

    private static int rowLenth = 1000;
    private static int dirtyDataPrintFrequency = 1000;

    private transient ChoreService choreService;

    @Override
    public void configure(org.apache.flink.configuration.Configuration parameters) {
        LOG.warn("---configure---");
        try {
            conf = HBaseConfiguration.create();
            if (kerberosAuthEnable) {
                conf.set(HbaseConfigUtils.KEY_HBASE_ZOOKEEPER_QUORUM, host);
                conf.set(HbaseConfigUtils.KEY_HBASE_ZOOKEEPER_ZNODE_QUORUM, zkParent);
                fillSyncKerberosConfig(conf, regionserverKeytabFile, regionserverPrincipal, zookeeperSaslClient, securityKrb5Conf);

                UserGroupInformation userGroupInformation = HbaseConfigUtils.loginAndReturnUGI(conf, regionserverPrincipal, regionserverKeytabFile);
                org.apache.hadoop.conf.Configuration finalConf = conf;
                conn = userGroupInformation.doAs(new PrivilegedAction<Connection>() {
                    @Override
                    public Connection run() {
                        try {

                            ScheduledChore authChore = AuthUtil.getAuthChore(finalConf);
                            if (authChore != null) {
                                ChoreService choreService = new ChoreService("hbaseKerberosSink");
                                choreService.scheduleChore(authChore);
                            }

                            return ConnectionFactory.createConnection(finalConf);
                        } catch (IOException e) {
                            LOG.error("Get connection fail with config:{}", finalConf);
                            throw new RuntimeException(e);
                        }
                    }
                });
            } else {
                conf.set(HbaseConfigUtils.KEY_HBASE_ZOOKEEPER_QUORUM, host);
                conf.set(HbaseConfigUtils.KEY_HBASE_ZOOKEEPER_ZNODE_QUORUM, zkParent);
                conn = ConnectionFactory.createConnection(conf);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void open(int taskNumber, int numTasks) throws IOException {
        LOG.warn("---open---");
        table = conn.getTable(TableName.valueOf(tableName));
        LOG.warn("---open end(get table from hbase) ---");
        initMetric();
    }

    @Override
    public void writeRecord(Tuple2 tuple2) {

        Tuple2<Boolean, Row> tupleTrans = tuple2;
        Boolean retract = tupleTrans.getField(0);
        if (!retract) {
            //FIXME 暂时不处理hbase删除操作--->hbase要求有key,所有认为都是可以执行update查找
            return;
        }

        Row record = tupleTrans.getField(1);
        List<String> rowKeyValues = getRowKeyValues(record);
        // all rowkey not null
        if (rowKeyValues.size() != rowkey.length) {
            LOG.error("row key value must not null,record is ..", record);
            outDirtyRecords.inc();
            return;
        }

        String key = StringUtils.join(rowKeyValues, "-");
        Put put = new Put(key.getBytes());
        for (int i = 0; i < record.getArity(); ++i) {
            Object fieldVal = record.getField(i);
            if (fieldVal == null) {
                continue;
            }
            byte[] val = fieldVal.toString().getBytes();
            byte[] cf = families[i].getBytes();
            byte[] qualifier = qualifiers[i].getBytes();

            put.addColumn(cf, qualifier, val);
        }

        try {
            table.put(put);
        } catch (IOException e) {
            outDirtyRecords.inc();
            if (outDirtyRecords.getCount() % dirtyDataPrintFrequency == 0 || LOG.isDebugEnabled()) {
                LOG.error("record insert failed ..", record.toString());
                LOG.error("", e);
            }
        }

        if (outRecords.getCount() % rowLenth == 0) {
            LOG.info(record.toString());
        }
        outRecords.inc();

    }

    private List<String> getRowKeyValues(Row record) {
        List<String> rowKeyValues = Lists.newArrayList();
        for (int i = 0; i < rowkey.length; ++i) {
            String colName = rowkey[i];
            int rowKeyIndex = 0;  //rowkey index
            for (; rowKeyIndex < columnNames.length; ++rowKeyIndex) {
                if (columnNames[rowKeyIndex].equals(colName)) {
                    break;
                }
            }

            if (rowKeyIndex != columnNames.length && record.getField(rowKeyIndex) != null) {
                Object field = record.getField(rowKeyIndex);
                if (field == null) {
                    continue;
                } else if (field instanceof java.util.Date) {
                    java.util.Date d = (java.util.Date) field;
                    rowKeyValues.add(ROWKEY_DATE_FORMAT.format(d));
                } else {
                    rowKeyValues.add(field.toString());
                }
            }
        }
        return rowKeyValues;
    }

    @Override
    public void close() throws IOException {
        if (conn != null) {
            conn.close();
            conn = null;
        }

        if (null != choreService) {
            choreService.shutdown();
        }
    }

    private HbaseOutputFormat() {
    }

    public static HbaseOutputFormatBuilder buildHbaseOutputFormat() {
        return new HbaseOutputFormatBuilder();
    }

    public static class HbaseOutputFormatBuilder {

        private HbaseOutputFormat format;

        private HbaseOutputFormatBuilder() {
            format = new HbaseOutputFormat();
        }

        public HbaseOutputFormatBuilder setHost(String host) {
            format.host = host;
            return this;
        }

        public HbaseOutputFormatBuilder setZkParent(String parent) {
            format.zkParent = parent;
            return this;
        }


        public HbaseOutputFormatBuilder setTable(String tableName) {
            format.tableName = tableName;
            return this;
        }

        public HbaseOutputFormatBuilder setRowkey(String[] rowkey) {
            format.rowkey = rowkey;
            return this;
        }

        public HbaseOutputFormatBuilder setColumnNames(String[] columnNames) {
            format.columnNames = columnNames;
            return this;
        }

        public HbaseOutputFormatBuilder setColumnTypes(String[] columnTypes) {
            format.columnTypes = columnTypes;
            return this;
        }

        public HbaseOutputFormatBuilder setColumnNameFamily(Map<String, String> columnNameFamily) {
            format.columnNameFamily = columnNameFamily;
            return this;
        }

        public HbaseOutputFormatBuilder setKerberosAuthEnable(boolean kerberosAuthEnable) {
            format.kerberosAuthEnable = kerberosAuthEnable;
            return this;
        }

        public HbaseOutputFormatBuilder setRegionserverKeytabFile(String regionserverKeytabFile) {
            format.regionserverKeytabFile = regionserverKeytabFile;
            return this;
        }

        public HbaseOutputFormatBuilder setRegionserverPrincipal(String regionserverPrincipal) {
            format.regionserverPrincipal = regionserverPrincipal;
            return this;
        }

        public HbaseOutputFormatBuilder setSecurityKrb5Conf(String securityKrb5Conf) {
            format.securityKrb5Conf = securityKrb5Conf;
            return this;
        }

        public HbaseOutputFormatBuilder setZookeeperSaslClient(String zookeeperSaslClient) {
            format.zookeeperSaslClient = zookeeperSaslClient;
            return this;
        }


        public HbaseOutputFormat finish() {
            Preconditions.checkNotNull(format.host, "zookeeperQuorum should be specified");
            Preconditions.checkNotNull(format.tableName, "tableName should be specified");
            Preconditions.checkNotNull(format.columnNames, "columnNames should be specified");
            Preconditions.checkArgument(format.columnNames.length != 0, "columnNames length should not be zero");

            String[] families = new String[format.columnNames.length];
            String[] qualifiers = new String[format.columnNames.length];

            if (format.columnNameFamily != null) {
                Set<String> keySet = format.columnNameFamily.keySet();
                String[] columns = keySet.toArray(new String[keySet.size()]);
                for (int i = 0; i < columns.length; ++i) {
                    String col = columns[i];
                    String[] part = col.split(":");
                    families[i] = part[0];
                    qualifiers[i] = part[1];
                }
            }
            format.families = families;
            format.qualifiers = qualifiers;

            return format;
        }

    }

    private void fillSyncKerberosConfig(Configuration config, String regionserverKeytabFile, String regionserverPrincipal,
                                        String zookeeperSaslClient, String securityKrb5Conf) throws IOException {
        if (StringUtils.isEmpty(regionserverKeytabFile)) {
            throw new IllegalArgumentException("Must provide regionserverKeytabFile when authentication is Kerberos");
        }
        String regionserverKeytabFilePath = System.getProperty("user.dir") + File.separator + regionserverKeytabFile;
        LOG.info("regionserverKeytabFilePath:{}",regionserverKeytabFilePath);
        config.set(HbaseConfigUtils.KEY_HBASE_MASTER_KEYTAB_FILE, regionserverKeytabFilePath);
        config.set(HbaseConfigUtils.KEY_HBASE_REGIONSERVER_KEYTAB_FILE, regionserverKeytabFilePath);

        if (StringUtils.isEmpty(regionserverPrincipal)) {
            throw new IllegalArgumentException("Must provide regionserverPrincipal when authentication is Kerberos");
        }
        config.set(HbaseConfigUtils.KEY_HBASE_MASTER_KERBEROS_PRINCIPAL, regionserverPrincipal);
        config.set(HbaseConfigUtils.KEY_HBASE_REGIONSERVER_KERBEROS_PRINCIPAL, regionserverPrincipal);
        config.set(HbaseConfigUtils.KEY_HBASE_SECURITY_AUTHORIZATION, "true");
        config.set(HbaseConfigUtils.KEY_HBASE_SECURITY_AUTHENTICATION, "kerberos");


        if (!StringUtils.isEmpty(zookeeperSaslClient)) {
            System.setProperty(HbaseConfigUtils.KEY_ZOOKEEPER_SASL_CLIENT, zookeeperSaslClient);
        }

        if (!StringUtils.isEmpty(securityKrb5Conf)) {
            String krb5ConfPath = System.getProperty("user.dir") + File.separator + securityKrb5Conf;
            LOG.info("krb5ConfPath:{}", krb5ConfPath);
            System.setProperty(HbaseConfigUtils.KEY_JAVA_SECURITY_KRB5_CONF, krb5ConfPath);
        }
    }


}
