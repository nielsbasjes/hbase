/**
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
package org.apache.hadoop.hbase.ipc;

import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.hbase.Abortable;
import org.apache.yetus.audience.InterfaceAudience;

@InterfaceAudience.Private
public class FastPathRpcHandler extends RpcHandler {
  // Below are for fast-path support. Push this Handler on to the fastPathHandlerStack Deque
  // if an empty queue of CallRunners so we are available for direct handoff when one comes in.
  final Deque<FastPathRpcHandler> fastPathHandlerStack;
  // Semaphore to coordinate loading of fastpathed loadedTask and our running it.
  // UNFAIR synchronization.
  private Semaphore semaphore = new Semaphore(0);
  // The task we get when fast-pathing.
  private CallRunner loadedCallRunner;

  FastPathRpcHandler(String name, double handlerFailureThreshhold, int handlerCount,
      BlockingQueue<CallRunner> q, AtomicInteger activeHandlerCount,
      AtomicInteger failedHandlerCount, final Abortable abortable,
      final Deque<FastPathRpcHandler> fastPathHandlerStack) {
    super(name, handlerFailureThreshhold, handlerCount, q, activeHandlerCount, failedHandlerCount,
      abortable);
    this.fastPathHandlerStack = fastPathHandlerStack;
  }

  @Override
  protected CallRunner getCallRunner() throws InterruptedException {
    // Get a callrunner if one in the Q.
    CallRunner cr = this.q.poll();
    if (cr == null) {
      // Else, if a fastPathHandlerStack present and no callrunner in Q, register ourselves for
      // the fastpath handoff done via fastPathHandlerStack.
      if (this.fastPathHandlerStack != null) {
        this.fastPathHandlerStack.push(this);
        this.semaphore.acquire();
        cr = this.loadedCallRunner;
        this.loadedCallRunner = null;
      } else {
        // No fastpath available. Block until a task comes available.
        cr = super.getCallRunner();
      }
    }
    return cr;
  }

  /**
   * @param cr Task gotten via fastpath.
   * @return True if we successfully loaded our task
   */
  boolean loadCallRunner(final CallRunner cr) {
    this.loadedCallRunner = cr;
    this.semaphore.release();
    return true;
  }
}
