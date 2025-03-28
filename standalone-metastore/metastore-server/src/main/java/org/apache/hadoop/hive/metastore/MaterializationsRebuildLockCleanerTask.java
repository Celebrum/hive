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
package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.txn.TxnCommonUtils;
import org.apache.hadoop.hive.metastore.txn.NoMutex;
import org.apache.hadoop.hive.metastore.txn.TxnStore;
import org.apache.hadoop.hive.metastore.txn.TxnUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Cleaner for the {@link MaterializationsRebuildLockHandler}. It removes outdated locks
 * in the intervals specified by the input property.
 */
public class MaterializationsRebuildLockCleanerTask implements MetastoreTaskThread {
  private static final Logger LOG = LoggerFactory.getLogger(MaterializationsRebuildLockCleanerTask.class);

  private Configuration conf;
  private TxnStore txnHandler;
  private boolean shouldUseMutex = true;

  @Override
  public long runFrequency(TimeUnit unit) {
    return MetastoreConf.getTimeVar(conf, MetastoreConf.ConfVars.TXN_TIMEOUT, unit) / 2;
  }

  @Override
  public void setConf(Configuration configuration) {
    conf = configuration;
    txnHandler = TxnUtils.getTxnStore(conf);
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void run() {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Cleaning up materialization rebuild locks");
    }

    TxnStore.MutexAPI mutex = shouldUseMutex ? txnHandler.getMutexAPI() : new NoMutex();
    try (AutoCloseable closeable = mutex.acquireLock(TxnStore.MUTEX_KEY.MaterializationRebuild.name())) {
      ValidTxnList validTxnList = TxnCommonUtils.createValidReadTxnList(txnHandler.getOpenTxns(), 0);
      long removedCnt = txnHandler.cleanupMaterializationRebuildLocks(validTxnList,
          MetastoreConf.getTimeVar(conf, MetastoreConf.ConfVars.TXN_TIMEOUT, TimeUnit.MILLISECONDS));
      if (removedCnt > 0) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Number of materialization locks deleted: " + removedCnt);
        }
      }
    } catch (Throwable t) {
      LOG.error("Unexpected error in thread: {}, message: {}", Thread.currentThread().getName(), t.getMessage(), t);
    }
  }

  @Override
  public void enforceMutex(boolean enableMutex) {
    this.shouldUseMutex = enableMutex;
  }
}
